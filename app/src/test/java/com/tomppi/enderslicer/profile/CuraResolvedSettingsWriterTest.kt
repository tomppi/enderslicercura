package com.tomppi.enderslicer.profile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CuraResolvedSettingsWriterTest {
    @Test
    fun preservesFinalBedCoordinatesInBothModelScopes() {
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
                globalValues = mapOf("machine_width" to "230"),
                extruderValues = mapOf("material_print_temperature" to "210"),
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
            assertFalse(extruder.getBoolean("center_object"))
            assertEquals(0, extruder.getInt("mesh_position_x"))
            assertEquals(0, extruder.getInt("mesh_position_y"))
            assertEquals(0, extruder.getInt("mesh_position_z"))

            val model = root.getJSONObject("current.stl")
            assertEquals(0, model.getInt("extruder_nr"))
            assertFalse(model.getBoolean("center_object"))
            assertEquals(0, model.getInt("mesh_position_x"))
            assertEquals(0, model.getInt("mesh_position_y"))
            assertEquals(0, model.getInt("mesh_position_z"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
