package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.engine.GcodeCommand
import com.tomppi.enderslicer.engine.GcodeModalState
import com.tomppi.enderslicer.engine.PrinterEnvelope
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurviSlicerSafetyRegressionTest {
    @Test
    fun rejectsStartupArcsBeforeReplacingThePlanarSource() {
        val directory = Files.createTempDirectory("curvi-startup-arc").toFile()
        try {
            val gcode = File(directory, "output.gcode").apply {
                writeText(
                    """
                    ;FLAVOR:Marlin
                    G90
                    M82
                    G2 X10 Y10 I5 J0 E1 F1200
                    ;LAYER:0
                    G1 X20 Y20 Z1 E2
                    """.trimIndent(),
                )
            }
            val original = gcode.readBytes()
            writePreparedField(directory, simpleField(), NonPlanarSettings(enabled = true))

            val failure = runCatching {
                CurviSlicerFieldStorage.curveStagedGcode(gcode, printerEnvelope())
            }.exceptionOrNull()

            assertTrue(requireNotNull(failure).message.orEmpty().contains("G2/G3"))
            assertArrayEquals(original, gcode.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun machineEndLiftWipeAndParkRemainVerbatim() {
        val directory = Files.createTempDirectory("curvi-machine-end").toFile()
        try {
            val endLines = listOf(
                "G91",
                "G1 E-2 F2700",
                "G1 E-2 Z0.2 F2400",
                "G1 X5 Y5 F3000",
                "G1 Z10",
                "G90",
                "G1 X0 Y20",
            )
            val gcode = File(directory, "output.gcode").apply {
                writeText(
                    buildString {
                        appendLine(";FLAVOR:Marlin")
                        appendLine("G90")
                        appendLine("M82")
                        appendLine("G92 E0")
                        appendLine("G0 X0 Y5 Z0.2 F6000")
                        appendLine(";LAYER:0")
                        appendLine("G1 X10 Y5 Z6 E1 F1200")
                        appendLine(CurviSlicerRuntime.MACHINE_END_SENTINEL)
                        endLines.forEach(::appendLine)
                        appendLine(";End of Gcode")
                    },
                )
            }
            writePreparedField(directory, simpleField(), NonPlanarSettings(enabled = true))

            CurviSlicerFieldStorage.curveStagedGcode(gcode, printerEnvelope())

            val output = gcode.readLines()
            val sentinel = output.indexOf(CurviSlicerRuntime.MACHINE_END_SENTINEL)
            assertTrue(sentinel >= 0)
            assertEquals(endLines, output.subList(sentinel + 1, sentinel + 1 + endLines.size))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun inversePreservesClearanceAbovePositiveAndNegativeRelief() {
        val positive = CurviSlicerField(
            minX = 0.0,
            minY = 0.0,
            minZ = 0.0,
            maxX = 10.0,
            maxY = 10.0,
            maxZ = 10.0,
            columns = 2,
            rows = 2,
            relief = floatArrayOf(1f, 1f, 1f, 1f),
            strength = 0.5,
            flatBaseHeightMm = 0.2,
        )
        val negative = positive.copy(relief = floatArrayOf(-1f, -1f, -1f, -1f))

        listOf(positive, negative).forEach { field ->
            val original = 20.0
            val flattened = field.flattenZ(5.0, 5.0, original)
            assertEquals(original, field.unflattenZ(5.0, 5.0, flattened), 1e-8)
            assertEquals(10.0, original - field.unflattenZ(5.0, 5.0, field.flattenZ(5.0, 5.0, 10.0)), 1e-8)
        }
    }

    @Test
    fun rejectsFinalInverseSlopeAboveConfiguredLimit() {
        val directory = Files.createTempDirectory("curvi-slope-limit").toFile()
        try {
            val gcode = File(directory, "output.gcode").apply {
                writeText(
                    """
                    ;FLAVOR:Marlin
                    G90
                    M82
                    G0 X0 Y5 Z5 F1200
                    ;LAYER:0
                    G1 X10 Y5 Z5 E1 F1200
                    """.trimIndent(),
                )
            }
            val settings = NonPlanarSettings(
                enabled = true,
                maximumSlopeDegrees = 5.0,
                nozzleClearanceAngleDegrees = 15.0,
                maximumSegmentLengthMm = 0.25,
            )
            writePreparedField(directory, simpleField(), settings)

            val failure = runCatching {
                CurviSlicerFieldStorage.curveStagedGcode(gcode, printerEnvelope())
            }.exceptionOrNull()

            assertTrue(requireNotNull(failure).message.orEmpty().contains("slope"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun relativeSubdivisionClosesOnTheOriginalXAndExtrusionTotals() {
        val directory = Files.createTempDirectory("curvi-relative-closure").toFile()
        try {
            val gcode = File(directory, "output.gcode").apply {
                writeText(
                    """
                    ;FLAVOR:Marlin
                    G91
                    M83
                    G0 X0 Y0 Z0.2 F1200
                    ;LAYER:0
                    G1 X10 Y0 Z0 E1 F1200
                    """.trimIndent(),
                )
            }
            val flatField = simpleField().copy(relief = FloatArray(4))
            writePreparedField(
                directory,
                flatField,
                NonPlanarSettings(enabled = true, maximumSegmentLengthMm = 0.7),
            )

            CurviSlicerFieldStorage.curveStagedGcode(gcode, printerEnvelope())

            val modal = GcodeModalState()
            var x = 0.0
            var e = 0.0
            gcode.readLines().forEach { line ->
                val command = GcodeCommand.parse(line) ?: return@forEach
                if (modal.apply(command)) return@forEach
                if (command.opcode == "G0" || command.opcode == "G1") {
                    x = modal.position(x, command.value('X'))
                    e = modal.extrusion(e, command.value('E'))
                }
            }
            assertEquals(10.0, x, 1e-6)
            assertEquals(1.0, e, 1e-6)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun writePreparedField(
        directory: File,
        field: CurviSlicerField,
        settings: NonPlanarSettings,
    ) {
        CurviSlicerFieldStorage.write(
            directory,
            CurviSlicerPipeline.Prepared(
                field = field,
                diagnostics = CurviSlicerPipeline.Diagnostics(
                    gridColumns = field.columns,
                    gridRows = field.rows,
                    requestedStrength = field.strength,
                    appliedStrength = field.strength,
                    maximumRawReliefMm = 1.0,
                    maximumAppliedDisplacementMm = field.maximumDisplacementMm,
                    maximumFieldSlopeDegrees = 10.0,
                    sourceTriangles = 2,
                ),
                settings = settings,
            ),
        )
    }

    private fun simpleField(): CurviSlicerField = CurviSlicerField(
        minX = 0.0,
        minY = 0.0,
        minZ = 0.0,
        maxX = 10.0,
        maxY = 10.0,
        maxZ = 10.0,
        columns = 2,
        rows = 2,
        relief = floatArrayOf(0f, 1f, 0f, 1f),
        strength = 1.0,
        flatBaseHeightMm = 0.2,
    )

    private fun printerEnvelope(): PrinterEnvelope = PrinterEnvelope(
        widthMm = 220.0,
        depthMm = 220.0,
        heightMm = 250.0,
        buildPlateShape = "rectangular",
        originAtCenter = false,
        gcodeFlavor = "marlin",
    )
}
