package com.tomppi.enderslicer.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    private val app = application
    private val printer = PrinterDefinitionLoader.loadModifiedEnder3V2(app.assets)
    private val engine = CuraEngineRunner(app)
    private val initialStartGcode = readAsset("gcode/start.gcode")
    private val initialEndGcode = readAsset("gcode/end.gcode")

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
        viewModelScope.launch {
            busy("Importing Cura profile…")
            val current = _uiState.value.settings
            runCatching {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        CuraProfileParser.parse(input, displayName(uri), current)
                    } ?: error("Unable to open the selected Cura profile")
                }
            }.onSuccess(::applyImportedConfig)
                .onFailure(::showFailure)
        }
    }

    fun importCuraProject(uri: Uri) {
        retainReadPermission(uri)
        viewModelScope.launch {
            busy("Importing Cura project…")
            val current = _uiState.value.settings
            runCatching {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        CuraProjectParser.parse(input, displayName(uri), current)
                    } ?: error("Unable to open the selected Cura project")
                }
            }.onSuccess(::applyImportedConfig)
                .onFailure(::showFailure)
        }
    }

    fun updateSettings(transform: (SlicerSettings) -> SlicerSettings) {
        _uiState.update {
            it.copy(
                settings = transform(it.settings),
                gcodePath = null,
                sliceLogPath = null,
                sliceDurationMilliseconds = null,
                statusMessage = "Settings changed; slice again to export G-code",
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
                    it.copy(
                        gcodePath = result.gcodeFile.absolutePath,
                        sliceLogPath = result.logFile.absolutePath,
                        sliceDurationMilliseconds = result.elapsedMilliseconds,
                        isBusy = false,
                        statusMessage = "Sliced ${formatFileSize(result.gcodeFile.length())} of validated G-code in ${formatDuration(result.elapsedMilliseconds)}",
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

    private fun applyImportedConfig(config: ImportedCuraConfig) {
        _uiState.update { current ->
            val concreteCount = config.engineProfile?.concreteSettingCount ?: config.rawValues.size
            val definitionLabel = if (config.engineProfile?.usesProjectDefinitions == true) {
                " with project machine/extruder definitions"
            } else {
                ""
            }
            current.copy(
                settings = config.mappedSettings,
                profileName = config.name,
                profileSource = config.source,
                importedRawSettingCount = concreteCount,
                curaVersion = config.curaVersion,
                settingVersion = config.settingVersion,
                engineProfile = config.engineProfile,
                startGcode = config.startGcode ?: current.startGcode,
                endGcode = config.endGcode ?: current.endGcode,
                gcodePath = null,
                sliceLogPath = null,
                sliceDurationMilliseconds = null,
                warnings = config.warnings,
                isBusy = false,
                statusMessage = "Imported $concreteCount concrete Cura values$definitionLabel; slice again to apply them",
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

    private fun formatDuration(milliseconds: Long): String {
        val seconds = milliseconds / 1_000.0
        return if (seconds < 60.0) "%.1f s".format(seconds) else "%.1f min".format(seconds / 60.0)
    }

    private fun formatFileSize(bytes: Long): String {
        return if (bytes < 1024L * 1024L) "%.1f KiB".format(bytes / 1024.0) else "%.1f MiB".format(bytes / 1024.0 / 1024.0)
    }

    private fun configurationJson(state: MainUiState): JSONObject {
        val settings = state.settings
        return JSONObject().apply {
            put("format", "enderslicer-config-v1")
            put("printer", JSONObject().apply {
                put("id", state.printer.id)
                put("name", state.printer.name)
                put("width_mm", state.printer.widthMm)
                put("depth_mm", state.printer.depthMm)
                put("height_mm", state.printer.heightMm)
                put("nozzle_mm", state.printer.nozzleSizeMm)
                put("filament_mm", state.printer.filamentDiameterMm)
                put("direct_drive", state.printer.directDrive)
                put("dual_z", state.printer.dualZ)
                put("z_probe", state.printer.zProbe)
                put("bed_leveling", state.printer.bedLeveling)
                put("ubl_mesh_slot", state.printer.ublMeshSlot)
            })
            put("settings", JSONObject().apply {
                put("layer_height", settings.layerHeightMm)
                put("layer_height_0", settings.initialLayerHeightMm)
                put("speed_print", settings.printSpeedMmPerSecond)
                put("material_print_temperature", settings.nozzleTemperatureC)
                put("material_print_temperature_layer_0", settings.initialNozzleTemperatureC)
                put("material_bed_temperature", settings.bedTemperatureC)
                put("infill_sparse_density", settings.infillDensityPercent)
                put("support_enable", settings.supportsEnabled)
                put("support_type", settings.supportPlacement)
                put("support_structure", settings.supportStructure)
                put("retraction_amount", settings.retractionDistanceMm)
                put("retraction_speed", settings.retractionSpeedMmPerSecond)
                put("machine_firmware_retract", settings.firmwareRetraction)
            })
            put("cura_profile", JSONObject().apply {
                put("source", state.profileSource)
                put("concrete_global_values", state.engineProfile?.globalValues?.size ?: 0)
                put("concrete_extruder_values", state.engineProfile?.extruderValues?.size ?: 0)
                put("material_values", state.engineProfile?.materialValueCount ?: 0)
                put("uses_project_definitions", state.engineProfile?.usesProjectDefinitions == true)
                put("skipped_formula_overrides", state.engineProfile?.unresolvedExpressions?.size ?: 0)
            })
            put("machine_start_gcode", state.startGcode)
            put("machine_end_gcode", state.endGcode)
        }
    }
}
