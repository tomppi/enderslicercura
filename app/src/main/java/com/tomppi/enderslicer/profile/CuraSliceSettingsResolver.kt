package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.model.resolveEndGcode
import com.tomppi.enderslicer.model.resolveStartGcode
import com.tomppi.enderslicer.model.withSettings

internal object CuraSliceSettingsResolver {
    data class Result(
        val globalValues: Map<String, String>,
        val extruderValues: Map<String, String>,
        val expressionCount: Int,
        val passes: Int,
    )

    fun resolve(
        profile: CuraEngineProfile,
        printer: PrinterDefinition,
        settings: SlicerSettings,
        startGcode: String,
        endGcode: String,
    ): Result {
        require(profile.usesProjectDefinitions) {
            "A complete Cura project with embedded machine and extruder definitions is required"
        }

        val effectivePrinter = printer.withSettings(settings)
        val effectiveStartGcode = settings.resolveStartGcode(startGcode)
        val effectiveEndGcode = settings.resolveEndGcode(endGcode)

        val globalOverrides = linkedMapOf<String, String>().apply {
            putAll(profile.rawGlobalValues)
            put("machine_name", effectivePrinter.name)
            put("machine_width", effectivePrinter.widthMm.toString())
            put("machine_depth", effectivePrinter.depthMm.toString())
            put("machine_height", effectivePrinter.heightMm.toString())
            put("machine_shape", effectivePrinter.buildPlateShape)
            put("machine_center_is_zero", effectivePrinter.originAtCenter.toString())
            put("machine_heated_bed", effectivePrinter.heatedBed.toString())
            put("machine_heated_build_volume", effectivePrinter.heatedBuildVolume.toString())
            put("machine_extruder_count", effectivePrinter.extruders.toString())
            put("machine_gcode_flavor", effectivePrinter.gcodeFlavor)
            put("machine_start_gcode", effectiveStartGcode)
            put("machine_end_gcode", effectiveEndGcode)
            put("gantry_height", effectivePrinter.gantryHeightMm.toString())
            put("machine_nozzle_size", effectivePrinter.nozzleSizeMm.toString())
            put("material_diameter", effectivePrinter.filamentDiameterMm.toString())
            put(
                "machine_head_with_fans_polygon",
                "[[${effectivePrinter.printheadXMinMm},${effectivePrinter.printheadYMaxMm}],[${effectivePrinter.printheadXMinMm},${effectivePrinter.printheadYMinMm}],[${effectivePrinter.printheadXMaxMm},${effectivePrinter.printheadYMinMm}],[${effectivePrinter.printheadXMaxMm},${effectivePrinter.printheadYMaxMm}]]",
            )
            applyExplicitSettings(settings)
        }

        val extruderOverrides = linkedMapOf<String, String>().apply {
            putAll(profile.rawExtruderValues)
            put("extruder_nr", "0")
            put("machine_nozzle_size", effectivePrinter.nozzleSizeMm.toString())
            put("material_diameter", effectivePrinter.filamentDiameterMm.toString())
            applyExplicitSettings(settings)
        }

        val resolved = CuraDefinitionResolver.resolve(
            definitionFiles = profile.definitionFiles,
            machineDefinitionFileName = requireNotNull(profile.machineDefinitionFileName),
            extruderDefinitionFileName = requireNotNull(profile.extruderDefinitionFileName),
            globalOverrides = globalOverrides,
            extruderOverrides = extruderOverrides,
        )

        validateResolvedSettings(resolved.globalValues, resolved.extruderValues)
        return Result(
            globalValues = resolved.globalValues,
            extruderValues = resolved.extruderValues,
            expressionCount = resolved.expressionCount,
            passes = resolved.passes,
        )
    }

    private fun MutableMap<String, String>.applyExplicitSettings(settings: SlicerSettings) {
        fun putOverride(appKey: String, curaKey: String, value: Any) {
            if (settings.isOverridden(appKey)) put(curaKey, value.toString())
        }

        putOverride(SlicerSettings.Keys.LAYER_HEIGHT, "layer_height", settings.layerHeightMm)
        putOverride(SlicerSettings.Keys.INITIAL_LAYER_HEIGHT, "layer_height_0", settings.initialLayerHeightMm)
        putOverride(SlicerSettings.Keys.LINE_WIDTH, "line_width", settings.lineWidthMm)
        putOverride(SlicerSettings.Keys.WALL_LINE_COUNT, "wall_line_count", settings.wallLineCount)
        putOverride(SlicerSettings.Keys.TOP_LAYERS, "top_layers", settings.topLayers)
        putOverride(SlicerSettings.Keys.BOTTOM_LAYERS, "bottom_layers", settings.bottomLayers)
        putOverride(SlicerSettings.Keys.INFILL_DENSITY, "infill_sparse_density", settings.infillDensityPercent)
        putOverride(SlicerSettings.Keys.INFILL_PATTERN, "infill_pattern", settings.infillPattern)
        putOverride(SlicerSettings.Keys.PRINT_SPEED, "speed_print", settings.printSpeedMmPerSecond)
        putOverride(SlicerSettings.Keys.WALL_SPEED, "speed_wall", settings.wallSpeedMmPerSecond)
        putOverride(SlicerSettings.Keys.OUTER_WALL_SPEED, "speed_wall_0", settings.outerWallSpeedMmPerSecond)
        putOverride(SlicerSettings.Keys.INNER_WALL_SPEED, "speed_wall_x", settings.innerWallSpeedMmPerSecond)
        putOverride(SlicerSettings.Keys.INFILL_SPEED, "speed_infill", settings.infillSpeedMmPerSecond)
        putOverride(SlicerSettings.Keys.TOP_BOTTOM_SPEED, "speed_topbottom", settings.topBottomSpeedMmPerSecond)
        putOverride(SlicerSettings.Keys.TRAVEL_SPEED, "speed_travel", settings.travelSpeedMmPerSecond)
        putOverride(SlicerSettings.Keys.INITIAL_LAYER_SPEED, "speed_layer_0", settings.initialLayerSpeedMmPerSecond)
        putOverride(SlicerSettings.Keys.BED_TEMPERATURE, "material_bed_temperature", settings.bedTemperatureC)
        putOverride(SlicerSettings.Keys.BED_TEMPERATURE, "material_bed_temperature_layer_0", settings.bedTemperatureC)
        putOverride(SlicerSettings.Keys.NOZZLE_TEMPERATURE, "material_print_temperature", settings.nozzleTemperatureC)
        putOverride(
            SlicerSettings.Keys.INITIAL_NOZZLE_TEMPERATURE,
            "material_print_temperature_layer_0",
            settings.initialNozzleTemperatureC,
        )
        putOverride(SlicerSettings.Keys.NOZZLE_TEMPERATURE, "material_initial_print_temperature", settings.nozzleTemperatureC)
        putOverride(SlicerSettings.Keys.NOZZLE_TEMPERATURE, "material_final_print_temperature", settings.nozzleTemperatureC)
        putOverride(SlicerSettings.Keys.MATERIAL_FLOW, "material_flow", settings.materialFlowPercent)
        putOverride(SlicerSettings.Keys.FAN_SPEED, "cool_fan_speed", settings.fanSpeedPercent)
        putOverride(SlicerSettings.Keys.INITIAL_FAN_SPEED, "cool_fan_speed_0", settings.initialFanSpeedPercent)
        putOverride(SlicerSettings.Keys.FAN_FULL_AT_LAYER, "cool_fan_full_layer", settings.fanFullAtLayer)
        putOverride(SlicerSettings.Keys.SUPPORTS_ENABLED, "support_enable", settings.supportsEnabled)
        putOverride(SlicerSettings.Keys.SUPPORT_PLACEMENT, "support_type", settings.supportPlacement)
        putOverride(SlicerSettings.Keys.SUPPORT_STRUCTURE, "support_structure", settings.supportStructure)
        putOverride(SlicerSettings.Keys.SUPPORT_ANGLE, "support_angle", settings.supportAngleDegrees)
        putOverride(SlicerSettings.Keys.SUPPORT_DENSITY, "support_infill_rate", settings.supportDensityPercent)
        putOverride(SlicerSettings.Keys.SUPPORT_PATTERN, "support_pattern", settings.supportPattern)
        putOverride(
            SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED,
            "support_interface_enable",
            settings.supportInterfaceEnabled,
        )
        putOverride(
            SlicerSettings.Keys.SUPPORT_INTERFACE_DENSITY,
            "support_interface_density",
            settings.supportInterfaceDensityPercent,
        )
        putOverride(SlicerSettings.Keys.SUPPORT_Z_DISTANCE, "support_z_distance", settings.supportZDistanceMm)
        putOverride(SlicerSettings.Keys.SUPPORT_XY_DISTANCE, "support_xy_distance", settings.supportXyDistanceMm)
        putOverride(SlicerSettings.Keys.SUPPORT_SPEED, "speed_support", settings.supportSpeedMmPerSecond)
        putOverride(
            SlicerSettings.Keys.SUPPORT_INTERFACE_SPEED,
            "speed_support_interface",
            settings.supportInterfaceSpeedMmPerSecond,
        )
        putOverride(SlicerSettings.Keys.RETRACTION_DISTANCE, "retraction_enable", settings.retractionDistanceMm > 0.0)
        putOverride(SlicerSettings.Keys.RETRACTION_DISTANCE, "retraction_amount", settings.retractionDistanceMm)
        putOverride(SlicerSettings.Keys.RETRACTION_SPEED, "retraction_speed", settings.retractionSpeedMmPerSecond)
        putOverride(
            SlicerSettings.Keys.RETRACTION_MINIMUM_TRAVEL,
            "retraction_min_travel",
            settings.retractionMinimumTravelMm,
        )
        putOverride(
            SlicerSettings.Keys.RETRACT_AT_LAYER_CHANGE,
            "retract_at_layer_change",
            settings.retractAtLayerChange,
        )
        putOverride(SlicerSettings.Keys.COMBING_MODE, "retraction_combing", settings.combingMode)
        putOverride(SlicerSettings.Keys.AVOID_PRINTED_PARTS, "travel_avoid_other_parts", settings.avoidPrintedParts)
        putOverride(
            SlicerSettings.Keys.TRAVEL_AVOID_DISTANCE,
            "travel_avoid_distance",
            settings.travelAvoidDistanceMm,
        )
        putOverride(SlicerSettings.Keys.Z_HOP, "retraction_hop_enabled", settings.zHopEnabled)
        putOverride(SlicerSettings.Keys.Z_HOP_HEIGHT, "retraction_hop", settings.zHopHeightMm)
        putOverride(SlicerSettings.Keys.FIRMWARE_RETRACTION, "machine_firmware_retract", settings.firmwareRetraction)
        putOverride(SlicerSettings.Keys.ADHESION_TYPE, "adhesion_type", settings.adhesionType)
        putOverride(SlicerSettings.Keys.SKIRT_LINE_COUNT, "skirt_line_count", settings.skirtLineCount)
        putOverride(SlicerSettings.Keys.BRIM_WIDTH, "brim_width", settings.brimWidthMm)
        putOverride(SlicerSettings.Keys.IRONING_ENABLED, "ironing_enabled", settings.ironingEnabled)
        putOverride(SlicerSettings.Keys.IRONING_FLOW, "ironing_flow", settings.ironingFlowPercent)
        putOverride(SlicerSettings.Keys.IRONING_SPEED, "speed_ironing", settings.ironingSpeedMmPerSecond)
    }

    private fun validateResolvedSettings(
        global: Map<String, String>,
        extruder: Map<String, String>,
    ) {
        val unresolved = (global.entries + extruder.entries)
            .filter { (_, value) -> value.trim().startsWith("=") }
            .map { it.key }
        require(unresolved.isEmpty()) {
            "Unresolved Cura formulas remain: ${unresolved.take(10).joinToString()}"
        }

        fun number(values: Map<String, String>, key: String): Double {
            val raw = values[key] ?: error("Resolved Cura setting is missing: $key")
            val value = raw.toDoubleOrNull() ?: error("Resolved Cura setting is not numeric: $key=$raw")
            require(value.isFinite()) { "Resolved Cura setting is not finite: $key=$raw" }
            return value
        }

        fun range(values: Map<String, String>, key: String, minimum: Double, maximum: Double) {
            val value = number(values, key)
            require(value in minimum..maximum) {
                "Resolved Cura setting is outside its safe range: $key=$value, expected $minimum..$maximum"
            }
        }

        range(global, "machine_width", 1.0, 2000.0)
        range(global, "machine_depth", 1.0, 2000.0)
        range(global, "machine_height", 1.0, 2000.0)
        range(global, "layer_height", 0.01, 5.0)
        range(extruder, "machine_nozzle_size", 0.05, 5.0)
        range(extruder, "material_diameter", 0.5, 5.0)
        range(extruder, "line_width", 0.01, 5.0)
        range(extruder, "wall_line_count", 0.0, 1000.0)
        range(extruder, "top_layers", 0.0, 1000000.0)
        range(extruder, "bottom_layers", 0.0, 1000000.0)
        range(extruder, "infill_sparse_density", 0.0, 100.0)
        range(extruder, "material_print_temperature", 150.0, 500.0)
        range(extruder, "material_print_temperature_layer_0", 150.0, 500.0)
        range(extruder, "cool_min_temperature", 150.0, 500.0)
        range(global, "material_bed_temperature", 0.0, 200.0)
        range(extruder, "material_flow", 1.0, 300.0)
        range(extruder, "cool_fan_speed", 0.0, 100.0)
        range(extruder, "cool_fan_speed_0", 0.0, 100.0)
        range(extruder, "cool_fan_full_layer", 0.0, 1000000.0)
        listOf(
            "speed_print",
            "speed_wall",
            "speed_wall_0",
            "speed_wall_x",
            "speed_infill",
            "speed_topbottom",
            "speed_travel",
            "speed_layer_0",
            "speed_support",
            "speed_support_interface",
            "speed_ironing",
        ).forEach { key -> range(extruder, key, 0.1, 1000.0) }
        range(extruder, "support_infill_rate", 0.0, 100.0)
        range(extruder, "support_interface_density", 0.0, 100.0)
        range(extruder, "support_z_distance", 0.0, 20.0)
        range(extruder, "support_xy_distance", 0.0, 20.0)
        range(extruder, "retraction_amount", 0.0, 100.0)
        range(extruder, "retraction_speed", 0.0, 1000.0)
        range(extruder, "retraction_min_travel", 0.0, 1000.0)
        range(extruder, "travel_avoid_distance", 0.0, 100.0)
        range(extruder, "retraction_hop", 0.0, 100.0)
        range(extruder, "skirt_line_count", 0.0, 1000.0)
        range(extruder, "brim_width", 0.0, 100.0)
        range(extruder, "ironing_flow", 0.0, 100.0)
    }
}
