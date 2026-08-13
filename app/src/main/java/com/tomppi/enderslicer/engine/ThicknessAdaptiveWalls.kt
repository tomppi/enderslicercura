package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.StlMeshWriter
import com.tomppi.enderslicer.viewer.StlParser
import com.tomppi.enderslicer.viewer.StlSliceTransform
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One nested modifier volume and the wall reinforcement it applies in its region. */
data class AdaptiveWallModifier(
    val wallLineCount: Int,
    val wallFlowPercent: Double,
    val file: File,
)

/**
 * Automatic wall reinforcement by build-direction material column height, gated by the
 * local cross-sectional thickness so thin-but-tall features (ribs, fins) are left alone.
 * Every point whose column is tall is a reinforcement candidate, but only points whose
 * cross-section is genuinely fat (2x distance to the nearest wall) count as thick.
 * Modifier volumes are emitted as nested iso-surface shells; slicing reuses the Smart
 * Infill infill_mesh mechanism with wall overrides instead of density overrides.
 */
object ThicknessAdaptiveWalls {
    private const val MAX_EXTRA_BANDS = 3
    private const val EXTRA_WALLS_PER_BAND = 2
    private const val MIN_CROSS_SECTION_MM = 6.0
    private const val MAX_GRID_VOXELS = 1_800_000
    private const val GRID_PAD_MM = 1.5
    private const val CHAMFER_AXIS = 3.0f
    private const val CHAMFER_DIAGONAL = 4.0f
    private const val CHAMFER_KNIGHT = 5.0f
    private const val RAY_EPSILON = 1e-6f
    private const val BEND_CORNER_TURN_RAD = 0.785f
    private const val PI_F = 3.1415927f
    private const val TWO_PI_F = 6.2831855f

    fun generate(
        modelFile: File,
        settings: SlicerSettings,
        destination: File,
        transform: StlSliceTransform? = null,
    ): List<AdaptiveWallModifier> {
        require(destination.mkdirs() || destination.isDirectory) {
            "Unable to create the adaptive-walls staging directory"
        }
        require(settings.thicknessAdaptiveWallsFlowPercent in 100.0..200.0) {
            "Adaptive wall flow must be between 100% and 200%"
        }
        val mesh = StlParser.parse(modelFile, modelFile.name, MeshTriangleLimits.current())
        val (mask, nx, ny, nz, pitch, originX, originY, originZ) = voxelize(mesh)
        throwIfInterrupted()

        val columnHeight = zColumnHeight(mask, nx, ny, nz)
        val surfaceDistance = chamfer(mask, nx, ny, nz)
        val inscribed = FloatArray(nx * ny * nz) { i ->
            2.0f * surfaceDistance[i] * pitch / CHAMFER_AXIS
        }
        val thickness = FloatArray(nx * ny * nz) { i ->
            if (inscribed[i] >= MIN_CROSS_SECTION_MM.toFloat()) {
                columnHeight[i] * pitch
            } else {
                inscribed[i]
            }
        }

        val baseWalls = settings.wallLineCount.coerceAtLeast(1)
        val triangles = triangleList(mesh)
        val layerSegments = layerOutlineSegments(triangles, nz, originZ, pitch)
        val coarseLayerMax = FloatArray(nz)
        for (iz in 0 until nz) {
            var layerMax = 0f
            for (iy in 0 until ny) {
                for (ix in 0 until nx) {
                    val i = (iz * ny + iy) * nx + ix
                    if (mask[i] && thickness[i] > layerMax) layerMax = thickness[i]
                }
            }
            coarseLayerMax[iz] = layerMax
        }

        val layerHeight = settings.layerHeightMm.toFloat()
        val initialLayerHeight = settings.initialLayerHeightMm.toFloat()
        val zMin = mesh.bounds.minZ
        val zMax = mesh.bounds.maxZ
        val boundaries = ArrayList<Float>()
        boundaries.add(zMin)
        var boundaryZ = zMin + initialLayerHeight
        boundaries.add(boundaryZ)
        while (boundaryZ < zMax) {
            boundaryZ += layerHeight
            boundaries.add(boundaryZ)
        }
        val fineCount = boundaries.size - 1
        val bendRadiusMm = settings.thicknessAdaptiveWallsBendRadiusMm.toFloat()
        val fineWallCount = IntArray(fineCount)
        for (k in 0 until fineCount) {
            val z = (boundaries[k] + boundaries[k + 1]) * 0.5f
            val iz = ((z - originZ) / pitch).toInt().coerceIn(0, nz - 1)
            val layerMax = coarseLayerMax[iz]
            val minRadius = minBendRadius(layerSegments[iz])
            val hasBend = layerMax > 0f && minRadius < bendRadiusMm
            fineWallCount[k] = if (layerMax > 0f) {
                if (hasBend) {
                    val tightness = 1.0f - minRadius / bendRadiusMm
                    val band = ceil(MAX_EXTRA_BANDS * tightness).toInt().coerceIn(1, MAX_EXTRA_BANDS)
                    baseWalls + EXTRA_WALLS_PER_BAND * band
                } else {
                    baseWalls
                }
            } else {
                0
            }
        }

        val modifiers = mutableListOf<AdaptiveWallModifier>()
        var order = 0
        var bandStart = 0
        while (bandStart < fineCount) {
            val wallCount = if (fineWallCount[bandStart] == 0) baseWalls else fineWallCount[bandStart]
            var bandEnd = bandStart
            while (bandEnd + 1 < fineCount) {
                val next = fineWallCount[bandEnd + 1]
                if (next == wallCount || next == 0) {
                    bandEnd++
                } else {
                    break
                }
            }
            val file = File(destination, "adaptive-walls-${++order}-${wallCount}walls.stl")
            val midK = (bandStart + bandEnd) / 2
            val midZ = (boundaries[midK] + boundaries[midK + 1]) * 0.5f
            val midIz = ((midZ - originZ) / pitch).toInt().coerceIn(0, nz - 1)
            val slabZ0 = boundaries[bandStart] - 0.1f
            val slabZ1 = boundaries[bandEnd + 1] + 0.1f
            slabModifierStl(
                layerSegments[midIz],
                slabZ0,
                slabZ1,
                transform,
                file,
            )
            modifiers += AdaptiveWallModifier(wallCount, settings.thicknessAdaptiveWallsFlowPercent, file)
            bandStart = bandEnd + 1
            throwIfInterrupted()
        }
        return modifiers
    }

    private fun slabModifierStl(
        segments: List<FloatArray>,
        z0: Float,
        z1: Float,
        transform: StlSliceTransform?,
        file: File,
    ) {
        val loops = chainSegments(segments)
        require(loops.isNotEmpty()) { "Adaptive wall band produced no cross-section outline" }
        var best = loops[0]
        for (loop in loops) if (loop.size > best.size) best = loop
        val n = best.size / 2
        require(n >= 3) { "Adaptive wall band cross-section is degenerate" }
        val ox = FloatArray(n) { best[it * 2] }
        val oy = FloatArray(n) { best[it * 2 + 1] }
        val vertices = mutableListOf<Float>()
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ax = ox[i]; val ay = oy[i]
            val bx = ox[j]; val by = oy[j]
            vertices.add(ax); vertices.add(ay); vertices.add(z0)
            vertices.add(bx); vertices.add(by); vertices.add(z0)
            vertices.add(bx); vertices.add(by); vertices.add(z1)
            vertices.add(ax); vertices.add(ay); vertices.add(z0)
            vertices.add(bx); vertices.add(by); vertices.add(z1)
            vertices.add(ax); vertices.add(ay); vertices.add(z1)
        }
        val caps = earClip(ox, oy)
        for (tri in caps) {
            val a = tri[0]; val b = tri[1]; val c = tri[2]
            vertices.add(ox[a]); vertices.add(oy[a]); vertices.add(z0)
            vertices.add(ox[c]); vertices.add(oy[c]); vertices.add(z0)
            vertices.add(ox[b]); vertices.add(oy[b]); vertices.add(z0)
            vertices.add(ox[a]); vertices.add(oy[a]); vertices.add(z1)
            vertices.add(ox[b]); vertices.add(oy[b]); vertices.add(z1)
            vertices.add(ox[c]); vertices.add(oy[c]); vertices.add(z1)
        }
        require(vertices.isNotEmpty() && vertices.size % 9 == 0) {
            "Adaptive wall band produced no closed shell"
        }
        writeShellStl(vertices.toFloatArray(), transform, file)
    }

    private fun earClip(ox: FloatArray, oy: FloatArray): List<IntArray> {
        val idx = ArrayList<Int>(ox.size)
        for (i in ox.indices) idx.add(i)
        val tris = mutableListOf<IntArray>()
        while (idx.size > 3) {
            var clipped = false
            for (i in idx.indices) {
                val a = idx[(i - 1 + idx.size) % idx.size]
                val b = idx[i]
                val c = idx[(i + 1) % idx.size]
                val cross = (ox[b] - ox[a]) * (oy[c] - oy[b]) - (oy[b] - oy[a]) * (ox[c] - ox[b])
                if (cross <= 1e-6f) continue
                var ok = true
                for (j in idx) {
                    if (j == a || j == b || j == c) continue
                    val d1 = (ox[b] - ox[a]) * (oy[j] - oy[a]) - (oy[b] - oy[a]) * (ox[j] - ox[a])
                    val d2 = (ox[c] - ox[b]) * (oy[j] - oy[b]) - (oy[c] - oy[b]) * (ox[j] - ox[b])
                    val d3 = (ox[a] - ox[c]) * (oy[j] - oy[c]) - (oy[a] - oy[c]) * (ox[j] - ox[c])
                    val hasNeg = d1 < -1e-6f || d2 < -1e-6f || d3 < -1e-6f
                    val hasPos = d1 > 1e-6f || d2 > 1e-6f || d3 > 1e-6f
                    if (!(hasNeg && hasPos)) {
                        ok = false
                        break
                    }
                }
                if (ok) {
                    tris += intArrayOf(a, b, c)
                    idx.removeAt(i)
                    clipped = true
                    break
                }
            }
            if (!clipped) break
        }
        if (idx.size == 3) tris += intArrayOf(idx[0], idx[1], idx[2])
        return tris
    }


    private fun voxelize(mesh: StlMesh): VoxelGrid {
        val b = mesh.bounds
        val width = (b.maxX - b.minX) + 2.0f * GRID_PAD_MM.toFloat()
        val depth = (b.maxY - b.minY) + 2.0f * GRID_PAD_MM.toFloat()
        val height = (b.maxZ - b.minZ) + 2.0f * GRID_PAD_MM.toFloat()
        val targetN = max(1, ceil(Math.cbrt(MAX_GRID_VOXELS.toDouble())).toInt())
        val pitch = max(width, max(depth, height)) / targetN
        val nx = ceil(width / pitch).toInt() + 1
        val ny = ceil(depth / pitch).toInt() + 1
        val nz = ceil(height / pitch).toInt() + 1
        require(nx.toLong() * ny * nz <= MAX_GRID_VOXELS * 2L) { "Adaptive walls grid is too large" }
        val originX = b.minX - GRID_PAD_MM.toFloat()
        val originY = b.minY - GRID_PAD_MM.toFloat()
        val originZ = b.minZ - GRID_PAD_MM.toFloat()

        val triangles = triangleList(mesh)
        val cellSize = (4 * pitch).coerceAtLeast(1e-3f)
        val cellsY = max(1, ceil(depth / cellSize).toInt())
        val cellsZ = max(1, ceil(height / cellSize).toInt())
        val occupancy = buildCellOccupancy(triangles, cellsY, cellsZ, cellSize, originY, originZ)

        val mask = BooleanArray(nx * ny * nz)
        val cx = FloatArray(nx) { ix -> originX + (ix + 0.5f) * pitch }
        val cy = FloatArray(ny) { iy -> originY + (iy + 0.5f) * pitch }
        val cz = FloatArray(nz) { iz -> originZ + (iz + 0.5f) * pitch }
        val crossingX = FloatArray(triangles.size)
        for (iz in 0 until nz) {
            for (iy in 0 until ny) {
                val cyI = min(cellsY - 1, max(0, ((cy[iy] - originY) / cellSize).toInt()))
                val czI = min(cellsZ - 1, max(0, ((cz[iz] - originZ) / cellSize).toInt()))
                val candidates = occupancy[cyI * cellsZ + czI]
                val py = cy[iy]
                val pz = cz[iz]
                var crossingCount = 0
                for (ci in candidates) {
                    val tri = triangles[ci]
                    val ax = tri[0]; val ay = tri[1]; val az = tri[2]
                    val bx = tri[3]; val by = tri[4]; val bz = tri[5]
                    val cx2 = tri[6]; val cy2 = tri[7]; val cz2 = tri[8]
                    if (!pointInTriangle2D(py, pz, ay, az, by, bz, cy2, cz2)) continue
                    val ux = bx - ax; val uy = by - ay; val uz = bz - az
                    val vx = cx2 - ax; val vy = cy2 - ay; val vz = cz2 - az
                    val normalX = uy * vz - uz * vy
                    if (kotlin.math.abs(normalX) < 1e-12f) continue
                    val normalY = uz * vx - ux * vz
                    val normalZ = ux * vy - uy * vx
                    val xAtPlane = ax - (normalY * (py - ay) + normalZ * (pz - az)) / normalX
                    crossingX[crossingCount++] = xAtPlane
                }
                crossingX.sort(0, crossingCount)
                var xIndex = 0
                var inside = false
                var cross = 0
                while (xIndex < nx) {
                    while (cross < crossingCount && crossingX[cross] <= cx[xIndex]) {
                        inside = !inside
                        cross++
                    }
                    mask[(iz * ny + iy) * nx + xIndex] = inside
                    xIndex++
                }
            }
            throwIfInterrupted()
        }
        return VoxelGrid(mask, nx, ny, nz, pitch, originX, originY, originZ)
    }

    private fun pointInTriangle2D(
        px: Float,
        pz: Float,
        ax: Float,
        az: Float,
        bx: Float,
        bz: Float,
        cx: Float,
        cz: Float,
    ): Boolean {
        val d1 = (pz - az) * (bx - ax) - (px - ax) * (bz - az)
        val d2 = (pz - bz) * (cx - bx) - (px - bx) * (cz - bz)
        val d3 = (pz - cz) * (ax - cx) - (px - cx) * (az - cz)
        val hasNegative = d1 < 0f || d2 < 0f || d3 < 0f
        val hasPositive = d1 > 0f || d2 > 0f || d3 > 0f
        return !(hasNegative && hasPositive)
    }

    private fun layerOutlineSegments(
        triangles: List<FloatArray>,
        nz: Int,
        originZ: Float,
        pitch: Float,
    ): Array<MutableList<FloatArray>> {
        val layers = Array(nz) { mutableListOf<FloatArray>() }
        for (tri in triangles) {
            val zMin = min(tri[2], min(tri[5], tri[8]))
            val zMax = max(tri[2], max(tri[5], tri[8]))
            val izStart = ((zMin - originZ) / pitch).toInt().coerceAtLeast(0)
            val izEnd = ((zMax - originZ) / pitch).toInt().coerceAtMost(nz - 1)
            for (iz in izStart..izEnd) {
                val z = originZ + (iz + 0.5f) * pitch
                if (z <= zMin || z >= zMax) continue
                var count = 0
                val points = FloatArray(4)
                fun edgeCross(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float) {
                    if ((z1 - z) * (z2 - z) < 0f && count < 2) {
                        val t = (z - z1) / (z2 - z1)
                        points[count * 2] = x1 + t * (x2 - x1)
                        points[count * 2 + 1] = y1 + t * (y2 - y1)
                        count++
                    }
                }
                edgeCross(tri[0], tri[1], tri[2], tri[3], tri[4], tri[5])
                edgeCross(tri[3], tri[4], tri[5], tri[6], tri[7], tri[8])
                edgeCross(tri[6], tri[7], tri[8], tri[0], tri[1], tri[2])
                if (count == 2) layers[iz] += points
            }
        }
        return layers
    }

    private fun minBendRadius(segments: List<FloatArray>): Float {
        if (segments.isEmpty()) return Float.POSITIVE_INFINITY
        var minRadius = Float.POSITIVE_INFINITY
        for (loop in chainSegments(segments)) {
            val n = loop.size / 2
            if (n < 4) continue
            for (i in 0 until n) {
                val ax = loop[((i - 1 + n) % n) * 2]
                val ay = loop[((i - 1 + n) % n) * 2 + 1]
                val bx = loop[i * 2]
                val by = loop[i * 2 + 1]
                val cx = loop[((i + 1) % n) * 2]
                val cy = loop[((i + 1) % n) * 2 + 1]
                val turn = turnAngleRad(ax, ay, bx, by, cx, cy)
                if (abs(turn) > BEND_CORNER_TURN_RAD) continue
                val la = hypot(bx - cx, by - cy)
                val lb = hypot(ax - cx, ay - cy)
                val lc = hypot(ax - bx, ay - by)
                val halfPerimeter = (la + lb + lc) * 0.5f
                val areaSquared = halfPerimeter * (halfPerimeter - la) * (halfPerimeter - lb) * (halfPerimeter - lc)
                if (areaSquared <= 1e-12f) continue
                val radius = (la * lb * lc) / (4f * sqrt(areaSquared))
                if (radius < minRadius) minRadius = radius
            }
        }
        return minRadius
    }

    private fun turnAngleRad(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float {
        val a1 = atan2(by - ay, bx - ax)
        val a2 = atan2(cy - by, cx - bx)
        var turn = a2 - a1
        while (turn > PI_F) turn -= TWO_PI_F
        while (turn < -PI_F) turn += TWO_PI_F
        return turn
    }

    private fun chainSegments(segments: List<FloatArray>): List<FloatArray> {
        data class Key(val x: Int, val y: Int)
        fun key(x: Float, y: Float) = Key((x * 100f).roundToInt(), (y * 100f).roundToInt())
        val endToSeg = HashMap<Key, MutableList<Int>>()
        segments.forEachIndexed { index, s ->
            endToSeg.getOrPut(key(s[0], s[1])) { mutableListOf() } += index
            endToSeg.getOrPut(key(s[2], s[3])) { mutableListOf() } += index
        }
        val used = BooleanArray(segments.size)
        val loops = mutableListOf<FloatArray>()
        for (start in segments.indices) {
            if (used[start]) continue
            used[start] = true
            val loop = mutableListOf<Float>()
            val startKey = key(segments[start][0], segments[start][1])
            var currentKey = key(segments[start][2], segments[start][3])
            loop += segments[start][0]
            loop += segments[start][1]
            loop += segments[start][2]
            loop += segments[start][3]
            var guard = 0
            while (guard++ <= segments.size) {
                if (currentKey == startKey) break
                val candidates = endToSeg[currentKey] ?: break
                var next = -1
                for (ci in candidates) {
                    if (!used[ci]) {
                        next = ci
                        break
                    }
                }
                if (next == -1) break
                used[next] = true
                val s = segments[next]
                if (key(s[0], s[1]) == currentKey) {
                    loop += s[2]
                    loop += s[3]
                    currentKey = key(s[2], s[3])
                } else {
                    loop += s[0]
                    loop += s[1]
                    currentKey = key(s[0], s[1])
                }
            }
            loops += loop.toFloatArray()
        }
        return loops
    }

    private fun triangleList(mesh: StlMesh): List<FloatArray> {
        val v = mesh.interleavedVertices
        val count = mesh.triangleCount
        val out = ArrayList<FloatArray>(count)
        for (t in 0 until count) {
            val b = t * 18
            out += floatArrayOf(
                v[b], v[b + 1], v[b + 2],
                v[b + 6], v[b + 7], v[b + 8],
                v[b + 12], v[b + 13], v[b + 14],
            )
        }
        return out
    }

    private fun buildCellOccupancy(
        triangles: List<FloatArray>,
        cellsY: Int,
        cellsZ: Int,
        cellSize: Float,
        originY: Float,
        originZ: Float,
    ): Array<MutableList<Int>> {
        val cells = Array(cellsY * cellsZ) { mutableListOf<Int>() }
        triangles.forEachIndexed { index, tri ->
            var minY = Float.POSITIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            var minZ = Float.POSITIVE_INFINITY
            var maxZ = Float.NEGATIVE_INFINITY
            for (k in 0 until 3) {
                minY = min(minY, tri[k * 3 + 1])
                maxY = max(maxY, tri[k * 3 + 1])
                minZ = min(minZ, tri[k * 3 + 2])
                maxZ = max(maxZ, tri[k * 3 + 2])
            }
            val y0 = cellIndex(minY - originY, cellSize, cellsY)
            val y1 = cellIndex(maxY - originY, cellSize, cellsY)
            val z0 = cellIndex(minZ - originZ, cellSize, cellsZ)
            val z1 = cellIndex(maxZ - originZ, cellSize, cellsZ)
            for (cy in y0..y1) {
                for (cz in z0..z1) {
                    cells[cy * cellsZ + cz] += index
                }
            }
        }
        return cells
    }

    private fun cellIndex(offset: Float, cellSize: Float, cellCount: Int): Int =
        min(cellCount - 1, max(0, (offset / cellSize).toInt()))

    private fun zColumnHeight(mask: BooleanArray, nx: Int, ny: Int, nz: Int): FloatArray {
        val fromStart = FloatArray(nx * ny * nz)
        for (iz in 0 until nz) {
            for (iy in 0 until ny) {
                for (ix in 0 until nx) {
                    val i = (iz * ny + iy) * nx + ix
                    if (mask[i]) {
                        val below = if (iz > 0) fromStart[((iz - 1) * ny + iy) * nx + ix] else 0f
                        fromStart[i] = below + 1f
                    }
                }
            }
        }
        val total = FloatArray(nx * ny * nz)
        for (iz in 0 until nz) {
            for (iy in 0 until ny) {
                for (ix in 0 until nx) {
                    val i = (iz * ny + iy) * nx + ix
                    val isEnd = mask[i] && (iz == nz - 1 || !mask[((iz + 1) * ny + iy) * nx + ix])
                    if (isEnd) total[i] = fromStart[i]
                }
            }
        }
        for (iz in nz - 2 downTo 0) {
            for (iy in 0 until ny) {
                for (ix in 0 until nx) {
                    val i = (iz * ny + iy) * nx + ix
                    if (mask[i]) {
                        val above = total[((iz + 1) * ny + iy) * nx + ix]
                        if (above > total[i]) total[i] = above
                    }
                }
            }
        }
        return total
    }

    private fun chamfer(mask: BooleanArray, nx: Int, ny: Int, nz: Int): FloatArray {
        val dist = FloatArray(nx * ny * nz) { if (mask[it]) Float.POSITIVE_INFINITY else 0f }
        val dirs = arrayOf(
            intArrayOf(1, 0, 0, CHAMFER_AXIS.toInt()),
            intArrayOf(0, 1, 0, CHAMFER_AXIS.toInt()),
            intArrayOf(0, 0, 1, CHAMFER_AXIS.toInt()),
            intArrayOf(1, 1, 0, CHAMFER_DIAGONAL.toInt()),
            intArrayOf(1, 0, 1, CHAMFER_DIAGONAL.toInt()),
            intArrayOf(0, 1, 1, CHAMFER_DIAGONAL.toInt()),
            intArrayOf(1, 1, 1, CHAMFER_KNIGHT.toInt()),
            intArrayOf(1, 1, -1, CHAMFER_KNIGHT.toInt()),
        )
        for (iz in 0 until nz) {
            for (iy in 0 until ny) {
                for (ix in 0 until nx) {
                    val i = (iz * ny + iy) * nx + ix
                    var best = dist[i]
                    for (d in dirs) {
                        val pz = iz - d[0]; val py = iy - d[1]; val px = ix - d[2]
                        if (pz >= 0 && py >= 0 && px >= 0 && pz < nz && py < ny && px < nx) {
                            val neighbor = dist[(pz * ny + py) * nx + px]
                            if (neighbor.isFinite()) {
                                val candidate = neighbor + d[3]
                                if (candidate < best) best = candidate
                            }
                        }
                    }
                    dist[i] = best
                }
            }
        }
        for (iz in nz - 1 downTo 0) {
            for (iy in ny - 1 downTo 0) {
                for (ix in nx - 1 downTo 0) {
                    val i = (iz * ny + iy) * nx + ix
                    var best = dist[i]
                    for (d in dirs) {
                        val pz = iz + d[0]; val py = iy + d[1]; val px = ix + d[2]
                        if (pz >= 0 && py >= 0 && px >= 0 && pz < nz && py < ny && px < nx) {
                            val neighbor = dist[(pz * ny + py) * nx + px]
                            if (neighbor.isFinite()) {
                                val candidate = neighbor + d[3]
                                if (candidate < best) best = candidate
                            }
                        }
                    }
                    dist[i] = best
                }
            }
        }
        return dist
    }




    private fun writeShellStl(vertices: FloatArray, transform: StlSliceTransform?, file: File) {
        val count = vertices.size / 9
        val linear = transform?.linear
        val transformX = transform?.translationXmm ?: 0.0
        val transformY = transform?.translationYmm ?: 0.0
        val transformZ = transform?.translationZmm ?: 0.0
        fun place(x: Float, y: Float, z: Float): FloatArray {
            if (linear == null) return floatArrayOf(x, y, z)
            return floatArrayOf(
                (x * linear[0] + y * linear[1] + z * linear[2] + transformX).toFloat(),
                (x * linear[3] + y * linear[4] + z * linear[5] + transformY).toFloat(),
                (x * linear[6] + y * linear[7] + z * linear[8] + transformZ).toFloat(),
            )
        }
        val interleaved = FloatArray(count * 18)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        var out = 0
        for (t in 0 until count) {
            val b = t * 9
            val p0 = place(vertices[b], vertices[b + 1], vertices[b + 2])
            val p1 = place(vertices[b + 3], vertices[b + 4], vertices[b + 5])
            val p2 = place(vertices[b + 6], vertices[b + 7], vertices[b + 8])
            val ax = p1[0] - p0[0]; val ay = p1[1] - p0[1]; val az = p1[2] - p0[2]
            val bx = p2[0] - p0[0]; val by = p2[1] - p0[1]; val bz = p2[2] - p0[2]
            var nx = ay * bz - az * by
            var ny = az * bx - ax * bz
            var nz = ax * by - ay * bx
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            if (length > 1e-12f) {
                nx /= length
                ny /= length
                nz /= length
            }
            repeat(3) { vertex ->
                val px = when (vertex) { 0 -> p0[0]; 1 -> p1[0]; else -> p2[0] }
                val py = when (vertex) { 0 -> p0[1]; 1 -> p1[1]; else -> p2[1] }
                val pz = when (vertex) { 0 -> p0[2]; 1 -> p1[2]; else -> p2[2] }
                minX = min(minX, px); maxX = max(maxX, px)
                minY = min(minY, py); maxY = max(maxY, py)
                minZ = min(minZ, pz); maxZ = max(maxZ, pz)
                interleaved[out++] = px
                interleaved[out++] = py
                interleaved[out++] = pz
                interleaved[out++] = if (vertex == 0) nx else 0f
                interleaved[out++] = if (vertex == 0) ny else 0f
                interleaved[out++] = if (vertex == 0) nz else 0f
            }
        }
        val bounds = MeshBounds(minX, minY, minZ, maxX, maxY, maxZ)
        val mesh = StlMesh(
            displayName = file.name,
            interleavedVertices = interleaved,
            triangleCount = count,
            bounds = bounds,
        )
        StlMeshWriter.writeBinary(mesh, file)
        require(file.isFile && file.length() > 0L) { "Unable to write the adaptive wall modifier" }
    }

    private fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Adaptive-walls generation was cancelled")
        }
    }

    private data class VoxelGrid(
        val mask: BooleanArray,
        val nx: Int,
        val ny: Int,
        val nz: Int,
        val pitch: Float,
        val originX: Float,
        val originY: Float,
        val originZ: Float,
    )

}
