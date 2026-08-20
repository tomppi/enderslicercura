package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.engine.checkCancellation
import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
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
        require(bounds.width > 0f && bounds.depth > 0f && bounds.height > 0f) {
            "CurviSlicer requires a three-dimensional model"
        }
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
        repeat(mesh.triangleCount) { triangleIndex ->
            checkCancellation(triangleIndex, "CurviSlicer processing")
            rasterizeTriangle(
                grid = top,
                columns = columns,
                rows = rows,
                bounds = bounds,
                x0 = vertices[offset].toDouble(),
                y0 = vertices[offset + 1].toDouble(),
                z0 = vertices[offset + 2].toDouble(),
                x1 = vertices[offset + 6].toDouble(),
                y1 = vertices[offset + 7].toDouble(),
                z1 = vertices[offset + 8].toDouble(),
                x2 = vertices[offset + 12].toDouble(),
                y2 = vertices[offset + 13].toDouble(),
                z2 = vertices[offset + 14].toDouble(),
            )
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
            relief[index] = smoothed[index] - mean.toFloat()
        }
        val clearanceAmplitudeLimit = (
            settings.holderHeightMm * tan(Math.toRadians(settings.effectiveSlopeLimitDegrees))
        ).toFloat()
        // The user-measured lift cap is the surface displacement budget in
        // millimetres. The smoothstep vertical mapping also enforces its own
        // invertibility bound through monotonicStrength below, so an oversized
        // lift on a short model is safely strength-reduced instead of
        // destabilizing the inverse.
        val amplitudeLimit = min(settings.maximumLiftMm.toFloat(), clearanceAmplitudeLimit)
        var maximumRawRelief = 0.0
        for (index in relief.indices) {
            relief[index] = relief[index].coerceIn(-amplitudeLimit, amplitudeLimit)
            maximumRawRelief = max(maximumRawRelief, abs(relief[index].toDouble()))
        }

        val requestedStrength = settings.strengthPercent / 100.0
        val flatBaseHeight = settings.flatBaseLayers * layerHeightMm
        val usableHeight = max(bounds.height.toDouble() - flatBaseHeight, layerHeightMm)
        val slopeLimit = tan(Math.toRadians(settings.effectiveSlopeLimitDegrees))
        val maximumSmoothDerivative = 1.5 / usableHeight
        val monotonicStrength = if (maximumRawRelief <= 1e-9) {
            1.0
        } else {
            // Keep the inverse denominator comfortably positive for Newton
            // convergence and to avoid severe slope amplification.
            (0.65 / (maximumRawRelief * maximumSmoothDerivative)).coerceAtMost(1.0)
        }
        val appliedStrength = min(requestedStrength, monotonicStrength).coerceIn(0.0, 1.0)

        // Enforce the slope limit locally instead of scaling the requested
        // strength globally: a single steep feature used to weaken the curve
        // everywhere, so a dome with steep flanks lost most of its gentle peak
        // too. Projecting per-cell gradients flattens only what the nozzle
        // clearance requires and keeps full strength on gentle regions.
        if (appliedStrength > 0.0 && slopeLimit > 0.0) {
            val minimumVerticalDerivative = 1.0 - maximumRawRelief * appliedStrength * maximumSmoothDerivative
            if (minimumVerticalDerivative > 0.05) {
                // The smoothstep vertical mapping amplifies XY gradients by up
                // to 1 / minimumVerticalDerivative; the per-cell step budget
                // accounts for that so the printed field never exceeds the
                // configured slope limit.
                // The emitted path slope can exceed the analytical bound by a
                // few percent (grid interpolation and the local smoothstep
                // derivative), so the gradient budget keeps an 8% safety
                // margin instead of landing exactly on the configured limit.
                val maxGradient = slopeLimit * minimumVerticalDerivative / appliedStrength * 0.92
                // Diagonal moves combine both axes, so each axis budget is
                // divided by sqrt(2): a climb at the configured limit in X AND
                // Y at once would otherwise exceed it by 41%.
                val maxStepX = maxGradient * cellX / 1.4142135623730951
                val maxStepY = maxGradient * cellY / 1.4142135623730951
                val maxStepDiagonal = maxGradient * hypot(cellX, cellY) / 1.4142135623730951
                // A slope constraint propagates one cell per pass, so the
                // pass budget scales with the grid diameter instead of being a
                // fixed 64 - a 192-column field would otherwise keep residual
                // gradients above the configured limit.
                val projectionPasses = (columns + rows).coerceIn(64, 512)
                for (pass in 0 until projectionPasses) {
                    checkCancellation(pass, "CurviSlicer processing")
                    var changed = false
                    for (gy in 0 until rows) {
                        for (gx in 0 until columns) {
                            val index = gy * columns + gx
                            var value = relief[index].toDouble()
                            fun clampNeighbor(neighborIndex: Int, maxStep: Double) {
                                val limit = relief[neighborIndex].toDouble() + maxStep
                                if (value > limit) {
                                    value = limit
                                }
                            }
                            if (gx > 0) clampNeighbor(index - 1, maxStepX)
                            if (gx < columns - 1) clampNeighbor(index + 1, maxStepX)
                            if (gy > 0) clampNeighbor(index - columns, maxStepY)
                            if (gy < rows - 1) clampNeighbor(index + columns, maxStepY)
                            if (gx > 0 && gy > 0) clampNeighbor(index - columns - 1, maxStepDiagonal)
                            if (gx < columns - 1 && gy > 0) clampNeighbor(index - columns + 1, maxStepDiagonal)
                            if (gx > 0 && gy < rows - 1) clampNeighbor(index + columns - 1, maxStepDiagonal)
                            if (gx < columns - 1 && gy < rows - 1) clampNeighbor(index + columns + 1, maxStepDiagonal)
                            if (value != relief[index].toDouble()) {
                                relief[index] = value.toFloat()
                                changed = true
                            }
                        }
                    }
                    if (!changed) break
                }
            }
        }

        val maximumGradient = maximumCellGradient(relief, columns, rows, cellX, cellY)
        val verticalDerivative = (1.0 - maximumRawRelief * appliedStrength * maximumSmoothDerivative).coerceAtLeast(0.05)
        val appliedSlope = Math.toDegrees(kotlin.math.atan(maximumGradient * appliedStrength / verticalDerivative))

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

    private fun rasterizeTriangle(
        grid: FloatArray,
        columns: Int,
        rows: Int,
        bounds: MeshBounds,
        x0: Double,
        y0: Double,
        z0: Double,
        x1: Double,
        y1: Double,
        z1: Double,
        x2: Double,
        y2: Double,
        z2: Double,
    ) {
        val denominator = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
        if (abs(denominator) <= 1e-12) {
            deposit(grid, columns, rows, bounds, x0, y0, z0)
            deposit(grid, columns, rows, bounds, x1, y1, z1)
            deposit(grid, columns, rows, bounds, x2, y2, z2)
            deposit(grid, columns, rows, bounds, (x0 + x1) * 0.5, (y0 + y1) * 0.5, (z0 + z1) * 0.5)
            deposit(grid, columns, rows, bounds, (x1 + x2) * 0.5, (y1 + y2) * 0.5, (z1 + z2) * 0.5)
            deposit(grid, columns, rows, bounds, (x2 + x0) * 0.5, (y2 + y0) * 0.5, (z2 + z0) * 0.5)
            return
        }

        fun gridX(x: Double): Double = (x - bounds.minX) / bounds.width * (columns - 1)
        fun gridY(y: Double): Double = (y - bounds.minY) / bounds.depth * (rows - 1)
        val minGridX = floor(minOf(gridX(x0), gridX(x1), gridX(x2))).toInt().coerceIn(0, columns - 1)
        val maxGridX = ceil(maxOf(gridX(x0), gridX(x1), gridX(x2))).toInt().coerceIn(0, columns - 1)
        val minGridY = floor(minOf(gridY(y0), gridY(y1), gridY(y2))).toInt().coerceIn(0, rows - 1)
        val maxGridY = ceil(maxOf(gridY(y0), gridY(y1), gridY(y2))).toInt().coerceIn(0, rows - 1)

        for (gy in minGridY..maxGridY) {
            val py = bounds.minY + bounds.depth * gy.toDouble() / (rows - 1)
            for (gx in minGridX..maxGridX) {
                val px = bounds.minX + bounds.width * gx.toDouble() / (columns - 1)
                val w0 = ((y1 - y2) * (px - x2) + (x2 - x1) * (py - y2)) / denominator
                val w1 = ((y2 - y0) * (px - x2) + (x0 - x2) * (py - y2)) / denominator
                val w2 = 1.0 - w0 - w1
                if (w0 < -BARYCENTRIC_TOLERANCE || w1 < -BARYCENTRIC_TOLERANCE || w2 < -BARYCENTRIC_TOLERANCE) {
                    continue
                }
                val z = w0 * z0 + w1 * z1 + w2 * z2
                val index = gy * columns + gx
                if (!grid[index].isFinite() || z > grid[index]) grid[index] = z.toFloat()
            }
        }
    }

    private fun deposit(
        grid: FloatArray,
        columns: Int,
        rows: Int,
        bounds: MeshBounds,
        x: Double,
        y: Double,
        z: Double,
    ) {
        val gx = (((x - bounds.minX) / bounds.width) * (columns - 1)).toInt().coerceIn(0, columns - 1)
        val gy = (((y - bounds.minY) / bounds.depth) * (rows - 1)).toInt().coerceIn(0, rows - 1)
        val index = gy * columns + gx
        if (!grid[index].isFinite() || z > grid[index]) grid[index] = z.toFloat()
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
        var processed = 0
        while (queue.isNotEmpty()) {
            processed++
            checkCancellation(processed, "CurviSlicer processing")
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
            checkCancellation(y, "CurviSlicer processing", 1)
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
            checkCancellation(y, "CurviSlicer processing", 1)
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

    private fun maximumCellGradient(
        field: FloatArray,
        columns: Int,
        rows: Int,
        cellX: Double,
        cellY: Double,
    ): Double {
        var maximum = 0.0
        for (y in 0 until rows - 1) {
            checkCancellation(y, "CurviSlicer processing", 1)
            for (x in 0 until columns - 1) {
                val a = field[y * columns + x].toDouble()
                val b = field[y * columns + x + 1].toDouble()
                val c = field[(y + 1) * columns + x].toDouble()
                val d = field[(y + 1) * columns + x + 1].toDouble()
                for (tx in doubleArrayOf(0.0, 1.0)) {
                    for (ty in doubleArrayOf(0.0, 1.0)) {
                        val dx = ((b - a) * (1.0 - ty) + (d - c) * ty) / cellX
                        val dy = ((c - a) * (1.0 - tx) + (d - b) * tx) / cellY
                        maximum = max(maximum, hypot(dx, dy))
                    }
                }
            }
        }
        return maximum
    }

    private const val BARYCENTRIC_TOLERANCE = 1e-6
}
