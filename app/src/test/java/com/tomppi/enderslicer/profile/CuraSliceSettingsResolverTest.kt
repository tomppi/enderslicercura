package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CuraSliceSettingsResolverTest {
    private val printer = PrinterDefinition(
        id = "modified_ender3_v2",
        name = "Modified Ender 3 V2",
        manufacturer = "Creality",
        widthMm = 230.0,
        depthMm = 230.0,
        heightMm = 250.0,
        buildPlateShape = "rectangular",
        originAtCenter = false,
        heatedBed = true,
        heatedBuildVolume = false,
        gcodeFlavor = "Marlin",
        extruders = 1,
        nozzleSizeMm = 0.4,
        filamentDiameterMm = 1.75,
        printheadXMinMm = -26.0,
        printheadYMinMm = -32.0,
        printheadXMaxMm = 32.0,
        printheadYMaxMm = 34.0,
        gantryHeightMm = 25.0,
        directDrive = true,
        dualZ = true,
        zProbe = true,
        bedLeveling = "UBL",
        ublMeshSlot = 0,
    )

    @Test
    fun appEditsAreAppliedBeforeDependentCuraFormulas() {
        val definitions = loadDefinitions()
        val profile = CuraEngineProfile(
            globalValues = mapOf("layer_height" to "0.2"),
            extruderValues = mapOf("speed_print" to "120"),
            rawGlobalValues = linkedMapOf(
                "layer_height" to "0.2",
                "layer_height_0" to "0.28",
                "top_bottom_thickness" to "=layer_height_0+layer_height*3",
                "wall_thickness" to "=line_width*2",
                "support_enable" to "True",
                "support_type" to "everywhere",
                "support_structure" to "tree",
                "material_bed_temperature" to "60",
            ),
            rawExtruderValues = linkedMapOf(
                "machine_nozzle_size" to "0.4",
                "line_width" to "=machine_nozzle_size",
                "speed_print" to "120",
                "speed_infill" to "=speed_print",
                "speed_wall" to "=speed_print / 2",
                "speed_topbottom" to "=speed_print / 2",
                "infill_sparse_density" to "10",
                "infill_pattern" to "cubic",
                "infill_line_width" to "=line_width",
                "infill_line_distance" to "=0 if infill_sparse_density == 0 else (infill_line_width * 100) / infill_sparse_density * (3 if infill_pattern == 'cubic' else 1)",
                "material_print_temperature" to "200",
                "material_print_temperature_layer_0" to "220",
                "cool_min_temperature" to "=material_print_temperature",
                "cool_fan_speed" to "100",
                "cool_fan_speed_0" to "0",
                "cool_fan_full_at_height" to "=layer_height_0 + layer_height * 2",
                "cool_fan_full_layer" to "=max(1, int(math.floor((cool_fan_full_at_height - resolveOrValue('layer_height_0')) / resolveOrValue('layer_height')) + 2))",
                "top_bottom_thickness" to "=layer_height_0+layer_height*3",
                "top_thickness" to "=top_bottom_thickness",
                "bottom_thickness" to "=top_bottom_thickness",
                "top_layers" to "=0 if infill_sparse_density == 100 else math.ceil(round(top_thickness / resolveOrValue('layer_height'), 4))",
                "bottom_layers" to "=999999 if infill_sparse_density == 100 and not magic_spiralize else math.ceil(round(bottom_thickness / resolveOrValue('layer_height'), 4))",
                "initial_bottom_layers" to "=bottom_layers",
                "wall_line_width_0" to "=line_width",
                "wall_line_width_x" to "=line_width",
                "wall_line_count" to "=1 if magic_spiralize else max(1, round((wall_thickness - wall_line_width_0) / wall_line_width_x) + 1) if wall_thickness != 0 else 0",
                "wall_thickness" to "=line_width*2",
            ),
            definitionFiles = definitions,
            machineDefinitionFileName = "creality_ender3.def.json",
            extruderDefinitionFileName = "creality_base_extruder_0.def.json",
        )
        val settings = SlicerSettings(
            layerHeightMm = 0.20,
            initialLayerHeightMm = 0.28,
            lineWidthMm = 0.40,
            printSpeedMmPerSecond = 200.0,
            nozzleTemperatureC = 210,
            initialNozzleTemperatureC = 235,
            bedTemperatureC = 60,
            infillDensityPercent = 10.0,
            supportsEnabled = true,
            supportPlacement = "everywhere",
            supportStructure = "tree",
            supportAngleDegrees = 56.0,
            fanSpeedPercent = 100.0,
        )

        val resolved = CuraSliceSettingsResolver.resolve(
            profile = profile,
            printer = printer,
            settings = settings,
            startGcode = "G28",
            endGcode = "M104 S0",
        )

        assertNumeric(resolved.extruderValues, "material_print_temperature", 210.0)
        assertNumeric(resolved.extruderValues, "material_print_temperature_layer_0", 235.0)
        assertNumeric(resolved.extruderValues, "cool_min_temperature", 210.0)
        assertNumeric(resolved.extruderValues, "speed_print", 200.0)
        assertNumeric(resolved.extruderValues, "speed_topbottom", 100.0)
        assertNumeric(resolved.extruderValues, "speed_wall", 100.0)
        assertNumeric(resolved.extruderValues, "speed_infill", 200.0)
        assertNumeric(resolved.extruderValues, "top_layers", 5.0)
        assertNumeric(resolved.extruderValues, "bottom_layers", 5.0)
        assertNumeric(resolved.extruderValues, "wall_line_count", 2.0)
        assertNumeric(resolved.extruderValues, "infill_line_distance", 12.0)
        assertNumeric(resolved.extruderValues, "cool_fan_speed_0", 0.0)
        assertNumeric(resolved.extruderValues, "cool_fan_full_layer", 4.0)
        assertEquals("cubic", resolved.extruderValues["infill_pattern"])
        assertEquals("G28", resolved.globalValues["machine_start_gcode"])
        assertEquals("M104 S0", resolved.globalValues["machine_end_gcode"])
        assertTrue(resolved.expressionCount >= 20)
    }

    private fun assertNumeric(values: Map<String, String>, key: String, expected: Double) {
        val raw = values[key] ?: error("Missing resolved setting: $key")
        val actual = raw.toDoubleOrNull() ?: error("Resolved setting is not numeric: $key=$raw")
        assertEquals("Unexpected resolved value for $key", expected, actual, 1e-7)
    }

    private fun loadDefinitions(): Map<String, String> {
        val directory = sequenceOf(
            File("app/src/main/assets/cura/definitions"),
            File("src/main/assets/cura/definitions"),
        ).firstOrNull(File::isDirectory)
            ?: error("Pinned Cura definition directory was not found")
        return listOf(
            "fdmprinter.def.json",
            "fdmextruder.def.json",
            "creality_base.def.json",
            "creality_base_extruder_0.def.json",
            "creality_ender3.def.json",
        ).associateWith { name -> File(directory, name).readText() }
    }
}
