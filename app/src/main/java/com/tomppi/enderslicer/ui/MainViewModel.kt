package com.tomppi.enderslicer.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tomppi.enderslicer.calibration.CalibrationTowerGenerator
import com.tomppi.enderslicer.calibration.CalibrationTowerSpec
import com.tomppi.enderslicer.data.AppStateStore
import com.tomppi.enderslicer.data.PrinterDefinitionLoader
import com.tomppi.enderslicer.engine.CuraEngineRunner
import com.tomppi.enderslicer.engine.LayerEvent
import com.tomppi.enderslicer.engine.LayerEventSource
import com.tomppi.enderslicer.engine.LayerEventType
import com.tomppi.enderslicer.engine.PlannedLayerEvent
import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.model.SlicerSettings
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
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
    )

    private val app = application
    private val printer = PrinterDefinitionLoader.loadModifiedEnder3V2(app.assets)
    private val engine = CuraEngineRunner(app)
    private val stateStore = AppStateStore(app)
    private val initialStartGcode = readAsset("gcode/start.gcode")
    private val initialEndGcode = readAsset("gcode/end.gcode")
    private var importedSettingsBaseline: SlicerSettings? = null
    private var sourceMesh: StlMesh? = null
    private var importedScene: CuraProjectScene? = null
    private var plannedCalibrationEvents: List<PlannedLayerEvent> = emptyList()
    private val layerEventSequence = AtomicLong(0L)

    private val _uiState = MutableStateFlow(
        MainUiState(
            printer = printer,
            startGcode = initialStartGcode,
            endGcode = initialEndGcode,
            engineStatus = engine.status(),
            engineAvailable = engine.isAvailable(),
        ),
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        restorePersistedState()
    }

    fun importStl(uri: Uri) {
        retainReadPermission(uri)
        viewModelScope.launch {
            busy("Reading STL…")
            runCatching {
                withContext(Dispatchers.IO) {
                    val mesh = StlParser.parse(app.contentResolver, uri)
                    val modelFile = materializeModel(uri)
                    mesh to modelFile
                }
            }.onSuccess { (mesh, modelFile) ->
                sourceMesh = mesh
                plannedCalibrationEvents = emptyList()
                val automaticImportedPlacement = importedScene
                    ?.takeIf { scene -> scene.affine != null && modelNamesMatch(scene.modelName, mesh.displayName) }
                    ?.let { scene -> ModelPlacement.from3mf(mesh, requireNotNull(scene.affine), scene.dropToBuildPlate) }
                val placement = automaticImportedPlacement
                    ?: ModelPlacement.centeredOnBed(mesh, printer.widthMm, printer.depthMm)
                val transformed = placement.transformed(mesh)
                val mismatchWarning = importedScene
                    ?.takeIf { it.affine != null && !modelNamesMatch(it.modelName, mesh.displayName) }
                    ?.let { scene ->
                        "Imported Cura transform is for ${scene.modelName ?: "another model"}; it was not applied automatically to ${mesh.displayName}"
                    }
                _uiState.update { current ->
                    current.copy(
                        mesh = transformed,
                        modelPath = modelFile.absolutePath,
                        modelPlacement = placement,
                        importedSceneTransformAvailable = importedScene?.affine != null,
                        importedSceneModelName = importedScene?.modelName,
                        gcodePath = null,
                        baseGcodePath = null,
                        layerPreview = null,
                        layerEvents = emptyList(),
                        calibrationDescription = null,
                        estimatedPrintSeconds = null,
                        sliceLogPath = null,
                        sliceDurationMilliseconds = null,
                        warnings = (current.warnings.filterNot { it.startsWith("Imported Cura transform is for") } + listOfNotNull(mismatchWarning)).distinct(),
                        isBusy = false,
                        statusMessage = buildString {
                            append("Loaded ${mesh.displayName}: ${mesh.triangleCount} triangles")
                            if (automaticImportedPlacement != null) append(" · imported Cura scene transform applied")
                        },
                    )
                }
            }.onFailure(::showFailure)
        }
    }

    fun importCuraProfile(uri: Uri) {
        retainReadPermission(uri)
        val sourceName = displayName(uri)
        viewModelScope.launch {
            busy("Importing Cura profile…")
            runCatching {
                withContext(Dispatchers.IO) {
                    stageAndParseImport(uri, AppStateStore.KIND_PROFILE, sourceName) { file ->
                        file.inputStream().use { input ->
                            CuraProfileParser.parse(input, sourceName, SlicerSettings())
                        }
                    }
                }
            }.onSuccess(::commitImportedConfig)
                .onFailure(::showFailure)
        }
    }

    fun importCuraProject(uri: Uri) {
        retainReadPermission(uri)
        val sourceName = displayName(uri)
        viewModelScope.launch {
            busy("Importing Cura project…")
            runCatching {
                withContext(Dispatchers.IO) {
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
            }.onSuccess(::commitImportedConfig)
                .onFailure(::showFailure)
        }
    }

    fun updateSettings(
        key: String,
        transform: (SlicerSettings) -> SlicerSettings,
    ) {
        _uiState.update { current ->
            val changed = transform(current.settings).copy(
                overriddenSettingKeys = current.settings.overriddenSettingKeys + key,
            )
            stateStore.saveSettings(changed)
            current.copy(
                settings = changed,
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
    }

    fun resetAllSettingOverrides() {
        val baseline = importedSettingsBaseline ?: SlicerSettings()
        val restored = baseline.copy(overriddenSettingKeys = emptySet())
        stateStore.saveSettings(restored)
        _uiState.update {
            it.copy(
                settings = restored,
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
        changePlacement("Model transform reset and centered") { _, mesh ->
            ModelPlacement.centeredOnBed(mesh, printer.widthMm, printer.depthMm)
        }
    }

    fun applyImportedSceneTransform() {
        val scene = importedScene
        val affine = scene?.affine
        if (scene == null || affine == null) {
            showFailure(IllegalStateException("The imported Cura project has no object transform"))
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
            showFailure(IllegalStateException("Import an STL before slicing"))
            return
        }
        if (!engine.isAvailable()) {
            showFailure(IllegalStateException(engine.status()))
            return
        }

        viewModelScope.launch {
            busy("CuraEngine is slicing…")
            runCatching {
                withContext(Dispatchers.IO) {
                    val transformedFile = File(app.cacheDir, "model-placement/current-transformed.stl")
                    StlMeshWriter.writeBinary(transformedMesh, transformedFile)
                    engine.slice(
                        modelFile = transformedFile,
                        printer = snapshot.printer,
                        settings = snapshot.settings,
                        startGcode = snapshot.startGcode,
                        endGcode = snapshot.endGcode,
                        profile = snapshot.engineProfile,
                        layerEvents = snapshot.layerEvents.filter { it.source == LayerEventSource.USER },
                        plannedLayerEvents = plannedCalibrationEvents,
                    )
                }
            }.onSuccess { result ->
                _uiState.update {
                    val printTime = result.estimatedPrintSeconds?.let(::formatPrintTime)
                    it.copy(
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
            }.onFailure(::showFailure)
        }
    }

    fun generateCalibrationTower(spec: CalibrationTowerSpec) {
        val snapshot = _uiState.value
        viewModelScope.launch {
            busy("Generating calibration tower…")
            runCatching {
                withContext(Dispatchers.Default) {
                    CalibrationTowerGenerator.generate(spec, snapshot.settings.retractionSpeedMmPerSecond)
                }
            }.onSuccess { result ->
                val directory = File(app.filesDir, "models").apply { mkdirs() }
                val modelFile = File(directory, "current.stl")
                StlMeshWriter.writeBinary(result.mesh, modelFile)
                sourceMesh = result.mesh
                importedScene = null
                plannedCalibrationEvents = result.plannedEvents
                val placement = ModelPlacement.centeredOnBed(result.mesh, printer.widthMm, printer.depthMm)
                val settings = if (result.requiresFirmwareRetraction && !snapshot.settings.firmwareRetraction) {
                    snapshot.settings.copy(
                        firmwareRetraction = true,
                        overriddenSettingKeys = snapshot.settings.overriddenSettingKeys + SlicerSettings.Keys.FIRMWARE_RETRACTION,
                    ).also(stateStore::saveSettings)
                } else {
                    snapshot.settings
                }
                _uiState.update { current ->
                    current.copy(
                        settings = settings,
                        mesh = placement.transformed(result.mesh),
                        modelPath = modelFile.absolutePath,
                        modelPlacement = placement,
                        importedSceneTransformAvailable = false,
                        importedSceneModelName = null,
                        gcodePath = null,
                        baseGcodePath = null,
                        layerPreview = null,
                        layerEvents = emptyList(),
                        calibrationDescription = result.description,
                        estimatedPrintSeconds = null,
                        sliceLogPath = null,
                        sliceDurationMilliseconds = null,
                        isBusy = false,
                        statusMessage = buildString {
                            append("Generated ${result.description}; slice to create the stepped calibration G-code")
                            if (result.requiresFirmwareRetraction) append(" · firmware retraction enabled")
                        },
                    )
                }
            }.onFailure(::showFailure)
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
        viewModelScope.launch {
            busy("Applying layer events…")
            runCatching {
                withContext(Dispatchers.IO) { engine.applyLayerEvents(File(basePath), events) }
            }.onSuccess { result ->
                _uiState.update { current ->
                    current.copy(
                        gcodePath = result.gcodeFile.absolutePath,
                        layerPreview = result.layerPreview,
                        layerEvents = result.layerEvents,
                        estimatedPrintSeconds = result.estimatedPrintSeconds,
                        isBusy = false,
                        statusMessage = "$message · ${result.layerEvents.size} active events",
                    )
                }
            }.onFailure(::showEventFailure)
        }
    }

    fun exportGcode(uri: Uri) {
        val sourcePath = _uiState.value.gcodePath
        if (sourcePath == null) {
            showFailure(IllegalStateException("Slice the model before exporting G-code"))
            return
        }

        viewModelScope.launch {
            busy("Exporting G-code…")
            runCatching {
                withContext(Dispatchers.IO) {
                    val source = File(sourcePath)
                    check(source.isFile && source.length() > 0L) { "Generated G-code is no longer available" }
                    app.contentResolver.openOutputStream(uri, "w")?.buffered()?.use { output ->
                        source.inputStream().buffered().use { input -> input.copyTo(output) }
                    } ?: error("Unable to open the G-code destination")
                }
            }.onSuccess {
                _uiState.update { it.copy(isBusy = false, statusMessage = "G-code exported") }
            }.onFailure(::showFailure)
        }
    }

    fun exportConfiguration(uri: Uri) {
        viewModelScope.launch {
            busy("Exporting configuration…")
            runCatching {
                withContext(Dispatchers.IO) {
                    val snapshot = configurationJson(_uiState.value)
                    app.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                        writer.write(snapshot.toString(2))
                    } ?: error("Unable to open the export destination")
                }
            }.onSuccess {
                _uiState.update { it.copy(isBusy = false, statusMessage = "Configuration exported") }
            }.onFailure(::showFailure)
        }
    }

    private fun changePlacement(
        message: String,
        transform: (ModelPlacement, StlMesh) -> ModelPlacement,
    ) {
        val original = sourceMesh
        val current = _uiState.value.modelPlacement
        if (original == null || current == null) {
            showFailure(IllegalStateException("Import an STL before changing model placement"))
            return
        }
        runCatching {
            val changed = transform(current, original)
            changed to changed.transformed(original)
        }.onSuccess { (changed, transformed) ->
            _uiState.update {
                it.copy(
                    mesh = transformed,
                    modelPlacement = changed,
                    gcodePath = null,
                    baseGcodePath = null,
                    layerPreview = null,
                    layerEvents = emptyList(),
                    estimatedPrintSeconds = null,
                    sliceLogPath = null,
                    sliceDurationMilliseconds = null,
                    statusMessage = "$message; slice again to export G-code",
                )
            }
        }.onFailure(::showFailure)
    }

    private fun restorePersistedState() {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val saved = stateStore.savedImport()
                    if (saved == null) {
                        RestoredImport(null, stateStore.restoreSettings(SlicerSettings()), null)
                    } else {
                        val config = saved.file.inputStream().use { input ->
                            when (saved.kind) {
                                AppStateStore.KIND_PROJECT -> CuraProjectParser.parse(input, saved.displayName, SlicerSettings())
                                AppStateStore.KIND_PROFILE -> CuraProfileParser.parse(input, saved.displayName, SlicerSettings())
                                else -> error("Unknown persisted Cura import kind: ${saved.kind}")
                            }
                        }
                        val scene = if (saved.kind == AppStateStore.KIND_PROJECT) {
                            saved.file.inputStream().use(CuraProjectSceneParser::parse)
                        } else {
                            null
                        }
                        RestoredImport(config, stateStore.restoreSettings(config.mappedSettings), scene)
                    }
                }
            }

            result.onSuccess { restored ->
                importedScene = restored.scene
                if (restored.config == null) {
                    importedSettingsBaseline = null
                    _uiState.update {
                        it.copy(
                            settings = restored.settings,
                            importedSceneTransformAvailable = restored.scene?.affine != null,
                            importedSceneModelName = restored.scene?.modelName,
                            statusMessage = if (restored.settings.overriddenSettingKeys.isEmpty()) {
                                it.statusMessage
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
            }.onFailure { error ->
                _uiState.update {
                    it.copy(statusMessage = "Saved Cura configuration could not be restored: ${error.message}")
                }
            }
        }
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

    private fun commitImportedConfig(pending: PendingImport) {
        runCatching {
            stateStore.commitImport(pending.stagedFile, pending.kind, pending.displayName)
            stateStore.clearSavedSettings()
        }.onFailure {
            showFailure(it)
            return
        }
        importedScene = pending.scene
        val baseline = pending.config.mappedSettings.copy(overriddenSettingKeys = emptySet())
        stateStore.saveSettings(baseline)
        applyImportedConfig(
            config = pending.config,
            settings = baseline,
            scene = pending.scene,
            statusMessage = null,
        )
    }

    private fun applyImportedConfig(
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
                mesh = if (autoPlacement != null && original != null) autoPlacement.transformed(original) else current.mesh,
                modelPlacement = autoPlacement ?: current.modelPlacement,
                importedSceneTransformAvailable = scene?.affine != null,
                importedSceneModelName = scene?.modelName,
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
    }

    private fun materializeModel(uri: Uri): File {
        val directory = File(app.filesDir, "models").apply { mkdirs() }
        val target = File(directory, "current.stl")
        val temporary = File(directory, "current.stl.tmp")
        temporary.delete()
        app.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
            temporary.outputStream().buffered().use { output -> input.copyTo(output) }
        } ?: error("Unable to copy the selected STL")
        check(temporary.length() > 0L) { "The selected STL is empty" }
        if (target.exists()) target.delete()
        check(temporary.renameTo(target)) { "Unable to store the selected STL locally" }
        return target
    }

    private fun modelNamesMatch(projectName: String?, stlName: String): Boolean {
        if (projectName.isNullOrBlank()) return true
        fun normalize(value: String): String = value
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringBeforeLast('.', value)
            .lowercase()
            .filter(Char::isLetterOrDigit)
        return normalize(projectName) == normalize(stlName)
    }

    private fun busy(message: String) {
        _uiState.update { it.copy(isBusy = true, statusMessage = message) }
    }

    private fun showFailure(error: Throwable) {
        _uiState.update {
            it.copy(
                isBusy = false,
                gcodePath = null,
                baseGcodePath = null,
                layerPreview = null,
                layerEvents = emptyList(),
                estimatedPrintSeconds = null,
                sliceLogPath = (error as? CuraEngineRunner.SliceException)?.logFile?.absolutePath ?: it.sliceLogPath,
                statusMessage = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private fun showEventFailure(error: Throwable) {
        _uiState.update {
            it.copy(
                isBusy = false,
                statusMessage = error.message ?: error::class.java.simpleName,
            )
        }
    }

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

    private fun readAsset(path: String): String = app.assets.open(path).bufferedReader().use { it.readText() }

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
                    .put("materialFlowPercent", settings.materialFlowPercent),
            )
            .put("startGcode", state.startGcode)
            .put("endGcode", state.endGcode)
    }
}
