package com.tomppi.enderslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GcodeLayerEventProcessorTest {
    @Test
    fun resolvesCalibrationHeightAndInsertsCommandsAfterLayerMarker() {
        val base = kotlin.io.path.createTempFile("enderslicer-events-base", ".gcode").toFile().apply {
            writeText(
                """
                ;FLAVOR:Marlin
                G90
                M82
                ;LAYER:0
                G1 Z0.2
                G1 X10 E1
                ;LAYER:1
                G1 Z0.4
                G1 X20 E2
                """.trimIndent(),
            )
        }
        val preview = GcodeLayerPreviewParser.parse(base)
        val event = GcodeLayerEventProcessor.resolve(
            listOf(PlannedLayerEvent(0.35f, LayerEventType.NOZZLE_TEMPERATURE, value = 225.0)),
            preview,
        ).single()
        assertEquals(1, event.layerNumber)

        val output = kotlin.io.path.createTempFile("enderslicer-events-output", ".gcode").toFile()
        GcodeLayerEventProcessor.materialize(base, output, listOf(event))
        val text = output.readText()
        assertTrue(text.indexOf(";LAYER:1") < text.indexOf("M104 S225"))
        assertTrue(text.contains(";ENDERSLICER_LAYER_EVENT"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun blocksUnsafeCustomGcode() {
        GcodeLayerEventProcessor.commands(
            LayerEvent(
                id = "unsafe",
                layerNumber = 2,
                zMm = 0.6f,
                type = LayerEventType.CUSTOM_GCODE,
                text = "G28",
            ),
        )
    }
}
