package com.tomppi.enderslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GcodeLayerPreviewSamplingTest {
    @Test
    fun calibrationResolutionUsesSourceOccupancyWhenAPrintableLayerRetainsNoGeometry() {
        val file = temporaryGcode(
            buildString {
                appendLine(";FLAVOR:Marlin")
                appendLine("M83")
                appendLine(";LAYER:0")
                appendLine(";TYPE:WALL-OUTER")
                repeat(4) { index -> appendLine("G1 X${index + 1} Y0 Z0.2 E1 F1200") }
                appendLine(";LAYER:1")
                appendLine(";TYPE:WALL-OUTER")
                appendLine("G1 X5 Y1 Z0.4 E1 F1200")
                appendLine(";LAYER:2")
                appendLine(";TYPE:SUPPORT")
                repeat(4) { index -> appendLine("G1 X${index + 6} Y2 Z0.6 E1 F1200") }
            },
        )

        val preview = GcodeLayerPreviewParser.parse(file, maxSegments = 3)
        val middle = preview.layers.single { it.number == 1 }
        val resolved = GcodeLayerEventProcessor.resolve(
            planned = listOf(
                PlannedLayerEvent(
                    targetZMm = 0.4f,
                    type = LayerEventType.MESSAGE,
                    text = "middle",
                    label = "middle",
                ),
            ),
            preview = preview,
        )

        assertEquals(1, middle.sourceSegmentCount)
        assertEquals(0, middle.segmentCount)
        assertTrue(middle.hasPrintablePaths)
        assertEquals(1, resolved.single().layerNumber)
    }

    @Test
    fun feasibleCapRetainsEveryRareFeatureClass() {
        val file = featureGcode()

        val preview = GcodeLayerPreviewParser.parse(file, maxSegments = 4)
        val retained = retainedFeatures(preview)

        assertTrue(GcodeLayerPreview.Feature.ARC_OVERHANG in retained)
        assertTrue(GcodeLayerPreview.Feature.SUPPORT_INTERFACE in retained)
        assertTrue(GcodeLayerPreview.Feature.SUPPORT in retained)
        assertTrue(GcodeLayerPreview.Feature.ADHESION in retained)
    }

    @Test
    fun capOneUsesDocumentedRareFeaturePriority() {
        val preview = GcodeLayerPreviewParser.parse(featureGcode(), maxSegments = 1)

        assertEquals(
            setOf(GcodeLayerPreview.Feature.ARC_OVERHANG),
            retainedFeatures(preview),
        )
    }

    @Test
    fun coarseAndClippedCalibrationTargetsFailInsteadOfCollapsing() {
        val preview = previewWithPrintableLayers(5f, 10f)
        val planned = listOf(0.8f, 3.8f, 6.8f, 9.8f, 12.8f).mapIndexed { index, z ->
            PlannedLayerEvent(
                targetZMm = z,
                type = LayerEventType.FAN_SPEED,
                value = index * 20.0,
                label = "level-$index",
            )
        }

        val error = runCatching { GcodeLayerEventProcessor.resolve(planned, preview) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun multipleTargetsResolvingToOnePrintableLayerFailExplicitly() {
        val preview = previewWithPrintableLayers(0.4f, 0.8f)
        val planned = listOf(0.1f, 0.2f).mapIndexed { index, z ->
            PlannedLayerEvent(
                targetZMm = z,
                type = LayerEventType.FLOW_FACTOR,
                value = 90.0 + index,
                label = "level-$index",
            )
        }

        val error = runCatching { GcodeLayerEventProcessor.resolve(planned, preview) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("collapse"))
    }

    private fun featureGcode(): File = temporaryGcode(
        """
        ;FLAVOR:Marlin
        M83
        ;LAYER:0
        ;TYPE:ARC-OVERHANG
        G1 X1 Y0 Z0.2 E1 F1200
        ;TYPE:SUPPORT-INTERFACE
        G1 X2 Y0 E1 F1200
        ;TYPE:SUPPORT
        G1 X3 Y0 E1 F1200
        ;TYPE:SKIRT
        G1 X4 Y0 E1 F1200
        ;TYPE:WALL-OUTER
        G1 X5 Y0 E1 F1200
        G1 X6 Y0 E1 F1200
        G1 X7 Y0 E1 F1200
        G1 X8 Y0 E1 F1200
        """.trimIndent(),
    )

    private fun retainedFeatures(preview: GcodeLayerPreview): Set<GcodeLayerPreview.Feature> =
        preview.layers.flatMap { layer ->
            layer.segments.asList()
                .chunked(GcodeLayerPreview.VALUES_PER_SEGMENT)
                .map { values -> GcodeLayerPreview.Feature.fromCode(values[5].toInt()) }
        }.toSet()

    private fun previewWithPrintableLayers(vararg zValues: Float): GcodeLayerPreview {
        val layers = zValues.mapIndexed { index, z ->
            GcodeLayerPreview.Layer(
                number = index,
                z = z,
                height = if (index == 0) z else z - zValues[index - 1],
                segments = floatArrayOf(0f, 0f, 1f, 1f, 20f, 0f),
                supportSegmentCount = 0,
                supportInterfaceSegmentCount = 0,
                sourceSegmentCount = 1,
            )
        }
        return GcodeLayerPreview(
            layers = layers,
            minX = 0f,
            minY = 0f,
            maxX = 1f,
            maxY = 1f,
            minSpeedMmPerSecond = 20f,
            maxSpeedMmPerSecond = 20f,
            minLayerHeightMm = layers.minOf(GcodeLayerPreview.Layer::height),
            maxLayerHeightMm = layers.maxOf(GcodeLayerPreview.Layer::height),
            totalSegmentCount = layers.size,
            truncated = false,
        )
    }

    private fun temporaryGcode(content: String): File {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-preview-sampling").toFile()
        return File(directory, "output.gcode").apply { writeText(content) }
    }
}
