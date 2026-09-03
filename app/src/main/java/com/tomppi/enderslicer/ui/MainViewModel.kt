package com.tomppi.enderslicer.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tomppi.enderslicer.conical.ConicalRuntime
import com.tomppi.enderslicer.data.AppStateStore
import com.tomppi.enderslicer.data.BuiltInGcode
import com.tomppi.enderslicer.data.PendingDocumentExportStore
import com.tomppi.enderslicer.data.PrinterDefinitionLoader
import com.tomppi.enderslicer.data.SlicerSettingsJson
import com.tomppi.enderslicer.data.WorkspaceStateStore
import com.tomppi.enderslicer.engine.CuraEngineRunner
import com.tomppi.enderslicer.engine.GcodeLayerPreview
import com.tomppi.enderslicer.engine.LayerEvent
import com.tomppi.enderslicer.engine.PrusaEngineRunner
import com.tomppi.enderslicer.nonplanar.NozzleCollisionAlert
import com.tomppi.enderslicer.engine.LayerEventSource
import com.tomppi.enderslicer.engine.LayerEventType
import com.tomppi.enderslicer.engine.PrinterEnvelope
import com.tomppi.enderslicer.engine.SliceArtifactPublisher
import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.model.PrusaSliceSettings
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.model.withSettings
import com.tomppi.enderslicer.nonplanar.NonPlanarRuntime
import com.tomppi.enderslicer.nonplanar.SmartOverhangStrategy
import com.tomppi.enderslicer.profile.CuraImportedSettingsResolver
import com.tomppi.enderslicer.profile.CuraProfileParser
import com.tomppi.enderslicer.profile.CuraProjectAudit
import com.tomppi.enderslicer.profile.CuraProjectParser
import com.tomppi.enderslicer.profile.CuraProjectScene
import com.tomppi.enderslicer.profile.CuraProjectSceneParser
import com.tomppi.enderslicer.profile.ImportedCuraConfig
import com.tomppi.enderslicer.supportpaint.SupportPaintBrush
import com.tomppi.enderslicer.supportpaint.SupportPaintMode
import com.tomppi.enderslicer.supportpaint.SupportPaintState
import com.tomppi.enderslicer.viewer.MeshPicker
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.StlMeshWriter
import com.tomppi.enderslicer.viewer.StlParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private const val CONFIG_SNAPSHOT_FORMAT = "enderslicer-config-snapshot"
private const val CONFIG_SNAPSHOT_VERSION = 1
private const val PAINT_PERSIST_DEBOUNCE_MILLIS = 400L

/** Engine-agnostic slice result shared by the Cura and Prusa runners. */
private data class EngineSliceOutcome(
    val artifactId: String,
    val gcodeFile: File,
    val baseGcodeFile: File,
    val logFile: File,
    val elapsedMilliseconds: Long,
    val estimatedPrintSeconds: Int?,
    val layerPreview: GcodeLayerPreview?,
    val layerEvents: List<LayerEvent>,
    val nozzleCollisionAlert: NozzleCollisionAlert? = null,
    val collisionSweepFailure: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private data class PendingImport(
        val config: ImportedCuraConfig,
        val stagedFile: File,
        val kind: String,
        val displayName: String,
        val scene: CuraProjectScene? = null,
    )

    private data class SnapshotImport(
        val settings: SlicerSettings,
        val startGcode: String,
        val endGcode: String,
        val sourceName: String,
    )

    private data class RestoredImport(
        val config: ImportedCuraConfig?,
        val settings: SlicerSettings,
        val scene: CuraProjectScene?,
        val workspace: RestoredWorkspace?,
        val startGcode: String,
        val endGcode: String,
        val profileName: String,
        val profileSource: String,
        val baselineSettings: SlicerSettings?,
    )

    private data class RestoredWorkspace(
        val snapshot: WorkspaceStateStore.Snapshot,
        val source: StlMesh,
        val transformed: StlMesh,
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

    private val app = application
    private val printer = PrinterDefinitionLoader.loadModifiedEnder3V2(app.assets)
    private val engine = CuraEngineRunner(app)
    private val prusaEngine = PrusaEngineRunner(app)
    private val engineStore = SlicerEngineStore(app)
    private val activeEngine: SlicerEngine get() = engineStore.load()
    private val stateStore = AppStateStore(app)
    private val workspaceStore = WorkspaceStateStore(app)
    private val pendingExportStore = PendingDocumentExportStore(app)
    private val initialStartGcode = BuiltInGcode.START
    private val initialEndGcode = BuiltInGcode.END
    private var importedSettingsBaseline: SlicerSettings? = null
    private var sourceMesh: StlMesh? = null
    private var importedScene: CuraProjectScene? = null
    private var settingsPersistenceJob: Job? = null
    private var paintPersistenceJob: Job? = null
    private val workspaceMutationGeneration = AtomicLong(0L)
    private val layerEventSequence = AtomicLong(0L)
    private val deferredRestoreActions = ArrayDeque<() -> Unit>()
    @Volatile private var restoringPersistedState = true

    private val _uiState = MutableStateFlow(
        MainUiState(
            printer = printer,
            startGcode = initialStartGcode,
            endGcode = initialEndGcode,
            engineStatus = activeEngineStatus(),
            engineAvailable = activeEngineAvailable(),
            prusaSettings = stateStore.restorePrusaSettings(),
            statusMessage = "Restoring saved configuration…",
            isBusy = true,
        ),
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private fun activeEngineStatus(): String =
        if (activeEngine == SlicerEngine.PRUSA) prusaEngine.status() else engine.status()

    private fun activeEngineAvailable(): Boolean =
        if (activeEngine == SlicerEngine.PRUSA) prusaEngine.isAvailable() else engine.isAvailable()

    /** Called by the UI after the user switches the engine; refreshes the status text. */
    fun onEngineChanged() {
        _uiState.update {
            it.copy(engineStatus = activeEngineStatus(), engineAvailable = activeEngineAvailable())
        }
    }

    init {
        restorePersistedState()
    }

    fun importStl(uri: Uri) {
        if (deferUntilRestoreCompletes { importStl(uri) }) return
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
                            state = stateSnapshot.copy(supportPaint = SupportPaintState()),
                        ),
                    )
                }
                prepared
            }.onSuccess { prepared ->
                sourceMesh = prepared.source
                _uiState.update { current ->
                    current.copy(
                        mesh = prepared.transformed,
                        modelPath = prepared.modelFile.absolutePath,
                        modelPlacement = prepared.placement,
                        supportPaint = SupportPaintState(),
                        paintMode = SupportPaintMode.NONE,
                        importedSceneTransformAvailable = sceneSnapshot?.affine != null,
                        importedSceneModelName = sceneSnapshot?.modelName,
                        sliceResultId = null,
                        gcodePath = null,
                        baseGcodePath = null,
                        layerPreview = null,
                        layerEvents = emptyList(),
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
        if (deferUntilRestoreCompletes { importPartTopoResult(uri) }) return
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
                                state = stateSnapshot.copy(supportPaint = SupportPaintState()),
                            ),
                        )
                    }
                } catch (error: Throwable) {
                    prepared.modelFile.delete()
                    throw error
                }
                prepared
            }.onSuccess { prepared ->
                sourceMesh = prepared.source
                importedScene = null
                _uiState.update { current ->
                    current.copy(
                        mesh = prepared.transformed,
                        modelPath = prepared.modelFile.absolutePath,
                        modelPlacement = prepared.placement,
                        supportPaint = SupportPaintState(),
                        paintMode = SupportPaintMode.NONE,
                        importedSceneTransformAvailable = false,
                        importedSceneModelName = null,
                        sliceResultId = null,
                        gcodePath = null,
                        baseGcodePath = null,
                        layerPreview = null,
                        layerEvents = emptyList(),
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
        if (deferUntilRestoreCompletes { importCuraProfile(uri) }) return
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
        if (deferUntilRestoreCompletes { importCuraProject(uri) }) return
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
        val changed = transform(current.settings)
            .copy(overriddenSettingKeys = current.settings.overriddenSettingKeys + key)
            .withRecomputedDerived()
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
        persistSettings(changed, workspaceMutationGeneration.incrementAndGet())
    }

    fun updatePrusaSettings(
        key: String,
        transform: (PrusaSliceSettings) -> PrusaSliceSettings,
    ) {
        val current = _uiState.value
        if (current.isBusy) return
        val changed = transform(current.prusaSettings)
        _uiState.update { state ->
            state.copy(
                prusaSettings = changed,
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
        persistPrusaSettings(changed)
    }

    private fun persistPrusaSettings(settings: PrusaSliceSettings) {
        viewModelScope.launch(Dispatchers.IO) {
            stateStore.savePrusaSettings(settings)
        }
    }

    fun resetAllSettingOverrides() {
        if (_uiState.value.isBusy) return
        val baseline = importedSettingsBaseline ?: SlicerSettings()
        val restored = baseline.copy(overriddenSettingKeys = emptySet()).withRecomputedDerived()
        persistSettings(restored, workspaceMutationGeneration.incrementAndGet())
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
                sourceMesh = null
                importedScene = null
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

    fun scaleModel(percent: Double) {
        val label = if (percent == percent.toLong().toDouble()) {
            percent.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", percent).trimEnd('0').trimEnd('.')
        }
        changePlacement("Model scaled to $label%") { placement, _ ->
            placement.scaled(percent)
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

    fun setPaintMode(mode: SupportPaintMode) {
        _uiState.update { it.copy(paintMode = mode) }
    }

    fun setBrushRadius(mm: Double) {
        if (!mm.isFinite()) return
        val radius = mm.coerceIn(SupportPaintState.MIN_BRUSH_RADIUS_MM, SupportPaintState.MAX_BRUSH_RADIUS_MM)
        _uiState.update { current ->
            current.copy(supportPaint = current.supportPaint.copy(brushRadiusMm = radius))
        }
        persistPaintSoon()
    }

    private fun persistPaintSoon() {
        val snapshot = _uiState.value
        paintPersistenceJob?.cancel()
        paintPersistenceJob = viewModelScope.launch {
            delay(PAINT_PERSIST_DEBOUNCE_MILLIS)
            persistCurrentWorkspace(snapshot)
        }
    }

    fun paintAt(hit: MeshPicker.Hit) {
        val snapshot = _uiState.value
        val mesh = snapshot.mesh ?: return
        if (snapshot.paintMode == SupportPaintMode.NONE) return
        if (snapshot.isBusy) return
        val radiusMm = snapshot.supportPaint.brushRadiusMm.toFloat()
        viewModelScope.launch(Dispatchers.Default) {
            val triangles = SupportPaintBrush.expand(
                mesh = mesh,
                hitX = hit.x,
                hitY = hit.y,
                hitZ = hit.z,
                radiusMm = radiusMm,
            )
            if (triangles.isEmpty()) return@launch
            _uiState.update { current ->
                val updated = when (current.paintMode) {
                    SupportPaintMode.ENFORCER -> current.supportPaint.withEnforcer(triangles)
                    SupportPaintMode.BLOCKER -> current.supportPaint.withBlocker(triangles)
                    SupportPaintMode.ERASE -> current.supportPaint.erased(triangles)
                    SupportPaintMode.NONE -> current.supportPaint
                }
                current.copy(supportPaint = updated)
            }
            persistPaintSoon()
        }
    }

    fun clearSupportPaint() {
        _uiState.update { it.copy(supportPaint = SupportPaintState()) }
        persistPaintSoon()
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
        val sliceEngine = activeEngine
        if (!beginOperation(if (sliceEngine == SlicerEngine.PRUSA) "PrusaSlicer is slicing…" else "CuraEngine is slicing…")) return
        if (NonPlanarRuntime.snapshot() != null && ConicalRuntime.snapshot() != null) {
            _uiState.update {
                it.copy(
                    isBusy = false,
                    statusMessage = "Non-planar and conical slicing are mutually exclusive; disable one before slicing",
                    sliceResultId = null,
                    gcodePath = null,
                    baseGcodePath = null,
                    layerPreview = null,
                    estimatedPrintSeconds = null,
                )
            }
            return
        }

        viewModelScope.launch {
            var strategyMessage: String? = null
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
                        if (sliceEngine == SlicerEngine.PRUSA) {
                            val prusaResult = prusaEngine.slice(
                                modelFile = transformedFile,
                                printer = snapshot.printer,
                                settings = snapshot.prusaSettings,
                                machineSettings = snapshot.settings,
                                startGcode = snapshot.startGcode,
                                endGcode = snapshot.endGcode,
                                onProgress = { percent ->
                                    _uiState.update {
                                        it.copy(statusMessage = "PrusaSlicer is slicing… $percent%")
                                    }
                                },
                            )
                            EngineSliceOutcome(
                                artifactId = prusaResult.artifactId,
                                gcodeFile = prusaResult.gcodeFile,
                                baseGcodeFile = prusaResult.baseGcodeFile,
                                logFile = prusaResult.logFile,
                                elapsedMilliseconds = prusaResult.elapsedMilliseconds,
                                estimatedPrintSeconds = prusaResult.estimatedPrintSeconds,
                                layerPreview = prusaResult.layerPreview,
                                layerEvents = prusaResult.layerEvents,
                                nozzleCollisionAlert = null,
                                collisionSweepFailure = null,
                            )
                        } else {
                            val smartResolution = SmartOverhangStrategy.resolve(
                                settings = snapshot.settings,
                                nonPlanarSettings = NonPlanarRuntime.current(),
                                mesh = transformedMesh,
                                layerHeightMm = snapshot.settings.layerHeightMm,
                                nozzleDiameterMm = snapshot.printer.withSettings(snapshot.settings).nozzleSizeMm,
                            )
                            strategyMessage = smartResolution.message
                            val curaResult = engine.slice(
                                modelFile = transformedFile,
                                printer = snapshot.printer,
                                settings = smartResolution.settings,
                                startGcode = snapshot.startGcode,
                                endGcode = snapshot.endGcode,
                                profile = snapshot.engineProfile,
                                layerEvents = snapshot.layerEvents.filter { it.source == LayerEventSource.USER },
                                supportPaint = snapshot.supportPaint,
                            )
                            EngineSliceOutcome(
                                artifactId = curaResult.artifactId,
                                gcodeFile = curaResult.gcodeFile,
                                baseGcodeFile = curaResult.baseGcodeFile,
                                logFile = curaResult.logFile,
                                elapsedMilliseconds = curaResult.elapsedMilliseconds,
                                estimatedPrintSeconds = curaResult.estimatedPrintSeconds,
                                layerPreview = curaResult.layerPreview,
                                layerEvents = curaResult.layerEvents,
                                nozzleCollisionAlert = curaResult.nozzleCollisionAlert,
                                collisionSweepFailure = curaResult.collisionSweepFailure,
                            )
                        }
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
                            if (strategyMessage != null) append(" · $strategyMessage")
                            result.nozzleCollisionAlert?.let { alert ->
                                val zone = when (alert.worstViolationZone) {
                                    2 -> "the heating block"
                                    3 -> "the plate clearance"
                                    else -> "the nozzle cone"
                                }
                                val layersSuffix = alert.offendingLayers
                                    .takeIf { it.isNotEmpty() }
                                    ?.let { " on layers " + it.sorted().joinToString(", ") }
                                    ?: ""
                                append(
                                    " · ⚠ nozzle collision risk: up to " +
                                        "%.1f mm into $zone".format(alert.maximumViolationMm) +
                                        layersSuffix +
                                        if (alert.cutoffViolatingMoves > 0) {
                                            " · material exceeds the holding-object clearance"
                                        } else {
                                            ""
                                        },
                                )
                            }
                            result.collisionSweepFailure?.let { failure ->
                                append(" · ⚠ nozzle collision sweep failed: $failure")
                            }
                            if (result.layerPreview == null) append(" · layer preview unavailable; see diagnostic log")
                            if (result.layerEvents.isNotEmpty()) append(" · ${result.layerEvents.size} layer events")
                        },
                    )
                }
                previousArtifactId
                    ?.takeIf { it != result.artifactId }
                    ?.let { if (sliceEngine == SlicerEngine.PRUSA) prusaEngine.releaseArtifact(it) else engine.releaseArtifact(it) }
            }.onFailure(::showSliceFailure)
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
        reapplyLayerEvents(emptyList(), "User layer events cleared")
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
                        sliceResultId = result.artifactId,
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
        if (deferUntilRestoreCompletes { exportGcode(uri) }) return
        val artifactSnapshot = _uiState.value
        val sourcePath = artifactSnapshot.gcodePath
        val expectedArtifactId = artifactSnapshot.sliceResultId
        if (sourcePath == null || expectedArtifactId == null) {
            showOperationFailure(IllegalStateException("Slice the model before exporting G-code"))
            return
        }

        if (!beginOperation("Exporting G-code…")) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val source = File(sourcePath)
                    check(SliceArtifactPublisher.isCompleteGcode(source, expectedArtifactId)) {
                        "Generated G-code is incomplete, stale, or no longer available"
                    }
                    pendingExportStore.begin(uri)
                    try {
                        SliceArtifactPublisher.acquireLease(source, expectedArtifactId).use {
                            val written = app.contentResolver.openOutputStream(uri, "w")?.buffered()?.use { output ->
                                source.inputStream().buffered().use { input -> input.copyTo(output).also { output.flush() } }
                            } ?: error("Unable to open the G-code destination")
                            check(written == source.length()) { "The G-code export ended before every byte was written" }
                        }
                        pendingExportStore.complete(uri)
                    } catch (error: Throwable) {
                        pendingExportStore.fail(app.contentResolver, uri)
                        throw error
                    }
                }
            }.onSuccess {
                _uiState.update { it.copy(isBusy = false, statusMessage = "G-code exported") }
            }.onFailure(::showOperationFailure)
        }
    }

    fun exportConfiguration(uri: Uri) {
        if (deferUntilRestoreCompletes { exportConfiguration(uri) }) return
        if (!beginOperation("Exporting configuration…")) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = configurationJson(_uiState.value).toString(2).toByteArray(Charsets.UTF_8)
                    pendingExportStore.begin(uri)
                    try {
                        val written = app.contentResolver.openOutputStream(uri, "w")?.buffered()?.use { output ->
                            output.write(bytes)
                            output.flush()
                            bytes.size.toLong()
                        } ?: error("Unable to open the export destination")
                        check(written == bytes.size.toLong()) { "The configuration export was incomplete" }
                        pendingExportStore.complete(uri)
                    } catch (error: Throwable) {
                        pendingExportStore.fail(app.contentResolver, uri)
                        throw error
                    }
                }
            }.onSuccess {
                _uiState.update { it.copy(isBusy = false, statusMessage = "Configuration exported") }
            }.onFailure(::showOperationFailure)
        }
    }

    fun importConfiguration(uri: Uri) {
        if (deferUntilRestoreCompletes { importConfiguration(uri) }) return
        if (!beginOperation("Importing configuration snapshot…")) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    retainReadPermission(uri)
                    val sourceName = displayName(uri)
                    val root = app.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { JSONObject(it.readText()) }
                        ?: error("Unable to open the selected configuration snapshot")
                    val format = root.optString("format")
                    val version = root.optInt("version", -1)
                    require(format == CONFIG_SNAPSHOT_FORMAT) {
                        "The selected file is not an EnderSlicerCura configuration snapshot"
                    }
                    require(version == CONFIG_SNAPSHOT_VERSION) {
                        "Unsupported configuration snapshot version $version"
                    }
                    val values = root.optJSONObject("settings")
                        ?: error("The configuration snapshot has no settings")
                    val snapshot = SlicerSettingsJson.apply(SlicerSettings(), values, SlicerSettingsJson.allKeys)
                        .copy(overriddenSettingKeys = emptySet())
                    val startGcode = root.optString("startGcode", initialStartGcode)
                    val endGcode = root.optString("endGcode", initialEndGcode)
                    SnapshotImport(snapshot, startGcode, endGcode, sourceName)
                }
            }.onSuccess { pending -> commitSnapshotImport(pending) }
                .onFailure(::showOperationFailure)
        }
    }

    private suspend fun commitSnapshotImport(pending: SnapshotImport) {
        val pendingSettingsWrite = settingsPersistenceJob
        runCatching {
            withContext(Dispatchers.IO) {
                pendingSettingsWrite?.join()
                stateStore.clearImport()
                stateStore.saveSnapshotBaseline(
                    AppStateStore.SnapshotBaseline(
                        settings = pending.settings,
                        startGcode = pending.startGcode,
                        endGcode = pending.endGcode,
                        profileName = pending.sourceName,
                        profileSource = "Configuration snapshot",
                    ),
                )
                stateStore.saveSettings(pending.settings)
            }
        }.onFailure {
            showOperationFailure(it)
            return
        }
        importedSettingsBaseline = pending.settings
        importedScene = null
        _uiState.update { current ->
            current.copy(
                settings = pending.settings,
                startGcode = pending.startGcode,
                endGcode = pending.endGcode,
                profileName = pending.sourceName,
                profileSource = "Configuration snapshot",
                importedRawSettingCount = SlicerSettingsJson.allKeys.size,
                curaVersion = null,
                settingVersion = "27",
                engineProfile = null,
                importedSceneTransformAvailable = false,
                importedSceneModelName = null,
                sliceResultId = null,
                gcodePath = null,
                baseGcodePath = null,
                layerPreview = null,
                layerEvents = emptyList(),
                estimatedPrintSeconds = null,
                sliceLogPath = null,
                sliceDurationMilliseconds = null,
                isBusy = false,
                statusMessage = "Imported ${pending.sourceName}; settings and custom G-code are active until overridden",
            )
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
                    // Reject placements that leave the model hanging off the
                    // build volume or above the printer height so the user gets
                    // immediate feedback instead of a slice-time failure.
                    PrinterEnvelope.from(printer.withSettings(stateSnapshot.settings)).requireModelFits(transformed)
                    changed to transformed
                }
                val durableModel = requireNotNull(modelPath).let(::File)
                    ?: error("The active model path is unavailable")
                withContext(Dispatchers.IO) {
                    workspaceStore.save(
                        workspaceSnapshot(
                            modelFile = durableModel,
                            displayName = original.displayName,
                            placement = prepared.first,
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

    fun deferUntilRestoreCompletes(action: () -> Unit): Boolean = synchronized(deferredRestoreActions) {
        if (!restoringPersistedState) {
            false
        } else {
            deferredRestoreActions.addLast(action)
            true
        }
    }

    private fun finishRestoreAndReplayResults() {
        val actions = synchronized(deferredRestoreActions) {
            restoringPersistedState = false
            val pending = deferredRestoreActions.toList()
            deferredRestoreActions.clear()
            pending
        }
        if (actions.isEmpty()) return
        viewModelScope.launch {
            actions.forEach { action ->
                if (_uiState.value.isBusy) uiState.first { state -> !state.isBusy }
                action()
                if (_uiState.value.isBusy) uiState.first { state -> !state.isBusy }
            }
        }
    }

    private fun restorePersistedState() {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    pendingExportStore.recover(app.contentResolver)
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
                    val snapshotBaseline = if (saved == null) stateStore.snapshotBaseline() else null
                    val scene = saved?.takeIf { it.kind == AppStateStore.KIND_PROJECT }
                        ?.file
                        ?.inputStream()
                        ?.use(CuraProjectSceneParser::parse)
                    val baseSettings = config?.mappedSettings ?: snapshotBaseline?.settings ?: SlicerSettings()
                    val settings = stateStore.restoreSettings(baseSettings).withRecomputedDerived()
                    val effectiveStartGcode = config?.startGcode ?: snapshotBaseline?.startGcode ?: initialStartGcode
                    val effectiveEndGcode = config?.endGcode ?: snapshotBaseline?.endGcode ?: initialEndGcode
                    val effectiveProfileName = config?.name
                        ?: snapshotBaseline?.profileName
                        ?: "Built-in current Cura settings"
                    val effectiveProfileSource = config?.source
                        ?: snapshotBaseline?.profileSource
                        ?: "Cura 5.14.0-alpha.0 / setting version 27 reference"
                    val fingerprint = workspaceFingerprint(
                        config,
                        settings,
                        effectiveStartGcode,
                        effectiveEndGcode,
                        effectiveProfileName,
                        effectiveProfileSource,
                    )
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
                                fingerprintMatches = fingerprintMatches,
                            )
                        }
                    }.getOrNull()
                    RestoredImport(
                        config = config,
                        settings = settings,
                        scene = scene,
                        workspace = workspace,
                        startGcode = effectiveStartGcode,
                        endGcode = effectiveEndGcode,
                        profileName = effectiveProfileName,
                        profileSource = effectiveProfileSource,
                        baselineSettings = snapshotBaseline?.settings,
                    )
                }
            }

            result.onSuccess { restored ->
                importedScene = restored.scene
                sourceMesh = restored.workspace?.source
                if (restored.config == null) {
                    importedSettingsBaseline = restored.baselineSettings
                    _uiState.update {
                        it.copy(
                            settings = restored.settings,
                            startGcode = restored.startGcode,
                            endGcode = restored.endGcode,
                            profileName = restored.profileName,
                            profileSource = restored.profileSource,
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
            finishRestoreAndReplayResults()
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
                supportPaint = snapshot.supportPaint.clippedToMesh(workspace.transformed.triangleCount),
                sliceResultId = null,
                gcodePath = null,
                baseGcodePath = null,
                layerPreview = null,
                layerEvents = emptyList(),
                estimatedPrintSeconds = null,
                sliceLogPath = null,
                sliceDurationMilliseconds = null,
                isBusy = false,
                statusMessage = "Restored ${snapshot.modelDisplayName} workspace; slice again to create validated G-code",
            )
        }
    }

    private fun workspaceSnapshot(
        modelFile: File,
        displayName: String,
        placement: ModelPlacement,
        state: MainUiState,
    ): WorkspaceStateStore.Snapshot {
        return WorkspaceStateStore.Snapshot(
            modelPath = modelFile.absolutePath,
            modelDisplayName = displayName,
            placement = placement,
            configurationFingerprint = workspaceFingerprint(state),
            supportPaint = state.supportPaint,
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
        startGcode: String,
        endGcode: String,
        profileName: String,
        profileSource: String,
    ): String = WorkspaceStateStore.fingerprint(
        config?.name ?: profileName,
        config?.source ?: profileSource,
        config?.curaVersion,
        config?.settingVersion ?: "27",
        settings,
        startGcode,
        endGcode,
    )

    private fun persistSettings(settings: SlicerSettings, generation: Long) {
        val stateSnapshot = _uiState.value.copy(settings = settings)
        val previousWrite = settingsPersistenceJob
        settingsPersistenceJob = viewModelScope.launch(Dispatchers.IO) {
            previousWrite?.join()
            val settingsCommitted = stateStore.saveSettings(settings)
            if (settingsCommitted && workspaceMutationGeneration.get() == generation) {
                persistCurrentWorkspace(stateSnapshot)
            }
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
                stateStore.clearSnapshotBaseline()
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
                settingVersion = config.settingVersion ?: "27",
                engineProfile = config.engineProfile,
                startGcode = config.startGcode ?: initialStartGcode,
                endGcode = config.endGcode ?: initialEndGcode,
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
                statusMessage = statusMessage
                    ?: buildString {
                        append("Imported $concreteCount concrete Cura values$definitionLabel")
                        if (autoPlacement != null) append(" and applied the matching scene transform")
                        append("; imported values remain active until overridden")
                    },
            )
        }
        withContext(Dispatchers.IO) { persistCurrentWorkspace(_uiState.value) }
        _uiState.update { it.copy(isBusy = false) }
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
        workspaceMutationGeneration.incrementAndGet()
        _uiState.update { it.copy(isBusy = true, statusMessage = message) }
        return true
    }

    private fun showOperationFailure(error: Throwable) {
        if (error is CancellationException) throw error
        _uiState.update { current ->
            current.copy(
                isBusy = false,
                statusMessage = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private fun showSliceFailure(error: Throwable) {
        if (error is CancellationException) throw error
        _uiState.update { current ->
            current.copy(
                isBusy = false,
                sliceResultId = null,
                gcodePath = null,
                baseGcodePath = null,
                layerPreview = null,
                layerEvents = emptyList(),
                estimatedPrintSeconds = null,
                sliceLogPath = (error as? CuraEngineRunner.SliceException)?.logFile?.absolutePath
                    ?: (error as? PrusaEngineRunner.SliceException)?.logFile?.absolutePath
                    ?: current.sliceLogPath,
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

    private fun configurationJson(state: MainUiState): JSONObject {
        val settings = state.settings
        val placement = state.modelPlacement
        return JSONObject()
            .put("format", CONFIG_SNAPSHOT_FORMAT)
            .put("version", CONFIG_SNAPSHOT_VERSION)
            .put("printer", state.printer.name)
            .put("profileName", state.profileName)
            .put("profileSource", state.profileSource)
            .put("curaVersion", state.curaVersion)
            .put("settingVersion", state.settingVersion)
            .put("importedValues", state.importedRawSettingCount)
            .put("appOverrideKeys", settings.overriddenSettingKeys.sorted())
            .put("estimatedPrintSeconds", state.estimatedPrintSeconds)
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
            .put("settings", SlicerSettingsJson.serialize(settings))
            .put("startGcode", state.startGcode)
            .put("endGcode", state.endGcode)
    }
}
