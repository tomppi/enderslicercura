package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.engine.GcodeCommand
import com.tomppi.enderslicer.engine.PrinterEnvelope
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurviSlicerPipelineTest {
    @Test
    fun fieldFlattenAndInverseRoundTripWhileBaseRemainsFlat() {
        val field = sampleField()

        assertEquals(0.1, field.flattenZ(10.0, 5.0, 0.1), 0.000001)
        val original = 6.5
        val flattened = field.flattenZ(8.0, 5.0, original)
        val restored = field.unflattenZ(8.0, 5.0, flattened)

        assertTrue(flattened < original)
        assertEquals(original, restored, 0.0001)
    }

    @Test
    fun requestFieldCurvesAndSubdividesGcodeBeforeValidation() {
        val directory = createTempDir(prefix = "curvislicer-test-")
        val gcode = File(directory, "output.gcode")
        gcode.writeText(
            """
            ;FLAVOR:Marlin
            ;Generated with Cura
            G90
            M82
            G92 E0
            G0 X0 Y5 Z0.2 F6000
            ;LAYER:0
            ;MESH:model.stl
            G1 X0 Y5 Z1 E0 F1200
            G1 X10 Y5 Z1 E1 F1200
            ;End of Gcode
            G0 Z20
            """.trimIndent(),
        )
        val settings = NonPlanarSettings(
            enabled = true,
            maximumSegmentLengthMm = 1.0,
            maximumZSpeedMmPerSecond = 5.0,
            compensateExtrusion = true,
        )
        val diagnostics = CurviSlicerPipeline.Diagnostics(
            gridColumns = 2,
            gridRows = 2,
            requestedStrength = 0.5,
            appliedStrength = 0.5,
            maximumRawReliefMm = 1.0,
            maximumAppliedDisplacementMm = 0.5,
            maximumFieldSlopeDegrees = 5.0,
            sourceTriangles = 12,
        )
        CurviSlicerFieldStorage.write(
            directory,
            CurviSlicerPipeline.Prepared(sampleField(), diagnostics, settings),
        )

        val result = CurviSlicerFieldStorage.curveStagedGcode(
            gcode,
            PrinterEnvelope(
                widthMm = 220.0,
                depthMm = 220.0,
                heightMm = 250.0,
                buildPlateShape = "rectangular",
                originAtCenter = false,
                gcodeFlavor = "marlin",
            ),
        )

        assertNotNull(result)
        val curved = gcode.readLines()
        assertTrue(curved.any { it.startsWith(";ENDERSLICER_NON_PLANAR:CurviSlicer-Android-v1") })
        assertTrue(requireNotNull(result).emittedMoves > result.sourceMoves)
        assertTrue(result.maximumZmm > 1.0)
        assertTrue(result.minimumZmm >= 0.0)
        assertTrue(result.maximumObservedZSpeedMmPerSecond <= settings.maximumZSpeedMmPerSecond + 0.0001)

        val finalExtrusion = curved.asSequence()
            .mapNotNull(GcodeCommand::parse)
            .filter { it.opcode == "G1" }
            .mapNotNull { it.value('E') }
            .maxOrNull()
        assertNotNull(finalExtrusion)
        assertTrue(requireNotNull(finalExtrusion) > 1.0)
        assertTrue(curved.count { it.startsWith("G1 X") } > 2)
    }

    private fun sampleField(): CurviSlicerField = CurviSlicerField(
        minX = 0.0,
        minY = 0.0,
        minZ = 0.0,
        maxX = 10.0,
        maxY = 10.0,
        maxZ = 10.0,
        columns = 2,
        rows = 2,
        relief = floatArrayOf(0f, 1f, 0f, 1f),
        strength = 0.5,
        flatBaseHeightMm = 0.2,
    )
}
