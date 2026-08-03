package com.tomppi.enderslicer.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tomppi.enderslicer.calibration.CalibrationPlacementPolicy
import com.tomppi.enderslicer.calibration.CalibrationPlanValidator
import com.tomppi.enderslicer.calibration.CalibrationSliceState
import com.tomppi.enderslicer.calibration.CalibrationTestType
import com.tomppi.enderslicer.calibration.CalibrationTowerGenerator
import com.tomppi.enderslicer.calibration.CalibrationTowerSpec
import com.tomppi.enderslicer.data.AppStateStore
import com.tomppi.enderslicer.data.BuiltInGcode
import com.tomppi.enderslicer.data.PrinterDefinitionLoader
import com.tomppi.enderslicer.data.WorkspaceStateStore
import com.tomppi.enderslicer.engine.CuraEngineRunner
import com.tomppi.enderslicer.engine.LayerEvent
import com.tomppi.enderslicer.engine.LayerEventSource
import com.tomppi.enderslicer.engine.LayerEventType
import com.tomppi.enderslicer.engine.PlannedLayerEvent
import com.tomppi.enderslicer.engine.SliceArtifactPublisher
import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.profile.CuraImportedSettingsResolver
import com.tomppi.enderslicer.profile.CuraProfileParser
import com.tomppi.enderslicer.profile.CuraProjectAudit
import com.tomppi.enderslicer.profile.CuraProjectParser
import com.tomppi.enderslicer.profile.CuraProjectScene
import com.tomppi.enderslicer.profile.CuraProjectSceneParser
import com.tomppi.enderslicer.profile.ImportedCuraConfig
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.StlMeshWriter
import com.tomppi.enderslicer.viewer.StlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private data class PendingImport(
        val config: ImportedCuraConfig,
        val stagedFile: File,
        val kind: String,
        val displayName: String,
        val scene: CuraProjectScene? = null,
    )

    private data class RestoredImport(
        val config: ImportedCuraConfig?,
        val settings: SlicerSettings,
        val scene: CuraProjectScene?,
        val workspace: RestoredWorkspace?,
    )

    private data class RestoredWorkspace(
        val snapshot: WorkspaceStateStore.Snapshot,
        val source: StlMesh,
        val transformed: StlMesh,
        val plannedEvents: List<PlannedLayerEvent>,
        val fingerprintMatches: Boolean,
    )

    private data class PreparedModelImport(
        val source: StlMesh,
        val transformed: StlMesh,
        val modelFile: File,
        val placement: ModelPlacement,
        val automaticImportedPlacement: Boolean,
        val mismatchWarning: String?,
    )

    private data class PreparedCalibration(
        val result: com.tomppi.enderslicer.calibration.CalibrationTowerResult,
        val transformed: StlMesh,
        val modelFile: File,
        val placement: ModelPlacement,
    )

    private val app = application
    private val printer = PrinterDefinitionLoader.loadModifiedEnder3V2(app.assets)
    private val engine = CuraEngineRunner(app)
    private val stateStore = AppStateStore(app)
    private val workspaceStore = WorkspaceStateStore(app)
    private val initialStartGcode = BuiltInGcode.START
    private val initialEndGcode = BuiltInGcode.END
    private var importedSettingsBaseline: SlicerSettings? = null
    private var sourceMesh: StlMesh? = null
    private var importedScene: CuraProjectScene? = null
    private var plannedCalibrationEvents: List<PlannedLayerEvent> = emptyList()
    private var settingsPersistenceJob: Job? = null
    private val layerEventSequence = AtomicLong(0L)

    private val _uiState = MutableStateFlow(
        MainUiState(
            printer = printer,
            startGcode = initialStartGcode,
            endGcode = initialEndGcode,
            engineStatus = engine.status(),
            engineAvailable = engine.isAvailable(),
            statusMessage = "Restoring saved configuration…",
            isBusy = true,
        ),
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        restorePersistedState()
    }

    fun importStl(uri: Uri) {
        if (!beginOperation("Reading STL…")) return
        val sceneSnapshot = importedScene
        val stateSnapshot = _uiState.value
        val previousModelPath = stateSnapshot.modelPath
        viewModelScope.launch {
            runCatching {
                val (mesh, modelFile) = withContext(Dispatchers.IO) {
                    retainReadPermission(uri)
                    val triangleLimit = MeshTriangleLimits.current()
                    val file = materializeModel(uri, triangleLimit)
                    try {
                        StlParser.parse(file, displayName(uri), triangleLimit) to file
                    } catch (error: Throwable) {
                        file.delete()
                        throw error
                    }
                }
                val prepared = withContext(Dispatchers.Default) {
                    val automaticPlacement = sceneSnapshot
                        ?.takeIf { scene -> scene.affine != null && modelNamesMatch(scene.modelName, mesh.displayName) }
                        ?.let { scene -> ModelPlacement.from3mf(mesh, requireNotNull(scene.affine), scene.dropToBuildPlate) }
                    val placement = automaticPlacement
                        ?: ModelPlacement.centeredOnBed(
                            mesh = mesh,
                            bedWidthMm = stateSnapshot.settings.machineWidthMm,
                            bedDepthMm = stateSnapshot.settings.machineDepthMm,
                            originAtCenter = stateSnapshot.settings.originAtCenter,
                        )
                    val transformed = placement.transformed(mesh)
                    val mismatchWarning = sceneSnapshot
                        ?.takeIf { it.affine != null && !modelNamesMatch(it.modelName, mesh.displayName) }
                        ?.let { scene ->
                            "Imported Cura transform is for ${scene.modelName ?: "another model"}; it was not applied automatically to ${mesh.displayName}"
                        }
                    PreparedModelImport(
                        source = mesh,
                        transformed = transformed,
                        modelFile = modelFile,
                        placement = placement,
                        automaticImportedPlacement = automaticPlacement != null,
                        mismatchWarning = mismatchWarning,
                    )
                }
                withContext(Dispatchers.IO) {
                    workspaceStore.save(
                        workspaceSnapshot(
                            modelFile = prepared.modelFile,
                            displayName = prepared.source.displayName,
                            placement = prepared.placement,
                            plannedEvents = emptyList(),
                            calibrationDescription = null,
                            state = stateSnapshot,
                        ),
                    )
                }
                prepared
            }.onSuccess { prepared ->
                CalibrationSliceState.clear()
                sourceMesh = prepared.source
                plannedCalibrationEvents = emptyList()
                _uiState.update { current ->
                    current.copy(
                        mesh = prepared.transformed,
                        modelPath = prepared.modelFile.absolutePath,
                        modelPlacement = prepared.placement,
                        importedSceneTransformAvailable = sceneSnapshot?.affine != null,
                        importedSceneModelName = sceneSnapshot?.modelName,
                        sliceResultId = null,
                        gcodePath = null,
                        baseGcodePath = null,
                        layerPreview = null,
                        layerEvents = emptyList(),
                        calibrationDescription = null,
                        estimatedPrintSeconds = null,
                        sliceLogPath = null,
                        sliceDurationMilliseconds = null,
                        warnings = (current.warnings.filterNot { it.startsWith("Imported Cura transform is for") } + listOfNotNull(prepared.mismatchWarning)).distinct(),
                        isBusy = false,
                        statusMessage = buildString {
                            append("Loaded ${prepared.source.displayName}: ${prepared.source.triangleCount} triangles")
                            if (prepared.automaticImportedPlacement) append(" · imported Cura scene transform applied")
                        },
                    )
                }
                previousModelPath
                    ?.takeIf { it != prepared.modelFile.absolutePath }
                    ?.let(::File)
                    ?.takeIf { it.parentFile == prepared.modelFile.parentFile }
                    ?.delete()
            }.onFailure(::showOperationFailure)
        }
    }

    /**
     * Transactionally replaces the active model with a filaSim Part Topo solid.
     * The generic STL importer is intentionally bypassed: a previous 3MF affine
     * must never be inferred for geometry already derived from the displayed STL.
     */
    fun importPartTopoResult(uri: Uri) {
        val stateSnapshot = _uiState.value
        val analyzedDisplayedMesh = stateSnapshot.mesh
        if (analyzedDisplayedMesh == null || stateSnapshot.modelPath == null) {
            showOperationFailure(IllegalStateException("The analyzed model is no longer available"))
            return
        }
        if (!beginOperation("Importing filaSim Part Topo result…")) return
        val previousModelPath = stateSnapshot.modelPath
        viewModelScope.launch {
            runCatching {
                val prepared = withContext(Dispatchers.IO) {
                    PartTopoResultPreparer.prepare(
                        context = app,
                        uri = uri,
                        analyzedDisplayedMesh = analyzedDisplayedMesh,
                        printer = stateSnapshot.printer,
                        settings = stateSnapshot.settings,
                    )
                }
                try {
                    withContext(Dispatchers.IO) {
                        workspaceStore.save(
                            workspaceSnapshot(
                                modelFile = prepared.modelFile,
                                displayName = prepared.source.displayName,
                                placement = prepared.placement,
                                plannedEvents = emptyList(),
                                calibrationDescription = null,
                                state = stateSnapshot,
                            ),
                        )
                    }
                } catch (error: Throwable) {
                    prepared.modelFile.delete()
                    throw error
                }
                prepared
            }.onSuccess { prepared ->
                CalibrationSliceState.clear()
                sourceMesh = prepared.source
                importedScene = null
                plannedCalibrationEvents = emptyList()
                _uiState.update { current ->
                    current.copy(
                        mesh = prepared.transformed,
                        modelPath = prepared.modelFile.absolutePath,
                        modelPlacement = prepared.placement,
                        importedSceneTransformAvailable = false,
                        importedSceneModelName = null,
                        sliceResultId = null,
                        gcodePath = null,
                        baseGcodePath = null,
                        layerPreview = null,
                        layerEvents = emptyList(),
                        calibrationDescription = null,
                        estimatedPrintSeconds = null,
                        sliceLogPath = null,
                        sliceDurationMilliseconds = null,
                        warnings = current.warnings.filterNot {
                            it.startsWith("Imported Cura transform is for")
                        },
                        isBusy = false,
                        statusMessage = "Imported ${prepared.source.displayName} as a standalone Part Topo model; inspect and slice it",
                    )
                }
                previousModelPath
                    ?.takeIf { it != prepared.modelFile.absolutePath }
                    ?.let(::File)
                    ?.takeIf { it.parentFile == prepared.modelFile.parentFile }
                    ?.delete()
            }.onFailure(::showOperationFailure)
        }
    }

    fun importCuraProfile(uri: Uri) {
        if (!beginOperation("Importing Cura profile…")) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    retainReadPermission(uri)
                    val sourceName = displayName(uri)
                    stageAndParseImport(uri, AppStateStore.KIND_PROFILE, sourceName) { file ->
                        file.inputStream().use { input ->
                            CuraProfileParser.parse(input, sourceName, SlicerSettings())
                        }
                    }
                }
            }.onSuccess { pending -> commitImportedConfig(pending) }
                .onFailure(::showOperationFailure)
        }
    }

    fun importCuraProject(uri: Uri) {
        if (!beginOperation("Importing Cura project…")) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    retainReadPermission(uri)
                    val sourceName = displayName(uri)
                    stageAndParseImport(
                        uri = uri,
                        kind = AppStateStore.KIND_PROJECT,
                        sourceName = sourceName,
                        parseScene = { file -> file.inputStream().use(CuraProjectSceneParser::parse) },
                    ) { file ->
                        file.inputStream().use { input ->
                            CuraProjectParser.parse(input, sourceName, SlicerSettings())
                        }
                    }
                }
            }.onSuccess { pending -> commitImportedConfig(pending) }
                .onFailure(::showOperationFailure)
        }
    }

    fun updateSettings(
        key: String,
        transform: (SlicerSettings) -> SlicerSettings,
    ) {
        val current = _uiState.value
        if (current.isBusy) return
        val changed = transform(current.settings).copy(
            overriddenSettingKeys = current.settings.overriddenSettingKeys + key,
        )
        _uiState.update { state ->
            state.copy(
                settings = changed,
                sliceResultId = null,
                gcodePath = null,
                baseGcodePath = null,
                layerPreview = null,
                layerEvents = emptyList(),
                estimatedPrintSeconds = null,
                sliceLogPath = null,
                sliceDurationMilliseconds = null,
                statusMessage = "Settings changed; slice again to export G-code",
            )
        }
        persistSettings(changed)
    }

    fun resetAllSettingOverrides() {
        if (_uiState.value.isBusy) return
        val baseline = importedSettingsBaseline ?: SlicerSettings()
        val restored = baseline.copy(overriddenSettingKeys = emptySet())
        persistSettings(restored)
        _uiState.update {
            it.copy(
                settings = restored,
                sliceResultId = null,
                gcodePath = null,
                baseGcodePath = null,
                layerPreview = null,
                layerEvents = emptyList(),
                estimatedPrintSeconds = null,
                sliceLogPath = null,
                sliceDurationMilliseconds = null,
                statusMessage = if (importedSettingsBaseline != null) {
                    "App overrides cleared; imported Cura values are active"
                } else {
                    "App overrides cleared; built-in defaults are active"
                },
            )
        }
    }

    fun clearBuildPlate() {
        val snapshot = _uiState.value
        if (!beginOperation("Clearing build plate…")) return
        val pendingSettingsWrite = settingsPersistenceJob
        val artifactId = snapshot.gcodePath?.let(::File)?.parentFile?.name
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    pendingSettingsWrite?.join()
                    workspaceStore.clear()
                    File(app.filesDir, "models").listFiles().orEmpty().forEach { it.delete() }
                    artifactId?.let(engine::releaseArtifact)
                }
            }.onSuccess {
                CalibrationSliceState.clear()
                sourceMesh = null
                importedScene = null
                plannedCalibrationEvents = emptyList()
                _uiState.update { current ->
                    current.copy(
                        mesh = null,
                        modelPath = null,
                        modelPlacement = null,
                        importedSceneTransformAvailable = false,
                        importedSceneModelName = null,
                        sliceResultId = null,
                        gcodePath = null,
                        baseGcodePath = null,
                        layerPreview = null,
                        layerEvents = emptyList(),
                        calibrationDescription = null,
                        estimatedPrintSeconds = null,
                        sliceLogPath = null,
                        sliceDurationMilliseconds = null,
                        warnings = current.warnings.filterNot {
                            it.startsWith("Imported Cura transform is for")
                        },
                        isBusy = false,
                        statusMessage = "Build plate cleared; import an STL to begin",
                    )
                }
            }.onFailure(::showOperationFailure)
        }
    }

    fun moveModel(centerXmm: Double, centerYmm: Double, baseZmm: Double) {
        changePlacement("Model position changed") { placement, _ ->
            placement.moved(centerXmm, centerYmm, baseZmm)
        }
    }

    fun rotateModel(axis: ModelPlacement.Axis, degrees: Double) {
        changePlacement("Model rotated ${degrees.toInt()}° around ${axis.name}") { placement, _ ->
            placement.rotated(axis, degrees)
        }
    }

    fun dropModelToBed() {
        changePlacement("Model dropped to the build plate") { placement, _ -> placement.droppedToBed() }
    }

    fun layModelFlat() {
        changePlacement("Model laid flat on its largest face") { placement, mesh -> placement.layFlat(mesh) }
    }

    fun resetModelTransform() {
        val settings = _uiState.value.settings
        changePlacement("Model transform reset and centered") { _, mesh ->
            ModelPlacement.centeredOnBed(
                mesh = mesh,
                bedWidthMm = settings.machineWidthMm,
                bedDepthMm = settings.machineDepthMm,
                originAtCenter = settings.originAtCenter,
            )
        }
    }

    fun applyImportedSceneTransform() {
        val scene = importedScene
        val affine = scene?.affine
        if (scene == null || affine == null) {
            showOperationFailure(IllegalStateException("The imported Cura project has no object transform"))
            return
        }
        changePlacement("Imported Cura scene transform applied") { _, mesh ->
            ModelPlacement.from3mf(mesh, affine, scene.dropToBuildPlate)
        }
    }

    fun sliceModel() {
        val snapshot = _uiState.value
        val originalPath = snapshot.modelPath
        val transformedMesh = snapshot.mesh
        if (originalPath == null || transformedMesh == null) {
            showOperationFailure(IllegalStateException("Import an STL before slicing"))
            return
        }
        if (!engine.isAvailable()) {
            showOperationFailure(IllegalStateException(engine.status()))
            return
        }
        if (!beginOperation("CuraEngine is slicing…")) return
        val plannedEventsSnapshot = plannedCalibrationEvents.toList()

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val stagingRoot = File(app.cacheDir, "model-placement").apply {
                        check(mkdirs() || isDirectory) { "Unable to create the model staging directory" }
                    }
                    val stagingDirectory = File(stagingRoot, "slice-${UUID.randomUUID()}").apply {
                        check(mkdir()) { "Unable to create an isolated model staging directory" }
                    }
                    try {
                        val transformedFile = File(stagingDirectory, "transformed.stl")
                        StlMeshWriter.writeBinary(transformedMesh, transformedFile)
                        if (plannedEventsSnapshot.isNotEmpty()) {
                            val transform = requireNotNull(
                                StlMeshWriter.resolvedSliceSource(transformedFile)?.transform,
                            ) { "Calibration slice transform is unavailable" }
                            CalibrationPlacementPolicy.requireAllowed(transform)
                        }
                        engine.slice(
                            modelFile = transformedFile,
                            printer = snapshot.printer,
                            settings = snapshot.settings,
                            startGcode = snapshot.startGcode,
                            endGcode = snapshot.endGcode,
                            profile = snapshot.engineProfile,
                            layerEvents = snapshot.layerEvents.filter { it.source == LayerEventSource.USER },
                            plannedLayerEvents = plannedEventsSnapshot,
                        )
                    } finally {
                        stagingDirectory.deleteRecursively()
                    }
                }
            }.onSuccess { result ->
                val previousArtifactId = _uiState.value.gcodePath?.let(::File)?.parentFile?.name
                _uiState.update { current ->
                    val printTime = result.estimatedPrintSeconds?.let(::formatPrintTime)
                    current.copy(
                        sliceResultId = result.artifactId,
                        gcodePath = result.gcodeFile.absolutePath,
                        baseGcodePath = result.baseGcodeFile.absolutePath,
                        layerPreview = result.layerPreview,
                        layerEvents = result.layerEvents,
                        estimatedPrintSeconds = result.estimatedPrintSeconds,
                        sliceLogPath = result.logFile.absolutePath,
                        sliceDurationMilliseconds = result.elapsedMilliseconds,
                        isBusy = false,
                        statusMessage = buildString {
                            append("Sliced ${formatFileSize(result.gcodeFile.length())} of validated G-code in ${formatDuration(result.elapsedMilliseconds)}")
                            if (printTime != null) append(" · estimated print $printTime")
                            if (result.layerPreview == null) append(" · layer preview unavailable; see diagnostic log")
                            if (result.layerEvents.isNotEmpty()) append(" · ${result.layerEvents.size} layer events")
                        },
                    )
                }
                previousArtifactId
                    ?.takeIf { it != result.artifactId }
                    ?.let(engine::releaseArtifact)
            }.onFailure(::showSliceFailure)
        }
    }

    fun generateCalibrationTower(spec: CalibrationTowerSpec) {
        if (!beginOperation("Generating calibration tower…")) return
        val snapshot = _uiState.value
        val previousModelPath = snapshot.modelPath
        viewModelScope.launch {
            runCatching {
                val result = withContext(Dispatchers.Default) {
                    CalibrationPlanValidator.validate(
                        spec = spec,
                        gcodeFlavor = snapshot.settings.gcodeFlavor,
                        retractionSpeedMmPerSecond = snapshot.settings.retractionSpeedMmPerSecond,
                    )
                    CalibrationTowerGenerator.generate(spec, snapshot.settings.retractionSpeedMmPerSecond)
                }
                val placement = ModelPlacement.centeredOnBed(
                    mesh = result.mesh,
                    bedWidthMm = snapshot.settings.machineWidthMm,
                    bedDepthMm = snapshot.settings.machineDepthMm,
                    originAtCenter = snapshot.settings.originAtCenter,
                )
                val transformed = withContext(Dispatchers.Default) { placement.transformed(result.mesh) }
                val modelFile = withContext(Dispatchers.IO) {
                    val directory = File(app.filesDir, "models").apply { mkdirs() }
                    val target = File(directory, "calibration-${System.nanoTime()}.stl")
                    StlMeshWriter.writeBinary(result.mesh, target)
                    target
                }
                val prepared = PreparedCalibration(result, transformed, modelFile, placement)
                withContext(Dispatchers.IO) {
                    workspaceStore.save(
                        workspaceSnapshot(
                            modelFile = prepared.modelFile,
                            displayName = prepared.result.mesh.displayName,
                            placement = prepared.placement,
                            plannedEvents = prepared.result.plannedEvents,
                            calibrationDescription = prepared.result.description,
                            state = snapshot,
                        ),
                    )
                }
                prepared
            }.onSuccess { prepared ->
                CalibrationSliceState.activate(spec.type, prepared.result.levelValues.first())
                sourceMesh = prepared.result.mesh
                importedScene = null
                plannedCalibrationEvents = prepared.result.plannedEvents
                _uiState.update { current ->
                    current.copy(
                        mesh = prepared.transformed,
                        modelPath = prepared.modelFile.absolutePath,
                        modelPlacement = prepared.placement,
                        importedSceneTransformAvailable = false,
                        importedSceneModelName = null,
                        sliceResultId = null,
                        gcodePath = null,
                        baseGcodePath = null,
                        layerPreview = null,
                        layerEvents = emptyList(),
                        calibrationDescription = prepared.result.description,
                        estimatedPrintSeconds = null,
                        sliceLogPath = null,
                        sliceDurationMilliseconds = null,
                        isBusy = false,
                        statusMessage = "Generated ${prepared.result.description}; slice to create the stepped calibration G-code",
                    )
                }
                previousModelPath
                    ?.takeIf { it != prepared.modelFile.absolutePath }
                    ?.let(::File)
                    ?.takeIf { it.parentFile == prepared.modelFile.parentFile }
                    ?.delete()
            }.onFailure(::showOperationFailure)
        }
    }

    fun addLayerEvent(
        layerNumber: Int,
        zMm: Float,
        type: LayerEventType,
        value: Double? = null,
        secondaryValue: Double? = null,
        text: String = "",
    ) {
        val event = LayerEvent(
            id = "user-${layerEventSequence.incrementAndGet()}",
            layerNumber = layerNumber,
            zMm = zMm,
            type = type,
            value = value,
            secondaryValue = secondaryValue,
            text = text,
        )
        reapplyLayerEvents(_uiState.value.layerEvents + event, "Layer event added")
    }

    fun removeLayerEvent(id: String) {
        reapplyLayerEvents(_uiState.value.layerEvents.filterNot { it.id == id }, "Layer event removed")
    }

    fun clearLayerEvents() {
        val retainedCalibration = _uiState.value.layerEvents.filter { it.source == LayerEventSource.CALIBRATION }
        reapplyLayerEvents(retainedCalibration, "User layer events cleared")
    }

    private fun reapplyLayerEvents(events: List<LayerEvent>, message: String) {
        val basePath = _uiState.value.baseGcodePath
        if (basePath == null) {
            showEventFailure(IllegalStateException("Slice the model before editing layer events"))
            return
        }
        if (!beginOperation("Applying layer events…")) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { engine.applyLayerEvents(File(basePath), events) }
            }.onSuccess { result ->
                val previousArtifactId = _uiState.value.gcodePath?.let(::File)?.parentFile?.name
                _uiState.update { current ->
                    current.copy(
                        sliceResultId = current.sliceResultId ?: result.artifactId,
                        gcodePath = result.gcodeFile.absolutePath,
                        baseGcodePath = result.baseGcodeFile.absolutePath,
                        layerPreview = result.layerPreview,
                        layerEvents = result.layerEvents,
                        estimatedPrintSeconds = result.estimatedPrintSeconds,
                        isBusy = false,
                        statusMessage = "$message · ${result.layerEvents.size} active events",
                    )
                }
                previousArtifactId
                    ?.takeIf { it != result.artifactId }
                    ?.let(engine::releaseArtifact)
            }.onFailure(::showEventFailure)
        }
    }

    fun exportGcode(uri: Uri) {
        val sourcePath = _uiState.value.gcodePath
        if (sourcePath == null) {
            showOperationFailure(IllegalStateException("Slice the model before exporting G-code"))
            return
        }

        if (!beginOperation("Exporting G-code…")) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val source = File(sourcePath)
                    check(source.isFile && source.length() > 0L) { "Generated G-code is no longer available" }
                    SliceArtifactPublisher.acquireLease(source).use {
                        app.contentResolver.openOutputStream(uri, "w")?.buffered()?.use { output ->
                            source.inputStream().buffered().use { input -> input.copyTo(output) }
                        } ?: error("Unable to open the G-code destination")
                    }
                }
            }.onSuccess {
                _uiState.update { it.copy(isBusy = false, statusMessage = "G-code exported") }
            }.onFailure(::showOperationFailure)
        }
    }

    fun exportConfiguration(uri: Uri) {
        if (!beginOperation("Exporting configuration…")) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val snapshot = configurationJson(_uiState.value)
                    app.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                        writer.write(snapshot.toString(2))
                    } ?: error("Unable to open the export destination")
                }
            }.onSuccess {
                _uiState.update { it.copy(isBusy = false, statusMessage = "Configuration exported") }
            }.onFailure(::showOperationFailure)
        }
    }

    private fun changePlacement(
        message: String,
        transform: (ModelPlacement, StlMesh) -> ModelPlacement,
    ) {
        val original = sourceMesh
        val stateSnapshot = _uiState.value
        val current = stateSnapshot.modelPlacement
        val modelPath = stateSnapshot.modelPath
        if (original == null || current == null) {
            showOperationFailure(IllegalStateException("Import an STL before changing model placement"))
            return
        }
        if (!beginOperation("Updating model placement…")) return
        viewModelScope.launch {
            runCatching {
                val prepared = withContext(Dispatchers.Default) {
                    val changed = transform(current, original)
                    val transformed = changed.transformed(original)
                    if (plannedCalibrationEvents.isNotEmpty()) {
                        CalibrationPlacementPolicy.requireAllowed(
                            requireNotNull(transformed.slicingTransform) {
                                "Calibration placement transform is unavailable"
                            },
                        )
                    }
                    changed to transformed
                }
                val durableModel = requireNotNull(modelPath)?.let(::File)
                    ?: error("The active model path is unavailable")
                withContext(Dispatchers.IO) {
                    workspaceStore.save(
                        workspaceSnapshot(
                            modelFile = durableModel,
                            displayName = original.displayName,
                            placement = prepared.first,
                            plannedEvents = plannedCalibrationEvents,
                            calibrationDescription = stateSnapshot.calibrationDescription,
                            state = stateSnapshot,
                        ),
                    )
                }
                prepared
            }.onSuccess { (changed, transformed) ->
                _uiState.update { state ->
                    state.copy(
                        mesh = transformed,
                        modelPlacement = changed,
                        sliceResultId = null,
                        gcodePath = null,
                        baseGcodePath = null,
                        layerPreview = null,
                        layerEvents = emptyList(),
                        estimatedPrintSeconds = null,
                        sliceLogPath = null,
                        sliceDurationMilliseconds = null,
                        isBusy = false,
                        statusMessage = "$message; slice again to export G-code",
                    )
                }
            }.onFailure(::showOperationFailure)
        }
    }

    private fun restorePersistedState() {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val saved = stateStore.savedImport()
                    val config = saved?.let { persisted ->
                        val parsed = persisted.file.inputStream().use { input ->
                            when (persisted.kind) {
                                AppStateStore.KIND_PROJECT -> CuraProjectParser.parse(input, persisted.displayName, SlicerSettings())
                                AppStateStore.KIND_PROFILE -> CuraProfileParser.parse(input, persisted.displayName, SlicerSettings())
                                else -> error("Unknown persisted Cura import kind: ${persisted.kind}")
                            }
                        }
                        CuraImportedSettingsResolver.resolveForUi(
                            config = parsed,
                            printer = printer,
                            fallbackStartGcode = initialStartGcode,
                            fallbackEndGcode = initialEndGcode,
                        )
                    }
                    val scene = saved?.takeIf { it.kind == AppStateStore.KIND_PROJECT }
                        ?.file
                        ?.inputStream()
                        ?.use(CuraProjectSceneParser::parse)
                    val settings = stateStore.restoreSettings(config?.mappedSettings ?: SlicerSettings())
                    val fingerprint = workspaceFingerprint(config, settings)
                    val workspace = runCatching {
                        workspaceStore.load()?.let { snapshot ->
                            val modelFile = File(snapshot.modelPath)
                            val source = StlParser.parse(
                                file = modelFile,
                                displayName = snapshot.modelDisplayName,
                                maxTriangles = MeshTriangleLimits.current(),
                            )
                            val transformed = snapshot.placement.transformed(source)
                            val fingerprintMatches = snapshot.configurationFingerprint == fingerprint
                            RestoredWorkspace(
                                snapshot = snapshot,
                                source = source,
                                transformed = transformed,
                                plannedEvents = if (fingerprintMatches) snapshot.plannedEvents else emptyList(),
                                fingerprintMatches = fingerprintMatches,
                            )
                        }
                    }.getOrNull()
                    RestoredImport(config, settings, scene, workspace)
                }
            }

            result.onSuccess { restored ->
                importedScene = restored.scene
                sourceMesh = restored.workspace?.source
                plannedCalibrationEvents = restored.workspace?.plannedEvents.orEmpty()
                val restoredCalibration = restored.workspace?.snapshot
                    ?.takeIf { restored.workspace.fingerprintMatches }
                    ?.takeIf { it.calibrationType != null && it.calibrationFirstValue != null }
                if (restoredCalibration != null) {
                    CalibrationSliceState.activate(
                        requireNotNull(restoredCalibration.calibrationType),
                        requireNotNull(restoredCalibration.calibrationFirstValue),
                    )
                } else {
                    CalibrationSliceState.clear()
                }
                if (restored.config == null) {
                    importedSettingsBaseline = null
                    _uiState.update {
                        it.copy(
                            settings = restored.settings,
                            importedSceneTransformAvailable = restored.scene?.affine != null,
                            importedSceneModelName = restored.scene?.modelName,
                            isBusy = false,
                            statusMessage = if (restored.settings.overriddenSettingKeys.isEmpty()) {
                                "Import an STL to begin"
                            } else {
                                "Restored ${restored.settings.overriddenSettingKeys.size} saved app setting overrides"
                            },
                        )
                    }
                } else {
                    applyImportedConfig(
                        config = restored.config,
                        settings = restored.settings,
                        scene = restored.scene,
                        statusMessage = "Restored ${restored.config.name} and ${restored.settings.overriddenSettingKeys.size} app overrides",
                    )
                }
                restoreWorkspace(restored.workspace)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = "Saved Cura configuration could not be restored: ${error.message}",
                    )
                }
            }
        }
    }

    private fun restoreWorkspace(workspace: RestoredWorkspace?) {
        if (workspace == null) return
        val snapshot = workspace.snapshot
        _uiState.update { current ->
            current.copy(
                mesh = workspace.transformed,
                modelPath = snapshot.modelPath,
                modelPlacement = snapshot.placement,
                sliceResultId = null,
                gcodePath = null,
                baseGcodePath = null,
                layerPreview = null,
                layerEvents = emptyList(),
                calibrationDescription = if (workspace.fingerprintMatches) snapshot.calibrationDescription else null,
                estimatedPrintSeconds = null,
                sliceLogPath = null,
                sliceDurationMilliseconds = null,
                isBusy = false,
                statusMessage = buildString {
                    append("Restored ${snapshot.modelDisplayName} workspace; slice again to create validated G-code")
                    if (!workspace.fingerprintMatches) {
                        append(" · configuration changed, so calibration events were cleared")
                    }
                },
            )
        }
    }

    private fun workspaceSnapshot(
        modelFile: File,
        displayName: String,
        placement: ModelPlacement,
        plannedEvents: List<PlannedLayerEvent>,
        calibrationDescription: String?,
        state: MainUiState,
    ): WorkspaceStateStore.Snapshot {
        val calibrationType = plannedEvents.firstOrNull()?.type?.let { eventType ->
            CalibrationTestType.entries.firstOrNull { it.eventType == eventType }
        }
        return WorkspaceStateStore.Snapshot(
            modelPath = modelFile.absolutePath,
            modelDisplayName = displayName,
            placement = placement,
            plannedEvents = plannedEvents,
            calibrationDescription = calibrationDescription,
            calibrationType = calibrationType,
            calibrationFirstValue = if (calibrationType == null) null else plannedEvents.firstOrNull()?.value,
            configurationFingerprint = workspaceFingerprint(state),
        )
    }

    private fun workspaceFingerprint(state: MainUiState): String = WorkspaceStateStore.fingerprint(
        state.profileName,
        state.profileSource,
        state.curaVersion,
        state.settingVersion,
        state.settings,
        state.startGcode,
        state.endGcode,
    )

    private fun workspaceFingerprint(
        config: ImportedCuraConfig?,
        settings: SlicerSettings,
    ): String = WorkspaceStateStore.fingerprint(
        config?.name ?: "Built-in current Cura settings",
        config?.source ?: "Cura 5.11 / setting version 25 reference",
        config?.curaVersion,
        config?.settingVersion ?: "25",
        settings,
        config?.startGcode ?: initialStartGcode,
        config?.endGcode ?: initialEndGcode,
    )

    private fun persistSettings(settings: SlicerSettings) {
        val stateSnapshot = _uiState.value.copy(settings = settings)
        val previousWrite = settingsPersistenceJob
        settingsPersistenceJob = viewModelScope.launch(Dispatchers.IO) {
            previousWrite?.join()
            stateStore.saveSettings(settings)
            persistCurrentWorkspace(stateSnapshot)
        }
    }

    private fun persistCurrentWorkspace(state: MainUiState) {
        val modelPath = state.modelPath ?: return
        val placement = state.modelPlacement ?: return
        val source = sourceMesh ?: return
        val modelFile = File(modelPath)
        if (!modelFile.isFile) return
        workspaceStore.save(
            workspaceSnapshot(
                modelFile = modelFile,
                displayName = source.displayName,
                placement = placement,
                plannedEvents = plannedCalibrationEvents,
                calibrationDescription = state.calibrationDescription,
                state = state,
            ),
        )
    }

    private fun stageAndParseImport(
        uri: Uri,
        kind: String,
        sourceName: String,
        parseScene: ((File) -> CuraProjectScene?)? = null,
        parse: (File) -> ImportedCuraConfig,
    ): PendingImport {
        val staged = app.contentResolver.openInputStream(uri)?.use(stateStore::stageImport)
            ?: error("Unable to open the selected Cura file")
        return try {
            PendingImport(
                config = parse(staged),
                stagedFile = staged,
                kind = kind,
                displayName = sourceName,
                scene = parseScene?.invoke(staged),
            )
        } catch (error: Throwable) {
            staged.delete()
            throw error
        }
    }

    private suspend fun commitImportedConfig(pending: PendingImport) {
        val pendingSettingsWrite = settingsPersistenceJob
        runCatching {
            withContext(Dispatchers.IO) {
                pendingSettingsWrite?.join()
                stateStore.commitImport(pending.stagedFile, pending.kind, pending.displayName)
                stateStore.clearSavedSettings()
            }
        }.onFailure {
            showOperationFailure(it)
            return
        }
        importedScene = pending.scene
        val resolvedConfig = withContext(Dispatchers.Default) {
            CuraImportedSettingsResolver.resolveForUi(
                config = pending.config,
                printer = printer,
                fallbackStartGcode = initialStartGcode,
                fallbackEndGcode = initialEndGcode,
            )
        }
        val baseline = resolvedConfig.mappedSettings.copy(overriddenSettingKeys = emptySet())
        withContext(Dispatchers.IO) { stateStore.saveSettings(baseline) }
        applyImportedConfig(
            config = resolvedConfig,
            settings = baseline,
            scene = pending.scene,
            statusMessage = null,
        )
    }

    private suspend fun applyImportedConfig(
        config: ImportedCuraConfig,
        settings: SlicerSettings,
        scene: CuraProjectScene?,
        statusMessage: String?,
    ) {
        importedSettingsBaseline = config.mappedSettings.copy(overriddenSettingKeys = emptySet())
        importedScene = scene
        val original = sourceMesh
        val autoPlacement = if (
            original != null && scene?.affine != null && modelNamesMatch(scene.modelName, original.displayName)
        ) {
            ModelPlacement.from3mf(original, scene.affine, scene.dropToBuildPlate)
        } else {
            null
        }
        val transformed = if (autoPlacement != null && original != null) {
            withContext(Dispatchers.Default) { autoPlacement.transformed(original) }
        } else {
            null
        }
        _uiState.update { current ->
            val concreteCount = config.engineProfile?.concreteSettingCount ?: config.rawValues.size
            val definitionLabel = if (config.engineProfile?.usesProjectDefinitions == true) {
                " with project machine/extruder definitions"
            } else {
                ""
            }
            val mismatchWarning = if (
                original != null && scene?.affine != null && !modelNamesMatch(scene.modelName, original.displayName)
            ) {
                "Imported Cura transform is for ${scene.modelName ?: "another model"}; use Model position & rotation to apply it manually"
            } else {
                null
            }
            val auditWarnings = CuraProjectAudit.warnings(config.rawValues)
            val warnings = (config.warnings + scene?.warnings.orEmpty() + auditWarnings + listOfNotNull(mismatchWarning)).distinct()
            current.copy(
                settings = settings,
                profileName = config.name,
                profileSource = config.source,
                importedRawSettingCount = concreteCount,
                curaVersion = config.curaVersion,
                settingVersion = config.settingVersion,
                engineProfile = config.engineProfile,
                startGcode = config.startGcode ?: current.startGcode,
                endGcode = config.endGcode ?: current.endGcode,
                mesh = transformed ?: current.mesh,
                modelPlacement = autoPlacement ?: current.modelPlacement,
                importedSceneTransformAvailable = scene?.affine != null,
                importedSceneModelName = scene?.modelName,
                sliceResultId = null,
                gcodePath = null,
                baseGcodePath = null,
                layerPreview = null,
                layerEvents = emptyList(),
                estimatedPrintSeconds = null,
                sliceLogPath = null,
                sliceDurationMilliseconds = null,
                warnings = warnings,
                isBusy = false,
                statusMessage = statusMessage
                    ?: buildString {
                        append("Imported $concreteCount concrete Cura values$definitionLabel")
                        if (autoPlacement != null) append(" and applied the matching scene transform")
                        append("; imported values remain active until overridden")
                    },
            )
        }
        withContext(Dispatchers.IO) { persistCurrentWorkspace(_uiState.value) }
    }

    private fun materializeModel(uri: Uri, maxTriangles: Int): File {
        val directory = File(app.filesDir, "models").apply { mkdirs() }
        val target = File(directory, "model-${System.nanoTime()}.stl")
        val temporary = File(directory, "${target.name}.tmp")
        val maxBytes = MeshTriangleLimits.maxInputFileBytes(maxTriangles)
        temporary.delete()
        try {
            app.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= maxBytes) {
                            "STL is larger than ${MeshTriangleLimits.formatBytes(maxBytes)} for the ${MeshTriangleLimits.formatCount(maxTriangles)}-triangle limit"
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Unable to copy the selected STL")
            check(temporary.length() > 0L) { "The selected STL is empty" }
            check(temporary.renameTo(target) || temporary.copyTo(target, overwrite = false).let { temporary.delete(); true }) {
                "Unable to store the selected STL locally"
            }
            return target
        } catch (error: Throwable) {
            temporary.delete()
            target.delete()
            throw error
        }
    }

    private fun modelNamesMatch(projectName: String?, stlName: String): Boolean {
        if (projectName.isNullOrBlank()) return false
        fun normalize(value: String): String = value
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringBeforeLast('.', value)
            .lowercase()
            .filter(Char::isLetterOrDigit)
        return normalize(projectName) == normalize(stlName)
    }

    private fun beginOperation(message: String): Boolean {
        if (_uiState.value.isBusy) return false
        _uiState.update { it.copy(isBusy = true, statusMessage = message) }
        return true
    }

    private fun showOperationFailure(error: Throwable) {
        _uiState.update { current ->
            current.copy(
                isBusy = false,
                statusMessage = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private fun showSliceFailure(error: Throwable) {
        _uiState.update { current ->
            current.copy(
                isBusy = false,
                sliceResultId = null,
                gcodePath = null,
                baseGcodePath = null,
                layerPreview = null,
                layerEvents = emptyList(),
                estimatedPrintSeconds = null,
                sliceLogPath = (error as? CuraEngineRunner.SliceException)?.logFile?.absolutePath ?: current.sliceLogPath,
                statusMessage = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private fun showEventFailure(error: Throwable) = showOperationFailure(error)

    private fun retainReadPermission(uri: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun displayName(uri: Uri): String {
        return app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment
            ?: "imported file"
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
        else -> "$bytes bytes"
    }

    private fun formatDuration(milliseconds: Long): String = when {
        milliseconds >= 60_000L -> "%.1f min".format(milliseconds / 60_000.0)
        milliseconds >= 1_000L -> "%.1f s".format(milliseconds / 1_000.0)
        else -> "$milliseconds ms"
    }

    private fun formatPrintTime(totalSeconds: Int): String {
        val seconds = totalSeconds.coerceAtLeast(0)
        val days = seconds / 86_400
        val hours = (seconds % 86_400) / 3_600
        val minutes = (seconds % 3_600) / 60
        return buildString {
            if (days > 0) append("${days}d ")
            if (hours > 0 || days > 0) append("${hours}h ")
            append("${minutes}m")
        }.trim()
    }

    private fun configurationJson(state: MainUiState): JSONObject {
        val settings = state.settings
        val placement = state.modelPlacement
        return JSONObject()
            .put("printer", state.printer.name)
            .put("profileName", state.profileName)
            .put("profileSource", state.profileSource)
            .put("curaVersion", state.curaVersion)
            .put("settingVersion", state.settingVersion)
            .put("importedValues", state.importedRawSettingCount)
            .put("appOverrideKeys", settings.overriddenSettingKeys.sorted())
            .put("estimatedPrintSeconds", state.estimatedPrintSeconds)
            .put("calibration", state.calibrationDescription)
            .put("maxMeshTriangles", MeshTriangleLimits.current())
            .put("layerEvents", state.layerEvents.map { event ->
                JSONObject()
                    .put("layer", event.layerNumber)
                    .put("zMm", event.zMm)
                    .put("type", event.type.name)
                    .put("value", event.value)
                    .put("secondaryValue", event.secondaryValue)
                    .put("text", event.text)
                    .put("source", event.source.name)
            })
            .put("warnings", state.warnings)
            .put(
                "modelPlacement",
                placement?.let {
                    JSONObject()
                        .put("source", it.source)
                        .put("centerXmm", it.centerXmm)
                        .put("centerYmm", it.centerYmm)
                        .put("baseZmm", it.baseZmm)
                        .put("linear", it.linear)
                },
            )
            .put(
                "settings",
                JSONObject()
                    .put("layerHeightMm", settings.layerHeightMm)
                    .put("initialLayerHeightMm", settings.initialLayerHeightMm)
                    .put("adaptiveLayerHeightEnabled", settings.adaptiveLayerHeightEnabled)
                    .put("adaptiveLayerHeightVariationMm", settings.adaptiveLayerHeightVariationMm)
                    .put("adaptiveLayerHeightVariationStepMm", settings.adaptiveLayerHeightVariationStepMm)
                    .put("adaptiveLayerHeightThreshold", settings.adaptiveLayerHeightThreshold)
                    .put("lineWidthMm", settings.lineWidthMm)
                    .put("wallThicknessMm", settings.wallThicknessMm)
                    .put("topBottomThicknessMm", settings.topBottomThicknessMm)
                    .put("initialBottomLayers", settings.initialBottomLayers)
                    .put("holeHorizontalExpansionMm", settings.holeHorizontalExpansionMm)
                    .put("initialLayerHorizontalExpansionMm", settings.initialLayerHorizontalExpansionMm)
                    .put("zigZagConnectInfill", settings.zigZagConnectInfill)
                    .put("printSpeedMmPerSecond", settings.printSpeedMmPerSecond)
                    .put("nozzleTemperatureC", settings.nozzleTemperatureC)
                    .put("initialNozzleTemperatureC", settings.initialNozzleTemperatureC)
                    .put("bedTemperatureC", settings.bedTemperatureC)
                    .put("infillDensityPercent", settings.infillDensityPercent)
                    .put("supportsEnabled", settings.supportsEnabled)
                    .put("supportPlacement", settings.supportPlacement)
                    .put("supportStructure", settings.supportStructure)
                    .put("supportAngleDegrees", settings.supportAngleDegrees)
                    .put("supportDensityPercent", settings.supportDensityPercent)
                    .put("supportPattern", settings.supportPattern)
                    .put("supportInterfaceEnabled", settings.supportInterfaceEnabled)
                    .put("supportInterfaceDensityPercent", settings.supportInterfaceDensityPercent)
                    .put("supportInterfaceHeightMm", settings.supportInterfaceHeightMm)
                    .put("supportZDistanceMm", settings.supportZDistanceMm)
                    .put("supportXyDistanceMm", settings.supportXyDistanceMm)
                    .put("supportSpeedMmPerSecond", settings.supportSpeedMmPerSecond)
                    .put("supportInterfaceSpeedMmPerSecond", settings.supportInterfaceSpeedMmPerSecond)
                    .put("adhesionType", settings.adhesionType)
                    .put("retractionDistanceMm", settings.retractionDistanceMm)
                    .put("retractionSpeedMmPerSecond", settings.retractionSpeedMmPerSecond)
                    .put("retractAtLayerChange", settings.retractAtLayerChange)
                    .put("zHopEnabled", settings.zHopEnabled)
                    .put("firmwareRetraction", settings.firmwareRetraction)
                    .put("fanSpeedPercent", settings.fanSpeedPercent)
                    .put("buildVolumeTemperatureC", settings.buildVolumeTemperatureC)
                    .put("materialStandbyTemperatureC", settings.materialStandbyTemperatureC)
                    .put("materialDensityGPerCm3", settings.materialDensityGPerCm3)
                    .put("materialAdhesionTendency", settings.materialAdhesionTendency)
                    .put("materialSurfaceEnergyPercent", settings.materialSurfaceEnergyPercent)
                    .put("materialBrand", settings.materialBrand)
                    .put("materialType", settings.materialType)
                    .put("materialGuid", settings.materialGuid)
                    .put("enabledExtruderCount", settings.enabledExtruderCount)
                    .put("materialFlowPercent", settings.materialFlowPercent)
                    .put("arcOverhangEnabled", settings.arcOverhangEnabled)
                    .put("arcOverhangSpeedMmPerSecond", settings.arcOverhangSpeedMmPerSecond)
                    .put("arcOverhangFlowPercent", settings.arcOverhangFlowPercent)
                    .put("arcOverhangLineSpacingPercent", settings.arcOverhangLineSpacingPercent)
                    .put("arcOverhangMinRadiusMm", settings.arcOverhangMinRadiusMm)
                    .put("arcOverhangMaxRadiusMm", settings.arcOverhangMaxRadiusMm)
                    .put("arcOverhangMaxAreaMm2", settings.arcOverhangMaxAreaMm2)
                    .put("arcOverhangResolutionMm", settings.arcOverhangResolutionMm)
                    .put("arcOverhangFanSpeedPercent", settings.arcOverhangFanSpeedPercent)
                    .put("raftMarginMm", settings.raftMarginMm)
                    .put("ironingOnlyHighestLayer", settings.ironingOnlyHighestLayer),
            )
            .put("startGcode", state.startGcode)
            .put("endGcode", state.endGcode)
    }
}
