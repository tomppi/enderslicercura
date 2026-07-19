package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.model.resolveEndGcode
import com.tomppi.enderslicer.model.resolveStartGcode
import com.tomppi.enderslicer.model.withSettings
import com.tomppi.enderslicer.profile.CuraEngineProfile

object CuraEngineCommand {
    fun buildResolved(
        executablePath: String,
        definitionsDirectory: String,
        resolvedSettingsPath: String,
        outputPath: String,
        threadCount: Int = 4,
    ): List<String> {
        require(threadCount in 1..32) { "Invalid CuraEngine thread count: $threadCount" }
        listOf(executablePath, definitionsDirectory, resolvedSettingsPath, outputPath)
            .forEach(::requireSafeArgument)
        return listOf(
            executablePath,
            "slice",
            "-m$threadCount",
            "-d",
            definitionsDirectory,
            "-r",
            resolvedSettingsPath,
            "-o",
            outputPath,
        )
    }

    fun build(
        executablePath: String,
        definitionsDirectory: String,
        machineDefinitionPath: String,
        extruderDefinitionPath: String,
        modelPath: String,
        outputPath: String,
        printer: PrinterDefinition,
        settings: SlicerSettings,
        startGcode: String,
        endGcode: String,
        profile: CuraEngineProfile? = null,
        threadCount: Int = 4,
    ): List<String> {
        require(threadCount in 1..32) { "Invalid CuraEngine thread count: $threadCount" }
        listOf(
            executablePath,
            definitionsDirectory,
            machineDefinitionPath,
            extruderDefinitionPath,
            modelPath,
            outputPath,
        ).forEach(::requireSafeArgument)

        val effectivePrinter = printer.withSettings(settings)
        val effectiveStartGcode = settings.resolveStartGcode(startGcode)
        val effectiveEndGcode = settings.resolveEndGcode(endGcode)
        val engineOffsetX = if (effectivePrinter.originAtCenter) 0.0 else -effectivePrinter.widthMm / 2.0
        val engineOffsetY = if (effectivePrinter.originAtCenter) 0.0 else -effectivePrinter.depthMm / 2.0
        val applyAllAppSettings = profile == null
        requireSafeArgument(effectiveStartGcode)
        requireSafeArgument(effectiveEndGcode)

        val command = mutableListOf(
            executablePath,
            "slice",
            "-m$threadCount",
            "-d",
            definitionsDirectory,
            "--force-read-parent",
            "-j",
            machineDefinitionPath,
            "--end-force-read",
        )

        fun setting(key: String, value: Any) {
            val normalized = when (value) {
                is Boolean -> value.toString().lowercase()
                else -> value.toString()
            }
            requireSafeArgument(key)
            requireSafeArgument(normalized)
            command += "-s"
            command += "$key=$normalized"
        }

        fun applyConcrete(values: Map<String, String>) {
            values.forEach { (key, rawValue) ->
                val value = rawValue.trim()
                if (key.isBlank() || value.isBlank() || value.startsWith("=")) return@forEach
                val normalized = when (value.lowercase()) {
                    "true" -> "true"
                    "false" -> "false"
                    else -> rawValue
                }
                setting(key, normalized)
            }
        }

        fun shouldApply(appKey: String): Boolean = applyAllAppSettings || settings.isOverridden(appKey)

        fun appSetting(appKey: String, curaKey: String, value: Any) {
            if (shouldApply(appKey)) setting(curaKey, value)
        }

        fun applyPrintSettings() {
            appSetting(SlicerSettings.Keys.LAYER_HEIGHT, "layer_height", settings.layerHeightMm)
            appSetting(SlicerSettings.Keys.INITIAL_LAYER_HEIGHT, "layer_height_0", settings.initialLayerHeightMm)
            appSetting(SlicerSettings.Keys.LINE_WIDTH, "line_width", settings.lineWidthMm)
            appSetting(SlicerSettings.Keys.SLICING_TOLERANCE, "slicing_tolerance", settings.slicingTolerance)
            appSetting(SlicerSettings.Keys.WALL_LINE_COUNT, "wall_line_count", settings.wallLineCount)
            appSetting(SlicerSettings.Keys.TOP_LAYERS, "top_layers", settings.topLayers)
            appSetting(SlicerSettings.Keys.BOTTOM_LAYERS, "bottom_layers", settings.bottomLayers)
            appSetting(SlicerSettings.Keys.Z_SEAM_TYPE, "z_seam_type", settings.zSeamType)
            appSetting(SlicerSettings.Keys.Z_SEAM_X, "z_seam_x", settings.zSeamXmm)
            appSetting(SlicerSettings.Keys.Z_SEAM_Y, "z_seam_y", settings.zSeamYmm)
            appSetting(SlicerSettings.Keys.Z_SEAM_RELATIVE, "z_seam_relative", settings.zSeamRelative)
            appSetting(SlicerSettings.Keys.Z_SEAM_CORNER, "z_seam_corner", settings.zSeamCorner)
            appSetting(SlicerSettings.Keys.INFILL_DENSITY, "infill_sparse_density", settings.infillDensityPercent)
            appSetting(SlicerSettings.Keys.INFILL_PATTERN, "infill_pattern", settings.infillPattern)
            appSetting(SlicerSettings.Keys.PRINT_SPEED, "speed_print", settings.printSpeedMmPerSecond)
            appSetting(SlicerSettings.Keys.WALL_SPEED, "speed_wall", settings.wallSpeedMmPerSecond)
            appSetting(SlicerSettings.Keys.OUTER_WALL_SPEED, "speed_wall_0", settings.outerWallSpeedMmPerSecond)
            appSetting(SlicerSettings.Keys.INNER_WALL_SPEED, "speed_wall_x", settings.innerWallSpeedMmPerSecond)
            appSetting(SlicerSettings.Keys.INFILL_SPEED, "speed_infill", settings.infillSpeedMmPerSecond)
            appSetting(SlicerSettings.Keys.TOP_BOTTOM_SPEED, "speed_topbottom", settings.topBottomSpeedMmPerSecond)
            appSetting(SlicerSettings.Keys.TRAVEL_SPEED, "speed_travel", settings.travelSpeedMmPerSecond)
            appSetting(SlicerSettings.Keys.INITIAL_LAYER_SPEED, "speed_layer_0", settings.initialLayerSpeedMmPerSecond)

            appSetting(SlicerSettings.Keys.SUPPORTS_ENABLED, "support_enable", settings.supportsEnabled)
            appSetting(SlicerSettings.Keys.SUPPORT_PLACEMENT, "support_type", settings.supportPlacement)
            appSetting(SlicerSettings.Keys.SUPPORT_STRUCTURE, "support_structure", settings.supportStructure)
            appSetting(SlicerSettings.Keys.SUPPORT_ANGLE, "support_angle", settings.supportAngleDegrees)
            appSetting(SlicerSettings.Keys.SUPPORT_PATTERN, "support_pattern", settings.supportPattern)

            if (shouldApply(SlicerSettings.Keys.SUPPORT_DENSITY)) {
                setting("support_infill_rate", settings.supportDensityPercent)
            } else if (
                settings.supportsEnabled &&
                settings.supportStructure.equals("tree", ignoreCase = true)
            ) {
                // Cura's frontend formula resolves normal-support density to zero
                // when tree support is selected. CuraEngine does not evaluate
                // definition expressions when using the command transport.
                setting("support_infill_rate", 0.0)
            }

            if (shouldApply(SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED)) {
                setting(
                    "support_interface_enable",
                    settings.supportsEnabled && settings.supportInterfaceEnabled,
                )
            }

            if (shouldApply(SlicerSettings.Keys.SUPPORT_INTERFACE_DENSITY)) {
                val density = settings.supportInterfaceDensityPercent.coerceIn(0.0, 100.0)
                setting("support_interface_density", density)
                val lineDistance = if (density <= 0.0) {
                    0.0
                } else {
                    settings.lineWidthMm * 100.0 / density * 2.0
                }
                setting("support_roof_line_distance", lineDistance)
                setting("support_bottom_line_distance", lineDistance)
            }

            // These values provide a usable standalone fallback. Imported Cura
            // profiles keep their own interface/roof/bottom values and formulas.
            if (applyAllAppSettings) {
                val interfaceHeight = settings.layerHeightMm * 4.0
                setting("support_interface_extruder_nr", 0)
                setting("support_roof_extruder_nr", 0)
                setting("support_bottom_extruder_nr", 0)
                setting("support_interface_height", interfaceHeight)
                setting("support_interface_pattern", "grid")
                setting("support_roof_line_width", settings.lineWidthMm)
                setting("support_bottom_line_width", settings.lineWidthMm)
            }

            appSetting(SlicerSettings.Keys.SUPPORT_Z_DISTANCE, "support_z_distance", settings.supportZDistanceMm)
            appSetting(SlicerSettings.Keys.SUPPORT_XY_DISTANCE, "support_xy_distance", settings.supportXyDistanceMm)
            appSetting(SlicerSettings.Keys.SUPPORT_SPEED, "speed_support", settings.supportSpeedMmPerSecond)
            appSetting(
                SlicerSettings.Keys.SUPPORT_INTERFACE_SPEED,
                "speed_support_interface",
                settings.supportInterfaceSpeedMmPerSecond,
            )

            appSetting(SlicerSettings.Keys.ADHESION_TYPE, "adhesion_type", settings.adhesionType)
            appSetting(SlicerSettings.Keys.SKIRT_LINE_COUNT, "skirt_line_count", settings.skirtLineCount)
            appSetting(SlicerSettings.Keys.BRIM_WIDTH, "brim_width", settings.brimWidthMm)
            appSetting(SlicerSettings.Keys.BED_TEMPERATURE, "material_bed_temperature", settings.bedTemperatureC)
            appSetting(
                SlicerSettings.Keys.BED_TEMPERATURE,
                "material_bed_temperature_layer_0",
                settings.bedTemperatureC,
            )
            appSetting(SlicerSettings.Keys.MATERIAL_FLOW, "material_flow", settings.materialFlowPercent)
            appSetting(SlicerSettings.Keys.FAN_SPEED, "cool_fan_speed", settings.fanSpeedPercent)
            appSetting(SlicerSettings.Keys.INITIAL_FAN_SPEED, "cool_fan_speed_0", settings.initialFanSpeedPercent)
            appSetting(SlicerSettings.Keys.FAN_FULL_AT_LAYER, "cool_fan_full_layer", settings.fanFullAtLayer)
            appSetting(
                SlicerSettings.Keys.RETRACTION_DISTANCE,
                "retraction_enable",
                settings.retractionDistanceMm > 0.0,
            )
            appSetting(SlicerSettings.Keys.RETRACTION_DISTANCE, "retraction_amount", settings.retractionDistanceMm)
            appSetting(SlicerSettings.Keys.RETRACTION_SPEED, "retraction_speed", settings.retractionSpeedMmPerSecond)
            appSetting(
                SlicerSettings.Keys.RETRACTION_MINIMUM_TRAVEL,
                "retraction_min_travel",
                settings.retractionMinimumTravelMm,
            )
            appSetting(
                SlicerSettings.Keys.RETRACT_AT_LAYER_CHANGE,
                "retract_at_layer_change",
                settings.retractAtLayerChange,
            )
            appSetting(SlicerSettings.Keys.COMBING_MODE, "retraction_combing", settings.combingMode)
            appSetting(SlicerSettings.Keys.AVOID_PRINTED_PARTS, "travel_avoid_other_parts", settings.avoidPrintedParts)
            appSetting(
                SlicerSettings.Keys.TRAVEL_AVOID_DISTANCE,
                "travel_avoid_distance",
                settings.travelAvoidDistanceMm,
            )
            appSetting(SlicerSettings.Keys.Z_HOP, "retraction_hop_enabled", settings.zHopEnabled)
            appSetting(SlicerSettings.Keys.Z_HOP_HEIGHT, "retraction_hop", settings.zHopHeightMm)
            appSetting(SlicerSettings.Keys.FIRMWARE_RETRACTION, "machine_firmware_retract", settings.firmwareRetraction)
            appSetting(SlicerSettings.Keys.COASTING_ENABLED, "coasting_enable", settings.coastingEnabled)
            appSetting(SlicerSettings.Keys.COASTING_VOLUME, "coasting_volume", settings.coastingVolumeMm3)
            appSetting(
                SlicerSettings.Keys.COASTING_MINIMUM_VOLUME,
                "coasting_min_volume",
                settings.coastingMinimumVolumeMm3,
            )
            appSetting(SlicerSettings.Keys.COASTING_SPEED, "coasting_speed", settings.coastingSpeedPercent)
            appSetting(SlicerSettings.Keys.IRONING_ENABLED, "ironing_enabled", settings.ironingEnabled)
            appSetting(SlicerSettings.Keys.IRONING_FLOW, "ironing_flow", settings.ironingFlowPercent)
            appSetting(SlicerSettings.Keys.IRONING_SPEED, "speed_ironing", settings.ironingSpeedMmPerSecond)
        }

        applyConcrete(profile?.globalValues.orEmpty())

        setting("machine_name", effectivePrinter.name)
        setting("machine_width", effectivePrinter.widthMm)
        setting("machine_depth", effectivePrinter.depthMm)
        setting("machine_height", effectivePrinter.heightMm)
        setting("machine_shape", effectivePrinter.buildPlateShape)
        setting("machine_center_is_zero", effectivePrinter.originAtCenter)
        setting("machine_heated_bed", effectivePrinter.heatedBed)
        setting("machine_heated_build_volume", effectivePrinter.heatedBuildVolume)
        setting("machine_extruder_count", effectivePrinter.extruders)
        setting("machine_gcode_flavor", effectivePrinter.gcodeFlavor)
        setting("machine_start_gcode", effectiveStartGcode)
        setting("machine_end_gcode", effectiveEndGcode)
        setting("gantry_height", effectivePrinter.gantryHeightMm)
        setting(
            "machine_head_with_fans_polygon",
            "[[${effectivePrinter.printheadXMinMm},${effectivePrinter.printheadYMaxMm}],[${effectivePrinter.printheadXMinMm},${effectivePrinter.printheadYMinMm}],[${effectivePrinter.printheadXMaxMm},${effectivePrinter.printheadYMinMm}],[${effectivePrinter.printheadXMaxMm},${effectivePrinter.printheadYMaxMm}]]",
        )
        setting("center_object", false)
        setting("mesh_position_x", engineOffsetX)
        setting("mesh_position_y", engineOffsetY)
        setting("mesh_position_z", 0)
        applyPrintSettings()

        command += listOf(
            "-e0",
            "--force-read-parent",
            "-j",
            machineDefinitionPath,
            "-j",
            extruderDefinitionPath,
            "--end-force-read",
        )
        applyConcrete(profile?.extruderValues.orEmpty())
        applyPrintSettings()

        setting("extruder_nr", 0)
        setting("machine_nozzle_size", effectivePrinter.nozzleSizeMm)
        setting("material_diameter", effectivePrinter.filamentDiameterMm)

        if (applyAllAppSettings || settings.isOverridden(SlicerSettings.Keys.NOZZLE_TEMPERATURE)) {
            setting("material_print_temperature", settings.nozzleTemperatureC)
            setting("material_initial_print_temperature", settings.nozzleTemperatureC)
            setting("material_final_print_temperature", settings.nozzleTemperatureC)
            setting("cool_min_temperature", settings.nozzleTemperatureC)
        }
        if (applyAllAppSettings || settings.isOverridden(SlicerSettings.Keys.INITIAL_NOZZLE_TEMPERATURE)) {
            setting("material_print_temperature_layer_0", settings.initialNozzleTemperatureC)
        }

        setting("center_object", false)
        setting("mesh_position_x", engineOffsetX)
        setting("mesh_position_y", engineOffsetY)
        setting("mesh_position_z", 0)

        command += listOf("-l", modelPath, "-o", outputPath)
        return command
    }

    private fun requireSafeArgument(value: String) {
        require('\u0000' !in value) { "CuraEngine argument contains a NUL character" }
    }
}
