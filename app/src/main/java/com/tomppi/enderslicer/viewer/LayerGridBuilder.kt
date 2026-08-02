package com.tomppi.enderslicer.viewer

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Builds a bounded grid using integer indices so Float addition can never stall. */
internal object LayerGridBuilder {
    fun build(
        minX: Float,
        maxX: Float,
        minY: Float,
        maxY: Float,
        spacing: Double = 10.0,
        padding: Double = 10.0,
        z: Float = -0.08f,
        maxLines: Int = 512,
    ): FloatArray {
        require(listOf(minX, maxX, minY, maxY).all(Float::isFinite)) {
            "Layer grid bounds must be finite"
        }
        require(minX <= maxX && minY <= maxY) { "Layer grid bounds are inverted" }
        require(spacing.isFinite() && spacing > 0.0) { "Layer grid spacing must be positive" }
        require(padding.isFinite() && padding >= 0.0) { "Layer grid padding cannot be negative" }
        require(maxLines in 2..MAX_GRID_LINES_HARD_LIMIT) { "Layer grid line limit is invalid" }

        var effectiveSpacing = spacing
        var extents = extents(minX, maxX, minY, maxY, effectiveSpacing, padding)
        repeat(MAX_COARSENING_STEPS) {
            val total = Math.addExact(extents.xCount, extents.yCount)
            if (total <= maxLines.toLong()) return@repeat
            val factor = ceil(total.toDouble() / maxLines.toDouble()).coerceAtLeast(2.0)
            effectiveSpacing = Math.multiplyExact(
                effectiveSpacing.toLong().coerceAtLeast(1L),
                factor.toLong().coerceAtLeast(2L),
            ).toDouble()
            extents = extents(minX, maxX, minY, maxY, effectiveSpacing, padding)
        }

        val totalLines = Math.addExact(extents.xCount, extents.yCount)
        if (totalLines > maxLines.toLong()) return FloatArray(0)
        val floatCount = Math.multiplyExact(totalLines, FLOATS_PER_LINE.toLong())
        require(floatCount <= Int.MAX_VALUE.toLong()) { "Layer grid allocation is too large" }
        val result = FloatArray(floatCount.toInt())
        var output = 0

        for (index in 0 until extents.xCount) {
            val x = (extents.minX + index.toDouble() * effectiveSpacing).toFloat()
            result[output++] = x
            result[output++] = extents.minY.toFloat()
            result[output++] = z
            result[output++] = x
            result[output++] = extents.maxY.toFloat()
            result[output++] = z
        }
        for (index in 0 until extents.yCount) {
            val y = (extents.minY + index.toDouble() * effectiveSpacing).toFloat()
            result[output++] = extents.minX.toFloat()
            result[output++] = y
            result[output++] = z
            result[output++] = extents.maxX.toFloat()
            result[output++] = y
            result[output++] = z
        }
        check(output == result.size)
        return result
    }

    private fun extents(
        minX: Float,
        maxX: Float,
        minY: Float,
        maxY: Float,
        spacing: Double,
        padding: Double,
    ): Extents {
        val gridMinX = floor((minX.toDouble() - padding) / spacing) * spacing
        val gridMaxX = ceil((maxX.toDouble() + padding) / spacing) * spacing
        val gridMinY = floor((minY.toDouble() - padding) / spacing) * spacing
        val gridMaxY = ceil((maxY.toDouble() + padding) / spacing) * spacing
        val xCount = checkedCount(gridMinX, gridMaxX, spacing)
        val yCount = checkedCount(gridMinY, gridMaxY, spacing)
        return Extents(gridMinX, gridMaxX, gridMinY, gridMaxY, xCount, yCount)
    }

    private fun checkedCount(minimum: Double, maximum: Double, spacing: Double): Long {
        require(minimum.isFinite() && maximum.isFinite()) { "Layer grid extents overflowed" }
        val span = max(0.0, maximum - minimum)
        val count = floor(span / spacing).toLong() + 1L
        require(count in 1..MAX_GRID_LINES_HARD_LIMIT.toLong() * MAX_GRID_LINES_HARD_LIMIT) {
            "Layer grid line count is outside the defensive limit"
        }
        return count
    }

    private data class Extents(
        val minX: Double,
        val maxX: Double,
        val minY: Double,
        val maxY: Double,
        val xCount: Long,
        val yCount: Long,
    )

    private const val FLOATS_PER_LINE = 6
    private const val MAX_GRID_LINES_HARD_LIMIT = 16_384
    private const val MAX_COARSENING_STEPS = 8
}
