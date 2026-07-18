package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings

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

        val globalOverrides = linkedMapOf<String, String>().apply {
            putAll(profile.rawGlobalValues)
            put("machine_name", printer.name)
            put("machine_width", printer.widthMm.toString())
            put("machine_depth", printer.depthMm.toString())
            put("machine_height", printer.heightMm.toString())
            put("machine_shape", printer.buildPlateShape)
            put("machine_center_is_zero", printer.originAtCenter.toString())
            put("machine_heated_bed", printer.heatedBed.toString())
            put("machine_heated_build_volume", printer.heatedBuildVolume.toString())
            put("machine_extruder_count", printer.extruders.toString())
            put("machine_gcode_flavor", printer.gcodeFlavor)
            put("machine_start_gcode", startGcode)
            put("machine_end_gcode", endGcode)
            put("gantry_height", printer.gantryHeightMm.toString())
            put(
                "machine_head_with_fans_polygon",
                "[[${printer.printheadXMinMm},${printer.printheadYMaxMm}],[${printer.printheadXMinMm},${printer.printheadYMinMm}],[${printer.printheadXMaxMm},${printer.printheadYMinMm}],[${printer.printheadXMaxMm},${printer.printheadYMaxMm}]]",
            )
            put("layer_height", settings.layerHeightMm.toString())
            put("layer_height_0", settings.initialLayerHeightMm.toString())
            put("line_width", settings.lineWidthMm.toString())
            put("speed_print", settings.printSpeedMmPerSecond.toString())
            put("infill_sparse_density", settings.infillDensityPercent.toString())
            put("support_enable", settings.supportsEnabled.toString())
            put("support_type", settings.supportPlacement)
            put("support_structure", settings.supportStructure)
            put("support_angle", settings.supportAngleDegrees.toString())
            put("adhesion_type", settings.adhesionType)
            put("material_bed_temperature", settings.bedTemperatureC.toString())
            put("material_bed_temperature_layer_0", settings.bedTemperatureC.toString())
            put("material_flow", settings.materialFlowPercent.toString())
            put("cool_fan_speed", settings.fanSpeedPercent.toString())
        }

        val extruderOverrides = linkedMapOf<String, String>().apply {
            putAll(profile.rawExtruderValues)
            put("extruder_nr", "0")
            put("machine_nozzle_size", printer.nozzleSizeMm.toString())
            put("material_diameter", printer.filamentDiameterMm.toString())
            put("layer_height", settings.layerHeightMm.toString())
            put("layer_height_0", settings.initialLayerHeightMm.toString())
            put("line_width", settings.lineWidthMm.toString())
            put("speed_print", settings.printSpeedMmPerSecond.toString())
            put("infill_sparse_density", settings.infillDensityPercent.toString())
            put("support_enable", settings.supportsEnabled.toString())
            put("support_type", settings.supportPlacement)
            put("support_structure", settings.supportStructure)
            put("support_angle", settings.supportAngleDegrees.toString())
            put("adhesion_type", settings.adhesionType)
            put("material_flow", settings.materialFlowPercent.toString())
            put("cool_fan_speed", settings.fanSpeedPercent.toString())
            put("material_print_temperature", settings.nozzleTemperatureC.toString())
            put("material_print_temperature_layer_0", settings.initialNozzleTemperatureC.toString())
            put("material_initial_print_temperature", settings.nozzleTemperatureC.toString())
            put("material_final_print_temperature", settings.nozzleTemperatureC.toString())
            put("retraction_enable", (settings.retractionDistanceMm > 0.0).toString())
            put("retraction_amount", settings.retractionDistanceMm.toString())
            put("retraction_speed", settings.retractionSpeedMmPerSecond.toString())
            put("retract_at_layer_change", settings.retractAtLayerChange.toString())
            put("retraction_hop_enabled", settings.zHopEnabled.toString())
            put("machine_firmware_retract", settings.firmwareRetraction.toString())
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
        range(extruder, "line_width", 0.01, 5.0)
        range(extruder, "material_print_temperature", 150.0, 500.0)
        range(extruder, "material_print_temperature_layer_0", 150.0, 500.0)
        range(extruder, "cool_min_temperature", 150.0, 500.0)
        range(global, "material_bed_temperature", 0.0, 200.0)
        range(extruder, "infill_sparse_density", 0.0, 100.0)
        range(extruder, "cool_fan_speed", 0.0, 100.0)
        range(extruder, "cool_fan_speed_0", 0.0, 100.0)
        range(extruder, "speed_print", 0.1, 1000.0)
        range(extruder, "wall_line_count", 0.0, 1000.0)
        range(extruder, "top_layers", 0.0, 1000000.0)
        range(extruder, "bottom_layers", 0.0, 1000000.0)
    }
}
