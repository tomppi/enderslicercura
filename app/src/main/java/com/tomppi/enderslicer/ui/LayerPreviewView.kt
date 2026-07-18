package com.tomppi.enderslicer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.engine.GcodeLayerPreview
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
internal fun LayerPreviewView(
    preview: GcodeLayerPreview,
    selectedLayerIndex: Int,
    onLayerSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeIndex = selectedLayerIndex.coerceIn(preview.layers.indices)
    val layer = preview.layers[safeIndex]

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                val printWidth = max(preview.maxX - preview.minX, 0.001f)
                val printDepth = max(preview.maxY - preview.minY, 0.001f)
                val margin = 16.dp.toPx()
                val availableWidth = max(size.width - margin * 2f, 1f)
                val availableHeight = max(size.height - margin * 2f, 1f)
                val scale = min(availableWidth / printWidth, availableHeight / printDepth)
                val drawnWidth = printWidth * scale
                val drawnHeight = printDepth * scale
                val left = (size.width - drawnWidth) * 0.5f
                val top = (size.height - drawnHeight) * 0.5f

                fun point(x: Float, y: Float): Offset = Offset(
                    x = left + (x - preview.minX) * scale,
                    y = top + (preview.maxY - y) * scale,
                )

                drawRect(
                    color = Color.White.copy(alpha = 0.10f),
                    topLeft = Offset(left, top),
                    size = Size(drawnWidth, drawnHeight),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                )

                val values = layer.segments
                var offset = 0
                while (offset + 5 < values.size) {
                    val start = point(values[offset], values[offset + 1])
                    val end = point(values[offset + 2], values[offset + 3])
                    val speed = values[offset + 4]
                    val feature = GcodeLayerPreview.Feature.fromCode(values[offset + 5].toInt())
                    val isSupport = feature == GcodeLayerPreview.Feature.SUPPORT ||
                        feature == GcodeLayerPreview.Feature.SUPPORT_INTERFACE

                    if (isSupport) {
                        drawLine(
                            color = Color.White.copy(
                                alpha = if (feature == GcodeLayerPreview.Feature.SUPPORT_INTERFACE) 0.90f else 0.55f,
                            ),
                            start = start,
                            end = end,
                            strokeWidth = if (feature == GcodeLayerPreview.Feature.SUPPORT_INTERFACE) {
                                5.0.dp.toPx()
                            } else {
                                3.8.dp.toPx()
                            },
                        )
                    }
                    drawLine(
                        color = speedColor(
                            speed = speed,
                            minimum = preview.minSpeedMmPerSecond,
                            maximum = preview.maxSpeedMmPerSecond,
                        ),
                        start = start,
                        end = end,
                        strokeWidth = 1.55.dp.toPx(),
                    )
                    offset += GcodeLayerPreview.VALUES_PER_SEGMENT
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    "Layer ${safeIndex + 1}/${preview.layers.size} · Cura layer ${layer.number} · Z %.2f mm".format(layer.z),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    buildString {
                        append("${layer.segmentCount} extrusion paths")
                        if (layer.supportSegmentCount > 0 || layer.supportInterfaceSegmentCount > 0) {
                            append(" · support ${layer.supportSegmentCount}")
                            append(" · interface ${layer.supportInterfaceSegmentCount}")
                        }
                        if (preview.truncated) append(" · preview capped")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = safeIndex.toFloat(),
                    onValueChange = { value -> onLayerSelected(value.roundToInt().coerceIn(preview.layers.indices)) },
                    valueRange = 0f..max(preview.layers.lastIndex, 1).toFloat(),
                    enabled = preview.layers.size > 1,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { onLayerSelected((safeIndex - 1).coerceAtLeast(0)) },
                        enabled = safeIndex > 0,
                        modifier = Modifier.weight(1f),
                    ) { Text("Previous") }
                    OutlinedButton(
                        onClick = { onLayerSelected((safeIndex + 1).coerceAtMost(preview.layers.lastIndex)) },
                        enabled = safeIndex < preview.layers.lastIndex,
                        modifier = Modifier.weight(1f),
                    ) { Text("Next") }
                }
                SpeedLegend(preview.minSpeedMmPerSecond, preview.maxSpeedMmPerSecond)
                Text(
                    "White outlined paths are supports; thicker outlines are support interfaces.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun SpeedLegend(minimum: Float, maximum: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.hsv(240f, 0.88f, 1f),
                            Color.hsv(180f, 0.88f, 1f),
                            Color.hsv(90f, 0.88f, 1f),
                            Color.hsv(0f, 0.88f, 1f),
                        ),
                    ),
                ),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Slow %.1f mm/s".format(minimum), style = MaterialTheme.typography.labelSmall)
            Text("Fast %.1f mm/s".format(maximum), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun speedColor(speed: Float, minimum: Float, maximum: Float): Color {
    val range = max(maximum - minimum, 0.001f)
    val normalized = ((speed - minimum) / range).coerceIn(0f, 1f)
    return Color.hsv(
        hue = 240f * (1f - normalized),
        saturation = 0.88f,
        value = 1f,
    )
}
