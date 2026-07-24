package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerSettings
import kotlin.math.abs

/**
 * Converts EnderSlicer's persisted user edits into Cura input values.
 *
 * The imported Cura project remains the immutable baseline. Only keys listed in
 * overriddenSettingKeys are added to that baseline. Values owned by Cura
 * formulas are deliberately not copied here: changing an upstream input must
 * allow Cura's original dependency chain to calculate them again.
 */
internal object CuraSettingDelta {
    fun explicitValues(settings: SlicerSettings): LinkedHashMap<String, String> =
        values(settings, includeAll = false)

    /** Used only when no Cura profile/project exists and the app is the baseline. */
    fun standaloneValues(settings: SlicerSettings): LinkedHashMap<String, String> =
        values(settings, includeAll = true)

    private fun values(
        settings: SlicerSettings,
        includeAll: Boolean,
    ): LinkedHashMap<String, String> = linkedMapOf<String, String>().apply {
        fun include(appKey: String): Boolean = includeAll || settings.isOverridden(appKey)
        fun putValue(appKey: String, curaKey: String, value: Any) {
            if (!include(appKey)) return
            put(
                curaKey,
                when (value) {
                    is Boolean -> value.toString().lowercase()
                    else -> value.toString()
                },
            )
        }

        putValue(SlicerSettings.Keys.LAYER_HEIGHT, "layer_height", settings.layerHeightMm)
        putValue(SlicerSettings.Keys.INITIAL_LAYER_HEIGHT, "layer_height_0", settings.initialLayerHeightMm)
        putValue(
            SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_ENABLED,
            "adaptive_layer_height_enabled",
            settings.adaptiveLayerHeightEnabled,
        )
        putValue(
            SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION,
            "adaptive_layer_height_variation",
            settings.adaptiveLayerHeightVariationMm,
        )
        putValue(
            SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION_STEP,
            "adaptive_layer_height_variation_step",
            settings.adaptiveLayerHeightVariationStepMm,
        )
        putValue(
            SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_THRESHOLD,
            "adaptive_layer_height_threshold",
            settings.adaptiveLayerHeightThreshold,
        )
        putValue(SlicerSettings.Keys.LINE_WIDTH, "line_width", settings.lineWidthMm)
        putValue(SlicerSettings.Keys.SLICING_TOLERANCE, "slicing_tolerance", settings.slicingTolerance)
        putValue(SlicerSettings.Keys.WALL_LINE_COUNT, "wall_line_count", settings.wallLineCount)
        putValue(SlicerSettings.Keys.TOP_LAYERS, "top_layers", settings.topLayers)
        putValue(SlicerSettings.Keys.BOTTOM_LAYERS, "bottom_layers", settings.bottomLayers)
        putValue(SlicerSettings.Keys.Z_SEAM_TYPE, "z_seam_type", settings.zSeamType)
        putValue(SlicerSettings.Keys.Z_SEAM_X, "z_seam_x", settings.zSeamXmm)
        putValue(SlicerSettings.Keys.Z_SEAM_Y, "z_seam_y", settings.zSeamYmm)
        putValue(SlicerSettings.Keys.Z_SEAM_RELATIVE, "z_seam_relative", settings.zSeamRelative)
        putValue(SlicerSettings.Keys.Z_SEAM_CORNER, "z_seam_corner", settings.zSeamCorner)
        putValue(SlicerSettings.Keys.INFILL_DENSITY, "infill_sparse_density", settings.infillDensityPercent)
        putValue(SlicerSettings.Keys.INFILL_PATTERN, "infill_pattern", settings.infillPattern)

        putValue(SlicerSettings.Keys.PRINT_SPEED, "speed_print", settings.printSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.WALL_SPEED, "speed_wall", settings.wallSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.OUTER_WALL_SPEED, "speed_wall_0", settings.outerWallSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.INNER_WALL_SPEED, "speed_wall_x", settings.innerWallSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.INFILL_SPEED, "speed_infill", settings.infillSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.TOP_BOTTOM_SPEED, "speed_topbottom", settings.topBottomSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.TRAVEL_SPEED, "speed_travel", settings.travelSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.INITIAL_LAYER_SPEED, "speed_layer_0", settings.initialLayerSpeedMmPerSecond)

        // Only the user-facing root temperatures are inputs. Cura formulas remain
        // responsible for initial/final/cooling derivative temperatures.
        putValue(SlicerSettings.Keys.NOZZLE_TEMPERATURE, "material_print_temperature", settings.nozzleTemperatureC)
        putValue(
            SlicerSettings.Keys.INITIAL_NOZZLE_TEMPERATURE,
            "material_print_temperature_layer_0",
            settings.initialNozzleTemperatureC,
        )
        putValue(SlicerSettings.Keys.BED_TEMPERATURE, "material_bed_temperature", settings.bedTemperatureC)
        putValue(SlicerSettings.Keys.MATERIAL_FLOW, "material_flow", settings.materialFlowPercent)
        putValue(SlicerSettings.Keys.FAN_SPEED, "cool_fan_speed", settings.fanSpeedPercent)
        putValue(SlicerSettings.Keys.INITIAL_FAN_SPEED, "cool_fan_speed_0", settings.initialFanSpeedPercent)
        putValue(SlicerSettings.Keys.FAN_FULL_AT_LAYER, "cool_fan_full_layer", settings.fanFullAtLayer)

        putValue(SlicerSettings.Keys.SUPPORTS_ENABLED, "support_enable", settings.supportsEnabled)
        putValue(SlicerSettings.Keys.SUPPORT_PLACEMENT, "support_type", settings.supportPlacement)
        putValue(SlicerSettings.Keys.SUPPORT_STRUCTURE, "support_structure", settings.supportStructure)
        putValue(SlicerSettings.Keys.SUPPORT_ANGLE, "support_angle", settings.supportAngleDegrees)
        if (include(SlicerSettings.Keys.SUPPORT_DENSITY)) {
            put(
                "support_infill_rate",
                if (includeAll && settings.supportsEnabled && settings.supportStructure.equals("tree", ignoreCase = true)) {
                    "0.0"
                } else {
                    settings.supportDensityPercent.toString()
                },
            )
        }
        putValue(SlicerSettings.Keys.SUPPORT_PATTERN, "support_pattern", settings.supportPattern)
        putValue(
            SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED,
            "support_interface_enable",
            settings.supportInterfaceEnabled,
        )
        putValue(
            SlicerSettings.Keys.SUPPORT_INTERFACE_DENSITY,
            "support_interface_density",
            settings.supportInterfaceDensityPercent,
        )
        putValue(SlicerSettings.Keys.SUPPORT_Z_DISTANCE, "support_z_distance", settings.supportZDistanceMm)
        putValue(SlicerSettings.Keys.SUPPORT_XY_DISTANCE, "support_xy_distance", settings.supportXyDistanceMm)
        putValue(SlicerSettings.Keys.SUPPORT_SPEED, "speed_support", settings.supportSpeedMmPerSecond)
        putValue(
            SlicerSettings.Keys.SUPPORT_INTERFACE_SPEED,
            "speed_support_interface",
            settings.supportInterfaceSpeedMmPerSecond,
        )

        if (include(SlicerSettings.Keys.RETRACTION_DISTANCE)) {
            put("retraction_enable", (settings.retractionDistanceMm > 0.0).toString())
            put("retraction_amount", settings.retractionDistanceMm.toString())
        }
        putValue(SlicerSettings.Keys.RETRACTION_SPEED, "retraction_speed", settings.retractionSpeedMmPerSecond)
        putValue(
            SlicerSettings.Keys.RETRACTION_MINIMUM_TRAVEL,
            "retraction_min_travel",
            settings.retractionMinimumTravelMm,
        )
        putValue(
            SlicerSettings.Keys.RETRACT_AT_LAYER_CHANGE,
            "retract_at_layer_change",
            settings.retractAtLayerChange,
        )
        putValue(SlicerSettings.Keys.COMBING_MODE, "retraction_combing", settings.combingMode)
        putValue(SlicerSettings.Keys.AVOID_PRINTED_PARTS, "travel_avoid_other_parts", settings.avoidPrintedParts)
        putValue(SlicerSettings.Keys.TRAVEL_AVOID_DISTANCE, "travel_avoid_distance", settings.travelAvoidDistanceMm)
        putValue(SlicerSettings.Keys.Z_HOP, "retraction_hop_enabled", settings.zHopEnabled)
        putValue(SlicerSettings.Keys.Z_HOP_HEIGHT, "retraction_hop", settings.zHopHeightMm)
        putValue(SlicerSettings.Keys.FIRMWARE_RETRACTION, "machine_firmware_retract", settings.firmwareRetraction)
        putValue(SlicerSettings.Keys.COASTING_ENABLED, "coasting_enable", settings.coastingEnabled)
        putValue(SlicerSettings.Keys.COASTING_VOLUME, "coasting_volume", settings.coastingVolumeMm3)
        putValue(
            SlicerSettings.Keys.COASTING_MINIMUM_VOLUME,
            "coasting_min_volume",
            settings.coastingMinimumVolumeMm3,
        )
        putValue(SlicerSettings.Keys.COASTING_SPEED, "coasting_speed", settings.coastingSpeedPercent)

        putValue(SlicerSettings.Keys.ADHESION_TYPE, "adhesion_type", settings.adhesionType)
        putValue(SlicerSettings.Keys.SKIRT_LINE_COUNT, "skirt_line_count", settings.skirtLineCount)
        putValue(SlicerSettings.Keys.BRIM_WIDTH, "brim_width", settings.brimWidthMm)
        putValue(SlicerSettings.Keys.IRONING_ENABLED, "ironing_enabled", settings.ironingEnabled)
        putValue(SlicerSettings.Keys.IRONING_FLOW, "ironing_flow", settings.ironingFlowPercent)
        putValue(SlicerSettings.Keys.IRONING_SPEED, "speed_ironing", settings.ironingSpeedMmPerSecond)
    }

    fun requireResolvedMatch(
        settings: SlicerSettings,
        globalValues: Map<String, String>,
        extruderValues: Map<String, String>,
    ) {
        val mismatches = explicitValues(settings).mapNotNull { (key, expected) ->
            val actual = extruderValues[key] ?: globalValues[key]
            when {
                actual == null -> "$key is missing; edit=$expected"
                equivalent(expected, actual) -> null
                else -> "$key edit=$expected resolved=$actual"
            }
        }
        require(mismatches.isEmpty()) {
            "Explicit Cura edits diverged from the resolved slice snapshot: ${mismatches.take(12).joinToString()}"
        }
    }

    private fun equivalent(expected: String, actual: String): Boolean {
        val left = expected.trim()
        val right = actual.trim()
        if (left.equals(right, ignoreCase = true)) return true
        val leftNumber = left.toDoubleOrNull()
        val rightNumber = right.toDoubleOrNull()
        if (leftNumber != null && rightNumber != null) {
            val scale = maxOf(1.0, abs(leftNumber), abs(rightNumber))
            return abs(leftNumber - rightNumber) <= 1e-7 * scale
        }
        return false
    }
}
