package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.profile.CuraEngineProfile

object CuraEngineCommand {
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
        requireSafeArgument(startGcode)
        requireSafeArgument(endGcode)

        val command = mutableListOf(
            executablePath,
            "slice",
            "-m$threadCount",
            "-d",
            definitionsDirectory,
            // Parent settings such as roofing_layer_count must be retained. Do not
            // use --force-read-nondefault here: Cura value expressions must be
            // resolved by the profile stack rather than injected as raw strings.
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

        // Cura project global stack: definition changes -> quality -> user values.
        applyConcrete(profile?.globalValues.orEmpty())

        // App printer configuration is authoritative and is applied after the
        // imported Cura stack.
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
        setting(
            "machine_head_with_fans_polygon",
            "[[${printer.printheadXMinMm},${printer.printheadYMaxMm}],[${printer.printheadXMinMm},${printer.printheadYMinMm}],[${printer.printheadXMaxMm},${printer.printheadYMinMm}],[${printer.printheadXMaxMm},${printer.printheadYMaxMm}]]",
        )

        // App-editable global settings override imported values last.
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

        // -j applies to the currently selected stack. Load the flattened project
        // machine/extruder definitions into extruder 0, then apply the complete
        // concrete extruder stack from material, quality, and user containers.
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

        // Settings edited in the app must also override values stored in Cura's
        // extruder stack. Cura places several ordinary print settings there.
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
        setting("material_flow", settings.materialFlowPercent)
        setting("cool_fan_speed", settings.fanSpeedPercent)

        // App-editable extruder values remain the final authority.
        setting("extruder_nr", 0)
        setting("machine_nozzle_size", printer.nozzleSizeMm)
        setting("material_diameter", printer.filamentDiameterMm)
        setting("material_print_temperature", settings.nozzleTemperatureC)
        setting("material_print_temperature_layer_0", settings.initialNozzleTemperatureC)
        setting("material_initial_print_temperature", settings.nozzleTemperatureC)
        setting("material_final_print_temperature", settings.nozzleTemperatureC)
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
