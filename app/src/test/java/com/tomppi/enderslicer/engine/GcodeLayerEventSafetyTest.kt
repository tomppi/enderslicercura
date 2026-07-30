package com.tomppi.enderslicer.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GcodeLayerEventSafetyTest {
    @Test
    fun manualSpeedAndFlowEventsRestoreNeutralFactors() {
        val base = temporaryGcode()
        val output = File(base.parentFile, "output.gcode")
        GcodeLayerEventProcessor.materialize(
            base,
            output,
            listOf(
                LayerEvent("speed", 0, 0.2f, LayerEventType.SPEED_FACTOR, value = 80.0),
                LayerEvent("flow", 0, 0.2f, LayerEventType.FLOW_FACTOR, value = 95.0),
            ),
        )
        val text = output.readText()
        assertTrue(text.contains("M220 S80"))
        assertTrue(text.contains("M221 S95"))
        assertTrue(text.contains("M220 S100 ; enderslicercura restore speed factor"))
        assertTrue(text.contains("M221 S100 ; enderslicercura restore flow factor"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun blocksTabSeparatedUnsafeCustomCommand() {
        GcodeLayerEventProcessor.commands(
            LayerEvent(
                id = "unsafe-tab",
                layerNumber = 0,
                zMm = 0.2f,
                type = LayerEventType.CUSTOM_GCODE,
                text = "g28\tx0",
            ),
        )
    }

    private fun temporaryGcode(): File {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-event-safety").toFile()
        return File(directory, "base.gcode").apply {
            writeText(
                """
                ;FLAVOR:Marlin
                M82
                ;LAYER:0
                G1 X1 Y1 E1
                ;End of Gcode
                """.trimIndent(),
            )
        }
    }
}
