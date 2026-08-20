package com.tomppi.enderslicer.nonplanar

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

internal data class CurviSlicerField(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
    val columns: Int,
    val rows: Int,
    val relief: FloatArray,
    val strength: Double,
    val flatBaseHeightMm: Double,
    val uniformShift: Boolean = false,
    val fadeStartFraction: Double = 0.0,
) {
    init {
        require(columns >= 2 && rows >= 2 && relief.size == columns * rows)
        require(maxX > minX && maxY > minY && maxZ > minZ)
        require(strength in 0.0..1.0)
    }

    val modelHeightMm: Double get() = maxZ - minZ
    val maximumDisplacementMm: Double
        get() = relief.maxOf { abs(it.toDouble() * strength) }

    fun displacement(x: Double, y: Double, originalZ: Double): Double {
        if (uniformShift) {
            // Drape mode: every layer above the flat base is a scaled copy of
            // the surface shape (the original CurviSlicer curved solid). The
            // displacement grows linearly with height above the base.
            val usableHeight = modelHeightMm - flatBaseHeightMm
            if (usableHeight <= 1e-9 || originalZ <= minZ + flatBaseHeightMm) return 0.0
            return (originalZ - minZ - flatBaseHeightMm) * sampleRelief(x, y) * strength / usableHeight
        }
        val usableHeight = modelHeightMm - flatBaseHeightMm
        if (usableHeight <= 1e-9) return 0.0
        val fadeBand = usableHeight * (1.0 - fadeStartFraction)
        if (fadeBand <= 1e-9) return 0.0
        val u = ((originalZ - minZ - flatBaseHeightMm - usableHeight * fadeStartFraction) / fadeBand).coerceIn(0.0, 1.0)
        val weight = u * u * (3.0 - 2.0 * u)
        return sampleRelief(x, y) * strength * weight
    }

    fun flattenZ(x: Double, y: Double, originalZ: Double): Double {
        if (uniformShift) {
            val usableHeight = modelHeightMm - flatBaseHeightMm
            val baseZ = minZ + flatBaseHeightMm
            if (usableHeight <= 1e-9 || originalZ <= baseZ) return originalZ
            val scale = (usableHeight - sampleRelief(x, y) * strength) / usableHeight
            return baseZ + (originalZ - baseZ) * scale.coerceAtLeast(0.05)
        }
        return originalZ - displacement(x, y, originalZ)
    }

    fun unflattenZ(x: Double, y: Double, flatZ: Double): Double {
        val amplitude = sampleRelief(x, y) * strength
        if (uniformShift) {
            // Drape mode: invert the per-XY height scaling. Above the deformed
            // top continue linearly so Z-hops keep their clearance.
            val usableHeight = modelHeightMm - flatBaseHeightMm
            val baseZ = minZ + flatBaseHeightMm
            if (usableHeight <= 1e-9 || flatZ <= baseZ) return flatZ
            val scale = (usableHeight - amplitude) / usableHeight
            if (scale <= 0.05) return flatZ
            val mappedTopZ = maxZ - amplitude
            if (flatZ >= mappedTopZ) return flatZ + amplitude
            return baseZ + (flatZ - baseZ) / scale
        }
        val usableHeight = modelHeightMm - flatBaseHeightMm
        val baseZ = minZ + flatBaseHeightMm
        if (usableHeight <= 1e-9 || abs(amplitude) <= 1e-12 || flatZ <= baseZ) return flatZ

        // Above the deformed model top smoothstep has saturated to one. Continue
        // linearly so Z-hop, lift, and park clearance is preserved exactly.
        val mappedTopZ = maxZ - amplitude
        if (flatZ >= mappedTopZ) return flatZ + amplitude

        val fadeBand = usableHeight * (1.0 - fadeStartFraction)
        if (fadeBand <= 1e-9) return flatZ
        val startZ = baseZ + usableHeight * fadeStartFraction
        var original = (flatZ + amplitude).coerceIn(startZ, maxZ)
        repeat(8) {
            val u = ((original - startZ) / fadeBand).coerceIn(0.0, 1.0)
            val smooth = u * u * (3.0 - 2.0 * u)
            val derivative = 1.0 - amplitude * (6.0 * u * (1.0 - u) / fadeBand)
            val residual = original - amplitude * smooth - flatZ
            if (abs(residual) <= 1e-9) return original
            require(derivative > 0.0) { "CurviSlicer field is not invertible at X=$x, Y=$y, Z=$flatZ" }
            original = (original - residual / derivative).coerceIn(baseZ, maxZ)
        }
        val residual = flattenZ(x, y, original) - flatZ
        require(abs(residual) <= 1e-6) {
            "CurviSlicer inverse did not converge at X=$x, Y=$y, Z=$flatZ"
        }
        return original
    }

    fun sampleRelief(x: Double, y: Double): Double {
        val gx = ((x - minX) / (maxX - minX) * (columns - 1)).coerceIn(0.0, (columns - 1).toDouble())
        val gy = ((y - minY) / (maxY - minY) * (rows - 1)).coerceIn(0.0, (rows - 1).toDouble())
        val x0 = floor(gx).toInt().coerceIn(0, columns - 1)
        val y0 = floor(gy).toInt().coerceIn(0, rows - 1)
        val x1 = min(x0 + 1, columns - 1)
        val y1 = min(y0 + 1, rows - 1)
        val tx = gx - x0
        val ty = gy - y0
        val a = relief[y0 * columns + x0].toDouble()
        val b = relief[y0 * columns + x1].toDouble()
        val c = relief[y1 * columns + x0].toDouble()
        val d = relief[y1 * columns + x1].toDouble()
        return (a + (b - a) * tx) + ((c + (d - c) * tx) - (a + (b - a) * tx)) * ty
    }
}
