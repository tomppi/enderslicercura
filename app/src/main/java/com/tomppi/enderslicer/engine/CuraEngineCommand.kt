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
        setting("mesh_position_x", 0)
        setting("mesh_position_y", 0)
        setting("mesh_position_z", 0)

        fun applyPrintSettings() {
            setting("layer_height", settings.layerHeightMm)
            setting("layer_height_0", settings.initialLayerHeightMm)
            setting("line_width", settings.lineWidthMm)
            setting("wall_line_count", settings.wallLineCount)
            setting("top_layers", settings.topLayers)
            setting("bottom_layers", settings.bottomLayers)
            setting("infill_sparse_density", settings.infillDensityPercent)
            setting("infill_pattern", settings.infillPattern)
            setting("speed_print", settings.printSpeedMmPerSecond)
            setting("speed_wall", settings.wallSpeedMmPerSecond)
            setting("speed_wall_0", settings.outerWallSpeedMmPerSecond)
            setting("speed_wall_x", settings.innerWallSpeedMmPerSecond)
            setting("speed_infill", settings.infillSpeedMmPerSecond)
            setting("speed_topbottom", settings.topBottomSpeedMmPerSecond)
            setting("speed_travel", settings.travelSpeedMmPerSecond)
            setting("speed_layer_0", settings.initialLayerSpeedMmPerSecond)
            setting("support_enable", settings.supportsEnabled)
            setting("support_type", settings.supportPlacement)
            setting("support_structure", settings.supportStructure)
            setting("support_angle", settings.supportAngleDegrees)
            setting("support_infill_rate", settings.supportDensityPercent)
            setting("support_pattern", settings.supportPattern)
            setting("support_interface_enable", settings.supportInterfaceEnabled)
            setting("support_interface_density", settings.supportInterfaceDensityPercent)
            setting("support_z_distance", settings.supportZDistanceMm)
            setting("support_xy_distance", settings.supportXyDistanceMm)
            setting("speed_support", settings.supportSpeedMmPerSecond)
            setting("speed_support_interface", settings.supportInterfaceSpeedMmPerSecond)
            setting("adhesion_type", settings.adhesionType)
            setting("skirt_line_count", settings.skirtLineCount)
            setting("brim_width", settings.brimWidthMm)
            setting("material_bed_temperature", settings.bedTemperatureC)
            setting("material_bed_temperature_layer_0", settings.bedTemperatureC)
            setting("material_flow", settings.materialFlowPercent)
            setting("cool_fan_speed", settings.fanSpeedPercent)
            setting("cool_fan_speed_0", settings.initialFanSpeedPercent)
            setting("cool_fan_full_layer", settings.fanFullAtLayer)
            setting("retraction_enable", settings.retractionDistanceMm > 0.0)
            setting("retraction_amount", settings.retractionDistanceMm)
            setting("retraction_speed", settings.retractionSpeedMmPerSecond)
            setting("retraction_min_travel", settings.retractionMinimumTravelMm)
            setting("retract_at_layer_change", settings.retractAtLayerChange)
            setting("retraction_combing", settings.combingMode)
            setting("travel_avoid_other_parts", settings.avoidPrintedParts)
            setting("travel_avoid_distance", settings.travelAvoidDistanceMm)
            setting("retraction_hop_enabled", settings.zHopEnabled)
            setting("retraction_hop", settings.zHopHeightMm)
            setting("machine_firmware_retract", settings.firmwareRetraction)
            setting("ironing_enabled", settings.ironingEnabled)
            setting("ironing_flow", settings.ironingFlowPercent)
            setting("speed_ironing", settings.ironingSpeedMmPerSecond)
        }

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
        setting("material_print_temperature", settings.nozzleTemperatureC)
        setting("material_print_temperature_layer_0", settings.initialNozzleTemperatureC)
        setting("material_initial_print_temperature", settings.nozzleTemperatureC)
        setting("material_final_print_temperature", settings.nozzleTemperatureC)
        setting("cool_min_temperature", settings.nozzleTemperatureC)
        setting("center_object", false)
        setting("mesh_position_x", 0)
        setting("mesh_position_y", 0)
        setting("mesh_position_z", 0)

        command += listOf("-l", modelPath, "-o", outputPath)
        return command
    }

    private fun requireSafeArgument(value: String) {
        require('\u0000' !in value) { "CuraEngine argument contains a NUL character" }
    }
}
