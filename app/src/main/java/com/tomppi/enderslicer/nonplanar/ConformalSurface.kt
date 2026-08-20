package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.engine.checkCancellation
import com.tomppi.enderslicer.viewer.StlMesh
import java.util.HashMap
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The non-planar "surface projection" backend (Ahlers' method, as implemented
 * in the Slic3r_NonPlanar_Slicing fork): instead of warping the whole mesh,
 * the facets that face up and stay within the printable angle are grouped into
 * connected surface regions. The sliced toolpath is later projected straight
 * down onto these regions, so the nozzle follows the true 3D surface - diving
 * below the layer plane to the thinnest part and climbing to the thickest.
 */
internal class ConformalSurface(
    val regions: List<Region>,
    val diagnostics: ConformalDiagnostics,
) {
    /**
     * One connected, printable surface region. Triangles are stored as 9
     * floats each (x0,y0,z0,x1,y1,z1,x2,y2,z2) and indexed by a coarse XY grid
     * so ray-casts only test the triangles near the query point.
     */
    class Region(
        val triangles: FloatArray,
        val minX: Double,
        val minY: Double,
        val minZ: Double,
        val maxX: Double,
        val maxY: Double,
        val maxZ: Double,
        val areaMm2: Double,
        val gridOriginX: Double,
        val gridOriginY: Double,
        val gridCellMm: Double,
        val gridColumns: Int,
        val gridRows: Int,
        private val cellTriangleOffsets: IntArray,
        private val cellTriangles: IntArray,
    ) {
        val triangleCount: Int get() = triangles.size / 9

        /** Coarse rejection: is the query point near the region's XY projection? */
        fun maybeInside(x: Double, y: Double): Boolean {
            if (x < minX || x > maxX || y < minY || y > maxY) return false
            val gx = ((x - gridOriginX) / gridCellMm).toInt().coerceIn(0, gridColumns - 1)
            val gy = ((y - gridOriginY) / gridCellMm).toInt().coerceIn(0, gridRows - 1)
            return cellTriangleOffsets[gy * gridColumns + gx] != cellTriangleOffsets[gy * gridColumns + gx + 1]
        }

        /** Exact test: does any triangle's XY projection contain (x,y)? */
        fun contains(x: Double, y: Double): Boolean = surfaceZ(x, y) != null

        /** The z of the topmost triangle whose XY projection covers (x,y), or null. */
        fun surfaceZ(x: Double, y: Double): Double? {
            if (!maybeInside(x, y)) return null
            val gx = ((x - gridOriginX) / gridCellMm).toInt().coerceIn(0, gridColumns - 1)
            val gy = ((y - gridOriginY) / gridCellMm).toInt().coerceIn(0, gridRows - 1)
            val cell = gy * gridColumns + gx
            var best: Double? = null
            for (entry in cellTriangleOffsets[cell] until cellTriangleOffsets[cell + 1]) {
                val offset = cellTriangles[entry] * 9
                val x0 = triangles[offset].toDouble()
                val y0 = triangles[offset + 1].toDouble()
                val z0 = triangles[offset + 2].toDouble()
                val x1 = triangles[offset + 3].toDouble()
                val y1 = triangles[offset + 4].toDouble()
                val z1 = triangles[offset + 5].toDouble()
                val x2 = triangles[offset + 6].toDouble()
                val y2 = triangles[offset + 7].toDouble()
                val z2 = triangles[offset + 8].toDouble()
                if (x < min(x0, min(x1, x2)) || x > max(x0, max(x1, x2)) ||
                    y < min(y0, min(y1, y2)) || y > max(y0, max(y1, y2))
                ) {
                    continue
                }
                if (!pointInTriangle(x, y, x0, y0, x1, y1, x2, y2)) continue
                val z = planeZ(x, y, x0, y0, z0, x1, y1, z1, x2, y2, z2) ?: continue
                if (best == null || z > best) best = z
            }
            return best
        }
    }

    companion object {
        private fun pointInTriangle(
            px: Double, py: Double,
            ax: Double, ay: Double,
            bx: Double, by: Double,
            cx: Double, cy: Double,
        ): Boolean {
            val d1 = crossSign(px, py, ax, ay, bx, by)
            val d2 = crossSign(px, py, bx, by, cx, cy)
            val d3 = crossSign(px, py, cx, cy, ax, ay)
            val hasNegative = d1 < 0.0 || d2 < 0.0 || d3 < 0.0
            val hasPositive = d1 > 0.0 || d2 > 0.0 || d3 > 0.0
            return !(hasNegative && hasPositive)
        }

        private fun crossSign(
            px: Double, py: Double,
            ax: Double, ay: Double,
            bx: Double, by: Double,
        ): Double = (px - bx) * (ay - by) - (ax - bx) * (py - by)

        private fun planeZ(
            px: Double, py: Double,
            x0: Double, y0: Double, z0: Double,
            x1: Double, y1: Double, z1: Double,
            x2: Double, y2: Double, z2: Double,
        ): Double? {
            // Barycentric solve of p = p0 + u*(p1-p0) + v*(p2-p0).
            val denominator = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
            if (absSmall(denominator)) return null
            val u = ((y2 - y0) * (px - x0) - (x2 - x0) * (py - y0)) / denominator
            val v = ((x1 - x0) * (py - y0) - (y1 - y0) * (px - x0)) / denominator
            return z0 + u * (z1 - z0) + v * (z2 - z0)
        }

        private fun absSmall(value: Double): Boolean = value > -1e-12 && value < 1e-12
    }
}

internal data class ConformalDiagnostics(
    val sourceTriangles: Int,
    val candidateTriangles: Int,
    val regionsFound: Int,
    val regionsFilteredBySpan: Int,
    val regionsFilteredByArea: Int,
)

/**
 * Builds [ConformalSurface] regions from the displayed mesh: collect the
 * up-facing facets within the printable angle, group them by shared edges into
 * connected components, then keep the components whose height span fits the
 * nozzle travel and whose area is worth curving.
 */
internal object ConformalSurfaceBuilder {
    const val MIN_REGION_AREA_MM2 = 20.0
    private const val GRID_CELL_MM = 1.0
    private const val VERTEX_QUANTUM_MM = 1e-3

    fun build(mesh: StlMesh, settings: NonPlanarSettings): ConformalSurface {
        val safe = settings.validated()
        val maxAngle = safe.effectiveSlopeLimitDegrees
        val cosLimit = cos(Math.toRadians(maxAngle))
        val vertices = mesh.interleavedVertices
        val triangleCount = mesh.triangleCount

        // Pass 1: candidate facets (up-facing and within the printable angle).
        val candidates = ArrayList<Int>(triangleCount)
        for (triangle in 0 until triangleCount) {
            checkCancellation(triangle, "Conformal surface search")
            val offset = triangle * 18
            val ax = vertices[offset].toDouble()
            val ay = vertices[offset + 1].toDouble()
            val az = vertices[offset + 2].toDouble()
            val bx = vertices[offset + 6].toDouble()
            val by = vertices[offset + 7].toDouble()
            val bz = vertices[offset + 8].toDouble()
            val cx = vertices[offset + 12].toDouble()
            val cy = vertices[offset + 13].toDouble()
            val cz = vertices[offset + 14].toDouble()
            val ux = bx - ax; val uy = by - ay; val uz = bz - az
            val vx = cx - ax; val vy = cy - ay; val vz = cz - az
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            if (length <= 1e-12) continue
            nx /= length; ny /= length; nz /= length
            if (nz >= cosLimit) candidates += triangle
        }

        // Pass 2: connect candidates through shared edges (quantized vertex ids).
        val vertexIds = HashMap<Long, Int>(candidates.size * 3)
        fun vertexId(x: Double, y: Double, z: Double): Int {
            val key = packVertex(x, y, z)
            return vertexIds.getOrPut(key) { vertexIds.size }
        }
        val edgeTriangles = HashMap<Long, ArrayList<Int>>()
        for (candidateIndex in candidates.indices) {
            val triangle = candidates[candidateIndex]
            val offset = triangle * 18
            val ids = IntArray(3)
            for (vertex in 0 until 3) {
                val base = offset + vertex * 6
                ids[vertex] = vertexId(
                    vertices[base].toDouble(),
                    vertices[base + 1].toDouble(),
                    vertices[base + 2].toDouble(),
                )
            }
            for (edge in 0 until 3) {
                val a = ids[edge]
                val b = ids[(edge + 1) % 3]
                val key = edgeKey(a, b)
                edgeTriangles.getOrPut(key) { ArrayList(2) }.add(candidateIndex)
            }
        }
        val parent = IntArray(candidates.size) { it }
        fun find(index: Int): Int {
            var root = index
            while (parent[root] != root) root = parent[root]
            var current = index
            while (parent[current] != current) {
                val next = parent[current]
                parent[current] = root
                current = next
            }
            return root
        }
        fun union(a: Int, b: Int) {
            val rootA = find(a)
            val rootB = find(b)
            if (rootA != rootB) parent[maxOf(rootA, rootB)] = minOf(rootA, rootB)
        }
        for (sharing in edgeTriangles.values) {
            if (sharing.size < 2) continue
            for (index in 1 until sharing.size) union(sharing[0], sharing[index])
        }

        // Pass 3: per-component stats + filters.
        val componentBounds = HashMap<Int, DoubleArray>() // root -> minX,minY,minZ,maxX,maxY,maxZ
        val componentArea = HashMap<Int, Double>()
        val componentTriangles = HashMap<Int, ArrayList<Int>>()
        for (candidateIndex in candidates.indices) {
            val triangle = candidates[candidateIndex]
            val offset = triangle * 18
            val x0 = vertices[offset].toDouble(); val y0 = vertices[offset + 1].toDouble(); val z0 = vertices[offset + 2].toDouble()
            val x1 = vertices[offset + 6].toDouble(); val y1 = vertices[offset + 7].toDouble(); val z1 = vertices[offset + 8].toDouble()
            val x2 = vertices[offset + 12].toDouble(); val y2 = vertices[offset + 13].toDouble(); val z2 = vertices[offset + 14].toDouble()
            val root = find(candidateIndex)
            val bounds = componentBounds.getOrPut(root) {
                doubleArrayOf(
                    Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                    Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                )
            }
            bounds[0] = min(bounds[0], min(x0, min(x1, x2)))
            bounds[1] = min(bounds[1], min(y0, min(y1, y2)))
            bounds[2] = min(bounds[2], min(z0, min(z1, z2)))
            bounds[3] = max(bounds[3], max(x0, max(x1, x2)))
            bounds[4] = max(bounds[4], max(y0, max(y1, y2)))
            bounds[5] = max(bounds[5], max(z0, max(z1, z2)))
            val ux = x1 - x0; val uy = y1 - y0; val uz = z1 - z0
            val vx = x2 - x0; val vy = y2 - y0; val vz = z2 - z0
            val crossX = uy * vz - uz * vy
            val crossY = uz * vx - ux * vz
            val crossZ = ux * vy - uy * vx
            componentArea.merge(root, 0.5 * sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ)) { a, b -> a + b }
            componentTriangles.getOrPut(root) { ArrayList() }.add(triangle)
        }

        var filteredBySpan = 0
        var filteredByArea = 0
        val regions = ArrayList<ConformalSurface.Region>()
        for ((root, bounds) in componentBounds) {
            val spanZ = bounds[5] - bounds[2]
            if (spanZ > safe.maximumLiftMm) {
                filteredBySpan++
                continue
            }
            val area = componentArea[root] ?: 0.0
            if (area < MIN_REGION_AREA_MM2) {
                filteredByArea++
                continue
            }
            val component = componentTriangles[root] ?: continue
            val triangleData = FloatArray(component.size * 9)
            var dataOffset = 0
            for (triangle in component) {
                val offset = triangle * 18
                for (vertex in 0 until 3) {
                    val base = offset + vertex * 6
                    triangleData[dataOffset++] = vertices[base]
                    triangleData[dataOffset++] = vertices[base + 1]
                    triangleData[dataOffset++] = vertices[base + 2]
                }
            }
            regions += buildRegion(triangleData, bounds, area)
        }

        return ConformalSurface(
            regions = regions,
            diagnostics = ConformalDiagnostics(
                sourceTriangles = triangleCount,
                candidateTriangles = candidates.size,
                regionsFound = regions.size,
                regionsFilteredBySpan = filteredBySpan,
                regionsFilteredByArea = filteredByArea,
            ),
        )
    }

    /** Rebuilds the grid index for a region read back from the sidecar. */
    fun rebuildRegion(
        triangles: FloatArray,
        minX: Double,
        minY: Double,
        minZ: Double,
        maxX: Double,
        maxY: Double,
        maxZ: Double,
        areaMm2: Double,
    ): ConformalSurface.Region = buildRegion(
        triangles,
        doubleArrayOf(minX, minY, minZ, maxX, maxY, maxZ),
        areaMm2,
    )

    private fun buildRegion(
        triangleData: FloatArray,
        bounds: DoubleArray,
        area: Double,
    ): ConformalSurface.Region {
        val minX = bounds[0]; val minY = bounds[1]; val minZ = bounds[2]
        val maxX = bounds[3]; val maxY = bounds[4]; val maxZ = bounds[5]
        val columns = max(2, ((maxX - minX) / GRID_CELL_MM).toInt() + 1)
        val rows = max(2, ((maxY - minY) / GRID_CELL_MM).toInt() + 1)
        val cellTriangleCounts = IntArray(columns * rows)
        val triangleCount = triangleData.size / 9
        for (triangle in 0 until triangleCount) {
            val offset = triangle * 9
            val x0 = triangleData[offset].toDouble(); val y0 = triangleData[offset + 1].toDouble()
            val x1 = triangleData[offset + 3].toDouble(); val y1 = triangleData[offset + 4].toDouble()
            val x2 = triangleData[offset + 6].toDouble(); val y2 = triangleData[offset + 7].toDouble()
            val loX = max(min(x0, min(x1, x2)), minX)
            val hiX = min(max(x0, max(x1, x2)), maxX)
            val loY = max(min(y0, min(y1, y2)), minY)
            val hiY = min(max(y0, max(y1, y2)), maxY)
            val gx0 = ((loX - minX) / GRID_CELL_MM).toInt().coerceIn(0, columns - 1)
            val gx1 = ((hiX - minX) / GRID_CELL_MM).toInt().coerceIn(0, columns - 1)
            val gy0 = ((loY - minY) / GRID_CELL_MM).toInt().coerceIn(0, rows - 1)
            val gy1 = ((hiY - minY) / GRID_CELL_MM).toInt().coerceIn(0, rows - 1)
            for (gy in gy0..gy1) {
                for (gx in gx0..gx1) {
                    cellTriangleCounts[gy * columns + gx]++
                }
            }
        }
        val cellTriangleOffsets = IntArray(columns * rows + 1)
        for (cell in 0 until columns * rows) {
            cellTriangleOffsets[cell + 1] = cellTriangleOffsets[cell] + cellTriangleCounts[cell]
        }
        val cellTriangles = IntArray(cellTriangleOffsets[columns * rows])
        val cursor = cellTriangleOffsets.copyOf()
        for (triangle in 0 until triangleCount) {
            val offset = triangle * 9
            val x0 = triangleData[offset].toDouble(); val y0 = triangleData[offset + 1].toDouble()
            val x1 = triangleData[offset + 3].toDouble(); val y1 = triangleData[offset + 4].toDouble()
            val x2 = triangleData[offset + 6].toDouble(); val y2 = triangleData[offset + 7].toDouble()
            val loX = max(min(x0, min(x1, x2)), minX)
            val hiX = min(max(x0, max(x1, x2)), maxX)
            val loY = max(min(y0, min(y1, y2)), minY)
            val hiY = min(max(y0, max(y1, y2)), maxY)
            val gx0 = ((loX - minX) / GRID_CELL_MM).toInt().coerceIn(0, columns - 1)
            val gx1 = ((hiX - minX) / GRID_CELL_MM).toInt().coerceIn(0, columns - 1)
            val gy0 = ((loY - minY) / GRID_CELL_MM).toInt().coerceIn(0, rows - 1)
            val gy1 = ((hiY - minY) / GRID_CELL_MM).toInt().coerceIn(0, rows - 1)
            for (gy in gy0..gy1) {
                for (gx in gx0..gx1) {
                    val cell = gy * columns + gx
                    cellTriangles[cursor[cell]++] = triangle
                }
            }
        }
        return ConformalSurface.Region(
            triangles = triangleData,
            minX = minX, minY = minY, minZ = minZ, maxX = maxX, maxY = maxY, maxZ = maxZ,
            areaMm2 = area,
            gridOriginX = minX, gridOriginY = minY,
            gridCellMm = GRID_CELL_MM,
            gridColumns = columns, gridRows = rows,
            cellTriangleOffsets = cellTriangleOffsets,
            cellTriangles = cellTriangles,
        )
    }

    private fun packVertex(x: Double, y: Double, z: Double): Long {
        val qx = (x / VERTEX_QUANTUM_MM).toLong() and 0x1FFFFF
        val qy = (y / VERTEX_QUANTUM_MM).toLong() and 0x1FFFFF
        val qz = (z / VERTEX_QUANTUM_MM).toLong() and 0x1FFFFF
        return (qx shl 42) or (qy shl 21) or qz
    }

    private fun edgeKey(a: Int, b: Int): Long = minOf(a, b).toLong() * 1_000_003L + maxOf(a, b)
}
