package com.tomppi.enderslicer.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tomppi.enderslicer.data.AppStateStore
import com.tomppi.enderslicer.data.PrinterDefinitionLoader
import com.tomppi.enderslicer.engine.CuraEngineRunner
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.profile.CuraProfileParser
import com.tomppi.enderslicer.profile.CuraProjectParser
import com.tomppi.enderslicer.profile.ImportedCuraConfig
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

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private data class PendingImport(
        val config: ImportedCuraConfig,
        val stagedFile: File,
        val kind: String,
        val displayName: String,
    )

    private val app = application
    private val printer = PrinterDefinitionLoader.loadModifiedEnder3V2(app.assets)
    private val engine = CuraEngineRunner(app)
    private val stateStore = AppStateStore(app)
    private val initialStartGcode = readAsset("gcode/start.gcode")
    private val initialEndGcode = readAsset("gcode/end.gcode")
    private var importedSettingsBaseline: SlicerSettings? = null

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
                _uiState.update {
                    it.copy(
                        mesh = mesh,
                        modelPath = modelFile.absolutePath,
                        gcodePath = null,
                        layerPreview = null,
                        estimatedPrintSeconds = null,
                        sliceLogPath = null,
                        sliceDurationMilliseconds = null,
                        isBusy = false,
                        statusMessage = "Loaded ${mesh.displayName}: ${mesh.triangleCount} triangles",
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
                    stageAndParseImport(uri, AppStateStore.KIND_PROJECT, sourceName) { file ->
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
                layerPreview = null,
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
                layerPreview = null,
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

    fun sliceModel() {
        val snapshot = _uiState.value
        val modelPath = snapshot.modelPath
        if (modelPath == null) {
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
                    engine.slice(
                        modelFile = File(modelPath),
                        printer = snapshot.printer,
                        settings = snapshot.settings,
                        startGcode = snapshot.startGcode,
                        endGcode = snapshot.endGcode,
                        profile = snapshot.engineProfile,
                    )
                }
            }.onSuccess { result ->
                _uiState.update {
                    val printTime = result.estimatedPrintSeconds?.let(::formatPrintTime)
                    it.copy(
                        gcodePath = result.gcodeFile.absolutePath,
                        layerPreview = result.layerPreview,
                        estimatedPrintSeconds = result.estimatedPrintSeconds,
                        sliceLogPath = result.logFile.absolutePath,
                        sliceDurationMilliseconds = result.elapsedMilliseconds,
                        isBusy = false,
                        statusMessage = buildString {
                            append("Sliced ${formatFileSize(result.gcodeFile.length())} of validated G-code in ${formatDuration(result.elapsedMilliseconds)}")
                            if (printTime != null) append(" · estimated print $printTime")
                            if (result.layerPreview == null) append(" · layer preview unavailable; see diagnostic log")
                        },
                    )
                }
            }.onFailure(::showFailure)
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

    private fun restorePersistedState() {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val saved = stateStore.savedImport()
                    if (saved == null) {
                        null to stateStore.restoreSettings(SlicerSettings())
                    } else {
                        val config = saved.file.inputStream().use { input ->
                            when (saved.kind) {
                                AppStateStore.KIND_PROJECT -> CuraProjectParser.parse(input, saved.displayName, SlicerSettings())
                                AppStateStore.KIND_PROFILE -> CuraProfileParser.parse(input, saved.displayName, SlicerSettings())
                                else -> error("Unknown persisted Cura import kind: ${saved.kind}")
                            }
                        }
                        config to stateStore.restoreSettings(config.mappedSettings)
                    }
                }
            }

            result.onSuccess { (config, settings) ->
                if (config == null) {
                    importedSettingsBaseline = null
                    _uiState.update {
                        it.copy(
                            settings = settings,
                            statusMessage = if (settings.overriddenSettingKeys.isEmpty()) {
                                it.statusMessage
                            } else {
                                "Restored ${settings.overriddenSettingKeys.size} saved app setting overrides"
                            },
                        )
                    }
                } else {
                    applyImportedConfig(
                        config = config,
                        settings = settings,
                        statusMessage = "Restored ${config.name} and ${settings.overriddenSettingKeys.size} app overrides",
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
        val baseline = pending.config.mappedSettings.copy(overriddenSettingKeys = emptySet())
        stateStore.saveSettings(baseline)
        applyImportedConfig(
            config = pending.config,
            settings = baseline,
            statusMessage = null,
        )
    }

    private fun applyImportedConfig(
        config: ImportedCuraConfig,
        settings: SlicerSettings,
        statusMessage: String?,
    ) {
        importedSettingsBaseline = config.mappedSettings.copy(overriddenSettingKeys = emptySet())
        _uiState.update { current ->
            val concreteCount = config.engineProfile?.concreteSettingCount ?: config.rawValues.size
            val definitionLabel = if (config.engineProfile?.usesProjectDefinitions == true) {
                " with project machine/extruder definitions"
            } else {
                ""
            }
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
                gcodePath = null,
                layerPreview = null,
                estimatedPrintSeconds = null,
                sliceLogPath = null,
                sliceDurationMilliseconds = null,
                warnings = config.warnings,
                isBusy = false,
                statusMessage = statusMessage
                    ?: "Imported $concreteCount concrete Cura values$definitionLabel; imported values remain active until overridden",
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

    private fun busy(message: String) {
        _uiState.update { it.copy(isBusy = true, statusMessage = message) }
    }

    private fun showFailure(error: Throwable) {
        _uiState.update {
            it.copy(
                isBusy = false,
                gcodePath = null,
                layerPreview = null,
                estimatedPrintSeconds = null,
                sliceLogPath = (error as? CuraEngineRunner.SliceException)?.logFile?.absolutePath ?: it.sliceLogPath,
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
        return JSONObject()
            .put("printer", state.printer.name)
            .put("profileName", state.profileName)
            .put("profileSource", state.profileSource)
            .put("curaVersion", state.curaVersion)
            .put("settingVersion", state.settingVersion)
            .put("importedValues", state.importedRawSettingCount)
            .put("appOverrideKeys", settings.overriddenSettingKeys.sorted())
            .put("estimatedPrintSeconds", state.estimatedPrintSeconds)
            .put(
                "settings",
                JSONObject()
                    .put("layerHeightMm", settings.layerHeightMm)
                    .put("initialLayerHeightMm", settings.initialLayerHeightMm)
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
