package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.engine.PrinterEnvelope
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CurviSlicerArcSafetyTest {
    @Test
    fun rejectsFittedArcsInsidePrintableLayers() {
        val directory = createTempDir(prefix = "curvislicer-arc-test-")
        val gcode = File(directory, "output.gcode").apply {
            writeText(
                """
                ;FLAVOR:Marlin
                G90
                M82
                G0 X0 Y0 Z0.2
                ;LAYER:0
                G2 X10 Y10 I5 J0 E1 F1200
                """.trimIndent(),
            )
        }
        val field = CurviSlicerField(
            minX = 0.0,
            minY = 0.0,
            minZ = 0.0,
            maxX = 20.0,
            maxY = 20.0,
            maxZ = 20.0,
            columns = 2,
            rows = 2,
            relief = floatArrayOf(0f, 1f, 0f, 1f),
            strength = 0.5,
            flatBaseHeightMm = 0.2,
        )
        val prepared = CurviSlicerPipeline.Prepared(
            field = field,
            diagnostics = CurviSlicerPipeline.Diagnostics(2, 2, 0.5, 0.5, 1.0, 0.5, 5.0, 12),
            settings = NonPlanarSettings(enabled = true),
        )
        CurviSlicerFieldStorage.write(directory, prepared)

        val failure = runCatching {
            CurviSlicerFieldStorage.curveStagedGcode(
                gcode,
                PrinterEnvelope(220.0, 220.0, 250.0, "rectangular", false, "marlin"),
            )
        }.exceptionOrNull()

        assertTrue(requireNotNull(failure).message.orEmpty().contains("disable arc fitting"))
    }
}
