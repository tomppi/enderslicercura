package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh

internal fun testMesh(
    vararg triangles: FloatArray,
    name: String = "strategy-test.stl",
): StlMesh {
    require(triangles.all { it.size == 9 }) { "Each test triangle needs three XYZ vertices" }
    val interleaved = FloatArray(triangles.size * 18)
    var offset = 0
    for (triangle in triangles) {
        for (vertex in 0..2) {
            interleaved[offset] = triangle[vertex * 3]
            interleaved[offset + 1] = triangle[vertex * 3 + 1]
            interleaved[offset + 2] = triangle[vertex * 3 + 2]
            interleaved[offset + 3] = 0f
            interleaved[offset + 4] = 0f
            interleaved[offset + 5] = 0f
            offset += 6
        }
    }
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY
    for (triangle in triangles) {
        for (vertex in 0..2) {
            val x = triangle[vertex * 3]
            val y = triangle[vertex * 3 + 1]
            val z = triangle[vertex * 3 + 2]
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            minZ = minOf(minZ, z)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
            maxZ = maxOf(maxZ, z)
        }
    }
    return StlMesh(
        displayName = name,
        interleavedVertices = interleaved,
        triangleCount = triangles.size,
        bounds = MeshBounds(minX, minY, minZ, maxX, maxY, maxZ),
    )
}

internal fun flatBoxTriangles(
    minX: Float,
    minY: Float,
    minZ: Float,
    maxX: Float,
    maxY: Float,
    maxZ: Float,
): List<FloatArray> {
    val a = floatArrayOf(minX, minY, minZ)
    val b = floatArrayOf(maxX, minY, minZ)
    val c = floatArrayOf(maxX, maxY, minZ)
    val d = floatArrayOf(minX, maxY, minZ)
    val e = floatArrayOf(minX, minY, maxZ)
    val f = floatArrayOf(maxX, minY, maxZ)
    val g = floatArrayOf(maxX, maxY, maxZ)
    val h = floatArrayOf(minX, maxY, maxZ)

    fun tri(p: FloatArray, q: FloatArray, r: FloatArray): FloatArray =
        floatArrayOf(p[0], p[1], p[2], q[0], q[1], q[2], r[0], r[1], r[2])

    return listOf(
        tri(a, c, b), tri(a, d, c),
        tri(e, f, g), tri(e, g, h),
        tri(a, b, f), tri(a, f, e),
        tri(b, c, g), tri(b, g, f),
        tri(c, d, h), tri(c, h, g),
        tri(d, a, e), tri(d, e, h),
    )
}
