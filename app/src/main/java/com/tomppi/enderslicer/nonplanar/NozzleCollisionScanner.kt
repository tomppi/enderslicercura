package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.engine.GcodeCommand
import com.tomppi.enderslicer.engine.GcodeModalState
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
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
 * 1. Nozzle cone: apex at the tip, widening at the nozzle taper angle
 *    measured from horizontal (75 degrees = thin cone) up to the protrusion
 *    height.
 * 2. Heating block frustum: from the nozzle/block junction the free space
 *    widens at the SAME measured clearance angle (from horizontal, 90 = up)
 *    from the block footprint (centered on the measured nozzle-axis offset)
 *    up to the holder height.
 * 3. Cutoff level: above the holding-object height the whole build plate
 *    is a no-go zone - anything protruding above it warns, regardless of
 *    horizontal distance.
 */
internal object NozzleCollisionScanner {
    private const val FINE_CELL_SIZE_MM = 0.25
    private const val COARSE_CELL_SIZE_MM = 1.0
    private const val QUERY_STRIDE = 4
    private const val REPORTED_LAYERS_CAP = 6
    private const val SURFACE_MARGIN_MM = 0.05
    private const val SWEEP_STEP_MM = 1.0
    private const val MAX_SWEEP_STEPS = 400
    private const val MAX_GRID_CELLS = 2_000_000.0

    private class SurfaceGrid(
        val cellSizeMm: Double,
        val minX: Double,
        val minY: Double,
        val maxX: Double,
        val maxY: Double,
    ) {
        val columns: Int
        val rows: Int

        init {
            // Guard against corrupt coordinates: a stray point far outside the
            // build plate must fail loudly instead of allocating a runaway grid.
            val widthCells = ceil((maxX - minX) / cellSizeMm)
            val heightCells = ceil((maxY - minY) / cellSizeMm)
            require(
                widthCells.isFinite() && heightCells.isFinite() &&
                    widthCells >= 0.0 && heightCells >= 0.0 &&
                    widthCells * heightCells <= MAX_GRID_CELLS,
            ) { "Collision-sweep grid out of range: G-code coordinates span too large an area" }
            columns = (widthCells + 1.0).toInt().coerceAtLeast(1)
            rows = (heightCells + 1.0).toInt().coerceAtLeast(1)
        }
        private val maxZ = FloatArray(columns * rows) { Float.NEGATIVE_INFINITY }
        private val pointX = FloatArray(columns * rows)
        private val pointY = FloatArray(columns * rows)
        // A leaning-away wall keeps its low, near part closer to the nozzle
        // than the stored top: keep a second representative (the point nearest
        // to the cell centre) so those walls cannot hide below their top.
        private val nearestDistance = FloatArray(columns * rows) { Float.POSITIVE_INFINITY }
        private val nearestX = FloatArray(columns * rows)
        private val nearestY = FloatArray(columns * rows)
        private val nearestZ = FloatArray(columns * rows) { Float.NEGATIVE_INFINITY }

        fun deposit(x: Double, y: Double, z: Double) {
            val gx = floor((x - minX) / cellSizeMm).toInt().coerceIn(0, columns - 1)
            val gy = floor((y - minY) / cellSizeMm).toInt().coerceIn(0, rows - 1)
            val index = gy * columns + gx
            if (z > maxZ[index]) {
                maxZ[index] = z.toFloat()
                pointX[index] = x.toFloat()
                pointY[index] = y.toFloat()
            }
            val centreX = minX + (gx + 0.5) * cellSizeMm
            val centreY = minY + (gy + 0.5) * cellSizeMm
            val distance = hypot(x - centreX, y - centreY).toFloat()
            if (distance < nearestDistance[index]) {
                nearestDistance[index] = distance
                nearestX[index] = x.toFloat()
                nearestY[index] = y.toFloat()
                nearestZ[index] = z.toFloat()
            }
        }

        fun cellX(index: Int): Int = index % columns
        fun cellY(index: Int): Int = index / columns

        fun surfaceHeight(x: Double, y: Double): Double {
            val gx = floor((x - minX) / cellSizeMm).toInt().coerceIn(0, columns - 1)
            val gy = floor((y - minY) / cellSizeMm).toInt().coerceIn(0, rows - 1)
            return maxZ[gy * columns + gx].toDouble()
        }

        /** Both surface representatives of the cell, highest first. */
        fun surfacePointsAt(gx: Int, gy: Int): List<Triple<Double, Double, Double>> {
            if (gx !in 0 until columns || gy !in 0 until rows) return emptyList()
            val index = gy * columns + gx
            val result = ArrayList<Triple<Double, Double, Double>>(2)
            val z = maxZ[index].toDouble()
            if (z.isFinite() && maxZ[index] != Float.NEGATIVE_INFINITY) {
                result += Triple(pointX[index].toDouble(), pointY[index].toDouble(), z)
            }
            val nz = nearestZ[index].toDouble()
            if (nz.isFinite() && nearestZ[index] != Float.NEGATIVE_INFINITY) {
                val candidate = Triple(nearestX[index].toDouble(), nearestY[index].toDouble(), nz)
                if (candidate !in result) result += candidate
            }
            return result
        }

        fun isFiniteHeight(gx: Int, gy: Int): Boolean {
            if (gx !in 0 until columns || gy !in 0 until rows) return false
            return maxZ[gy * columns + gx].isFinite()
        }
    }

    fun scan(
        gcode: File,
        settings: NonPlanarSettings,
    ): NozzleCollisionAlert? {
        require(gcode.isFile && gcode.length() > 0L) { "Curved G-code is missing for the collision sweep" }
        require(settings.enabled) { "Non-planar printing must be enabled for the collision sweep" }
        val holderHeightMm = settings.holderHeightMm
        val protrusionMm = settings.nozzleProtrusionMm
        val blockHalfWidth = settings.heatingBlockWidthMm / 2.0
        val blockHalfDepth = settings.heatingBlockDepthMm / 2.0
        val blockOffsetX = settings.heatingBlockOffsetXmm
        val blockOffsetY = settings.heatingBlockOffsetYmm
        // Taper angle is measured from horizontal, so the cone widens from
        // vertical at the complementary angle: 75 degrees -> tan(15 degrees).
        val nozzleK = tan(Math.toRadians(90.0 - settings.nozzleAngleDegrees))
        // The clearance angle is also measured from horizontal: the frustum
        // widens from vertical at the complementary angle.
        val blockK = tan(Math.toRadians(90.0 - settings.nozzleClearanceAngleDegrees))

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
                if (modal.apply(command)) continue
                if (command.opcode == "G92") {
                    command.value('X')?.let { currentX = it }
                    command.value('Y')?.let { currentY = it }
                    command.value('Z')?.let { currentZ = it }
                    continue
                }
                if (command.opcode != "G0" && command.opcode != "G1") continue
                // Z-only moves (layer hops) still move the nozzle: track them
                // so the following layer is not swept at the previous height.
                val x = modal.position(currentX, command.value('X'))
                val y = modal.position(currentY, command.value('Y'))
                val z = modal.position(currentZ, command.value('Z'))
                currentX = x
                currentY = y
                currentZ = z
                if (!command.has('X') && !command.has('Y')) continue
                val sampled = moveIndex % QUERY_STRIDE == 0
                moveIndex++
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
                    // Both per-cell representatives are checked: the topmost
                    // point plus the one nearest the cell centre, so a wall
                    // that leans away from the nozzle cannot hide below its
                    // own crest.
                    for (point in grid.surfacePointsAt(nx, ny)) {
                    val (sx, sy, sz) = point
                    val dz = sz - tip.z
                    if (dz <= SURFACE_MARGIN_MM) continue
                    val dxMm = sx - tip.x
                    val dyMm = sy - tip.y
                    if (zone == 1) {
                        val allowed = dz * nozzleK
                        val intrusion = allowed - hypot(dxMm, dyMm)
                        if (intrusion > SURFACE_MARGIN_MM) worst = maxOf(worst, intrusion)
                    } else {
                        // The heating block only exists above the nozzle/block
                        // junction: surfaces at or below the protrusion height
                        // can only collide with the nozzle cone itself.
                        if (dz <= protrusionMm) continue
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
            }
            return worst
        }

        // Second pass in print order: sweep each sampled move against the
        // surface deposited so far, then deposit its own extrusion so a move
        // can never collide with material printed after it. The path from the
        // previously sampled position to this move's tip is checked in steps
        // of at most one millimetre so a long move can never hide a collision
        // in its middle (a straight travel or chord across a dome crest would
        // otherwise pass the endpoint-only check).
        var lastCheckedX = 0.0
        var lastCheckedY = 0.0
        var lastCheckedZ = 0.0
        for (move in moves) {
            if (move.sampled) {
                checkedMoves++
                val distance = hypot(move.x - lastCheckedX, move.y - lastCheckedY) +
                    abs(move.z - lastCheckedZ)
                // Chunk the path so the step cap never silently coarsens the
                // sampling on long moves.
                val steps = max(1, ceil(distance / SWEEP_STEP_MM).toInt())
                var moveViolation = 0.0
                var moveCutoffViolation = 0.0
                var stepBase = 0
                while (stepBase < steps) {
                    val stepEnd = minOf(stepBase + MAX_SWEEP_STEPS, steps)
                    for (step in (stepBase + 1)..stepEnd) {
                    val t = step.toDouble() / steps
                    val tip = Point(
                        lastCheckedX + (move.x - lastCheckedX) * t,
                        lastCheckedY + (move.y - lastCheckedY) * t,
                        lastCheckedZ + (move.z - lastCheckedZ) * t,
                    )

                    // Zone 3: the whole-plate cutoff above the holding object.
                    val cutoffDepth = globalMaxZ - tip.z - holderHeightMm
                    if (cutoffDepth > SURFACE_MARGIN_MM) {
                        moveCutoffViolation = maxOf(moveCutoffViolation, cutoffDepth)
                    }

                    // Zone 1: the nozzle cone, at fine resolution.
                    var violation = 0.0
                    for (radius in 0..fineRadiusCells) {
                        violation = maxOf(violation, sweepRing(fine, tip, radius, 1))
                    }

                    // Zone 2: the block frustum, at coarse resolution.
                    for (radius in 1..coarseRadiusCells) {
                        violation = maxOf(violation, sweepRing(coarse, tip, radius, 2))
                    }
                    moveViolation = maxOf(moveViolation, violation, moveCutoffViolation)
                    }
                    stepBase = stepEnd
                }
                lastCheckedX = move.x
                lastCheckedY = move.y
                lastCheckedZ = move.z
                if (moveCutoffViolation > SURFACE_MARGIN_MM) cutoffViolatingMoves++
                if (moveViolation > 0.0) {
                    violatingMoves++
                    maximumViolation = maxOf(maximumViolation, moveViolation)
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
