package com.tomppi.enderslicer.data

import android.content.Context
import com.tomppi.enderslicer.model.SlicerSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

class AppStateStore(context: Context) {
    data class SavedImport(
        val kind: String,
        val displayName: String,
        val file: File,
    )

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val stateDirectory = File(appContext.filesDir, "persistent-state").apply { mkdirs() }
    private val importFile = File(stateDirectory, "current-cura-import.bin")

    fun stageImport(input: InputStream): File {
        val temporary = File(stateDirectory, "current-cura-import.tmp")
        temporary.delete()
        input.buffered().use { source ->
            temporary.outputStream().buffered().use { destination -> source.copyTo(destination) }
        }
        check(temporary.isFile && temporary.length() > 0L) { "The imported Cura file is empty" }
        return temporary
    }

    fun commitImport(staged: File, kind: String, displayName: String) {
        require(kind == KIND_PROJECT || kind == KIND_PROFILE) { "Unsupported Cura import kind: $kind" }
        if (importFile.exists()) importFile.delete()
        check(staged.renameTo(importFile) || staged.copyTo(importFile, overwrite = true).let { staged.delete(); true }) {
            "Unable to persist the imported Cura configuration"
        }
        preferences.edit()
            .putString(KEY_IMPORT_KIND, kind)
            .putString(KEY_IMPORT_NAME, displayName)
            .apply()
    }

    fun savedImport(): SavedImport? {
        val kind = preferences.getString(KEY_IMPORT_KIND, null) ?: return null
        val displayName = preferences.getString(KEY_IMPORT_NAME, null) ?: "Restored Cura configuration"
        if (!importFile.isFile || importFile.length() == 0L) return null
        return SavedImport(kind, displayName, importFile)
    }

    fun clearSavedSettings() {
        preferences.edit().remove(KEY_SETTINGS).apply()
    }

    fun saveSettings(settings: SlicerSettings) {
        val values = JSONObject()
            .put(SlicerSettings.Keys.LAYER_HEIGHT, settings.layerHeightMm)
            .put(SlicerSettings.Keys.INITIAL_LAYER_HEIGHT, settings.initialLayerHeightMm)
            .put(SlicerSettings.Keys.LINE_WIDTH, settings.lineWidthMm)
            .put(SlicerSettings.Keys.PRINT_SPEED, settings.printSpeedMmPerSecond)
            .put(SlicerSettings.Keys.NOZZLE_TEMPERATURE, settings.nozzleTemperatureC)
            .put(SlicerSettings.Keys.INITIAL_NOZZLE_TEMPERATURE, settings.initialNozzleTemperatureC)
            .put(SlicerSettings.Keys.BED_TEMPERATURE, settings.bedTemperatureC)
            .put(SlicerSettings.Keys.INFILL_DENSITY, settings.infillDensityPercent)
            .put(SlicerSettings.Keys.SUPPORTS_ENABLED, settings.supportsEnabled)
            .put(SlicerSettings.Keys.SUPPORT_PLACEMENT, settings.supportPlacement)
            .put(SlicerSettings.Keys.SUPPORT_STRUCTURE, settings.supportStructure)
            .put(SlicerSettings.Keys.SUPPORT_ANGLE, settings.supportAngleDegrees)
            .put(SlicerSettings.Keys.SUPPORT_DENSITY, settings.supportDensityPercent)
            .put(SlicerSettings.Keys.SUPPORT_PATTERN, settings.supportPattern)
            .put(SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED, settings.supportInterfaceEnabled)
            .put(SlicerSettings.Keys.SUPPORT_INTERFACE_DENSITY, settings.supportInterfaceDensityPercent)
            .put(SlicerSettings.Keys.SUPPORT_Z_DISTANCE, settings.supportZDistanceMm)
            .put(SlicerSettings.Keys.SUPPORT_XY_DISTANCE, settings.supportXyDistanceMm)
            .put(SlicerSettings.Keys.SUPPORT_SPEED, settings.supportSpeedMmPerSecond)
            .put(SlicerSettings.Keys.SUPPORT_INTERFACE_SPEED, settings.supportInterfaceSpeedMmPerSecond)
            .put(SlicerSettings.Keys.ADHESION_TYPE, settings.adhesionType)
            .put(SlicerSettings.Keys.RETRACTION_DISTANCE, settings.retractionDistanceMm)
            .put(SlicerSettings.Keys.RETRACTION_SPEED, settings.retractionSpeedMmPerSecond)
            .put(SlicerSettings.Keys.RETRACT_AT_LAYER_CHANGE, settings.retractAtLayerChange)
            .put(SlicerSettings.Keys.Z_HOP, settings.zHopEnabled)
            .put(SlicerSettings.Keys.FIRMWARE_RETRACTION, settings.firmwareRetraction)
            .put(SlicerSettings.Keys.FAN_SPEED, settings.fanSpeedPercent)
            .put(SlicerSettings.Keys.MATERIAL_FLOW, settings.materialFlowPercent)

        val overrides = JSONArray()
        settings.overriddenSettingKeys.sorted().forEach(overrides::put)
        values.put(KEY_OVERRIDES_JSON, overrides)
        preferences.edit().putString(KEY_SETTINGS, values.toString()).apply()
    }

    fun restoreSettings(base: SlicerSettings): SlicerSettings {
        val encoded = preferences.getString(KEY_SETTINGS, null) ?: return base.copy(overriddenSettingKeys = emptySet())
        val values = runCatching { JSONObject(encoded) }.getOrNull() ?: return base.copy(overriddenSettingKeys = emptySet())
        val overridesArray = values.optJSONArray(KEY_OVERRIDES_JSON) ?: JSONArray()
        val overrides = buildSet {
            for (index in 0 until overridesArray.length()) {
                overridesArray.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }

        var restored = base.copy(overriddenSettingKeys = emptySet())
        overrides.forEach { key ->
            restored = when (key) {
                SlicerSettings.Keys.LAYER_HEIGHT -> restored.copy(layerHeightMm = values.optDouble(key, restored.layerHeightMm))
                SlicerSettings.Keys.INITIAL_LAYER_HEIGHT -> restored.copy(initialLayerHeightMm = values.optDouble(key, restored.initialLayerHeightMm))
                SlicerSettings.Keys.LINE_WIDTH -> restored.copy(lineWidthMm = values.optDouble(key, restored.lineWidthMm))
                SlicerSettings.Keys.PRINT_SPEED -> restored.copy(printSpeedMmPerSecond = values.optDouble(key, restored.printSpeedMmPerSecond))
                SlicerSettings.Keys.NOZZLE_TEMPERATURE -> restored.copy(nozzleTemperatureC = values.optInt(key, restored.nozzleTemperatureC))
                SlicerSettings.Keys.INITIAL_NOZZLE_TEMPERATURE -> restored.copy(initialNozzleTemperatureC = values.optInt(key, restored.initialNozzleTemperatureC))
                SlicerSettings.Keys.BED_TEMPERATURE -> restored.copy(bedTemperatureC = values.optInt(key, restored.bedTemperatureC))
                SlicerSettings.Keys.INFILL_DENSITY -> restored.copy(infillDensityPercent = values.optDouble(key, restored.infillDensityPercent))
                SlicerSettings.Keys.SUPPORTS_ENABLED -> restored.copy(supportsEnabled = values.optBoolean(key, restored.supportsEnabled))
                SlicerSettings.Keys.SUPPORT_PLACEMENT -> restored.copy(supportPlacement = values.optString(key, restored.supportPlacement))
                SlicerSettings.Keys.SUPPORT_STRUCTURE -> restored.copy(supportStructure = values.optString(key, restored.supportStructure))
                SlicerSettings.Keys.SUPPORT_ANGLE -> restored.copy(supportAngleDegrees = values.optDouble(key, restored.supportAngleDegrees))
                SlicerSettings.Keys.SUPPORT_DENSITY -> restored.copy(supportDensityPercent = values.optDouble(key, restored.supportDensityPercent))
                SlicerSettings.Keys.SUPPORT_PATTERN -> restored.copy(supportPattern = values.optString(key, restored.supportPattern))
                SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED -> restored.copy(supportInterfaceEnabled = values.optBoolean(key, restored.supportInterfaceEnabled))
                SlicerSettings.Keys.SUPPORT_INTERFACE_DENSITY -> restored.copy(supportInterfaceDensityPercent = values.optDouble(key, restored.supportInterfaceDensityPercent))
                SlicerSettings.Keys.SUPPORT_Z_DISTANCE -> restored.copy(supportZDistanceMm = values.optDouble(key, restored.supportZDistanceMm))
                SlicerSettings.Keys.SUPPORT_XY_DISTANCE -> restored.copy(supportXyDistanceMm = values.optDouble(key, restored.supportXyDistanceMm))
                SlicerSettings.Keys.SUPPORT_SPEED -> restored.copy(supportSpeedMmPerSecond = values.optDouble(key, restored.supportSpeedMmPerSecond))
                SlicerSettings.Keys.SUPPORT_INTERFACE_SPEED -> restored.copy(supportInterfaceSpeedMmPerSecond = values.optDouble(key, restored.supportInterfaceSpeedMmPerSecond))
                SlicerSettings.Keys.ADHESION_TYPE -> restored.copy(adhesionType = values.optString(key, restored.adhesionType))
                SlicerSettings.Keys.RETRACTION_DISTANCE -> restored.copy(retractionDistanceMm = values.optDouble(key, restored.retractionDistanceMm))
                SlicerSettings.Keys.RETRACTION_SPEED -> restored.copy(retractionSpeedMmPerSecond = values.optDouble(key, restored.retractionSpeedMmPerSecond))
                SlicerSettings.Keys.RETRACT_AT_LAYER_CHANGE -> restored.copy(retractAtLayerChange = values.optBoolean(key, restored.retractAtLayerChange))
                SlicerSettings.Keys.Z_HOP -> restored.copy(zHopEnabled = values.optBoolean(key, restored.zHopEnabled))
                SlicerSettings.Keys.FIRMWARE_RETRACTION -> restored.copy(firmwareRetraction = values.optBoolean(key, restored.firmwareRetraction))
                SlicerSettings.Keys.FAN_SPEED -> restored.copy(fanSpeedPercent = values.optDouble(key, restored.fanSpeedPercent))
                SlicerSettings.Keys.MATERIAL_FLOW -> restored.copy(materialFlowPercent = values.optDouble(key, restored.materialFlowPercent))
                else -> restored
            }
        }
        return restored.copy(overriddenSettingKeys = overrides)
    }

    companion object {
        const val KIND_PROJECT = "project"
        const val KIND_PROFILE = "profile"

        private const val PREFERENCES_NAME = "enderslicer-state"
        private const val KEY_IMPORT_KIND = "import-kind"
        private const val KEY_IMPORT_NAME = "import-name"
        private const val KEY_SETTINGS = "settings-json"
        private const val KEY_OVERRIDES_JSON = "overrides"
    }
}
