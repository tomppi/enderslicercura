package com.tomppi.enderslicer.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CuraEnginePostProcessorTest {
    @Test
    fun skipsLayerEventRewriteWhenNoEventsAreRequested() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-postprocess").toFile()
        val output = File(directory, "current.gcode").apply { writeText(sampleGcode()) }
        val base = File(directory, "current-base.gcode")

        val result = CuraEnginePostProcessor.process(
            outputFile = output,
            baseGcodeFile = base,
            settingsTransport = "resolved-json",
            layerEvents = emptyList(),
            plannedLayerEvents = emptyList(),
        )

        assertTrue(result.usedZeroEventFastPath)
        assertTrue(result.layerEvents.isEmpty())
        assertNull(result.previewFailure)
        assertNotNull(result.layerPreview)
        assertEquals(2, result.summary.layerCount)
        assertArrayEquals(base.readBytes(), output.readBytes())
        assertEquals(
            1,
            output.readLines().count { it == ";ENDERSLICER_SETTINGS_TRANSPORT:resolved-json" },
        )
        assertFalse(output.readText().contains("+layer-events"))
        assertFalse(output.readText().contains(";ENDERSLICER_LAYER_EVENT:"))
    }

    @Test
    fun preservesExistingLayerEventPipelineWhenAnEventIsRequested() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-postprocess-events").toFile()
        val output = File(directory, "current.gcode").apply { writeText(sampleGcode()) }
        val base = File(directory, "current-base.gcode")
        val event = LayerEvent(
            id = "user-message",
            layerNumber = 1,
            zMm = 0.4f,
            type = LayerEventType.MESSAGE,
            text = "Second layer",
        )

        val result = CuraEnginePostProcessor.process(
            outputFile = output,
            baseGcodeFile = base,
            settingsTransport = "resolved-json",
            layerEvents = listOf(event),
            plannedLayerEvents = emptyList(),
        )

        assertFalse(result.usedZeroEventFastPath)
        assertEquals(listOf(event), result.layerEvents)
        assertNull(result.previewFailure)
        assertNotNull(result.layerPreview)
        assertFalse(base.readText().contains(";ENDERSLICER_LAYER_EVENT:"))
        assertTrue(base.readText().contains(";ENDERSLICER_SETTINGS_TRANSPORT:resolved-json"))
        assertTrue(output.readText().contains(";ENDERSLICER_LAYER_EVENT:user-message:MESSAGE:USER"))
        assertTrue(output.readText().contains(";ENDERSLICER_SETTINGS_TRANSPORT:resolved-json+layer-events"))
    }

    private fun sampleGcode(): String = """
        ;FLAVOR:Marlin
        ;TIME:2
        ;Filament used: 0m
        ;LAYER_COUNT:2
        M82
        M104 S210
        G92 E0
        ;LAYER:0
        ;TYPE:WALL-OUTER
        ;MESH:model.stl
        G1 X0 Y0 Z0.2 F1200
        G1 X10 Y0 E1 F1200
        ;LAYER:1
        G1 X10 Y10 Z0.4 E2 F1200
        ;TIME_ELAPSED:2
        M104 S0
    """.trimIndent()
}
