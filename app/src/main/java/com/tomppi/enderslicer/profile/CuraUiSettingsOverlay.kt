package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerSettings
import kotlin.math.abs

/**
 * The single source of truth for every Cura value exposed in EnderSlicer's UI.
 *
 * Imported Cura profiles still provide settings that the app does not expose.
 * Every visible value, however, is always overlaid from the current UI state so
 * the engine cannot silently use a duplicate global/extruder value that differs
 * from what the user sees. overriddenSettingKeys is provenance for reset/source
 * labels only; it never controls whether a visible value reaches CuraEngine.
 */
internal object CuraUiSettingsOverlay {
    fun values(settings: SlicerSettings): LinkedHashMap<String, String> = linkedMapOf<String, String>().apply {
        fun putValue(key: String, value: Any) {
            put(
                key,
                when (value) {
                    is Boolean -> value.toString().lowercase()
                    else -> value.toString()
                },
            )
        }

        putValue("layer_height", settings.layerHeightMm)
        putValue("layer_height_0", settings.initialLayerHeightMm)
        putValue("line_width", settings.lineWidthMm)
        putValue("slicing_tolerance", settings.slicingTolerance)
        putValue("wall_line_count", settings.wallLineCount)
        putValue("top_layers", settings.topLayers)
        putValue("bottom_layers", settings.bottomLayers)
        putValue("z_seam_type", settings.zSeamType)
        putValue("z_seam_x", settings.zSeamXmm)
        putValue("z_seam_y", settings.zSeamYmm)
        putValue("z_seam_relative", settings.zSeamRelative)
        putValue("z_seam_corner", settings.zSeamCorner)
        putValue("infill_sparse_density", settings.infillDensityPercent)
        putValue("infill_pattern", settings.infillPattern)

        putValue("speed_print", settings.printSpeedMmPerSecond)
        putValue("speed_wall", settings.wallSpeedMmPerSecond)
        putValue("speed_wall_0", settings.outerWallSpeedMmPerSecond)
        putValue("speed_wall_x", settings.innerWallSpeedMmPerSecond)
        putValue("speed_infill", settings.infillSpeedMmPerSecond)
        putValue("speed_topbottom", settings.topBottomSpeedMmPerSecond)
        putValue("speed_travel", settings.travelSpeedMmPerSecond)
        putValue("speed_layer_0", settings.initialLayerSpeedMmPerSecond)

        putValue("material_print_temperature", settings.nozzleTemperatureC)
        putValue("material_print_temperature_layer_0", settings.initialNozzleTemperatureC)
        putValue("material_initial_print_temperature", settings.nozzleTemperatureC)
        putValue("material_final_print_temperature", settings.nozzleTemperatureC)
        putValue("cool_min_temperature", settings.nozzleTemperatureC)
        putValue("material_bed_temperature", settings.bedTemperatureC)
        putValue("material_bed_temperature_layer_0", settings.bedTemperatureC)
        putValue("material_flow", settings.materialFlowPercent)
        putValue("cool_fan_speed", settings.fanSpeedPercent)
        putValue("cool_fan_speed_0", settings.initialFanSpeedPercent)
        putValue("cool_fan_full_layer", settings.fanFullAtLayer)

        putValue("support_enable", settings.supportsEnabled)
        putValue("support_type", settings.supportPlacement)
        putValue("support_structure", settings.supportStructure)
        putValue("support_angle", settings.supportAngleDegrees)
        putValue(
            "support_infill_rate",
            if (settings.supportsEnabled && settings.supportStructure.equals("tree", ignoreCase = true)) {
                0.0
            } else {
                settings.supportDensityPercent
            },
        )
        putValue("support_pattern", settings.supportPattern)
        putValue(
            "support_interface_enable",
            settings.supportsEnabled && settings.supportInterfaceEnabled,
        )
        putValue("support_interface_density", settings.supportInterfaceDensityPercent)
        putValue("support_z_distance", settings.supportZDistanceMm)
        putValue("support_xy_distance", settings.supportXyDistanceMm)
        putValue("speed_support", settings.supportSpeedMmPerSecond)
        putValue("speed_support_interface", settings.supportInterfaceSpeedMmPerSecond)

        putValue("retraction_enable", settings.retractionDistanceMm > 0.0)
        putValue("retraction_amount", settings.retractionDistanceMm)
        putValue("retraction_speed", settings.retractionSpeedMmPerSecond)
        putValue("retraction_min_travel", settings.retractionMinimumTravelMm)
        putValue("retract_at_layer_change", settings.retractAtLayerChange)
        putValue("retraction_combing", settings.combingMode)
        putValue("travel_avoid_other_parts", settings.avoidPrintedParts)
        putValue("travel_avoid_distance", settings.travelAvoidDistanceMm)
        putValue("retraction_hop_enabled", settings.zHopEnabled)
        putValue("retraction_hop", settings.zHopHeightMm)
        putValue("machine_firmware_retract", settings.firmwareRetraction)
        putValue("coasting_enable", settings.coastingEnabled)
        putValue("coasting_volume", settings.coastingVolumeMm3)
        putValue("coasting_min_volume", settings.coastingMinimumVolumeMm3)
        putValue("coasting_speed", settings.coastingSpeedPercent)

        putValue("adhesion_type", settings.adhesionType)
        putValue("skirt_line_count", settings.skirtLineCount)
        putValue("brim_width", settings.brimWidthMm)
        putValue("ironing_enabled", settings.ironingEnabled)
        putValue("ironing_flow", settings.ironingFlowPercent)
        putValue("speed_ironing", settings.ironingSpeedMmPerSecond)
    }

    fun requireResolvedMatch(
        settings: SlicerSettings,
        globalValues: Map<String, String>,
        extruderValues: Map<String, String>,
    ) {
        val mismatches = values(settings).mapNotNull { (key, expected) ->
            val actual = extruderValues[key] ?: globalValues[key]
            when {
                actual == null -> "$key is missing; UI=$expected"
                equivalent(expected, actual) -> null
                else -> "$key UI=$expected engine=$actual"
            }
        }
        require(mismatches.isEmpty()) {
            "Visible Cura settings diverged from the engine snapshot: ${mismatches.take(12).joinToString()}"
        }
    }

    private fun equivalent(expected: String, actual: String): Boolean {
        val expectedTrimmed = expected.trim()
        val actualTrimmed = actual.trim()
        if (expectedTrimmed.equals(actualTrimmed, ignoreCase = true)) return true

        val expectedBoolean = expectedTrimmed.toBooleanStrictOrNull()
        val actualBoolean = actualTrimmed.toBooleanStrictOrNull()
        if (expectedBoolean != null && actualBoolean != null) return expectedBoolean == actualBoolean

        val expectedNumber = expectedTrimmed.toDoubleOrNull()
        val actualNumber = actualTrimmed.toDoubleOrNull()
        if (expectedNumber != null && actualNumber != null) {
            val scale = maxOf(1.0, abs(expectedNumber), abs(actualNumber))
            return abs(expectedNumber - actualNumber) <= 1e-7 * scale
        }
        return false
    }
}
