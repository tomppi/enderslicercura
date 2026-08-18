package com.tomppi.enderslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GcodeLayerEventProcessorTest {
    @Test
    fun retractionChangeWaitsUntilFirmwareRecover() {
        val base = kotlin.io.path.createTempFile("enderslicer-retract-events", ".gcode").toFile().apply {
            writeText(
                """
                ;FLAVOR:Marlin
                M82
                ;LAYER:0
                G1 X1 E1
                G10
                ;LAYER:1
                G11
                G1 X2 E2
                G10
                """.trimIndent(),
            )
        }
        val event = LayerEvent(
            id = "retract-safe",
            layerNumber = 1,
            zMm = 0.4f,
            type = LayerEventType.RETRACTION,
            value = 1.25,
            secondaryValue = 40.0,
        )
        val output = kotlin.io.path.createTempFile("enderslicer-retract-output", ".gcode").toFile()
        GcodeLayerEventProcessor.materialize(base, output, listOf(event))
        val result = output.readText()
        val layer = result.indexOf(";LAYER:1")
        val recover = result.indexOf("G11", layer)
        val setting = result.indexOf("M207 S1.25 F2400", layer)
        val extrusion = result.indexOf("G1 X2 E2", layer)
        assertTrue(layer >= 0 && recover > layer && setting > recover && extrusion > setting)
    }

    @Test
    fun speedAndFlowEventsRestoreFirmwareFactorsAtEnd() {
        val base = kotlin.io.path.createTempFile("enderslicer-factor-events", ".gcode").toFile().apply {
            writeText(
                """
                ;FLAVOR:Marlin
                M82
                ;LAYER:0
                G1 X1 E1
                ;LAYER:1
                G1 X2 E2
                ;End of Gcode
                """.trimIndent(),
            )
        }
        val events = listOf(
            LayerEvent(
                id = "speed",
                layerNumber = 1,
                zMm = 0.4f,
                type = LayerEventType.SPEED_FACTOR,
                value = 70.0,
            ),
            LayerEvent(
                id = "flow",
                layerNumber = 1,
                zMm = 0.4f,
                type = LayerEventType.FLOW_FACTOR,
                value = 95.0,
            ),
        )
        val output = kotlin.io.path.createTempFile("enderslicer-factor-output", ".gcode").toFile()
        GcodeLayerEventProcessor.materialize(base, output, events)
        val result = output.readText()
        assertTrue(result.contains("M220 S70"))
        assertTrue(result.contains("M221 S95"))
        assertTrue(result.contains("M220 S100 ; enderslicercura restore speed factor"))
        assertTrue(result.contains("M221 S100 ; enderslicercura restore flow factor"))
    }

    @Test
    fun restoresRunBeforeTheEndGcode() {
        val endGcodeBody = "G28 ; home the printer\nG1 E-4 F2400 ; final retract"
        val base = kotlin.io.path.createTempFile("enderslicer-restore-order", ".gcode").toFile().apply {
            writeText(
                """
                ;FLAVOR:Marlin
                M82
                ;LAYER:0
                G1 X1 E1
                ;LAYER:1
                G1 X2 E2
                ;End of Gcode
                $endGcodeBody
                """.trimIndent(),
            )
        }
        val events = listOf(
            LayerEvent(
                id = "flow-order",
                layerNumber = 1,
                zMm = 0.4f,
                type = LayerEventType.FLOW_FACTOR,
                value = 200.0,
            ),
            LayerEvent(
                id = "speed-order",
                layerNumber = 1,
                zMm = 0.4f,
                type = LayerEventType.SPEED_FACTOR,
                value = 300.0,
            ),
        )
        val output = kotlin.io.path.createTempFile("enderslicer-restore-order-output", ".gcode").toFile()
        GcodeLayerEventProcessor.materialize(base, output, events)
        val result = output.readText()
        val endMarker = result.indexOf(";End of Gcode")
        val retract = result.indexOf("G1 E-4")
        val speedRestore = result.indexOf("M220 S100 ; enderslicercura restore speed factor")
        val flowRestore = result.indexOf("M221 S100 ; enderslicercura restore flow factor")
        assertTrue(endMarker >= 0 && retract > endMarker)
        assertTrue(speedRestore in 0 until endMarker)
        assertTrue(flowRestore in 0 until endMarker)
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
