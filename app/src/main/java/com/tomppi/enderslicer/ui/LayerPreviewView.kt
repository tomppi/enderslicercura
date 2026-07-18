package com.tomppi.enderslicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tomppi.enderslicer.engine.GcodeLayerPreview
import com.tomppi.enderslicer.viewer.LayerPreviewSurfaceView
import kotlin.math.max
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
    val visibleLayers = preview.layers.subList(0, safeIndex + 1)
    val visibleSegmentCount = visibleLayers.sumOf { it.segmentCount }
    val visibleSupportCount = visibleLayers.sumOf { it.supportSegmentCount }
    val visibleInterfaceCount = visibleLayers.sumOf { it.supportInterfaceSegmentCount }

    Column(modifier = modifier) {
        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            factory = { context -> LayerPreviewSurfaceView(context) },
            update = { view -> view.setPreview(preview, safeIndex) },
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    "Showing layers 1-${safeIndex + 1}/${preview.layers.size} · Cura layer ${layer.number} · Z %.2f mm".format(layer.z),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    buildString {
                        append("$visibleSegmentCount visible extrusion paths")
                        append(" · current layer ${layer.segmentCount}")
                        if (visibleSupportCount > 0 || visibleInterfaceCount > 0) {
                            append(" · support $visibleSupportCount")
                            append(" · interface $visibleInterfaceCount")
                        }
                        if (preview.truncated) append(" · preview capped")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Drag orbit · Pinch zoom · Two-finger pan · Double-tap reset",
                    style = MaterialTheme.typography.labelSmall,
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
