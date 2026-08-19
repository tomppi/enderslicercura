package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.engine.GcodeCommand
import com.tomppi.enderslicer.engine.GcodeModalState
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.tan

/**
 * Result of the post-slice nozzle collision sweep.
 */
data class NozzleCollisionAlert(
    val offendingLayers: Set<Int>,
    val maximumViolationMm: Double,
    val violatingMoves: Int,
    val checkedMoves: Int,
    val cutoffViolatingMoves: Int,
)

/**
 * Post-slice nozzle collision sweep for non-planar output.
 *
 * The collision volume is built from hot-end measurements taken once on the
 * printer (all tip-relative, Z up):
 * 1. Nozzle cone: apex at the tip, widening at the smaller cone angle (the
 *    nozzle taper) up to the protrusion height.
 * 2. Heating block frustum: from the nozzle/block junction the free space
 *    widens at the SAME measured clearance angle from the block footprint
 *    (centered on the measured nozzle-axis offset) up to the holder height.
 * 3. Cutoff level: above the holding-object height the whole build plate
 *    (plus a 30% margin) is a no-go zone - anything protruding above it
 *    warns, regardless of horizontal distance.
 */
internal object NozzleCollisionScanner {
    private const val FINE_CELL_SIZE_MM = 0.25
    private const val COARSE_CELL_SIZE_MM = 1.0
    private const val QUERY_STRIDE = 4
    private const val REPORTED_LAYERS_CAP = 6
    private const val SURFACE_MARGIN_MM = 0.05

    private class SurfaceGrid(
        val cellSizeMm: Double,
        val minX: Double,
        val minY: Double,
        val maxX: Double,
        val maxY: Double,
    ) {
        val columns = (ceil((maxX - minX) / cellSizeMm) + 1).toInt().coerceAtLeast(1)
        val rows = (ceil((maxY - minY) / cellSizeMm) + 1).toInt().coerceAtLeast(1)
        private val maxZ = FloatArray(columns * rows) { Float.NEGATIVE_INFINITY }
        private val pointX = FloatArray(columns * rows)
        private val pointY = FloatArray(columns * rows)

        fun deposit(x: Double, y: Double, z: Double) {
            val gx = floor((x - minX) / cellSizeMm).toInt().coerceIn(0, columns - 1)
            val gy = floor((y - minY) / cellSizeMm).toInt().coerceIn(0, rows - 1)
            val index = gy * columns + gx
            if (z > maxZ[index]) {
                maxZ[index] = z.toFloat()
                pointX[index] = x.toFloat()
                pointY[index] = y.toFloat()
            }
        }

        fun cellX(index: Int): Int = index % columns
        fun cellY(index: Int): Int = index / columns

        fun surfaceHeight(x: Double, y: Double): Double {
            val gx = floor((x - minX) / cellSizeMm).toInt().coerceIn(0, columns - 1)
            val gy = floor((y - minY) / cellSizeMm).toInt().coerceIn(0, rows - 1)
            return maxZ[gy * columns + gx].toDouble()
        }

        fun highestPointAt(gx: Int, gy: Int): Triple<Double, Double, Double>? {
            if (gx !in 0 until columns || gy !in 0 until rows) return null
            val index = gy * columns + gx
            val z = maxZ[index].toDouble()
            if (!z.isFinite() || z <= 0.0 && maxZ[index] == Float.NEGATIVE_INFINITY) return null
            return Triple(pointX[index].toDouble(), pointY[index].toDouble(), z)
        }

        fun isFiniteHeight(gx: Int, gy: Int): Boolean {
            if (gx !in 0 until columns || gy !in 0 until rows) return false
            return maxZ[gy * columns + gx].isFinite()
        }
    }

    fun scan(
        gcode: File,
        settings: NonPlanarSettings,
        buildPlateHalfWidthMm: Double,
        buildPlateHalfDepthMm: Double,
    ): NozzleCollisionAlert? {
        require(gcode.isFile && gcode.length() > 0L) { "Curved G-code is missing for the collision sweep" }
        require(settings.enabled) { "CurviSlicer must be enabled for the collision sweep" }
        val holderHeightMm = settings.holderHeightMm
        val protrusionMm = settings.nozzleProtrusionMm
        val blockHalfWidth = settings.heatingBlockWidthMm / 2.0
        val blockHalfDepth = settings.heatingBlockDepthMm / 2.0
        val blockOffsetX = settings.heatingBlockOffsetXmm
        val blockOffsetY = settings.heatingBlockOffsetYmm
        val nozzleK = tan(Math.toRadians(settings.nozzleAngleDegrees))
        val blockK = tan(Math.toRadians(settings.nozzleClearanceAngleDegrees))

        data class Point(val x: Double, val y: Double, val z: Double)
        data class Move(
            val x: Double,
            val y: Double,
            val z: Double,
            val extrudes: Boolean,
            val layer: Int?,
            val sampled: Boolean,
        )
        val moves = ArrayList<Move>()
        val modal = GcodeModalState()
        var moveIndex = 0
        var currentLayer: Int? = null
        var currentX = 0.0
        var currentY = 0.0
        var currentZ = 0.0
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY

        gcode.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.startsWith(";LAYER:")) {
                    currentLayer = line.substringAfter(":").trim().toIntOrNull()
                    continue
                }
                val command = GcodeCommand.parse(line) ?: continue
                if (command.opcode != "G0" && command.opcode != "G1") continue
                if (!command.has('X') && !command.has('Y')) continue
                val sampled = moveIndex % QUERY_STRIDE == 0
                moveIndex++
                val x = modal.position(currentX, command.value('X'))
                val y = modal.position(currentY, command.value('Y'))
                val z = modal.position(currentZ, command.value('Z'))
                currentX = x
                currentY = y
                currentZ = z
                modal.apply(command)
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
                moves += Move(x, y, z, command.has('E'), currentLayer, sampled)
            }
        }
        if (moves.none { it.extrudes }) return null

        // A fine grid resolves the sub-millimetre nozzle cone, a coarse one
        // bounds the heating-block frustum sweep. Each cell remembers the
        // exact position of its highest surface point so distance checks use
        // true geometry instead of cell-centre approximations. Both grids grow
        // in print order so only already-printed material can collide.
        val fine = SurfaceGrid(FINE_CELL_SIZE_MM, minX, minY, maxX, maxY)
        val coarse = SurfaceGrid(COARSE_CELL_SIZE_MM, minX, minY, maxX, maxY)

        var violatingMoves = 0
        var cutoffViolatingMoves = 0
        var maximumViolation = 0.0
        var checkedMoves = 0
        var globalMaxZ = Double.NEGATIVE_INFINITY
        val offendingLayers = sortedSetOf<Int>()
        val fineRadiusCells = (ceil(protrusionMm * nozzleK / FINE_CELL_SIZE_MM) + 1).toInt().coerceAtLeast(1)
        val coarseRadiusCells = (ceil(
            (maxOf(blockHalfWidth + abs(blockOffsetX), blockHalfDepth + abs(blockOffsetY)) +
                (holderHeightMm - protrusionMm) * blockK) / COARSE_CELL_SIZE_MM,
        ) + 1).toInt().coerceAtLeast(1)

        fun sweepRing(
            grid: SurfaceGrid,
            tip: Point,
            radius: Int,
            zone: Int,
        ): Double {
            var worst = 0.0
            val gx = floor((tip.x - minX) / grid.cellSizeMm).toInt().coerceIn(0, grid.columns - 1)
            val gy = floor((tip.y - minY) / grid.cellSizeMm).toInt().coerceIn(0, grid.rows - 1)
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    if (maxOf(abs(dx), abs(dy)) != radius) continue
                    val nx = gx + dx
                    val ny = gy + dy
                    val highest = grid.highestPointAt(nx, ny) ?: continue
                    val (sx, sy, sz) = highest
                    val dz = sz - tip.z
                    if (dz <= SURFACE_MARGIN_MM) continue
                    val dxMm = sx - tip.x
                    val dyMm = sy - tip.y
                    if (zone == 1) {
                        val allowed = dz * nozzleK
                        val intrusion = allowed - hypot(dxMm, dyMm)
                        if (intrusion > SURFACE_MARGIN_MM) worst = maxOf(worst, intrusion)
                    } else {
                        val rise = dz - protrusionMm
                        val limitX = blockHalfWidth + rise * blockK
                        val limitY = blockHalfDepth + rise * blockK
                        val marginX = limitX - abs(dxMm - blockOffsetX)
                        val marginY = limitY - abs(dyMm - blockOffsetY)
                        if (marginX > SURFACE_MARGIN_MM && marginY > SURFACE_MARGIN_MM) {
                            worst = maxOf(worst, minOf(marginX, marginY))
                        }
                    }
                }
            }
            return worst
        }

        // Second pass in print order: sweep each sampled move against the
        // surface deposited so far, then deposit its own extrusion so a move
        // can never collide with material printed after it.
        for (move in moves) {
            if (move.sampled) {
                checkedMoves++
                val tip = Point(move.x, move.y, move.z)
                var violation = 0.0

                // Zone 3: the whole-plate cutoff above the holding object.
                val cutoffDepth = globalMaxZ - tip.z - holderHeightMm
                if (cutoffDepth > SURFACE_MARGIN_MM) {
                    cutoffViolatingMoves++
                    violation = maxOf(violation, cutoffDepth)
                }

                // Zone 1: the nozzle cone, at fine resolution.
                for (radius in 0..fineRadiusCells) {
                    violation = maxOf(violation, sweepRing(fine, tip, radius, 1))
                }

                // Zone 2: the block frustum, at coarse resolution.
                for (radius in 1..coarseRadiusCells) {
                    violation = maxOf(violation, sweepRing(coarse, tip, radius, 2))
                }

                if (violation > 0.0) {
                    violatingMoves++
                    maximumViolation = maxOf(maximumViolation, violation)
                    if (move.layer != null && move.layer >= 0 && offendingLayers.size < REPORTED_LAYERS_CAP) {
                        offendingLayers += move.layer
                    }
                }
            }
            if (move.extrudes) {
                fine.deposit(move.x, move.y, move.z)
                coarse.deposit(move.x, move.y, move.z)
                globalMaxZ = maxOf(globalMaxZ, move.z)
            }
        }

        if (violatingMoves == 0) return null
        return NozzleCollisionAlert(
            offendingLayers = offendingLayers,
            maximumViolationMm = maximumViolation,
            violatingMoves = violatingMoves,
            checkedMoves = checkedMoves,
            cutoffViolatingMoves = cutoffViolatingMoves,
        )
    }
}
