package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.StlMeshWriter
import com.tomppi.enderslicer.viewer.StlSliceTransform
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
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
    fun keepsStagedMeshBytesAndCopiesMeshSupportIntoExtruderScope() {
        val directory = Files.createTempDirectory("enderslicer-resolved").toFile()
        try {
            val modelFile = File(directory, "current.stl")
            writeTriangle(modelFile, 100.123456f, 100.654321f, 1f)
            val originalBytes = modelFile.readBytes()
            val destination = File(directory, "resolved-settings.json")
            val resolved = resolvedSettings(centerIsZero = false)

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
            assertEquals("[[1.0,0.0,0.0],[0.0,1.0,0.0],[0.0,0.0,1.0]]", extruder.getString("mesh_rotation_matrix"))
            assertEquals(-115.0, extruder.getDouble("mesh_position_x"), 1e-9)
            assertEquals(-115.0, extruder.getDouble("mesh_position_y"), 1e-9)

            val model = root.getJSONObject("current.stl")
            assertEquals(0, model.getInt("extruder_nr"))
            assertTrue(model.getBoolean("support_interface_enable"))
            assertTrue(model.getBoolean("support_roof_enable"))
            assertEquals(-115.0, model.getDouble("mesh_position_x"), 1e-9)
            assertEquals(-115.0, model.getDouble("mesh_position_y"), 1e-9)

            assertArrayEquals(
                "Writing resolved settings must not rewrite or re-round STL coordinates",
                originalBytes,
                modelFile.readBytes(),
            )
            val firstVertex = firstVertex(modelFile)
            assertEquals(100.123456, firstVertex[0].toDouble(), 1e-5)
            assertEquals(100.654321, firstVertex[1].toDouble(), 1e-5)
            assertEquals(1.0, firstVertex[2].toDouble(), 1e-6)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun replacesTemporaryDisplayedMeshWithOriginalAndWritesCompleteAffine() {
        val cacheRoot = Files.createTempDirectory("enderslicer-direct-affine").toFile()
        try {
            val placementDirectory = File(cacheRoot, "model-placement").apply { mkdirs() }
            val displayedFile = File(placementDirectory, "current-transformed.stl")
            val sourceVertices = interleavedTriangle(10f, 20f, 30f)
            val displayedVertices = interleavedTriangle(100f, 110f, 0f)
            val transform = StlSliceTransform(
                linear = listOf(
                    1.0, 0.0, 0.0,
                    0.0, 0.0, -1.0,
                    0.0, 1.0, 0.0,
                ),
                translationXmm = 115.25,
                translationYmm = 114.75,
                translationZmm = 22.462965929567872,
            )
            StlMeshWriter.writeBinary(
                StlMesh(
                    displayName = "test.stl",
                    interleavedVertices = displayedVertices,
                    triangleCount = 1,
                    bounds = MeshBounds(100f, 110f, 0f, 101f, 111f, 1f),
                    slicingSourceInterleavedVertices = sourceVertices,
                    slicingTransform = transform,
                ),
                displayedFile,
            )
            val stagedSource = requireNotNull(StlMeshWriter.resolvedSliceSource(displayedFile))

            val engineDirectory = File(cacheRoot, "curaengine").apply { mkdirs() }
            val modelFile = File(engineDirectory, "current.stl")
            displayedFile.copyTo(modelFile)
            val destination = File(engineDirectory, "resolved-settings.json")

            CuraResolvedSettingsWriter.write(
                destination = destination,
                modelFileName = modelFile.name,
                resolved = resolvedSettings(centerIsZero = false),
            )

            assertArrayEquals(stagedSource.modelFile.readBytes(), modelFile.readBytes())
            val firstVertex = firstVertex(modelFile)
            assertEquals(10.0, firstVertex[0].toDouble(), 1e-6)
            assertEquals(20.0, firstVertex[1].toDouble(), 1e-6)
            assertEquals(30.0, firstVertex[2].toDouble(), 1e-6)

            val model = JSONObject(destination.readText()).getJSONObject("current.stl")
            assertEquals("[[1.0,0.0,0.0],[0.0,0.0,-1.0],[0.0,1.0,0.0]]", model.getString("mesh_rotation_matrix"))
            assertEquals(0.25, model.getDouble("mesh_position_x"), 1e-9)
            assertEquals(-0.25, model.getDouble("mesh_position_y"), 1e-9)
            assertEquals(22.462965929567872, model.getDouble("mesh_position_z"), 1e-12)
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun keepsZeroOffsetsAndMeshBytesForCenterOriginMachines() {
        val directory = Files.createTempDirectory("enderslicer-resolved-center").toFile()
        try {
            val modelFile = File(directory, "current.stl")
            writeTriangle(modelFile, 10f, 20f, 1f)
            val originalBytes = modelFile.readBytes()
            val destination = File(directory, "resolved-settings.json")
            CuraResolvedSettingsWriter.write(
                destination = destination,
                modelFileName = modelFile.name,
                resolved = resolvedSettings(centerIsZero = true),
            )
            val root = JSONObject(destination.readText())
            assertEquals(0.0, root.getJSONObject("extruder.0").getDouble("mesh_position_x"), 1e-9)
            assertEquals(0.0, root.getJSONObject("extruder.0").getDouble("mesh_position_y"), 1e-9)
            assertArrayEquals(originalBytes, modelFile.readBytes())
            assertEquals(10.0, firstVertex(modelFile)[0].toDouble(), 1e-6)
            assertEquals(20.0, firstVertex(modelFile)[1].toDouble(), 1e-6)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun resolvedSettings(centerIsZero: Boolean): CuraSliceSettingsResolver.Result = CuraSliceSettingsResolver.Result(
        globalValues = mapOf(
            "machine_width" to "230",
            "machine_depth" to "230",
            "machine_center_is_zero" to centerIsZero.toString(),
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

    private fun interleavedTriangle(x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
        x, y, z, 0f, 0f, 1f,
        x + 1f, y, z, 0f, 0f, 1f,
        x, y + 1f, z + 1f, 0f, 0f, 1f,
    )

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
