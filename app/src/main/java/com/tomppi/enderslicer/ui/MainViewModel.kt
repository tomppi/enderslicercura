package com.tomppi.enderslicer.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tomppi.enderslicer.data.PrinterDefinitionLoader
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.nativebridge.NativeSlicer
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

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val printer = PrinterDefinitionLoader.loadModifiedEnder3V2(app.assets)
    private val initialStartGcode = readAsset("gcode/start.gcode")
    private val initialEndGcode = readAsset("gcode/end.gcode")

    private val _uiState = MutableStateFlow(
        MainUiState(
            printer = printer,
            startGcode = initialStartGcode,
            endGcode = initialEndGcode,
            engineStatus = NativeSlicer.status(),
        ),
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    fun importStl(uri: Uri) {
        retainReadPermission(uri)
        viewModelScope.launch {
            busy("Reading STL…")
            runCatching {
                withContext(Dispatchers.IO) { StlParser.parse(app.contentResolver, uri) }
            }.onSuccess { mesh ->
                _uiState.update {
                    it.copy(
                        mesh = mesh,
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
        _uiState.update { it.copy(settings = transform(it.settings), statusMessage = "Settings changed") }
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
            current.copy(
                settings = config.mappedSettings,
                profileName = config.name,
                profileSource = config.source,
                importedRawSettingCount = config.rawValues.size,
                curaVersion = config.curaVersion,
                settingVersion = config.settingVersion,
                startGcode = config.startGcode ?: current.startGcode,
                endGcode = config.endGcode ?: current.endGcode,
                warnings = config.warnings,
                isBusy = false,
                statusMessage = "Imported ${config.rawValues.size} Cura values",
            )
        }
    }

    private fun busy(message: String) {
        _uiState.update { it.copy(isBusy = true, statusMessage = message) }
    }

    private fun showFailure(error: Throwable) {
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
            put("machine_start_gcode", state.startGcode)
            put("machine_end_gcode", state.endGcode)
        }
    }
}
