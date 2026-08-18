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
    fun noEventsReuseFirstValidationAndBasePreviewWithoutRewritingOutput() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-postprocess").toFile()
        val output = File(directory, "output.gcode").apply { writeText(sampleGcode()) }
        val base = File(directory, "base.gcode")

        val result = CuraEnginePostProcessor.process(
            outputFile = output,
            baseGcodeFile = base,
            settingsTransport = "resolved-json",
            layerEvents = emptyList(),
            printerEnvelope = envelope(),
        )

        assertTrue(result.usedZeroEventFastPath)
        assertTrue(result.layerEvents.isEmpty())
        assertNull(result.previewFailure)
        assertNotNull(result.layerPreview)
        assertEquals(2, result.summary.layerCount)
        assertArrayEquals(base.readBytes(), output.readBytes())
        assertEquals(1, output.readLines().count { it == ";ENDERSLICER_SETTINGS_TRANSPORT:resolved-json" })
        assertFalse(output.readText().contains("+layer-events"))
        assertFalse(output.readText().contains(";ENDERSLICER_LAYER_EVENT:"))
    }

    @Test
    fun filteredInvalidEventsAlsoUseTheZeroEventFastPath() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-postprocess-filtered").toFile()
        val output = File(directory, "output.gcode").apply { writeText(sampleGcode()) }
        val base = File(directory, "base.gcode")
        val invalid = LayerEvent(
            id = "outside-model",
            layerNumber = 999,
            zMm = 99f,
            type = LayerEventType.MESSAGE,
            text = "Never emitted",
        )

        val result = CuraEnginePostProcessor.process(
            outputFile = output,
            baseGcodeFile = base,
            settingsTransport = "resolved-json",
            layerEvents = listOf(invalid),
            printerEnvelope = envelope(),
        )

        assertTrue(result.usedZeroEventFastPath)
        assertTrue(result.layerEvents.isEmpty())
        assertArrayEquals(base.readBytes(), output.readBytes())
        assertFalse(output.readText().contains(";ENDERSLICER_LAYER_EVENT:"))
    }

    @Test
    fun validEventsKeepTheExistingMaterializeRevalidateAndPreviewPipeline() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-postprocess-events").toFile()
        val output = File(directory, "output.gcode").apply { writeText(sampleGcode()) }
        val base = File(directory, "base.gcode")
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
            printerEnvelope = envelope(),
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

    @Test
    fun failsLoudWhenCurviGcodeHasNoFieldInsteadOfSilentlyGoingPlanar() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-postprocess-curvi-lost").toFile()
        val output = File(directory, "output.gcode").apply {
            writeText(
                sampleGcode() + "\n" +
                    com.tomppi.enderslicer.nonplanar.CurviSlicerRuntime.MACHINE_END_SENTINEL + "\n" +
                    "G28\n",
            )
        }
        val base = File(directory, "base.gcode")

        val error = runCatching {
            CuraEnginePostProcessor.process(
                outputFile = output,
                baseGcodeFile = base,
                settingsTransport = "resolved-json",
                layerEvents = emptyList(),
                printerEnvelope = envelope(),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("CurviSlicer was requested"))
    }

    private fun envelope(): PrinterEnvelope = PrinterEnvelope(
        widthMm = 230.0,
        depthMm = 230.0,
        heightMm = 250.0,
        buildPlateShape = "rectangular",
        originAtCenter = false,
    )

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
