package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings

object CuraEngineCommand {
    fun build(
        executablePath: String,
        definitionsDirectory: String,
        modelPath: String,
        outputPath: String,
        printer: PrinterDefinition,
        settings: SlicerSettings,
        startGcode: String,
        endGcode: String,
        threadCount: Int = 4,
    ): List<String> {
        require(threadCount in 1..32) { "Invalid CuraEngine thread count: $threadCount" }
        listOf(executablePath, definitionsDirectory, modelPath, outputPath).forEach(::requireSafeArgument)
        requireSafeArgument(startGcode)
        requireSafeArgument(endGcode)

        val machineDefinition = "$definitionsDirectory/creality_ender3.def.json"
        val extruderDefinition = "$definitionsDirectory/creality_base_extruder_0.def.json"

        val command = mutableListOf(
            executablePath,
            "slice",
            "-m$threadCount",
            "-d",
            definitionsDirectory,
            // Creality's machine overrides use both default_value and value.
            // CuraEngine's CLI normally ignores a value when default_value is absent,
            // so explicitly enable its non-default fallback while reading definitions.
            "--force-read-nondefault",
            "-j",
            machineDefinition,
            "--end-force-read",
        )

        fun setting(key: String, value: Any) {
            val normalized = when (value) {
                is Boolean -> value.toString().lowercase()
                else -> value.toString()
            }
            requireSafeArgument(normalized)
            command += "-s"
            command += "$key=$normalized"
        }

        setting("machine_name", printer.name)
        setting("machine_width", printer.widthMm)
        setting("machine_depth", printer.depthMm)
        setting("machine_height", printer.heightMm)
        setting("machine_shape", printer.buildPlateShape)
        setting("machine_center_is_zero", printer.originAtCenter)
        setting("machine_heated_bed", printer.heatedBed)
        setting("machine_heated_build_volume", printer.heatedBuildVolume)
        setting("machine_extruder_count", printer.extruders)
        setting("machine_gcode_flavor", printer.gcodeFlavor)
        setting("machine_start_gcode", startGcode)
        setting("machine_end_gcode", endGcode)
        setting("gantry_height", printer.gantryHeightMm)
        setting("machine_head_with_fans_polygon", "[[${printer.printheadXMinMm},${printer.printheadYMaxMm}],[${printer.printheadXMinMm},${printer.printheadYMinMm}],[${printer.printheadXMaxMm},${printer.printheadYMinMm}],[${printer.printheadXMaxMm},${printer.printheadYMaxMm}]]")

        setting("layer_height", settings.layerHeightMm)
        setting("layer_height_0", settings.initialLayerHeightMm)
        setting("line_width", settings.lineWidthMm)
        setting("speed_print", settings.printSpeedMmPerSecond)
        setting("infill_sparse_density", settings.infillDensityPercent)
        setting("support_enable", settings.supportsEnabled)
        setting("support_type", settings.supportPlacement)
        setting("support_structure", settings.supportStructure)
        setting("support_angle", settings.supportAngleDegrees)
        setting("adhesion_type", settings.adhesionType)
        setting("material_bed_temperature", settings.bedTemperatureC)
        setting("material_bed_temperature_layer_0", settings.bedTemperatureC)
        setting("material_flow", settings.materialFlowPercent)
        setting("cool_fan_speed", settings.fanSpeedPercent)

        // -j applies to the currently selected settings stack. Loading the machine
        // definition only before -e0 leaves all limit_to_extruder settings (including
        // roofing_layer_count) absent from extruder 0. Load the complete printer
        // settings into that stack first, then overlay the Creality extruder train.
        command += listOf(
            "-e0",
            "--force-read-nondefault",
            "-j",
            machineDefinition,
            "-j",
            extruderDefinition,
            "--end-force-read",
        )

        setting("extruder_nr", 0)
        setting("machine_nozzle_size", printer.nozzleSizeMm)
        setting("material_diameter", printer.filamentDiameterMm)
        setting("material_print_temperature", settings.nozzleTemperatureC)
        setting("material_print_temperature_layer_0", settings.initialNozzleTemperatureC)
        setting("retraction_enable", settings.retractionDistanceMm > 0.0)
        setting("retraction_amount", settings.retractionDistanceMm)
        setting("retraction_speed", settings.retractionSpeedMmPerSecond)
        setting("retract_at_layer_change", settings.retractAtLayerChange)
        setting("retraction_hop_enabled", settings.zHopEnabled)
        setting("machine_firmware_retract", settings.firmwareRetraction)

        command += listOf("-l", modelPath, "-o", outputPath)
        return command
    }

    private fun requireSafeArgument(value: String) {
        require('\u0000' !in value) { "CuraEngine argument contains a NUL character" }
    }
}
