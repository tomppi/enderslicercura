package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.calibration.CalibrationSliceState
import com.tomppi.enderslicer.calibration.CalibrationTestType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GcodeLayerEventProcessorTest {
    @After
    fun clearCalibrationState() {
        CalibrationSliceState.clear()
    }

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
        assertTrue(text.indexOf(";LAYER:1") < text.indexOf("M109 S225"))
        assertTrue(text.contains(";ENDERSLICER_LAYER_EVENT"))
    }

    @Test
    fun calibrationHeightSkipsEmptyTransitionLayer() {
        val base = kotlin.io.path.createTempFile("enderslicer-empty-layer", ".gcode").toFile().apply {
            writeText(
                """
                ;FLAVOR:Marlin
                M82
                ;LAYER:0
                G1 Z0.2 X1 E1
                ;LAYER:1
                G0 Z0.4
                ;LAYER:2
                G1 Z0.6 X2 E2
                """.trimIndent(),
            )
        }
        val preview = GcodeLayerPreviewParser.parse(base)
        val event = GcodeLayerEventProcessor.resolve(
            listOf(PlannedLayerEvent(0.35f, LayerEventType.FLOW_FACTOR, value = 100.0)),
            preview,
        ).single()
        assertEquals(2, event.layerNumber)
    }

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
            source = LayerEventSource.CALIBRATION,
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
    fun fanCalibrationSuppressesCuraFanChangesAndRestoresShutdown() {
        val base = kotlin.io.path.createTempFile("enderslicer-fan-events", ".gcode").toFile().apply {
            writeText(
                """
                ;FLAVOR:Marlin
                M82
                ;LAYER:0
                G1 X1 E1
                ;LAYER:1
                M106 S255
                G1 X2 E2
                M107
                ;End of Gcode
                """.trimIndent(),
            )
        }
        val event = LayerEvent(
            id = "fan-cal",
            layerNumber = 1,
            zMm = 0.4f,
            type = LayerEventType.FAN_SPEED,
            value = 50.0,
            source = LayerEventSource.CALIBRATION,
        )
        val output = kotlin.io.path.createTempFile("enderslicer-fan-output", ".gcode").toFile()
        GcodeLayerEventProcessor.materialize(base, output, listOf(event))
        val result = output.readText()
        assertTrue(result.contains("M106 S128"))
        assertTrue(!result.contains("M106 S255"))
        assertTrue(result.contains("M107 ; enderslicercura fan calibration safety shutdown"))
    }

    @Test
    fun speedAndFlowCalibrationRestoreFirmwareFactorsAtEnd() {
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
                id = "speed-cal",
                layerNumber = 1,
                zMm = 0.4f,
                type = LayerEventType.SPEED_FACTOR,
                value = 70.0,
                source = LayerEventSource.CALIBRATION,
            ),
            LayerEvent(
                id = "flow-cal",
                layerNumber = 1,
                zMm = 0.4f,
                type = LayerEventType.FLOW_FACTOR,
                value = 95.0,
                source = LayerEventSource.CALIBRATION,
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
    fun pressureAdvanceCalibrationEmitsM900AndRestoresFirstK() {
        CalibrationSliceState.activate(CalibrationTestType.PRESSURE_ADVANCE, 0.02)
        val base = simpleTwoLayerGcode("enderslicer-pressure-events")
        val event = LayerEvent(
            id = "pressure-cal",
            layerNumber = 1,
            zMm = 0.4f,
            type = LayerEventType.PRESSURE_ADVANCE,
            value = 0.12,
            source = LayerEventSource.CALIBRATION,
        )
        val output = kotlin.io.path.createTempFile("enderslicer-pressure-output", ".gcode").toFile()
        GcodeLayerEventProcessor.materialize(base, output, listOf(event))
        val result = output.readText()
        assertTrue(result.contains("M900 K0.12"))
        assertTrue(result.contains("M900 K0.02 ; enderslicercura restore pressure advance"))
    }

    @Test
    fun junctionDeviationCalibrationEmitsM205JAndRestoresFirstValue() {
        CalibrationSliceState.activate(CalibrationTestType.JUNCTION_DEVIATION, 0.005)
        val base = simpleTwoLayerGcode("enderslicer-junction-events")
        val event = LayerEvent(
            id = "junction-cal",
            layerNumber = 1,
            zMm = 0.4f,
            type = LayerEventType.JUNCTION_DEVIATION,
            value = 0.025,
            source = LayerEventSource.CALIBRATION,
        )
        val output = kotlin.io.path.createTempFile("enderslicer-junction-output", ".gcode").toFile()
        GcodeLayerEventProcessor.materialize(base, output, listOf(event))
        val result = output.readText()
        assertTrue(result.contains("M205 J0.025"))
        assertTrue(result.contains("M205 J0.005 ; enderslicercura restore junction deviation"))
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

    private fun simpleTwoLayerGcode(prefix: String) = kotlin.io.path.createTempFile(prefix, ".gcode").toFile().apply {
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
}
