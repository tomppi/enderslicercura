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
        val modelValues: Map<String, String>,
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
        val visibleValues = CuraUiSettingsOverlay.values(settings)

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
            putAll(visibleValues)
        }

        val extruderOverrides = linkedMapOf<String, String>().apply {
            putAll(profile.rawExtruderValues)
            put("extruder_nr", "0")
            put("machine_nozzle_size", effectivePrinter.nozzleSizeMm.toString())
            put("material_diameter", effectivePrinter.filamentDiameterMm.toString())
            putAll(visibleValues)
        }

        val resolved = CuraDefinitionResolver.resolve(
            definitionFiles = profile.definitionFiles,
            machineDefinitionFileName = requireNotNull(profile.machineDefinitionFileName),
            extruderDefinitionFileName = requireNotNull(profile.extruderDefinitionFileName),
            globalOverrides = globalOverrides,
            extruderOverrides = extruderOverrides,
        )

        // This assertion is intentionally part of production resolution. A
        // future mapping or precedence change must fail before CuraEngine runs
        // if any setting shown in the UI differs from the final engine snapshot.
        CuraUiSettingsOverlay.requireResolvedMatch(
            settings = settings,
            globalValues = resolved.globalValues,
            extruderValues = resolved.extruderValues,
        )
        validateResolvedSettings(resolved.globalValues, resolved.extruderValues)

        return Result(
            globalValues = resolved.globalValues,
            extruderValues = resolved.extruderValues,
            modelValues = resolved.modelValues,
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

        fun option(values: Map<String, String>, key: String, allowed: Set<String>) {
            val value = values[key] ?: error("Resolved Cura setting is missing: $key")
            require(value in allowed) {
                "Resolved Cura setting is invalid: $key=$value, expected one of ${allowed.joinToString()}"
            }
        }

        range(global, "machine_width", 1.0, 2000.0)
        range(global, "machine_depth", 1.0, 2000.0)
        range(global, "machine_height", 1.0, 2000.0)
        range(global, "layer_height", 0.01, 5.0)
        option(extruder, "slicing_tolerance", setOf("middle", "exclusive", "inclusive"))
        range(extruder, "machine_nozzle_size", 0.05, 5.0)
        range(extruder, "material_diameter", 0.5, 5.0)
        range(extruder, "line_width", 0.01, 5.0)
        range(extruder, "wall_line_count", 0.0, 1000.0)
        range(extruder, "top_layers", 0.0, 1000000.0)
        range(extruder, "bottom_layers", 0.0, 1000000.0)
        option(extruder, "z_seam_type", setOf("back", "shortest", "random", "sharpest_corner"))
        option(
            extruder,
            "z_seam_corner",
            setOf(
                "z_seam_corner_none",
                "z_seam_corner_inner",
                "z_seam_corner_outer",
                "z_seam_corner_any",
                "z_seam_corner_weighted",
            ),
        )
        range(extruder, "z_seam_x", -2000.0, 2000.0)
        range(extruder, "z_seam_y", -2000.0, 2000.0)
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
        range(extruder, "coasting_volume", 0.0, 1000.0)
        range(extruder, "coasting_min_volume", 0.0, 100000.0)
        range(extruder, "coasting_speed", 0.0001, 1000.0)
        if (extruder["coasting_enable"]?.toBooleanStrictOrNull() == true) {
            require(number(extruder, "coasting_min_volume") >= number(extruder, "coasting_volume")) {
                "Minimum volume before coasting must be at least the coasting volume"
            }
        }
        range(extruder, "skirt_line_count", 0.0, 1000.0)
        range(extruder, "brim_width", 0.0, 100.0)
        range(extruder, "ironing_flow", 0.0, 100.0)
    }
}
