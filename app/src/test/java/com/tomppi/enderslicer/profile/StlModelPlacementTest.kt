package com.tomppi.enderslicer.profile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class StlModelPlacementTest {
    @Test
    fun binaryStlBelowZeroIsRaisedOntoBuildPlate() {
        val directory = Files.createTempDirectory("enderslicer-z-placement").toFile()
        try {
            val model = File(directory, "model.stl")
            writeBinaryTriangle(model, minimumZ = -1.0f, maximumZ = 2.0f)

            assertEquals(-1.0, StlModelPlacement.minimumZMm(model), 1e-7)
            assertEquals(1.0, StlModelPlacement.buildPlateOffsetMm(model), 1e-7)

            val destination = File(directory, "resolved-settings.json")
            CuraResolvedSettingsWriter.write(
                destination = destination,
                modelFileName = model.name,
                resolved = CuraSliceSettingsResolver.Result(
                    globalValues = emptyMap(),
                    extruderValues = emptyMap(),
                    expressionCount = 0,
                    passes = 0,
                ),
            )

            val root = JSONObject(destination.readText())
            assertEquals(
                1.0,
                root.getJSONObject("extruder.0").getDouble("mesh_position_z"),
                1e-7,
            )
            assertEquals(
                1.0,
                root.getJSONObject(model.name).getDouble("mesh_position_z"),
                1e-7,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun asciiStlAboveZeroIsLoweredOntoBuildPlate() {
        val directory = Files.createTempDirectory("enderslicer-ascii-z-placement").toFile()
        try {
            val model = File(directory, "model.stl")
            model.writeText(
                """
                solid test
                  facet normal 0 0 1
                    outer loop
                      vertex 0 0 2.5
                      vertex 1 0 2.5
                      vertex 0 1 4.0
                    endloop
                  endfacet
                endsolid test
                """.trimIndent(),
            )

            assertEquals(2.5, StlModelPlacement.minimumZMm(model), 1e-7)
            assertEquals(-2.5, StlModelPlacement.buildPlateOffsetMm(model), 1e-7)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun writeBinaryTriangle(file: File, minimumZ: Float, maximumZ: Float) {
        val buffer = ByteBuffer.allocate(84 + 50).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(80)
        buffer.putInt(1)
        buffer.putFloat(0f)
        buffer.putFloat(0f)
        buffer.putFloat(1f)
        buffer.putFloat(0f)
        buffer.putFloat(0f)
        buffer.putFloat(minimumZ)
        buffer.putFloat(1f)
        buffer.putFloat(0f)
        buffer.putFloat(minimumZ)
        buffer.putFloat(0f)
        buffer.putFloat(1f)
        buffer.putFloat(maximumZ)
        buffer.putShort(0)
        file.writeBytes(buffer.array())
    }
}
