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
            .put(SlicerSettings.Keys.PRINTER_NAME, settings.printerName)
            .put(SlicerSettings.Keys.MACHINE_WIDTH, settings.machineWidthMm)
            .put(SlicerSettings.Keys.MACHINE_DEPTH, settings.machineDepthMm)
            .put(SlicerSettings.Keys.MACHINE_HEIGHT, settings.machineHeightMm)
            .put(SlicerSettings.Keys.BUILD_PLATE_SHAPE, settings.buildPlateShape)
            .put(SlicerSettings.Keys.ORIGIN_AT_CENTER, settings.originAtCenter)
            .put(SlicerSettings.Keys.HEATED_BED, settings.heatedBed)
            .put(SlicerSettings.Keys.HEATED_BUILD_VOLUME, settings.heatedBuildVolume)
            .put(SlicerSettings.Keys.GCODE_FLAVOR, settings.gcodeFlavor)
            .put(SlicerSettings.Keys.NOZZLE_SIZE, settings.nozzleSizeMm)
            .put(SlicerSettings.Keys.FILAMENT_DIAMETER, settings.filamentDiameterMm)
            .put(SlicerSettings.Keys.PRINTHEAD_X_MIN, settings.printheadXMinMm)
            .put(SlicerSettings.Keys.PRINTHEAD_Y_MIN, settings.printheadYMinMm)
            .put(SlicerSettings.Keys.PRINTHEAD_X_MAX, settings.printheadXMaxMm)
            .put(SlicerSettings.Keys.PRINTHEAD_Y_MAX, settings.printheadYMaxMm)
            .put(SlicerSettings.Keys.GANTRY_HEIGHT, settings.gantryHeightMm)
            .put(SlicerSettings.Keys.CUSTOM_START_GCODE_ENABLED, settings.customStartGcodeEnabled)
            .put(SlicerSettings.Keys.CUSTOM_START_GCODE, settings.customStartGcode)
            .put(SlicerSettings.Keys.CUSTOM_END_GCODE_ENABLED, settings.customEndGcodeEnabled)
            .put(SlicerSettings.Keys.CUSTOM_END_GCODE, settings.customEndGcode)
            .put(SlicerSettings.Keys.LAYER_HEIGHT, settings.layerHeightMm)
            .put(SlicerSettings.Keys.INITIAL_LAYER_HEIGHT, settings.initialLayerHeightMm)
            .put(SlicerSettings.Keys.LINE_WIDTH, settings.lineWidthMm)
            .put(SlicerSettings.Keys.WALL_LINE_COUNT, settings.wallLineCount)
            .put(SlicerSettings.Keys.TOP_LAYERS, settings.topLayers)
            .put(SlicerSettings.Keys.BOTTOM_LAYERS, settings.bottomLayers)
            .put(SlicerSettings.Keys.INFILL_DENSITY, settings.infillDensityPercent)
            .put(SlicerSettings.Keys.INFILL_PATTERN, settings.infillPattern)
            .put(SlicerSettings.Keys.PRINT_SPEED, settings.printSpeedMmPerSecond)
            .put(SlicerSettings.Keys.WALL_SPEED, settings.wallSpeedMmPerSecond)
            .put(SlicerSettings.Keys.OUTER_WALL_SPEED, settings.outerWallSpeedMmPerSecond)
            .put(SlicerSettings.Keys.INNER_WALL_SPEED, settings.innerWallSpeedMmPerSecond)
            .put(SlicerSettings.Keys.INFILL_SPEED, settings.infillSpeedMmPerSecond)
            .put(SlicerSettings.Keys.TOP_BOTTOM_SPEED, settings.topBottomSpeedMmPerSecond)
            .put(SlicerSettings.Keys.TRAVEL_SPEED, settings.travelSpeedMmPerSecond)
            .put(SlicerSettings.Keys.INITIAL_LAYER_SPEED, settings.initialLayerSpeedMmPerSecond)
            .put(SlicerSettings.Keys.NOZZLE_TEMPERATURE, settings.nozzleTemperatureC)
            .put(SlicerSettings.Keys.INITIAL_NOZZLE_TEMPERATURE, settings.initialNozzleTemperatureC)
            .put(SlicerSettings.Keys.BED_TEMPERATURE, settings.bedTemperatureC)
            .put(SlicerSettings.Keys.MATERIAL_FLOW, settings.materialFlowPercent)
            .put(SlicerSettings.Keys.FAN_SPEED, settings.fanSpeedPercent)
            .put(SlicerSettings.Keys.INITIAL_FAN_SPEED, settings.initialFanSpeedPercent)
            .put(SlicerSettings.Keys.FAN_FULL_AT_LAYER, settings.fanFullAtLayer)
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
            .put(SlicerSettings.Keys.RETRACTION_DISTANCE, settings.retractionDistanceMm)
            .put(SlicerSettings.Keys.RETRACTION_SPEED, settings.retractionSpeedMmPerSecond)
            .put(SlicerSettings.Keys.RETRACTION_MINIMUM_TRAVEL, settings.retractionMinimumTravelMm)
            .put(SlicerSettings.Keys.RETRACT_AT_LAYER_CHANGE, settings.retractAtLayerChange)
            .put(SlicerSettings.Keys.COMBING_MODE, settings.combingMode)
            .put(SlicerSettings.Keys.AVOID_PRINTED_PARTS, settings.avoidPrintedParts)
            .put(SlicerSettings.Keys.TRAVEL_AVOID_DISTANCE, settings.travelAvoidDistanceMm)
            .put(SlicerSettings.Keys.Z_HOP, settings.zHopEnabled)
            .put(SlicerSettings.Keys.Z_HOP_HEIGHT, settings.zHopHeightMm)
            .put(SlicerSettings.Keys.FIRMWARE_RETRACTION, settings.firmwareRetraction)
            .put(SlicerSettings.Keys.ADHESION_TYPE, settings.adhesionType)
            .put(SlicerSettings.Keys.SKIRT_LINE_COUNT, settings.skirtLineCount)
            .put(SlicerSettings.Keys.BRIM_WIDTH, settings.brimWidthMm)
            .put(SlicerSettings.Keys.IRONING_ENABLED, settings.ironingEnabled)
            .put(SlicerSettings.Keys.IRONING_FLOW, settings.ironingFlowPercent)
            .put(SlicerSettings.Keys.IRONING_SPEED, settings.ironingSpeedMmPerSecond)

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
                SlicerSettings.Keys.PRINTER_NAME -> restored.copy(printerName = values.optString(key, restored.printerName))
                SlicerSettings.Keys.MACHINE_WIDTH -> restored.copy(machineWidthMm = values.optDouble(key, restored.machineWidthMm))
                SlicerSettings.Keys.MACHINE_DEPTH -> restored.copy(machineDepthMm = values.optDouble(key, restored.machineDepthMm))
                SlicerSettings.Keys.MACHINE_HEIGHT -> restored.copy(machineHeightMm = values.optDouble(key, restored.machineHeightMm))
                SlicerSettings.Keys.BUILD_PLATE_SHAPE -> restored.copy(buildPlateShape = values.optString(key, restored.buildPlateShape))
                SlicerSettings.Keys.ORIGIN_AT_CENTER -> restored.copy(originAtCenter = values.optBoolean(key, restored.originAtCenter))
                SlicerSettings.Keys.HEATED_BED -> restored.copy(heatedBed = values.optBoolean(key, restored.heatedBed))
                SlicerSettings.Keys.HEATED_BUILD_VOLUME -> restored.copy(heatedBuildVolume = values.optBoolean(key, restored.heatedBuildVolume))
                SlicerSettings.Keys.GCODE_FLAVOR -> restored.copy(gcodeFlavor = values.optString(key, restored.gcodeFlavor))
                SlicerSettings.Keys.NOZZLE_SIZE -> restored.copy(nozzleSizeMm = values.optDouble(key, restored.nozzleSizeMm))
                SlicerSettings.Keys.FILAMENT_DIAMETER -> restored.copy(filamentDiameterMm = values.optDouble(key, restored.filamentDiameterMm))
                SlicerSettings.Keys.PRINTHEAD_X_MIN -> restored.copy(printheadXMinMm = values.optDouble(key, restored.printheadXMinMm))
                SlicerSettings.Keys.PRINTHEAD_Y_MIN -> restored.copy(printheadYMinMm = values.optDouble(key, restored.printheadYMinMm))
                SlicerSettings.Keys.PRINTHEAD_X_MAX -> restored.copy(printheadXMaxMm = values.optDouble(key, restored.printheadXMaxMm))
                SlicerSettings.Keys.PRINTHEAD_Y_MAX -> restored.copy(printheadYMaxMm = values.optDouble(key, restored.printheadYMaxMm))
                SlicerSettings.Keys.GANTRY_HEIGHT -> restored.copy(gantryHeightMm = values.optDouble(key, restored.gantryHeightMm))
                SlicerSettings.Keys.CUSTOM_START_GCODE_ENABLED -> restored.copy(customStartGcodeEnabled = values.optBoolean(key, restored.customStartGcodeEnabled))
                SlicerSettings.Keys.CUSTOM_START_GCODE -> restored.copy(customStartGcode = values.optString(key, restored.customStartGcode))
                SlicerSettings.Keys.CUSTOM_END_GCODE_ENABLED -> restored.copy(customEndGcodeEnabled = values.optBoolean(key, restored.customEndGcodeEnabled))
                SlicerSettings.Keys.CUSTOM_END_GCODE -> restored.copy(customEndGcode = values.optString(key, restored.customEndGcode))
                SlicerSettings.Keys.LAYER_HEIGHT -> restored.copy(layerHeightMm = values.optDouble(key, restored.layerHeightMm))
                SlicerSettings.Keys.INITIAL_LAYER_HEIGHT -> restored.copy(initialLayerHeightMm = values.optDouble(key, restored.initialLayerHeightMm))
                SlicerSettings.Keys.LINE_WIDTH -> restored.copy(lineWidthMm = values.optDouble(key, restored.lineWidthMm))
                SlicerSettings.Keys.WALL_LINE_COUNT -> restored.copy(wallLineCount = values.optInt(key, restored.wallLineCount))
                SlicerSettings.Keys.TOP_LAYERS -> restored.copy(topLayers = values.optInt(key, restored.topLayers))
                SlicerSettings.Keys.BOTTOM_LAYERS -> restored.copy(bottomLayers = values.optInt(key, restored.bottomLayers))
                SlicerSettings.Keys.INFILL_DENSITY -> restored.copy(infillDensityPercent = values.optDouble(key, restored.infillDensityPercent))
                SlicerSettings.Keys.INFILL_PATTERN -> restored.copy(infillPattern = values.optString(key, restored.infillPattern))
                SlicerSettings.Keys.PRINT_SPEED -> restored.copy(printSpeedMmPerSecond = values.optDouble(key, restored.printSpeedMmPerSecond))
                SlicerSettings.Keys.WALL_SPEED -> restored.copy(wallSpeedMmPerSecond = values.optDouble(key, restored.wallSpeedMmPerSecond))
                SlicerSettings.Keys.OUTER_WALL_SPEED -> restored.copy(outerWallSpeedMmPerSecond = values.optDouble(key, restored.outerWallSpeedMmPerSecond))
                SlicerSettings.Keys.INNER_WALL_SPEED -> restored.copy(innerWallSpeedMmPerSecond = values.optDouble(key, restored.innerWallSpeedMmPerSecond))
                SlicerSettings.Keys.INFILL_SPEED -> restored.copy(infillSpeedMmPerSecond = values.optDouble(key, restored.infillSpeedMmPerSecond))
                SlicerSettings.Keys.TOP_BOTTOM_SPEED -> restored.copy(topBottomSpeedMmPerSecond = values.optDouble(key, restored.topBottomSpeedMmPerSecond))
                SlicerSettings.Keys.TRAVEL_SPEED -> restored.copy(travelSpeedMmPerSecond = values.optDouble(key, restored.travelSpeedMmPerSecond))
                SlicerSettings.Keys.INITIAL_LAYER_SPEED -> restored.copy(initialLayerSpeedMmPerSecond = values.optDouble(key, restored.initialLayerSpeedMmPerSecond))
                SlicerSettings.Keys.NOZZLE_TEMPERATURE -> restored.copy(nozzleTemperatureC = values.optInt(key, restored.nozzleTemperatureC))
                SlicerSettings.Keys.INITIAL_NOZZLE_TEMPERATURE -> restored.copy(initialNozzleTemperatureC = values.optInt(key, restored.initialNozzleTemperatureC))
                SlicerSettings.Keys.BED_TEMPERATURE -> restored.copy(bedTemperatureC = values.optInt(key, restored.bedTemperatureC))
                SlicerSettings.Keys.MATERIAL_FLOW -> restored.copy(materialFlowPercent = values.optDouble(key, restored.materialFlowPercent))
                SlicerSettings.Keys.FAN_SPEED -> restored.copy(fanSpeedPercent = values.optDouble(key, restored.fanSpeedPercent))
                SlicerSettings.Keys.INITIAL_FAN_SPEED -> restored.copy(initialFanSpeedPercent = values.optDouble(key, restored.initialFanSpeedPercent))
                SlicerSettings.Keys.FAN_FULL_AT_LAYER -> restored.copy(fanFullAtLayer = values.optInt(key, restored.fanFullAtLayer))
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
                SlicerSettings.Keys.RETRACTION_DISTANCE -> restored.copy(retractionDistanceMm = values.optDouble(key, restored.retractionDistanceMm))
                SlicerSettings.Keys.RETRACTION_SPEED -> restored.copy(retractionSpeedMmPerSecond = values.optDouble(key, restored.retractionSpeedMmPerSecond))
                SlicerSettings.Keys.RETRACTION_MINIMUM_TRAVEL -> restored.copy(retractionMinimumTravelMm = values.optDouble(key, restored.retractionMinimumTravelMm))
                SlicerSettings.Keys.RETRACT_AT_LAYER_CHANGE -> restored.copy(retractAtLayerChange = values.optBoolean(key, restored.retractAtLayerChange))
                SlicerSettings.Keys.COMBING_MODE -> restored.copy(combingMode = values.optString(key, restored.combingMode))
                SlicerSettings.Keys.AVOID_PRINTED_PARTS -> restored.copy(avoidPrintedParts = values.optBoolean(key, restored.avoidPrintedParts))
                SlicerSettings.Keys.TRAVEL_AVOID_DISTANCE -> restored.copy(travelAvoidDistanceMm = values.optDouble(key, restored.travelAvoidDistanceMm))
                SlicerSettings.Keys.Z_HOP -> restored.copy(zHopEnabled = values.optBoolean(key, restored.zHopEnabled))
                SlicerSettings.Keys.Z_HOP_HEIGHT -> restored.copy(zHopHeightMm = values.optDouble(key, restored.zHopHeightMm))
                SlicerSettings.Keys.FIRMWARE_RETRACTION -> restored.copy(firmwareRetraction = values.optBoolean(key, restored.firmwareRetraction))
                SlicerSettings.Keys.ADHESION_TYPE -> restored.copy(adhesionType = values.optString(key, restored.adhesionType))
                SlicerSettings.Keys.SKIRT_LINE_COUNT -> restored.copy(skirtLineCount = values.optInt(key, restored.skirtLineCount))
                SlicerSettings.Keys.BRIM_WIDTH -> restored.copy(brimWidthMm = values.optDouble(key, restored.brimWidthMm))
                SlicerSettings.Keys.IRONING_ENABLED -> restored.copy(ironingEnabled = values.optBoolean(key, restored.ironingEnabled))
                SlicerSettings.Keys.IRONING_FLOW -> restored.copy(ironingFlowPercent = values.optDouble(key, restored.ironingFlowPercent))
                SlicerSettings.Keys.IRONING_SPEED -> restored.copy(ironingSpeedMmPerSecond = values.optDouble(key, restored.ironingSpeedMmPerSecond))
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
