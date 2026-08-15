package com.tomppi.enderslicer.conical

import com.tomppi.enderslicer.engine.GcodeCommand
import com.tomppi.enderslicer.engine.PrinterEnvelope
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConicalGcodeTransformerTest {
    @Test
    fun warpedExtrusionMoveRestoresOriginalCoordinates() {
        val directory = createTempDirectory("conical-test-").toFile()
        val gcode = File(directory, "output.gcode")
        val settings = ConicalSettings(
            enabled = true,
            coneAngleDegrees = 16.0,
            coneType = ConeType.OUTWARD,
            firstLayerHeightMm = 0.2,
        )
        // Warp the intended endpoints with the forward transform, then emit the
        // "sliced" G-code that CuraEngine would produce from the warped STL.
        val start = ConicalTransform.forward(0.0, 0.0, 0.0, 0.0, 0.0, settings)
        val end = ConicalTransform.forward(10.0, 0.0, 0.0, 0.0, 0.0, settings)
        gcode.writeText(
            """
            ;FLAVOR:Marlin
            ;Generated with Cura
            G90
            M82
            G92 E0
            ;LAYER:0
            G1 X${start[0]} Y${start[1]} Z${start[2]} E0 F1200
            G1 X${end[0]} Y${end[1]} Z${end[2]} E1 F1200
            ;End of Gcode
            """.trimIndent(),
        )
        writePrepared(directory, settings)

        val result = ConicalStorage.backtransformStagedGcode(gcode, printerEnvelope())

        assertNotNull(result)
        val diagnostics = requireNotNull(result)
        assertTrue(diagnostics.emittedMoves > diagnostics.sourceMoves)
        assertTrue(diagnostics.minimumZmm >= 0.0)

        val moves = gcode.readLines().mapNotNull(GcodeCommand::parse)
        val finalSpatial = moves
            .filter { it.opcode == "G1" && it.has('X') && it.has('E') }
            .last()
        assertEquals(10.0, finalSpatial.value('X')!!, 0.001)
        assertEquals(0.2, finalSpatial.value('Z')!!, 0.001)
        assertTrue(gcode.readLines().any { it.startsWith(";ENDERSLICER_CONICAL:EasyConical-Android-v1") })
    }

    @Test
    fun absoluteExtrusionIsCompensatedForTheTruePathLength() {
        val directory = createTempDirectory("conical-e-test-").toFile()
        val gcode = File(directory, "output.gcode")
        gcode.writeText(
            """
            ;FLAVOR:Marlin
            G90
            M82
            G92 E0
            ;LAYER:0
            G1 X0 Y5 Z0 E0 F1200
            G1 X10 Y5 Z0 E1 F1200
            ;End of Gcode
            """.trimIndent(),
        )
        writePrepared(directory, ConicalSettings(enabled = true, coneAngleDegrees = 16.0, firstLayerHeightMm = 0.0))

        ConicalStorage.backtransformStagedGcode(gcode, printerEnvelope())

        val finalE = gcode.readLines()
            .mapNotNull(GcodeCommand::parse)
            .filter { it.opcode == "G1" && it.has('X') && it.has('E') }
            .mapNotNull { it.value('E') }
            .last()
        // Horizontal distance shrinks by cos(16 deg); the compensated absolute E
        // is below the planar value of 1.0 but still positive.
        assertTrue("Compensated extrusion must shrink", finalE < 1.0)
        assertTrue("Compensated extrusion must stay positive", finalE > 0.5)
    }

    @Test
    fun relativeExtrusionIsSupported() {
        val directory = createTempDirectory("conical-m83-test-").toFile()
        val gcode = File(directory, "output.gcode")
        gcode.writeText(
            """
            ;FLAVOR:Marlin
            G90
            M83
            G92 E0
            ;LAYER:0
            G1 X0 Y5 Z0 E0 F1200
            G1 X10 Y5 Z0 E1 F1200
            ;End of Gcode
            """.trimIndent(),
        )
        writePrepared(directory, ConicalSettings(enabled = true, coneAngleDegrees = 16.0, firstLayerHeightMm = 0.0))

        ConicalStorage.backtransformStagedGcode(gcode, printerEnvelope())

        val relativeEs = gcode.readLines()
            .mapNotNull(GcodeCommand::parse)
            .filter { it.opcode == "G1" && it.has('X') && it.has('E') }
            .mapNotNull { it.value('E') }
        val sum = relativeEs.sum()
        assertTrue("Relative segments must sum to a positive compensated length", sum > 0.5)
        assertTrue("Relative segments must sum below the planar value", sum < 1.0)
    }

    @Test
    fun arcsAreRejected() {
        val directory = createTempDirectory("conical-arc-test-").toFile()
        val gcode = File(directory, "output.gcode")
        val original = """
            ;FLAVOR:Marlin
            G90
            M82
            G2 X10 Y10 I5 J0
            """.trimIndent()
        gcode.writeText(original)
        writePrepared(directory, ConicalSettings(enabled = true))

        val error = runCatching {
            ConicalStorage.backtransformStagedGcode(gcode, printerEnvelope())
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(requireNotNull(error).message.orEmpty().contains("G2/G3"))
    }

    @Test
    fun machineEndGcodePassesThroughVerbatim() {
        val directory = createTempDirectory("conical-end-test-").toFile()
        val gcode = File(directory, "output.gcode")
        gcode.writeText(
            """
            ;FLAVOR:Marlin
            G90
            M82
            G92 E0
            ;LAYER:0
            G1 X0 Y0 Z0 E0 F1200
            G1 X5 Y0 Z0 E1 F1200
            ;End of Gcode
            ${ConicalRuntime.MACHINE_END_SENTINEL}
            G0 Z20
            M104 S0
            M140 S0
            """.trimIndent(),
        )
        writePrepared(directory, ConicalSettings(enabled = true, firstLayerHeightMm = 0.0))

        ConicalStorage.backtransformStagedGcode(gcode, printerEnvelope())

        val lines = gcode.readLines()
        val sentinelIndex = lines.indexOf(ConicalRuntime.MACHINE_END_SENTINEL)
        assertTrue(sentinelIndex >= 0)
        val endLines = lines.subList(sentinelIndex + 1, lines.size)
        assertTrue("Machine end travel must not be cone-translated", "G0 Z20" in endLines)
        assertTrue("Machine end temperature commands must pass through", "M104 S0" in endLines)
        assertTrue("Machine end temperature commands must pass through", "M140 S0" in endLines)
    }

    @Test
    fun startGcodePrimeLinesAreNotBackTransformed() {
        val directory = createTempDirectory("conical-prime-test-").toFile()
        val gcode = File(directory, "output.gcode")
        gcode.writeText(
            """
            ;FLAVOR:Marlin
            ;Generated with Cura
            G90
            M82
            G92 E0
            G1 X0.1 Y200.0 Z0.3 F1500.0 E15 ; Draw the first line
            G1 X0.4 Y20 Z0.3 F1500.0 E30 ; Draw the second line
            G92 E0
            ;LAYER:0
            G0 F6000 X5 Y5 Z0.28
            G1 F1200 X5 Y5 E0.1
            G1 X10 Y5 E0.2
            ;End of Gcode
            """.trimIndent(),
        )
        writePrepared(directory, ConicalSettings(enabled = true, coneAngleDegrees = 16.0, firstLayerHeightMm = 0.2))

        ConicalStorage.backtransformStagedGcode(gcode, printerEnvelope())

        val lines = gcode.readLines()
        assertTrue(
            "Prime line must pass through verbatim",
            "G1 X0.1 Y200.0 Z0.3 F1500.0 E15 ; Draw the first line" in lines,
        )
        val extrudedZ = lines.mapNotNull(GcodeCommand::parse)
            .filter { it.opcode == "G1" && it.has('E') && it.has('Z') }
            .mapNotNull { it.value('Z') }
        val minimum = extrudedZ.minOrNull()
        val maximum = extrudedZ.maxOrNull()
        assertNotNull(minimum)
        assertNotNull(maximum)
        assertTrue("Print must touch the bed (min extruded Z ${requireNotNull(minimum)})", requireNotNull(minimum) >= 0.19)
        assertTrue("Print must not float above the bed (max extruded Z ${requireNotNull(maximum)})", requireNotNull(maximum) < 5.0)
    }

    private fun writePrepared(directory: File, settings: ConicalSettings) {
        ConicalStorage.write(
            directory,
            ConicalPipeline.Prepared(
                centerX = 0.0,
                centerY = 0.0,
                settings = settings.copy(enabled = true).validated(),
                diagnostics = ConicalPipeline.Diagnostics(
                    sourceTriangles = 1,
                    refinedTriangles = 1,
                    refinementIterations = settings.refinementIterations,
                    coneAngleDegrees = settings.coneAngleDegrees,
                ),
            ),
        )
    }

    private fun printerEnvelope(): PrinterEnvelope = PrinterEnvelope(
        widthMm = 220.0,
        depthMm = 220.0,
        heightMm = 250.0,
        buildPlateShape = "rectangular",
        originAtCenter = false,
        gcodeFlavor = "marlin",
    )
}
