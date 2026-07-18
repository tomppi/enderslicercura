package com.tomppi.enderslicer.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CuraDefinitionResolverIntegrationTest {
    @Test
    fun resolvesPinnedEnder3DefinitionsWithoutFormulaFallbacks() {
        val definitions = loadDefinitions()
        val resolved = CuraDefinitionResolver.resolve(
            definitionFiles = definitions,
            machineDefinitionFileName = "creality_ender3.def.json",
            extruderDefinitionFileName = "creality_base_extruder_0.def.json",
            globalOverrides = linkedMapOf(
                "machine_name" to "Modified Ender 3 V2",
                "machine_width" to "230",
                "machine_depth" to "230",
                "machine_height" to "250",
                "machine_shape" to "rectangular",
                "machine_center_is_zero" to "false",
                "machine_heated_bed" to "true",
                "machine_heated_build_volume" to "false",
                "machine_extruder_count" to "1",
                "machine_gcode_flavor" to "Marlin",
                "layer_height" to "0.2",
                "layer_height_0" to "0.28",
                "line_width" to "0.4",
                "speed_print" to "200",
                "infill_sparse_density" to "10",
                "support_enable" to "true",
                "support_type" to "everywhere",
                "support_structure" to "tree",
                "support_angle" to "56",
                "adhesion_type" to "none",
                "material_bed_temperature" to "60",
                "material_bed_temperature_layer_0" to "60",
                "material_flow" to "100",
                "cool_fan_speed" to "100",
                "top_bottom_thickness" to "=layer_height_0+layer_height*3",
                "wall_thickness" to "=line_width*2",
            ),
            extruderOverrides = linkedMapOf(
                "extruder_nr" to "0",
                "machine_nozzle_size" to "0.4",
                "material_diameter" to "1.75",
                "layer_height" to "0.2",
                "layer_height_0" to "0.28",
                "line_width" to "0.4",
                "speed_print" to "200",
                "infill_sparse_density" to "10",
                "support_enable" to "true",
                "support_type" to "everywhere",
                "support_structure" to "tree",
                "support_angle" to "56",
                "adhesion_type" to "none",
                "material_flow" to "100",
                "cool_fan_speed" to "100",
                "material_print_temperature" to "210",
                "material_print_temperature_layer_0" to "235",
                "material_initial_print_temperature" to "210",
                "material_final_print_temperature" to "210",
                "material_standby_temperature" to "180",
                "retraction_enable" to "true",
                "retraction_amount" to "1.5",
                "retraction_speed" to "120",
                "retract_at_layer_change" to "true",
                "retraction_hop_enabled" to "false",
                "machine_firmware_retract" to "true",
                "top_layers" to "=0 if infill_sparse_density == 100 else math.ceil(round(top_thickness / resolveOrValue('layer_height'), 4))",
                "bottom_layers" to "=999999 if infill_sparse_density == 100 and not magic_spiralize else math.ceil(round(bottom_thickness / resolveOrValue('layer_height'), 4))",
                "initial_bottom_layers" to "=bottom_layers",
            ),
        )

        assertTrue(resolved.expressionCount > 300)
        assertTrue(resolved.passes > 1)
        assertEquals("210", resolved.extruderValues["cool_min_temperature"])
        assertEquals("0", resolved.extruderValues["cool_fan_speed_0"])
        assertEquals("0.68", resolved.extruderValues["cool_fan_full_at_height"])
        assertEquals("4", resolved.extruderValues["cool_fan_full_layer"])
        assertEquals("0.88", resolved.extruderValues["top_bottom_thickness"])
        assertEquals("5", resolved.extruderValues["top_layers"])
        assertEquals("5", resolved.extruderValues["bottom_layers"])
        assertEquals("2", resolved.extruderValues["wall_line_count"])
        assertEquals("cubic", resolved.extruderValues["infill_pattern"])
        assertEquals("12", resolved.extruderValues["infill_line_distance"])
        assertEquals("200", resolved.extruderValues["speed_infill"])
        assertEquals("100", resolved.extruderValues["speed_topbottom"])
        assertFalse(resolved.globalValues.values.any { it.trim().startsWith("=") })
        assertFalse(resolved.extruderValues.values.any { it.trim().startsWith("=") })
    }

    private fun loadDefinitions(): Map<String, String> {
        val directory = sequenceOf(
            File("app/src/main/assets/cura/definitions"),
            File("src/main/assets/cura/definitions"),
        ).firstOrNull(File::isDirectory)
            ?: error("Pinned Cura definition directory was not found")
        val names = listOf(
            "fdmprinter.def.json",
            "fdmextruder.def.json",
            "creality_base.def.json",
            "creality_base_extruder_0.def.json",
            "creality_ender3.def.json",
        )
        return names.associateWith { name ->
            File(directory, name).also { file ->
                check(file.isFile && file.length() > 0L) { "Missing pinned Cura definition: $name" }
            }.readText()
        }
    }
}
