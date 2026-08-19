package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.conical.ConicalRuntime
import com.tomppi.enderslicer.conical.ConicalSettings
import com.tomppi.enderslicer.engine.AdaptiveWallModifier
import com.tomppi.enderslicer.nonplanar.CurviSlicerRuntime
import com.tomppi.enderslicer.nonplanar.NonPlanarSettings
import com.tomppi.enderslicer.supportpaint.SupportPaintModifier
import com.tomppi.enderslicer.viewer.StlParser
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Painted support enforcer/blocker prisms must survive the non-planar
 * pipelines: both writers warp the prisms with the same transform as the model
 * instead of rejecting the slice, while adaptive-wall modifiers stay rejected.
 */
class CuraResolvedSettingsWriterSupportPaintTest {
    @Test
    fun curviSlicerWarpsPaintedEnforcerBeforeSerializingItsMeshSection() {
        val directory = Files.createTempDirectory("resolved-curvi-paint").toFile()
        try {
            CurviSlicerRuntime.activate(NonPlanarSettings(enabled = true))
            val displayed = File(directory, "displayed.stl")
            val model = File(directory, "model.stl")
            val enforcer = File(directory, "support-enforcer.stl")
            writePyramid(displayed)
            writeFloatingPatch(enforcer, 4f, 4f, 0.8f)
            val sourceMaxZ = StlParser.parse(enforcer, enforcer.name).bounds.maxZ

            val marker = CuraResolvedSettingsWriter.copyResolvedSourceSnapshot(
                stagedDisplayedFile = displayed,
                destination = model,
            )

            CuraResolvedSettingsWriter.write(
                destination = File(directory, "resolved.json"),
                modelFileName = model.name,
                resolved = resolvedResult(),
                modelTransform = marker,
                supportPaintModifiers = listOf(SupportPaintModifier(isBlocker = false, file = enforcer)),
            )

            val root = JSONObject(File(directory, "resolved.json").readText())
            val section = root.getJSONObject(enforcer.name)
            assertEquals(true, section.getBoolean("support_mesh"))
            assertEquals(false, section.getBoolean("anti_overhang_mesh"))
            assertEquals(false, section.getBoolean("infill_mesh"))

            val warped = StlParser.parse(enforcer, enforcer.name)
            assertTrue(
                "CurviSlicer must flatten the painted prism with the relief field",
                warped.bounds.maxZ < sourceMaxZ,
            )
        } finally {
            CurviSlicerRuntime.activate(NonPlanarSettings())
            directory.deleteRecursively()
        }
    }

    @Test
    fun conicalSlicingWarpsPaintedBlockerBeforeSerializingItsMeshSection() {
        val directory = Files.createTempDirectory("resolved-conical-paint").toFile()
        try {
            ConicalRuntime.activate(ConicalSettings(enabled = true))
            val displayed = File(directory, "displayed.stl")
            val model = File(directory, "model.stl")
            val blocker = File(directory, "support-blocker.stl")
            writeTriangle(displayed, 100f, 100f, 0.2f)
            writeFloatingPatch(blocker, 100f, 100f, 1.0f)
            val source = StlParser.parse(blocker, blocker.name)

            val marker = CuraResolvedSettingsWriter.copyResolvedSourceSnapshot(
                stagedDisplayedFile = displayed,
                destination = model,
            )

            CuraResolvedSettingsWriter.write(
                destination = File(directory, "resolved.json"),
                modelFileName = model.name,
                resolved = resolvedResult(),
                modelTransform = marker,
                supportPaintModifiers = listOf(SupportPaintModifier(isBlocker = true, file = blocker)),
            )

            val root = JSONObject(File(directory, "resolved.json").readText())
            val section = root.getJSONObject(blocker.name)
            assertEquals(false, section.getBoolean("support_mesh"))
            assertEquals(true, section.getBoolean("anti_overhang_mesh"))

            val warped = StlParser.parse(blocker, blocker.name)
            assertTrue(
                "Conical slicing must lift the painted prism with the cone warp",
                warped.bounds.maxZ > source.bounds.maxZ,
            )
        } finally {
            ConicalRuntime.activate(ConicalSettings())
            directory.deleteRecursively()
        }
    }

    @Test
    fun adaptiveWallModifiersRemainRejectedInTheResolvedFlow() {
        val directory = Files.createTempDirectory("resolved-adaptive-wall-rejected").toFile()
        try {
            val displayed = File(directory, "displayed.stl")
            writeTriangle(displayed, 100f, 100f, 0.2f)
            val wallModifier = File(directory, "adaptive-wall.stl")
            writeTriangle(wallModifier, 101f, 101f, 0.4f)
            val adaptive = AdaptiveWallModifier(wallLineCount = 4, wallFlowPercent = 100.0, file = wallModifier)

            CurviSlicerRuntime.activate(NonPlanarSettings(enabled = true))
            val curviModel = File(directory, "curvi-model.stl")
            val curviMarker = CuraResolvedSettingsWriter.copyResolvedSourceSnapshot(displayed, curviModel)
            val curviError = runCatching {
                CuraResolvedSettingsWriter.write(
                    destination = File(directory, "curvi-resolved.json"),
                    modelFileName = curviModel.name,
                    resolved = resolvedResult(),
                    modelTransform = curviMarker,
                    adaptiveWallModifiers = listOf(adaptive),
                )
            }.exceptionOrNull()
            assertTrue(curviError is IllegalArgumentException)
            assertTrue(curviError?.message.orEmpty().contains("Adaptive walls"))

            CurviSlicerRuntime.activate(NonPlanarSettings())
            ConicalRuntime.activate(ConicalSettings(enabled = true))
            val conicalModel = File(directory, "conical-model.stl")
            val conicalMarker = CuraResolvedSettingsWriter.copyResolvedSourceSnapshot(displayed, conicalModel)
            val conicalError = runCatching {
                CuraResolvedSettingsWriter.write(
                    destination = File(directory, "conical-resolved.json"),
                    modelFileName = conicalModel.name,
                    resolved = resolvedResult(),
                    modelTransform = conicalMarker,
                    adaptiveWallModifiers = listOf(adaptive),
                )
            }.exceptionOrNull()
            assertTrue(conicalError is IllegalArgumentException)
            assertTrue(conicalError?.message.orEmpty().contains("Adaptive walls"))
        } finally {
            CurviSlicerRuntime.activate(NonPlanarSettings())
            ConicalRuntime.activate(ConicalSettings())
            directory.deleteRecursively()
        }
    }

    @Test
    fun duplicateModifierMeshNamesAreRejected() {
        val directory = Files.createTempDirectory("resolved-duplicate-mesh").toFile()
        try {
            val model = File(directory, "model.stl")
            writeTriangle(model, 100f, 100f, 0.2f)
            val clash = File(directory, "clash.stl")
            writeTriangle(clash, 101f, 101f, 0.4f)

            val error = runCatching {
                CuraResolvedSettingsWriter.write(
                    destination = File(directory, "resolved.json"),
                    modelFileName = model.name,
                    resolved = resolvedResult(),
                    supportPaintModifiers = listOf(
                        SupportPaintModifier(isBlocker = false, file = clash),
                        SupportPaintModifier(isBlocker = true, file = clash),
                    ),
                )
            }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("duplicate mesh names"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun resolvedResult(): CuraSliceSettingsResolver.Result = CuraSliceSettingsResolver.Result(
        globalValues = mapOf(
            "machine_width" to "230",
            "machine_depth" to "230",
            "machine_height" to "250",
            "machine_shape" to "rectangular",
            "machine_center_is_zero" to "false",
            "machine_gcode_flavor" to "Marlin",
            "layer_height" to "0.2",
            "machine_nozzle_size" to "0.4",
        ),
        extruderValues = emptyMap(),
        modelValues = emptyMap(),
        expressionCount = 0,
        passes = 1,
    )

    private fun writeTriangle(file: File, x: Float, y: Float, z: Float) {
        val bytes = ByteBuffer.allocate(84 + 50).order(ByteOrder.LITTLE_ENDIAN)
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

    /**
     * A 10 x 10 mm square pyramid whose apex sits at z = 1.2 over the centre:
     * tall enough for CurviSlicer's flat-base requirement and gentle enough
     * (about 13 degrees) to stay inside the default slope limit.
     */
    private fun writePyramid(file: File) {
        val apexX = 5f
        val apexY = 5f
        val apexZ = 1.2f
        val triangles = listOf(
            floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 10f, 10f, 0f),
            floatArrayOf(0f, 0f, 0f, 10f, 10f, 0f, 0f, 10f, 0f),
            floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, apexX, apexY, apexZ),
            floatArrayOf(10f, 0f, 0f, 10f, 10f, 0f, apexX, apexY, apexZ),
            floatArrayOf(10f, 10f, 0f, 0f, 10f, 0f, apexX, apexY, apexZ),
            floatArrayOf(0f, 10f, 0f, 0f, 0f, 0f, apexX, apexY, apexZ),
        )
        val bytes = ByteBuffer.allocate(84 + triangles.size * 50).order(ByteOrder.LITTLE_ENDIAN)
        bytes.position(80)
        bytes.putInt(triangles.size)
        for (triangle in triangles) {
            bytes.putFloat(0f).putFloat(0f).putFloat(1f)
            repeat(3) { vertex ->
                bytes.putFloat(triangle[vertex * 3])
                bytes.putFloat(triangle[vertex * 3 + 1])
                bytes.putFloat(triangle[vertex * 3 + 2])
            }
            bytes.putShort(0)
        }
        file.writeBytes(bytes.array())
    }

    /** Two triangles forming a small square patch at z within (x..x+2, y..y+2). */
    private fun writeFloatingPatch(file: File, x: Float, y: Float, z: Float) {
        val triangles = listOf(
            floatArrayOf(x, y, z, x + 2f, y, z, x, y + 2f, z),
            floatArrayOf(x + 2f, y, z, x + 2f, y + 2f, z, x, y + 2f, z),
        )
        val bytes = ByteBuffer.allocate(84 + triangles.size * 50).order(ByteOrder.LITTLE_ENDIAN)
        bytes.position(80)
        bytes.putInt(triangles.size)
        for (triangle in triangles) {
            bytes.putFloat(0f).putFloat(0f).putFloat(1f)
            repeat(3) { vertex ->
                bytes.putFloat(triangle[vertex * 3])
                bytes.putFloat(triangle[vertex * 3 + 1])
                bytes.putFloat(triangle[vertex * 3 + 2])
            }
            bytes.putShort(0)
        }
        file.writeBytes(bytes.array())
    }
}
