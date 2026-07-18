package com.tomppi.enderslicer.viewer

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object StlParser {
    private const val MAX_FILE_BYTES = 160 * 1024 * 1024
    private const val MAX_TRIANGLES = 1_500_000

    fun parse(resolver: ContentResolver, uri: Uri): StlMesh {
        val bytes = resolver.openInputStream(uri)?.use(::readLimited)
            ?: error("Unable to open the selected STL")
        val name = displayName(resolver, uri)

        return if (looksLikeBinary(bytes)) {
            parseBinary(name, bytes)
        } else {
            parseAscii(name, bytes.toString(Charsets.UTF_8))
        }
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_FILE_BYTES) { "STL is larger than ${MAX_FILE_BYTES / 1024 / 1024} MiB" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun looksLikeBinary(bytes: ByteArray): Boolean {
        if (bytes.size < 84) return false
        val count = ByteBuffer.wrap(bytes, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        if (count <= 0 || count > MAX_TRIANGLES) return false
        val expected = 84L + count * 50L
        return expected <= bytes.size.toLong()
    }

    private fun parseBinary(name: String, bytes: ByteArray): StlMesh {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(80)
        val triangleCount = buffer.int
        require(triangleCount in 1..MAX_TRIANGLES) { "Invalid STL triangle count: $triangleCount" }

        val floats = FloatArray(triangleCount * 18)
        val bounds = BoundsAccumulator()
        var out = 0
        repeat(triangleCount) {
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
                require(x.isFinite() && y.isFinite() && z.isFinite()) { "STL contains non-finite coordinates" }
                floats[out++] = x
                floats[out++] = y
                floats[out++] = z
                floats[out++] = nx
                floats[out++] = ny
                floats[out++] = nz
                bounds.include(x, y, z)
            }
        }

        return StlMesh(name, floats, triangleCount, bounds.finish())
    }

    private fun parseAscii(name: String, text: String): StlMesh {
        val output = FloatAccumulator()
        val bounds = BoundsAccumulator()
        var currentNormal = floatArrayOf(0f, 0f, 0f)
        val triangleVertices = FloatArray(9)
        var vertexCount = 0
        var triangleCount = 0

        text.lineSequence().forEach { raw ->
            val tokens = raw.trim().split(Regex("\\s+"))
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
                            output.add(vx, vy, vz, normal[0], normal[1], normal[2])
                            bounds.include(vx, vy, vz)
                        }
                        triangleCount++
                        require(triangleCount <= MAX_TRIANGLES) { "STL has too many triangles" }
                        vertexCount = 0
                    }
                }
            }
        }

        require(triangleCount > 0) { "No triangles were found in the STL" }
        return StlMesh(name, output.toArray(), triangleCount, bounds.finish())
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

    private fun normalIsUsable(x: Float, y: Float, z: Float): Boolean {
        return x.isFinite() && y.isFinite() && z.isFinite() && (x * x + y * y + z * z) > 1e-12f
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String {
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "model.stl"
    }

    private class FloatAccumulator(initialCapacity: Int = 18 * 1024) {
        private var data = FloatArray(initialCapacity)
        private var size = 0

        fun add(vararg values: Float) {
            ensure(size + values.size)
            values.copyInto(data, destinationOffset = size)
            size += values.size
        }

        fun toArray(): FloatArray = data.copyOf(size)

        private fun ensure(required: Int) {
            if (required <= data.size) return
            var capacity = data.size
            while (capacity < required) capacity *= 2
            data = data.copyOf(capacity)
        }
    }

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
}
