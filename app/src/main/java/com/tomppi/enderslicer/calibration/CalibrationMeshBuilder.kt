package com.tomppi.enderslicer.calibration

import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

private const val SOLID_OVERLAP_MM = 0.25

internal class MeshBuilder {
    private var data = FloatArray(18 * 12 * 32)
    private var size = 0
    private var triangleCount = 0
    private var minX = Float.POSITIVE_INFINITY
    private var minY = Float.POSITIVE_INFINITY
    private var minZ = Float.POSITIVE_INFINITY
    private var maxX = Float.NEGATIVE_INFINITY
    private var maxY = Float.NEGATIVE_INFINITY
    private var maxZ = Float.NEGATIVE_INFINITY

    fun addBox(centerX: Double, centerY: Double, minZ: Double, width: Double, depth: Double, height: Double) {
        require(width > 0.0 && depth > 0.0 && height > 0.0)
        val x0 = (centerX - width / 2.0).toFloat()
        val x1 = (centerX + width / 2.0).toFloat()
        val y0 = (centerY - depth / 2.0).toFloat()
        val y1 = (centerY + depth / 2.0).toFloat()
        val z0 = minZ.toFloat()
        val z1 = (minZ + height).toFloat()
        quad(x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, 0f, 0f, -1f)
        quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0f, 0f, 1f)
        quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0f, -1f, 0f)
        quad(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0f, 1f, 0f)
        quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1f, 0f, 0f)
        quad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, 1f, 0f, 0f)
    }

    fun addCylinder(centerX: Double, centerY: Double, minZ: Double, radius: Double, height: Double, segments: Int) {
        require(radius > 0.0 && height > 0.0 && segments >= 6)
        val z0 = minZ.toFloat()
        val z1 = (minZ + height).toFloat()
        val cx = centerX.toFloat()
        val cy = centerY.toFloat()
        repeat(segments) { index ->
            val angle0 = 2.0 * PI * index / segments
            val angle1 = 2.0 * PI * (index + 1) / segments
            val x0 = (centerX + cos(angle0) * radius).toFloat()
            val y0 = (centerY + sin(angle0) * radius).toFloat()
            val x1 = (centerX + cos(angle1) * radius).toFloat()
            val y1 = (centerY + sin(angle1) * radius).toFloat()
            triangle(cx, cy, z0, x1, y1, z0, x0, y0, z0, 0f, 0f, -1f)
            triangle(cx, cy, z1, x0, y0, z1, x1, y1, z1, 0f, 0f, 1f)
            val mid = (angle0 + angle1) / 2.0
            quad(x0, y0, z0, x1, y1, z0, x1, y1, z1, x0, y0, z1, cos(mid).toFloat(), sin(mid).toFloat(), 0f)
        }
    }

    fun addPolygonPrism(points: List<Point2>, minZ: Double, height: Double) {
        require(points.size >= 3 && height > 0.0)
        val centerX = points.sumOf { it.x } / points.size
        val centerY = points.sumOf { it.y } / points.size
        val z0 = minZ.toFloat()
        val z1 = (minZ + height).toFloat()
        repeat(points.size) { index ->
            val current = points[index]
            val next = points[(index + 1) % points.size]
            val x0 = current.x.toFloat()
            val y0 = current.y.toFloat()
            val x1 = next.x.toFloat()
            val y1 = next.y.toFloat()
            triangle(centerX.toFloat(), centerY.toFloat(), z0, x1, y1, z0, x0, y0, z0, 0f, 0f, -1f)
            triangle(centerX.toFloat(), centerY.toFloat(), z1, x0, y0, z1, x1, y1, z1, 0f, 0f, 1f)
            val dx = next.x - current.x
            val dy = next.y - current.y
            val normalLength = sqrt(dx * dx + dy * dy)
            quad(x0, y0, z0, x1, y1, z0, x1, y1, z1, x0, y0, z1, (dy / normalLength).toFloat(), (-dx / normalLength).toFloat(), 0f)
        }
    }

    fun addSteppedBracketX(
        rootX: Double,
        direction: Int,
        centerY: Double,
        minZ: Double,
        length: Double,
        depth: Double,
        rise: Double,
        tipThickness: Double,
    ) {
        require(direction == -1 || direction == 1)
        require(length > 0.0 && depth > 0.0 && rise >= length && tipThickness > 0.0)
        val steps = max(2, ceil(length / 0.55).toInt())
        val stepHeight = rise / steps
        repeat(steps) { index ->
            val extension = length * (index + 1) / steps
            val centerX = rootX + direction * (extension / 2.0 - SOLID_OVERLAP_MM / 2.0)
            addBox(
                centerX = centerX,
                centerY = centerY,
                minZ = minZ + index * stepHeight - if (index == 0) 0.0 else SOLID_OVERLAP_MM,
                width = extension + SOLID_OVERLAP_MM,
                depth = depth,
                height = stepHeight + SOLID_OVERLAP_MM,
            )
        }
        val topCenterX = rootX + direction * (length / 2.0 - SOLID_OVERLAP_MM / 2.0)
        addBox(topCenterX, centerY, minZ + rise - SOLID_OVERLAP_MM, length + SOLID_OVERLAP_MM, depth, tipThickness + SOLID_OVERLAP_MM)
    }

    private fun quad(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
        dx: Float, dy: Float, dz: Float,
        nx: Float, ny: Float, nz: Float,
    ) {
        triangle(ax, ay, az, bx, by, bz, cx, cy, cz, nx, ny, nz)
        triangle(ax, ay, az, cx, cy, cz, dx, dy, dz, nx, ny, nz)
    }

    private fun triangle(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
        requestedNx: Float, requestedNy: Float, requestedNz: Float,
    ) {
        val actual = normal(ax, ay, az, bx, by, bz, cx, cy, cz)
        if (actual[0] * requestedNx + actual[1] * requestedNy + actual[2] * requestedNz < 0f) {
            triangle(ax, ay, az, cx, cy, cz, bx, by, bz, requestedNx, requestedNy, requestedNz)
            return
        }
        val requestedLength = sqrt(requestedNx * requestedNx + requestedNy * requestedNy + requestedNz * requestedNz)
        putVertex(ax, ay, az, requestedNx / requestedLength, requestedNy / requestedLength, requestedNz / requestedLength)
        putVertex(bx, by, bz, requestedNx / requestedLength, requestedNy / requestedLength, requestedNz / requestedLength)
        putVertex(cx, cy, cz, requestedNx / requestedLength, requestedNy / requestedLength, requestedNz / requestedLength)
        triangleCount++
    }

    private fun normal(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
    ): FloatArray {
        val abx = bx - ax
        val aby = by - ay
        val abz = bz - az
        val acx = cx - ax
        val acy = cy - ay
        val acz = cz - az
        var nx = aby * acz - abz * acy
        var ny = abz * acx - abx * acz
        var nz = abx * acy - aby * acx
        val length = sqrt(nx * nx + ny * ny + nz * nz)
        require(length > 1e-12f) { "Calibration geometry produced a degenerate triangle" }
        nx /= length
        ny /= length
        nz /= length
        return floatArrayOf(nx, ny, nz)
    }

    private fun putVertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
        ensure(size + 6)
        data[size++] = x
        data[size++] = y
        data[size++] = z
        data[size++] = nx
        data[size++] = ny
        data[size++] = nz
        minX = minOf(minX, x)
        minY = minOf(minY, y)
        minZ = minOf(minZ, z)
        maxX = maxOf(maxX, x)
        maxY = maxOf(maxY, y)
        maxZ = maxOf(maxZ, z)
    }

    private fun ensure(required: Int) {
        if (required <= data.size) return
        var capacity = data.size
        while (capacity < required) capacity *= 2
        data = data.copyOf(capacity)
    }

    fun finish(displayName: String): StlMesh {
        require(triangleCount > 0)
        return StlMesh(
            displayName = displayName,
            interleavedVertices = data.copyOf(size),
            triangleCount = triangleCount,
            bounds = MeshBounds(minX, minY, minZ, maxX, maxY, maxZ),
        )
    }
}
