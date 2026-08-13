package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.StlMeshWriter
import com.tomppi.enderslicer.viewer.StlParser
import com.tomppi.enderslicer.viewer.StlSliceTransform
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
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
    private const val BAND_WIDTH_MM = 8.0
    private const val MAX_EXTRA_BANDS = 3
    private const val EXTRA_WALLS_PER_BAND = 2
    private const val MIN_CROSS_SECTION_MM = 6.0
    private const val MAX_GRID_VOXELS = 1_800_000
    private const val GRID_PAD_MM = 1.5
    private const val ISO_PAD_VOXELS = 3
    private const val CHAMFER_AXIS = 3.0f
    private const val CHAMFER_DIAGONAL = 4.0f
    private const val CHAMFER_KNIGHT = 5.0f
    private const val ISO_LEVEL = 0.6f
    private const val RAY_EPSILON = 1e-6f

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
        val layerWallCount = IntArray(nz)
        for (iz in 0 until nz) {
            var layerMax = 0f
            for (iy in 0 until ny) {
                for (ix in 0 until nx) {
                    val i = (iz * ny + iy) * nx + ix
                    if (mask[i] && thickness[i] > layerMax) layerMax = thickness[i]
                }
            }
            layerWallCount[iz] = if (layerMax > 0f) {
                baseWalls + EXTRA_WALLS_PER_BAND *
                    min(MAX_EXTRA_BANDS, (layerMax / BAND_WIDTH_MM).toInt())
            } else {
                0
            }
        }

        val modifiers = mutableListOf<AdaptiveWallModifier>()
        var order = 0
        var bandStart = 0
        while (bandStart < nz) {
            if (layerWallCount[bandStart] == 0) {
                bandStart++
                continue
            }
            val wallCount = layerWallCount[bandStart]
            var bandEnd = bandStart
            while (bandEnd + 1 < nz && layerWallCount[bandEnd + 1] == wallCount) bandEnd++
            val bandMask = BooleanArray(nx * ny * nz) { i ->
                mask[i] && (i / (ny * nx)) in bandStart..bandEnd
            }
            val file = File(destination, "adaptive-walls-${++order}-${wallCount}walls.stl")
            val vertices = isosurfaceShell(bandMask, nx, ny, nz, pitch, originX, originY, originZ)
            require(vertices.isNotEmpty() && vertices.size % 9 == 0) {
                "Adaptive wall band ${order} produced no closed shell"
            }
            writeShellStl(vertices, transform, file)
            modifiers += AdaptiveWallModifier(wallCount, settings.thicknessAdaptiveWallsFlowPercent, file)
            bandStart = bandEnd + 1
            throwIfInterrupted()
        }
        return modifiers
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

    private fun isosurfaceShell(
        band: BooleanArray,
        nx: Int,
        ny: Int,
        nz: Int,
        pitch: Float,
        originX: Float,
        originY: Float,
        originZ: Float,
    ): FloatArray {
        val pn = ISO_PAD_VOXELS
        val ex = nx + 2 * pn
        val ey = ny + 2 * pn
        val ez = nz + 2 * pn
        val padded = BooleanArray(ex * ey * ez)
        for (iz in 0 until nz) {
            for (iy in 0 until ny) {
                for (ix in 0 until nx) {
                    padded[((iz + pn) * ey + iy + pn) * ex + ix + pn] = band[(iz * ny + iy) * nx + ix]
                }
            }
        }
        val field = chamfer(padded, ex, ey, ez)
        val vertices = mutableListOf<Float>()
        val pxs = FloatArray(ex + 1) { k -> originX + (k - pn) * pitch }
        val pys = FloatArray(ey + 1) { k -> originY + (k - pn) * pitch }
        val pzs = FloatArray(ez + 1) { k -> originZ + (k - pn) * pitch }

        val corners = Array(8) { IntArray(3) }
        val tets = TETRAHEDRA
        for (iz in 0 until ez - 1) {
            for (iy in 0 until ey - 1) {
                for (ix in 0 until ex - 1) {
                    var o = 0
                    for (kz in 0..1) {
                        for (ky in 0..1) {
                            for (kx in 0..1) {
                                corners[o][0] = ix + kx
                                corners[o][1] = iy + ky
                                corners[o][2] = iz + kz
                                o++
                            }
                        }
                    }
                    val values = FloatArray(8) { c -> field[(corners[c][2] * ey + corners[c][1]) * ex + corners[c][0]] }
                    val firstAbove = values[0] >= ISO_LEVEL
                    var straddles = false
                    for (c in 1 until 8) {
                        if ((values[c] >= ISO_LEVEL) != firstAbove) {
                            straddles = true
                            break
                        }
                    }
                    if (!straddles) continue
                    for (tet in tets) {
                        emitTetTriangle(
                            vertices,
                            pxs, pys, pzs,
                            tet,
                            values,
                            corners,
                        )
                    }
                }
            }
        }
        return vertices.toFloatArray()
    }

    private fun emitTetTriangle(
        out: MutableList<Float>,
        pxs: FloatArray,
        pys: FloatArray,
        pzs: FloatArray,
        tet: IntArray,
        values: FloatArray,
        corners: Array<IntArray>,
    ) {
        val above = IntArray(4)
        var aboveCount = 0
        val below = IntArray(4)
        var belowCount = 0
        for (t in 0 until 4) {
            val c = tet[t]
            if (values[c] >= ISO_LEVEL) above[aboveCount++] = c else below[belowCount++] = c
        }
        if (aboveCount == 0 || aboveCount == 4) return
        if (aboveCount == 1 || aboveCount == 3) {
            val odd = if (aboveCount == 1) above[0] else below[0]
            val others = if (aboveCount == 1) below else above
            val p0 = interpolated(pxs, pys, pzs, corners[odd], corners[others[0]], values[odd], values[others[0]])
            val p1 = interpolated(pxs, pys, pzs, corners[odd], corners[others[1]], values[odd], values[others[1]])
            val p2 = interpolated(pxs, pys, pzs, corners[odd], corners[others[2]], values[odd], values[others[2]])
            out.addAll(p0.asList())
            out.addAll(p1.asList())
            out.addAll(p2.asList())
        } else {
            val a0 = above[0]; val a1 = above[1]
            val c0 = below[0]; val c1 = below[1]
            val pa = interpolated(pxs, pys, pzs, corners[a0], corners[c0], values[a0], values[c0])
            val pb = interpolated(pxs, pys, pzs, corners[a1], corners[c0], values[a1], values[c0])
            val pc = interpolated(pxs, pys, pzs, corners[a1], corners[c1], values[a1], values[c1])
            val pd = interpolated(pxs, pys, pzs, corners[a0], corners[c1], values[a0], values[c1])
            out.addAll(pa.asList())
            out.addAll(pb.asList())
            out.addAll(pc.asList())
            out.addAll(pa.asList())
            out.addAll(pc.asList())
            out.addAll(pd.asList())
        }
    }

    private fun interpolated(
        pxs: FloatArray,
        pys: FloatArray,
        pzs: FloatArray,
        a: IntArray,
        b: IntArray,
        va: Float,
        vb: Float,
    ): FloatArray {
        val f = if (kotlin.math.abs(vb - va) < 1e-6f) 0.5f else (ISO_LEVEL - va) / (vb - va)
        return floatArrayOf(
            pxs[a[0]] + f * (pxs[b[0]] - pxs[a[0]]),
            pys[a[1]] + f * (pys[b[1]] - pys[a[1]]),
            pzs[a[2]] + f * (pzs[b[2]] - pzs[a[2]]),
        )
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

    private val TETRAHEDRA = arrayOf(
        intArrayOf(0, 1, 3, 7),
        intArrayOf(0, 2, 3, 7),
        intArrayOf(0, 1, 5, 7),
        intArrayOf(0, 4, 5, 7),
        intArrayOf(0, 2, 6, 7),
        intArrayOf(0, 4, 6, 7),
    )
}
