package com.tomppi.enderslicer.profile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CuraResolvedSettingsWriterTest {
    @Test
    fun convertsBedCoordinatesToCuraEngineSpaceAndWritesMeshSupportSettings() {
        val directory = Files.createTempDirectory("enderslicer-resolved").toFile()
        try {
            val destination = File(directory, "resolved-settings.json")
            File(directory, "current.stl").writeText(
                """
                solid test
                  facet normal 0 0 1
                    outer loop
                      vertex 100 100 1
                      vertex 101 100 1
                      vertex 100 101 2
                    endloop
                  endfacet
                endsolid test
                """.trimIndent(),
            )
            val resolved = CuraSliceSettingsResolver.Result(
                globalValues = mapOf(
                    "machine_width" to "230",
                    "machine_depth" to "230",
                    "machine_center_is_zero" to "false",
                ),
                extruderValues = mapOf(
                    "material_print_temperature" to "210",
                    "support_infill_rate" to "0",
                    "support_interface_density" to "33.333",
                ),
                modelValues = mapOf(
                    "support_enable" to "true",
                    "support_interface_enable" to "true",
                    "support_roof_enable" to "true",
                    "support_z_distance" to "0.2",
                    "support_xy_distance" to "0.8",
                ),
                expressionCount = 400,
                passes = 5,
            )

            CuraResolvedSettingsWriter.write(
                destination = destination,
                modelFileName = "current.stl",
                resolved = resolved,
            )

            val root = JSONObject(destination.readText())
            assertEquals("230", root.getJSONObject("global").getString("machine_width"))

            val extruder = root.getJSONObject("extruder.0")
            assertEquals("210", extruder.getString("material_print_temperature"))
            assertEquals("0", extruder.getString("support_infill_rate"))
            assertEquals("33.333", extruder.getString("support_interface_density"))
            assertFalse(extruder.getBoolean("center_object"))
            assertEquals(-115.0, extruder.getDouble("mesh_position_x"), 1e-9)
            assertEquals(-115.0, extruder.getDouble("mesh_position_y"), 1e-9)
            assertEquals(0, extruder.getInt("mesh_position_z"))

            val model = root.getJSONObject("current.stl")
            assertEquals(0, model.getInt("extruder_nr"))
            assertTrue(model.getBoolean("support_enable"))
            assertTrue(model.getBoolean("support_interface_enable"))
            assertTrue(model.getBoolean("support_roof_enable"))
            assertEquals("0.2", model.getString("support_z_distance"))
            assertEquals("0.8", model.getString("support_xy_distance"))
            assertFalse(model.getBoolean("center_object"))
            assertEquals(-115.0, model.getDouble("mesh_position_x"), 1e-9)
            assertEquals(-115.0, model.getDouble("mesh_position_y"), 1e-9)
            assertEquals(0, model.getInt("mesh_position_z"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun keepsZeroOffsetForCenterOriginMachines() {
        val directory = Files.createTempDirectory("enderslicer-resolved-center").toFile()
        try {
            File(directory, "current.stl").writeText("solid test\nendsolid test")
            val destination = File(directory, "resolved-settings.json")
            CuraResolvedSettingsWriter.write(
                destination = destination,
                modelFileName = "current.stl",
                resolved = CuraSliceSettingsResolver.Result(
                    globalValues = mapOf(
                        "machine_width" to "230",
                        "machine_depth" to "230",
                        "machine_center_is_zero" to "true",
                    ),
                    extruderValues = emptyMap(),
                    modelValues = emptyMap(),
                    expressionCount = 0,
                    passes = 1,
                ),
            )
            val root = JSONObject(destination.readText())
            assertEquals(0.0, root.getJSONObject("extruder.0").getDouble("mesh_position_x"), 1e-9)
            assertEquals(0.0, root.getJSONObject("current.stl").getDouble("mesh_position_y"), 1e-9)
        } finally {
            directory.deleteRecursively()
        }
    }
}
