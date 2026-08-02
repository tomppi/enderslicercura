package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.smartinfill.SmartInfillModifier
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CuraResolvedSettingsWriterSmartInfillTest {
    @Test
    fun writesNestedFilaSimRegionsWithTheirOwnResolvedLineSpacing() {
        val directory = Files.createTempDirectory("resolved-smart-infill").toFile()
        try {
            val model = File(directory, "model.stl")
            val low = File(directory, "modifier-35pct.stl")
            val high = File(directory, "modifier-70pct.stl")
            writeTriangle(model, 100f, 100f, 0.2f)
            writeTriangle(low, 101f, 101f, 0.4f)
            writeTriangle(high, 102f, 102f, 0.6f)
            val destination = File(directory, "resolved.json")

            CuraResolvedSettingsWriter.write(
                destination = destination,
                modelFileName = model.name,
                resolved = CuraSliceSettingsResolver.Result(
                    globalValues = mapOf(
                        "machine_width" to "230",
                        "machine_depth" to "230",
                        "machine_height" to "250",
                        "machine_shape" to "rectangular",
                        "machine_center_is_zero" to "false",
                        "machine_gcode_flavor" to "Marlin",
                    ),
                    extruderValues = mapOf("infill_sparse_density" to "12.5"),
                    modelValues = mapOf(
                        "infill_mesh" to "false",
                        "support_mesh" to "false",
                        "infill_line_distance" to "10.8",
                    ),
                    expressionCount = 0,
                    passes = 1,
                    smartInfillModelValues = mapOf(
                        35 to mapOf(
                            "infill_sparse_density" to "35",
                            "infill_line_distance" to "3.857142857",
                        ),
                        70 to mapOf(
                            "infill_sparse_density" to "70",
                            "infill_line_distance" to "1.928571429",
                        ),
                    ),
                ),
                smartInfillModifiers = listOf(
                    SmartInfillModifier(70, high),
                    SmartInfillModifier(35, low),
                ),
            )

            val root = JSONObject(destination.readText())
            val modelValues = root.getJSONObject(model.name)
            assertFalse(modelValues.getBoolean("infill_mesh"))
            assertEquals(10.8, modelValues.getDouble("infill_line_distance"), 0.0)

            val lowValues = root.getJSONObject(low.name)
            assertTrue(lowValues.getBoolean("infill_mesh"))
            assertEquals(35, lowValues.getInt("infill_sparse_density"))
            assertEquals(3.857142857, lowValues.getDouble("infill_line_distance"), 0.0)
            assertEquals(1, lowValues.getInt("infill_mesh_order"))
            assertEquals("[[1.0,0.0,0.0],[0.0,1.0,0.0],[0.0,0.0,1.0]]", lowValues.getString("mesh_rotation_matrix"))

            val highValues = root.getJSONObject(high.name)
            assertTrue(highValues.getBoolean("infill_mesh"))
            assertEquals(70, highValues.getInt("infill_sparse_density"))
            assertEquals(1.928571429, highValues.getDouble("infill_line_distance"), 0.0)
            assertEquals(2, highValues.getInt("infill_mesh_order"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsModifierWithoutDensityResolvedValues() {
        val directory = Files.createTempDirectory("resolved-smart-infill-missing").toFile()
        try {
            val model = File(directory, "model.stl")
            val modifier = File(directory, "modifier-50pct.stl")
            writeTriangle(model, 100f, 100f, 0.2f)
            writeTriangle(modifier, 101f, 101f, 0.4f)

            val error = runCatching {
                CuraResolvedSettingsWriter.write(
                    destination = File(directory, "resolved.json"),
                    modelFileName = model.name,
                    resolved = CuraSliceSettingsResolver.Result(
                        globalValues = mapOf(
                            "machine_width" to "230",
                            "machine_depth" to "230",
                            "machine_height" to "250",
                            "machine_shape" to "rectangular",
                            "machine_center_is_zero" to "false",
                            "machine_gcode_flavor" to "Marlin",
                        ),
                        extruderValues = emptyMap(),
                        modelValues = emptyMap(),
                        expressionCount = 0,
                        passes = 1,
                    ),
                    smartInfillModifiers = listOf(SmartInfillModifier(50, modifier)),
                )
            }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("density-dependent"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun writeTriangle(file: File, x: Float, y: Float, z: Float) {
        val bytes = ByteBuffer.allocate(84 + 50)
            .order(ByteOrder.LITTLE_ENDIAN)
        bytes.position(80)
        bytes.putInt(1)
        bytes.putFloat(0f)
        bytes.putFloat(0f)
        bytes.putFloat(1f)
        bytes.putFloat(x)
        bytes.putFloat(y)
        bytes.putFloat(z)
        bytes.putFloat(x + 1f)
        bytes.putFloat(y)
        bytes.putFloat(z)
        bytes.putFloat(x)
        bytes.putFloat(y + 1f)
        bytes.putFloat(z)
        bytes.putShort(0)
        file.writeBytes(bytes.array())
    }
}
