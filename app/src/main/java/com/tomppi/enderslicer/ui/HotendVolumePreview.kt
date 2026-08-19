package com.tomppi.enderslicer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.tomppi.enderslicer.nonplanar.NonPlanarSettings
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Crude isometric wireframe of the collision volume built from the hot-end
 * measurements: the nozzle cone (the smaller cone angle), the heating block
 * frustum (centered on the nozzle-axis offset), the whole-plate cutoff level,
 * and the build plate. Dragging the nozzle tip moves the measured block
 * offset; dragging the nozzle's base ring adjusts the smaller cone angle so
 * the silhouette matches the real nozzle precisely.
 */
@Composable
internal fun HotendVolumePreview(
    nozzleAngleDegrees: Double?,
    protrusionMm: Double?,
    blockWidthMm: Double?,
    blockDepthMm: Double?,
    offsetXmm: Double?,
    offsetYmm: Double?,
    clearanceAngleDegrees: Double?,
    holderHeightMm: Double?,
    onOffsetChange: (Double, Double) -> Unit,
    onNozzleAngleChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val angle = clearanceAngleDegrees ?: 45.0
    val holder = holderHeightMm ?: 50.0
    val protrusion = protrusionMm ?: 5.0
    val nozzleAngle = nozzleAngleDegrees ?: 30.0
    val width = blockWidthMm ?: 20.0
    val depth = blockDepthMm ?: 16.0
    val offsetX = offsetXmm ?: 0.0
    val offsetY = offsetYmm ?: 0.0

    var dragging by remember { mutableStateOf<String?>(null) }

    val plateColor = MaterialTheme.colorScheme.outlineVariant
    val blockColor = MaterialTheme.colorScheme.primary
    val nozzleColor = MaterialTheme.colorScheme.secondary
    val cutoffColor = MaterialTheme.colorScheme.error

    fun fitScale(sizeX: Float, sizeY: Float, holder: Double, angle: Double, protrusion: Double, width: Double, depth: Double, offsetX: Double, offsetY: Double): Double {
        val worldExtent = max(
            holder,
            max(width, depth) + max(abs(offsetX), abs(offsetY)) * 2.0 +
                max(0.0, holder - protrusion) * tan(Math.toRadians(angle)) * 2.0,
        )
        return min(sizeX * 0.9f, sizeY * 0.55f) / worldExtent
    }

    fun project(x: Double, y: Double, z: Double, scale: Double, center: Offset): Offset {
        val sx = (x - y) * cos(Math.toRadians(30.0))
        val sy = (x + y) * sin(Math.toRadians(30.0)) - z
        return Offset(
            (sx * scale + center.x).toFloat(),
            (sy * scale + center.y).toFloat(),
        )
    }

    Canvas(
        modifier = modifier.pointerInput(
            angle, holder, protrusion, nozzleAngle, width, depth, offsetX, offsetY,
        ) {
            fun groundDelta(drag: Offset, scale: Double): Pair<Double, Double> {
                val c = cos(Math.toRadians(30.0)) * scale
                val s2 = sin(Math.toRadians(30.0)) * scale
                val denominator = 4.0 * c * s2
                if (denominator <= 1e-9) return 0.0 to 0.0
                val gx = (s2 * drag.x + c * drag.y) / denominator
                val gy = (-s2 * drag.x + c * drag.y) / denominator
                return gx to gy
            }

            detectDragGestures(
                onDragStart = { start ->
                    val scale = fitScale(size.width.toFloat(), size.height.toFloat(), holder, angle, protrusion, width, depth, offsetX, offsetY)
                    val center = Offset(size.width / 2f, size.height * 0.72f)
                    val tip = project(0.0, 0.0, 0.0, scale, center)
                    val junctionRadius = protrusion * tan(Math.toRadians(nozzleAngle))
                    val handle = project(junctionRadius, 0.0, protrusion, scale, center)
                    dragging = when {
                        (start - tip).getDistance() < (start - handle).getDistance() -> "tip"
                        else -> "angle"
                    }
                },
                onDrag = { change, drag ->
                    change.consume()
                    val scale = fitScale(size.width.toFloat(), size.height.toFloat(), holder, angle, protrusion, width, depth, offsetX, offsetY)
                    val (gx, gy) = groundDelta(drag, scale)
                    if (dragging == "tip") {
                        val nextX = (offsetX + gx).coerceIn(
                            -NonPlanarSettings.MAX_BLOCK_OFFSET_MM,
                            NonPlanarSettings.MAX_BLOCK_OFFSET_MM,
                        )
                        val nextY = (offsetY + gy).coerceIn(
                            -NonPlanarSettings.MAX_BLOCK_OFFSET_MM,
                            NonPlanarSettings.MAX_BLOCK_OFFSET_MM,
                        )
                        onOffsetChange(
                            kotlin.math.round(nextX * 10.0) / 10.0,
                            kotlin.math.round(nextY * 10.0) / 10.0,
                        )
                    } else {
                        // Dragging the nozzle's base ring: widen/narrow the
                        // smaller cone angle to match the real nozzle taper.
                        val currentRadius = protrusion * tan(Math.toRadians(nozzleAngle))
                        val nextRadius = (currentRadius + gx).coerceIn(0.1, 60.0)
                        val nextAngle = Math.toDegrees(atan(nextRadius / protrusion)).coerceIn(
                            NonPlanarSettings.MIN_NOZZLE_ANGLE_DEGREES,
                            NonPlanarSettings.MAX_NOZZLE_ANGLE_DEGREES,
                        )
                        onNozzleAngleChange(kotlin.math.round(nextAngle * 10.0) / 10.0)
                    }
                },
                onDragEnd = { dragging = null },
            )
        },
    ) {
        val scale = fitScale(size.width.toFloat(), size.height.toFloat(), holder, angle, protrusion, width, depth, offsetX, offsetY)
        val center = Offset(size.width / 2f, size.height * 0.72f)

        fun projectLocal(x: Double, y: Double, z: Double): Offset = project(x, y, z, scale, center)

        fun line(a: Offset, b: Offset, color: Color, width: Float = 2f) {
            drawLine(color, a, b, strokeWidth = width)
        }

        // Build plate (schematic, not to scale).
        val plateHalf = max(width, depth) * 1.5
        val plate = listOf(
            projectLocal(-plateHalf, -plateHalf, 0.0),
            projectLocal(plateHalf, -plateHalf, 0.0),
            projectLocal(plateHalf, plateHalf, 0.0),
            projectLocal(-plateHalf, plateHalf, 0.0),
        )
        val platePath = Path().apply {
            moveTo(plate[0].x, plate[0].y)
            for (i in 1..3) lineTo(plate[i].x, plate[i].y)
            close()
        }
        drawPath(platePath, plateColor.copy(alpha = 0.15f))
        drawPath(platePath, plateColor, style = Stroke(width = 1.5f))

        // Nozzle cone (the smaller cone angle) from the tip up to the junction.
        val tip = projectLocal(0.0, 0.0, 0.0)
        val junctionRadius = protrusion * tan(Math.toRadians(nozzleAngle))
        val nozzleBase = (0 until 12).map { i ->
            val a = 2.0 * Math.PI * i / 12.0
            projectLocal(cos(a) * junctionRadius, sin(a) * junctionRadius, protrusion)
        }
        for (vertex in nozzleBase) line(tip, vertex, nozzleColor.copy(alpha = 0.7f), 1.2f)
        for (i in nozzleBase.indices) {
            line(nozzleBase[i], nozzleBase[(i + 1) % nozzleBase.size], nozzleColor, 1.6f)
        }

        // Heating block frustum: footprint at the junction (offset from the
        // tip axis), widening at the clearance angle up to the holder.
        val rise = max(0.0, holder - protrusion)
        val topHalfW = width / 2.0 + rise * tan(Math.toRadians(angle))
        val topHalfD = depth / 2.0 + rise * tan(Math.toRadians(angle))
        val baseCorners = listOf(
            projectLocal(offsetX - width / 2.0, offsetY - depth / 2.0, protrusion),
            projectLocal(offsetX + width / 2.0, offsetY - depth / 2.0, protrusion),
            projectLocal(offsetX + width / 2.0, offsetY + depth / 2.0, protrusion),
            projectLocal(offsetX - width / 2.0, offsetY + depth / 2.0, protrusion),
        )
        val topCorners = listOf(
            projectLocal(offsetX - topHalfW, offsetY - topHalfD, holder),
            projectLocal(offsetX + topHalfW, offsetY - topHalfD, holder),
            projectLocal(offsetX + topHalfW, offsetY + topHalfD, holder),
            projectLocal(offsetX - topHalfW, offsetY + topHalfD, holder),
        )
        for (i in 0..3) {
            line(baseCorners[i], baseCorners[(i + 1) % 4], blockColor, 2f)
            line(topCorners[i], topCorners[(i + 1) % 4], blockColor, 2f)
            line(baseCorners[i], topCorners[i], blockColor.copy(alpha = 0.8f), 1.5f)
        }

        // Cutoff level: the whole-plate no-go plane above the holder.
        val cutoffHalf = plateHalf * 1.15
        val cutoff = listOf(
            projectLocal(-cutoffHalf, -cutoffHalf, holder),
            projectLocal(cutoffHalf, -cutoffHalf, holder),
            projectLocal(cutoffHalf, cutoffHalf, holder),
            projectLocal(-cutoffHalf, cutoffHalf, holder),
        )
        val cutoffPath = Path().apply {
            moveTo(cutoff[0].x, cutoff[0].y)
            for (i in 1..3) lineTo(cutoff[i].x, cutoff[i].y)
            close()
        }
        drawPath(cutoffPath, cutoffColor.copy(alpha = 0.10f))
        drawPath(cutoffPath, cutoffColor, style = Stroke(width = 1.5f))

        // Nozzle axis guide + tip marker + angle handle.
        line(projectLocal(0.0, 0.0, 0.0), projectLocal(0.0, 0.0, holder * 1.05), plateColor, 1f)
        drawCircle(nozzleColor, radius = 7f, center = tip)
        drawCircle(Color.White, radius = 2.5f, center = tip)
        val handle = projectLocal(junctionRadius, 0.0, protrusion)
        drawCircle(nozzleColor, radius = 6f, center = handle)
        drawCircle(Color.Black, radius = 2f, center = handle)
    }
}
