package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

internal object CurviSlicerFieldBuilder {
    data class Result(
        val field: CurviSlicerField,
        val diagnostics: CurviSlicerPipeline.Diagnostics,
    )

    fun build(
        mesh: StlMesh,
        settings: NonPlanarSettings,
        layerHeightMm: Double,
        nozzleDiameterMm: Double,
    ): Result {
        val bounds = mesh.bounds
        require(bounds.width > 0f && bounds.depth > 0f && bounds.height > 0f) { "CurviSlicer requires a three-dimensional model" }
        val aspect = bounds.width.toDouble() / bounds.depth.toDouble()
        val columns: Int
        val rows: Int
        if (aspect >= 1.0) {
            columns = settings.fieldResolution
            rows = max(32, (settings.fieldResolution / aspect).toInt()).coerceAtMost(settings.fieldResolution)
        } else {
            rows = settings.fieldResolution
            columns = max(32, (settings.fieldResolution * aspect).toInt()).coerceAtMost(settings.fieldResolution)
        }
        val top = FloatArray(columns * rows) { Float.NaN }
        val vertices = mesh.interleavedVertices
        var offset = 0
        repeat(mesh.triangleCount) {
            val x0 = vertices[offset]
            val y0 = vertices[offset + 1]
            val z0 = vertices[offset + 2]
            val x1 = vertices[offset + 6]
            val y1 = vertices[offset + 7]
            val z1 = vertices[offset + 8]
            val x2 = vertices[offset + 12]
            val y2 = vertices[offset + 13]
            val z2 = vertices[offset + 14]
            deposit(top, columns, rows, bounds, x0, y0, z0)
            deposit(top, columns, rows, bounds, x1, y1, z1)
            deposit(top, columns, rows, bounds, x2, y2, z2)
            deposit(top, columns, rows, bounds, (x0 + x1 + x2) / 3f, (y0 + y1 + y2) / 3f, (z0 + z1 + z2) / 3f)
            deposit(top, columns, rows, bounds, (x0 + x1) / 2f, (y0 + y1) / 2f, (z0 + z1) / 2f)
            deposit(top, columns, rows, bounds, (x1 + x2) / 2f, (y1 + y2) / 2f, (z1 + z2) / 2f)
            deposit(top, columns, rows, bounds, (x2 + x0) / 2f, (y2 + y0) / 2f, (z2 + z0) / 2f)
            offset += 18
        }
        fillNearest(top, columns, rows)

        val cellX = bounds.width.toDouble() / (columns - 1)
        val cellY = bounds.depth.toDouble() / (rows - 1)
        val cellSize = max(cellX, cellY)
        val clearanceRadius = settings.nozzleClearanceHeightMm * tan(Math.toRadians(settings.nozzleClearanceAngleDegrees))
        val smoothingRadius = maxOf(
            settings.smoothingRadiusMm,
            nozzleDiameterMm * 1.5,
            min(clearanceRadius * 0.04, 8.0),
        )
        val sigmaCells = (smoothingRadius / cellSize).coerceIn(0.75, 10.0)
        val smoothed = gaussianBlur(top, columns, rows, sigmaCells)
        val relief = FloatArray(top.size)
        val mean = smoothed.sumOf(Float::toDouble) / smoothed.size
        for (index in relief.indices) {
            // Flatten the broad, smoothed upper surface rather than only its
            // high-frequency residual. A planar top therefore produces zero
            // deformation, while domes and other broad contours produce the
            // curved layers the user requested.
            relief[index] = smoothed[index] - mean.toFloat()
        }
        val clearanceAmplitudeLimit = (settings.nozzleClearanceHeightMm * tan(Math.toRadians(settings.effectiveSlopeLimitDegrees))).toFloat()
        val amplitudeLimit = min(bounds.height * 0.28f, clearanceAmplitudeLimit)
        var maximumRawRelief = 0.0
        for (index in relief.indices) {
            relief[index] = relief[index].coerceIn(-amplitudeLimit, amplitudeLimit)
            maximumRawRelief = max(maximumRawRelief, abs(relief[index].toDouble()))
        }

        val requestedStrength = settings.strengthPercent / 100.0
        val flatBaseHeight = settings.flatBaseLayers * layerHeightMm
        val usableHeight = max(bounds.height.toDouble() - flatBaseHeight, layerHeightMm)
        val monotonicStrength = if (maximumRawRelief <= 1e-9) 1.0 else {
            // smoothstep has a maximum derivative of 1.5. Keep dz_flat/dz >= 0.25.
            (0.75 * usableHeight / (1.5 * maximumRawRelief)).coerceAtMost(1.0)
        }
        val maximumGradient = maximumGradient(relief, columns, rows, cellX, cellY)
        val slopeLimit = tan(Math.toRadians(settings.effectiveSlopeLimitDegrees))
        val slopeStrength = if (maximumGradient <= 1e-9) 1.0 else (slopeLimit / maximumGradient).coerceAtMost(1.0)
        val appliedStrength = min(requestedStrength, min(monotonicStrength, slopeStrength)).coerceIn(0.0, 1.0)
        val appliedSlope = Math.toDegrees(kotlin.math.atan(maximumGradient * appliedStrength))

        val field = CurviSlicerField(
            minX = bounds.minX.toDouble(),
            minY = bounds.minY.toDouble(),
            minZ = bounds.minZ.toDouble(),
            maxX = bounds.maxX.toDouble(),
            maxY = bounds.maxY.toDouble(),
            maxZ = bounds.maxZ.toDouble(),
            columns = columns,
            rows = rows,
            relief = relief,
            strength = appliedStrength,
            flatBaseHeightMm = flatBaseHeight,
        )
        return Result(
            field,
            CurviSlicerPipeline.Diagnostics(
                gridColumns = columns,
                gridRows = rows,
                requestedStrength = requestedStrength,
                appliedStrength = appliedStrength,
                maximumRawReliefMm = maximumRawRelief,
                maximumAppliedDisplacementMm = field.maximumDisplacementMm,
                maximumFieldSlopeDegrees = appliedSlope,
                sourceTriangles = mesh.triangleCount,
            ),
        )
    }

    private fun deposit(
        grid: FloatArray,
        columns: Int,
        rows: Int,
        bounds: MeshBounds,
        x: Float,
        y: Float,
        z: Float,
    ) {
        val gx = (((x - bounds.minX) / bounds.width) * (columns - 1)).toInt().coerceIn(0, columns - 1)
        val gy = (((y - bounds.minY) / bounds.depth) * (rows - 1)).toInt().coerceIn(0, rows - 1)
        val index = gy * columns + gx
        if (!grid[index].isFinite() || z > grid[index]) grid[index] = z
    }

    private fun fillNearest(values: FloatArray, columns: Int, rows: Int) {
        val queue = ArrayDeque<Int>()
        val distance = IntArray(values.size) { Int.MAX_VALUE }
        for (index in values.indices) {
            if (values[index].isFinite()) {
                queue.add(index)
                distance[index] = 0
            }
        }
        require(queue.isNotEmpty()) { "CurviSlicer could not sample the model surface" }
        val directions = intArrayOf(-1, 0, 1, 0, 0, -1, 0, 1)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val cx = current % columns
            val cy = current / columns
            var direction = 0
            while (direction < directions.size) {
                val nx = cx + directions[direction]
                val ny = cy + directions[direction + 1]
                direction += 2
                if (nx !in 0 until columns || ny !in 0 until rows) continue
                val next = ny * columns + nx
                if (distance[next] <= distance[current] + 1) continue
                distance[next] = distance[current] + 1
                values[next] = values[current]
                queue.add(next)
            }
        }
    }

    private fun gaussianBlur(source: FloatArray, columns: Int, rows: Int, sigma: Double): FloatArray {
        val radius = ceil(sigma * 2.5).toInt().coerceIn(1, 24)
        val weights = DoubleArray(radius * 2 + 1)
        var weightSum = 0.0
        for (offset in -radius..radius) {
            val weight = exp(-(offset * offset) / (2.0 * sigma * sigma))
            weights[offset + radius] = weight
            weightSum += weight
        }
        for (index in weights.indices) weights[index] /= weightSum

        val horizontal = FloatArray(source.size)
        for (y in 0 until rows) {
            for (x in 0 until columns) {
                var sum = 0.0
                for (offset in -radius..radius) {
                    val sx = (x + offset).coerceIn(0, columns - 1)
                    sum += source[y * columns + sx] * weights[offset + radius]
                }
                horizontal[y * columns + x] = sum.toFloat()
            }
        }
        val output = FloatArray(source.size)
        for (y in 0 until rows) {
            for (x in 0 until columns) {
                var sum = 0.0
                for (offset in -radius..radius) {
                    val sy = (y + offset).coerceIn(0, rows - 1)
                    sum += horizontal[sy * columns + x] * weights[offset + radius]
                }
                output[y * columns + x] = sum.toFloat()
            }
        }
        return output
    }

    private fun maximumGradient(
        field: FloatArray,
        columns: Int,
        rows: Int,
        cellX: Double,
        cellY: Double,
    ): Double {
        var maximum = 0.0
        for (y in 0 until rows) {
            for (x in 0 until columns) {
                val left = field[y * columns + max(0, x - 1)].toDouble()
                val right = field[y * columns + min(columns - 1, x + 1)].toDouble()
                val down = field[max(0, y - 1) * columns + x].toDouble()
                val up = field[min(rows - 1, y + 1) * columns + x].toDouble()
                val dx = (right - left) / (if (x in 1 until columns - 1) 2.0 * cellX else cellX)
                val dy = (up - down) / (if (y in 1 until rows - 1) 2.0 * cellY else cellY)
                maximum = max(maximum, hypot(dx, dy))
            }
        }
        return maximum
    }
}
