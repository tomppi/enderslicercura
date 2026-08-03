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
        val u = ((originalZ - minZ - flatBaseHeightMm) / (modelHeightMm - flatBaseHeightMm))
            .coerceIn(0.0, 1.0)
        val weight = u * u * (3.0 - 2.0 * u)
        return sampleRelief(x, y) * strength * weight
    }

    fun flattenZ(x: Double, y: Double, originalZ: Double): Double =
        originalZ - displacement(x, y, originalZ)

    fun unflattenZ(x: Double, y: Double, flatZ: Double): Double {
        val amplitude = sampleRelief(x, y) * strength
        val usableHeight = modelHeightMm - flatBaseHeightMm
        val baseZ = minZ + flatBaseHeightMm
        if (usableHeight <= 1e-9 || abs(amplitude) <= 1e-12 || flatZ <= baseZ) return flatZ

        var original = (flatZ + amplitude).coerceIn(
            minZ - maximumDisplacementMm,
            maxZ + maximumDisplacementMm,
        )
        repeat(5) {
            val u = ((original - baseZ) / usableHeight).coerceIn(0.0, 1.0)
            val smooth = u * u * (3.0 - 2.0 * u)
            val derivative = 1.0 - amplitude * (6.0 * u * (1.0 - u) / usableHeight)
            val residual = original - amplitude * smooth - flatZ
            if (abs(residual) <= 1e-8) return@repeat
            original = (original - residual / derivative.coerceAtLeast(0.20)).coerceIn(
                minZ - maximumDisplacementMm,
                maxZ + maximumDisplacementMm,
            )
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
