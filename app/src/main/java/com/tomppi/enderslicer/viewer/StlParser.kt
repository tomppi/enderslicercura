package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

object StlParser {
    fun parse(
        file: File,
        displayName: String = file.name,
        maxTriangles: Int = MeshTriangleLimits.current(),
    ): StlMesh {
        require(file.isFile && file.length() > 0L) { "The selected STL is empty or unavailable" }
        val limit = MeshTriangleLimits.sanitize(maxTriangles)
        require(file.length() <= MeshTriangleLimits.maxInputFileBytes(limit)) {
            "STL is larger than ${MeshTriangleLimits.formatBytes(MeshTriangleLimits.maxInputFileBytes(limit))} for the ${MeshTriangleLimits.formatCount(limit)}-triangle limit"
        }

        val binaryCount = binaryTriangleCount(file)
        return if (binaryCount != null) {
            require(binaryCount in 1L..limit.toLong()) {
                "STL contains ${MeshTriangleLimits.formatCount(binaryCount)} triangles; the current limit is ${MeshTriangleLimits.formatCount(limit)}"
            }
            parseBinary(displayName, file, binaryCount.toInt())
        } else {
            parseAscii(displayName, file, limit)
        }
    }

    internal fun binaryTriangleCount(file: File): Long? {
        if (!file.isFile || file.length() < STL_HEADER_BYTES) return null
        return runCatching {
            RandomAccessFile(file, "r").use { input ->
                input.seek(80L)
                val countBytes = ByteArray(4)
                input.readFully(countBytes)
                val count = ByteBuffer.wrap(countBytes)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .int
                    .toLong() and 0xffffffffL
                val expected = STL_HEADER_BYTES + count * STL_TRIANGLE_BYTES
                count.takeIf { it > 0L && expected == file.length() }
            }
        }.getOrNull()
    }

    private fun parseBinary(name: String, file: File, triangleCount: Int): StlMesh {
        val floats = FloatArray(Math.multiplyExact(triangleCount, FLOATS_PER_TRIANGLE))
        val bounds = BoundsAccumulator()
        var out = 0

        file.inputStream().channel.use { channel ->
            channel.position(STL_HEADER_BYTES)
            val buffer = ByteBuffer
                .allocateDirect(BINARY_BLOCK_TRIANGLES * STL_TRIANGLE_BYTES.toInt())
                .order(ByteOrder.LITTLE_ENDIAN)
            var remaining = triangleCount

            while (remaining > 0) {
                val records = minOf(remaining, BINARY_BLOCK_TRIANGLES)
                buffer.clear()
                buffer.limit(records * STL_TRIANGLE_BYTES.toInt())
                readFully(channel, buffer)
                buffer.flip()

                repeat(records) {
                    var nx = buffer.float
                    var ny = buffer.float
                    var nz = buffer.float
                    val vertices = FloatArray(9)
                    for (index in vertices.indices) vertices[index] = buffer.float
                    buffer.short

                    if (!normalIsUsable(nx, ny, nz)) {
                        val normal = computeNormal(vertices)
                        nx = normal[0]
                        ny = normal[1]
                        nz = normal[2]
                    }

                    for (vertex in 0 until 3) {
                        val base = vertex * 3
                        val x = vertices[base]
                        val y = vertices[base + 1]
                        val z = vertices[base + 2]
                        require(x.isFinite() && y.isFinite() && z.isFinite()) {
                            "STL contains non-finite coordinates"
                        }
                        floats[out++] = x
                        floats[out++] = y
                        floats[out++] = z
                        floats[out++] = nx
                        floats[out++] = ny
                        floats[out++] = nz
                        bounds.include(x, y, z)
                    }
                }
                remaining -= records
            }
        }

        return StlMesh(name, floats, triangleCount, bounds.finish())
    }

    private fun parseAscii(name: String, file: File, maxTriangles: Int): StlMesh {
        var vertexLines = 0L
        file.useLines { lines ->
            lines.forEach { raw ->
                val tokens = raw.trim().split(WHITESPACE)
                if (tokens.size >= 4 && tokens[0].equals("vertex", true)) {
                    vertexLines++
                    require(vertexLines <= maxTriangles.toLong() * 3L) {
                        "STL has more than ${MeshTriangleLimits.formatCount(maxTriangles)} triangles"
                    }
                }
            }
        }
        require(vertexLines > 0L && vertexLines % 3L == 0L) { "No complete triangles were found in the STL" }
        val triangleCount = (vertexLines / 3L).toInt()
        val floats = FloatArray(Math.multiplyExact(triangleCount, FLOATS_PER_TRIANGLE))
        val bounds = BoundsAccumulator()
        var currentNormal = floatArrayOf(0f, 0f, 0f)
        val triangleVertices = FloatArray(9)
        var vertexCount = 0
        var out = 0

        file.useLines { lines ->
            lines.forEach { raw ->
                val tokens = raw.trim().split(WHITESPACE)
                when {
                    tokens.size >= 5 && tokens[0].equals("facet", true) && tokens[1].equals("normal", true) -> {
                        currentNormal = floatArrayOf(
                            tokens[2].toFloatOrNull() ?: 0f,
                            tokens[3].toFloatOrNull() ?: 0f,
                            tokens[4].toFloatOrNull() ?: 0f,
                        )
                    }

                    tokens.size >= 4 && tokens[0].equals("vertex", true) -> {
                        val x = tokens[1].toFloatOrNull() ?: error("Invalid ASCII STL vertex")
                        val y = tokens[2].toFloatOrNull() ?: error("Invalid ASCII STL vertex")
                        val z = tokens[3].toFloatOrNull() ?: error("Invalid ASCII STL vertex")
                        val base = vertexCount * 3
                        triangleVertices[base] = x
                        triangleVertices[base + 1] = y
                        triangleVertices[base + 2] = z
                        vertexCount++

                        if (vertexCount == 3) {
                            var normal = currentNormal
                            if (!normalIsUsable(normal[0], normal[1], normal[2])) {
                                normal = computeNormal(triangleVertices)
                            }
                            for (vertex in 0 until 3) {
                                val offset = vertex * 3
                                val vx = triangleVertices[offset]
                                val vy = triangleVertices[offset + 1]
                                val vz = triangleVertices[offset + 2]
                                require(vx.isFinite() && vy.isFinite() && vz.isFinite()) {
                                    "STL contains non-finite coordinates"
                                }
                                floats[out++] = vx
                                floats[out++] = vy
                                floats[out++] = vz
                                floats[out++] = normal[0]
                                floats[out++] = normal[1]
                                floats[out++] = normal[2]
                                bounds.include(vx, vy, vz)
                            }
                            vertexCount = 0
                        }
                    }
                }
            }
        }

        require(vertexCount == 0 && out == floats.size) { "ASCII STL ended with an incomplete triangle" }
        return StlMesh(name, floats, triangleCount, bounds.finish())
    }

    private fun readFully(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            check(channel.read(buffer) > 0) { "Binary STL ended unexpectedly" }
        }
    }

    private fun computeNormal(vertices: FloatArray): FloatArray {
        val ax = vertices[3] - vertices[0]
        val ay = vertices[4] - vertices[1]
        val az = vertices[5] - vertices[2]
        val bx = vertices[6] - vertices[0]
        val by = vertices[7] - vertices[1]
        val bz = vertices[8] - vertices[2]
        var nx = ay * bz - az * by
        var ny = az * bx - ax * bz
        var nz = ax * by - ay * bx
        val length = sqrt(nx * nx + ny * ny + nz * nz)
        if (length > 1e-12f) {
            nx /= length
            ny /= length
            nz /= length
        }
        return floatArrayOf(nx, ny, nz)
    }

    private fun normalIsUsable(x: Float, y: Float, z: Float): Boolean =
        x.isFinite() && y.isFinite() && z.isFinite() && (x * x + y * y + z * z) > 1e-12f

    private class BoundsAccumulator {
        private var minX = Float.POSITIVE_INFINITY
        private var minY = Float.POSITIVE_INFINITY
        private var minZ = Float.POSITIVE_INFINITY
        private var maxX = Float.NEGATIVE_INFINITY
        private var maxY = Float.NEGATIVE_INFINITY
        private var maxZ = Float.NEGATIVE_INFINITY

        fun include(x: Float, y: Float, z: Float) {
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            minZ = minOf(minZ, z)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
            maxZ = maxOf(maxZ, z)
        }

        fun finish(): MeshBounds {
            require(minX.isFinite()) { "STL bounds could not be calculated" }
            return MeshBounds(minX, minY, minZ, maxX, maxY, maxZ)
        }
    }

    private const val FLOATS_PER_TRIANGLE = 18
    private const val BINARY_BLOCK_TRIANGLES = 4_096
    private const val STL_HEADER_BYTES = 84L
    private const val STL_TRIANGLE_BYTES = 50L
    private val WHITESPACE = Regex("\\s+")
}
