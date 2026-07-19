package com.tomppi.enderslicer.profile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class CuraResolvedSettingsWriterTest {
    @Test
    fun translatesStagedMeshAndCopiesMeshSupportIntoExtruderScope() {
        val directory = Files.createTempDirectory("enderslicer-resolved").toFile()
        try {
            val modelFile = File(directory, "current.stl")
            writeTriangle(modelFile, 100f, 100f, 1f)
            val destination = File(directory, "resolved-settings.json")
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
                modelFileName = modelFile.name,
                resolved = resolved,
            )

            val root = JSONObject(destination.readText())
            assertEquals("230", root.getJSONObject("global").getString("machine_width"))

            val extruder = root.getJSONObject("extruder.0")
            assertEquals("210", extruder.getString("material_print_temperature"))
            assertEquals("0", extruder.getString("support_infill_rate"))
            assertEquals("33.333", extruder.getString("support_interface_density"))
            assertTrue(extruder.getBoolean("support_enable"))
            assertTrue(extruder.getBoolean("support_interface_enable"))
            assertTrue(extruder.getBoolean("support_roof_enable"))
            assertEquals("0.2", extruder.getString("support_z_distance"))
            assertFalse(extruder.getBoolean("center_object"))
            assertEquals(0.0, extruder.getDouble("mesh_position_x"), 1e-9)
            assertEquals(0.0, extruder.getDouble("mesh_position_y"), 1e-9)

            val model = root.getJSONObject("current.stl")
            assertEquals(0, model.getInt("extruder_nr"))
            assertTrue(model.getBoolean("support_interface_enable"))
            assertTrue(model.getBoolean("support_roof_enable"))
            assertEquals(0.0, model.getDouble("mesh_position_x"), 1e-9)

            val firstVertex = firstVertex(modelFile)
            assertEquals(-15.0, firstVertex[0].toDouble(), 1e-6)
            assertEquals(-15.0, firstVertex[1].toDouble(), 1e-6)
            assertEquals(1.0, firstVertex[2].toDouble(), 1e-6)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun keepsStagedMeshCoordinatesForCenterOriginMachines() {
        val directory = Files.createTempDirectory("enderslicer-resolved-center").toFile()
        try {
            val modelFile = File(directory, "current.stl")
            writeTriangle(modelFile, 10f, 20f, 1f)
            val destination = File(directory, "resolved-settings.json")
            CuraResolvedSettingsWriter.write(
                destination = destination,
                modelFileName = modelFile.name,
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
            assertEquals(10.0, firstVertex(modelFile)[0].toDouble(), 1e-6)
            assertEquals(20.0, firstVertex(modelFile)[1].toDouble(), 1e-6)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun writeTriangle(file: File, x: Float, y: Float, z: Float) {
        val buffer = ByteBuffer.allocate(84 + 50).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(80)
        buffer.putInt(1)
        buffer.putFloat(0f).putFloat(0f).putFloat(1f)
        buffer.putFloat(x).putFloat(y).putFloat(z)
        buffer.putFloat(x + 1f).putFloat(y).putFloat(z)
        buffer.putFloat(x).putFloat(y + 1f).putFloat(z + 1f)
        buffer.putShort(0)
        file.writeBytes(buffer.array())
    }

    private fun firstVertex(file: File): FloatArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes, 84 + 12, 12).order(ByteOrder.LITTLE_ENDIAN)
        return floatArrayOf(buffer.float, buffer.float, buffer.float)
    }
}
