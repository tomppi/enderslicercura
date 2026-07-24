package com.tomppi.enderslicer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tomppi.enderslicer.engine.GcodeLayerPreview
import com.tomppi.enderslicer.engine.LayerEvent
import com.tomppi.enderslicer.viewer.LayerPreviewStyle
import com.tomppi.enderslicer.viewer.LayerPreviewSurfaceView
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
internal fun LayerPreviewView(
    preview: GcodeLayerPreview,
    selectedLayerIndex: Int,
    events: List<LayerEvent>,
    onLayerSelected: (Int) -> Unit,
    onEditEvents: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeIndex = selectedLayerIndex.coerceIn(preview.layers.indices)
    val layer = preview.layers[safeIndex]
    val visibleLayers = preview.layers.subList(0, safeIndex + 1)
    val visibleSegmentCount = visibleLayers.sumOf { it.segmentCount }
    val visibleSupportCount = visibleLayers.sumOf { it.supportSegmentCount }
    val visibleInterfaceCount = visibleLayers.sumOf { it.supportInterfaceSegmentCount }
    val layerEvents = events.filter { it.layerNumber == layer.number }
    var style by remember { mutableStateOf(LayerPreviewStyle.CURRENT_LAYER) }

    Column(modifier = modifier) {
        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            factory = { context -> LayerPreviewSurfaceView(context) },
            update = { view -> view.setPreview(preview, safeIndex, style) },
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Layer ${safeIndex + 1}/${preview.layers.size} · Cura ${layer.number} · Z %.3f mm".format(layer.z),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "Height %.3f mm · ${layer.segmentCount} paths · $visibleSegmentCount through this layer".format(layer.height),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(onClick = onEditEvents) {
                        Text(if (layerEvents.isEmpty()) "Add event" else "Events ${layerEvents.size}")
                    }
                }
                if (visibleSupportCount > 0 || visibleInterfaceCount > 0 || preview.truncated) {
                    Text(
                        buildString {
                            if (visibleSupportCount > 0) append("Support $visibleSupportCount  ")
                            if (visibleInterfaceCount > 0) append("Interface $visibleInterfaceCount  ")
                            if (preview.truncated) append("Preview sampled across full print")
                        }.trim(),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                LayerTimeline(
                    layerCount = preview.layers.size,
                    selectedIndex = safeIndex,
                    events = events,
                    preview = preview,
                    onLayerSelected = onLayerSelected,
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
                    if (style == LayerPreviewStyle.CURRENT_LAYER) {
                        Button(onClick = { style = LayerPreviewStyle.CURRENT_LAYER }, modifier = Modifier.weight(1f)) { Text("Current") }
                        OutlinedButton(onClick = { style = LayerPreviewStyle.BUILD_UP }, modifier = Modifier.weight(1f)) { Text("Build-up") }
                    } else {
                        OutlinedButton(onClick = { style = LayerPreviewStyle.CURRENT_LAYER }, modifier = Modifier.weight(1f)) { Text("Current") }
                        Button(onClick = { style = LayerPreviewStyle.BUILD_UP }, modifier = Modifier.weight(1f)) { Text("Build-up") }
                    }
                }
                SpeedLegend(preview.minSpeedMmPerSecond, preview.maxSpeedMmPerSecond)
                Text(
                    "The selected layer is rendered as thick ribbons with a dark outline. Cyan is support, magenta is support interface, and orange is adhesion.",
                    style = MaterialTheme.typography.labelSmall,
                )
                if (preview.minLayerHeightMm > 0f) {
                    Text(
                        "Layer-height range %.3f–%.3f mm".format(preview.minLayerHeightMm, preview.maxLayerHeightMm),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun LayerTimeline(
    layerCount: Int,
    selectedIndex: Int,
    events: List<LayerEvent>,
    preview: GcodeLayerPreview,
    onLayerSelected: (Int) -> Unit,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val selectedColor = MaterialTheme.colorScheme.primary
    val eventColor = MaterialTheme.colorScheme.tertiary
    val layerIndexByNumber = remember(preview) { preview.layers.mapIndexed { index, layer -> layer.number to index }.toMap() }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(trackColor, RoundedCornerShape(8.dp))
            .pointerInput(layerCount) {
                detectTapGestures { point ->
                    val ratio = (point.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    onLayerSelected((ratio * (layerCount - 1).coerceAtLeast(0)).roundToInt())
                }
            },
    ) {
        val centerY = size.height * 0.5f
        drawLine(trackColor.copy(alpha = 0.8f), Offset(8f, centerY), Offset(size.width - 8f, centerY), strokeWidth = 8f)
        val selectedX = if (layerCount <= 1) size.width * 0.5f else 8f + (size.width - 16f) * selectedIndex / (layerCount - 1f)
        drawLine(selectedColor, Offset(8f, centerY), Offset(selectedX, centerY), strokeWidth = 8f)
        events.forEach { event ->
            val index = layerIndexByNumber[event.layerNumber] ?: return@forEach
            val x = if (layerCount <= 1) size.width * 0.5f else 8f + (size.width - 16f) * index / (layerCount - 1f)
            drawCircle(eventColor, radius = 5.5f, center = Offset(x, centerY))
            drawCircle(Color.White, radius = 2f, center = Offset(x, centerY))
        }
        drawCircle(Color.White, radius = 7f, center = Offset(selectedX, centerY))
        drawCircle(selectedColor, radius = 5f, center = Offset(selectedX, centerY))
    }
}

@Composable
private fun SpeedLegend(minimum: Float, maximum: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.hsv(240f, 0.88f, 1f),
                            Color.hsv(180f, 0.88f, 1f),
                            Color.hsv(90f, 0.88f, 1f),
                            Color.hsv(0f, 0.88f, 1f),
                        ),
                    ),
                    RoundedCornerShape(5.dp),
                ),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Slow %.1f mm/s".format(minimum), style = MaterialTheme.typography.labelSmall)
            Text("Fast %.1f mm/s".format(maximum), style = MaterialTheme.typography.labelSmall)
        }
    }
}
