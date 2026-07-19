package com.tomppi.enderslicer.model

import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A model-space linear transform followed by placement using the transformed
 * XY bounds center and minimum Z. This keeps move/drop controls intuitive while
 * retaining imported Cura rotation, scaling and mirroring matrices.
 */
data class ModelPlacement(
    val linear: List<Double> = IDENTITY,
    val centerXmm: Double,
    val centerYmm: Double,
    val baseZmm: Double,
    val source: String = "Centered on build plate",
) {
    init {
        require(linear.size == 9) { "Model transform must contain nine linear values" }
        require(linear.all(Double::isFinite)) { "Model transform contains a non-finite value" }
        require(centerXmm.isFinite() && centerYmm.isFinite() && baseZmm.isFinite()) {
            "Model placement contains a non-finite position"
        }
    }

    data class Affine3mf(
        val linear: List<Double>,
        val translationXmm: Double,
        val translationYmm: Double,
        val translationZmm: Double,
        val targetCenterXmm: Double? = null,
        val targetCenterYmm: Double? = null,
        val targetBaseZmm: Double? = null,
    ) {
        init {
            require(linear.size == 9) { "3MF transform must contain nine linear values" }
            require(linear.all(Double::isFinite)) { "3MF transform contains a non-finite linear value" }
            require(
                listOf(translationXmm, translationYmm, translationZmm)
                    .all(Double::isFinite),
            ) { "3MF transform contains a non-finite translation" }
            require(
                listOfNotNull(targetCenterXmm, targetCenterYmm, targetBaseZmm)
                    .all(Double::isFinite),
            ) { "3MF target bounds contain a non-finite value" }
        }
    }

    fun transformed(mesh: StlMesh): StlMesh {
        val raw = transformedPositions(mesh)
        val bounds = boundsOf(raw)
        val dx = centerXmm - bounds.centerX
        val dy = centerYmm - bounds.centerY
        val dz = baseZmm - bounds.minZ
        val output = FloatArray(mesh.triangleCount * 18)
        var out = 0
        var position = 0
        repeat(mesh.triangleCount) {
            val vertices = FloatArray(9)
            repeat(3) { vertex ->
                val base = position + vertex * 3
                vertices[vertex * 3] = (raw[base] + dx).toFloat()
                vertices[vertex * 3 + 1] = (raw[base + 1] + dy).toFloat()
                vertices[vertex * 3 + 2] = (raw[base + 2] + dz).toFloat()
            }
            val normal = normal(vertices)
            repeat(3) { vertex ->
                val base = vertex * 3
                output[out++] = vertices[base]
                output[out++] = vertices[base + 1]
                output[out++] = vertices[base + 2]
                output[out++] = normal[0]
                output[out++] = normal[1]
                output[out++] = normal[2]
            }
            position += 9
        }
        return StlMesh(
            displayName = mesh.displayName,
            interleavedVertices = output,
            triangleCount = mesh.triangleCount,
            bounds = boundsOfInterleaved(output),
        )
    }

    fun moved(centerXmm: Double = this.centerXmm, centerYmm: Double = this.centerYmm, baseZmm: Double = this.baseZmm): ModelPlacement =
        copy(centerXmm = centerXmm, centerYmm = centerYmm, baseZmm = baseZmm, source = "Manual placement")

    fun droppedToBed(): ModelPlacement = copy(baseZmm = 0.0, source = "Dropped to build plate")

    fun rotated(axis: Axis, degrees: Double): ModelPlacement {
        require(degrees.isFinite()) { "Rotation must be finite" }
        val radians = Math.toRadians(degrees)
        val c = cos(radians)
        val s = sin(radians)
        val rotation = when (axis) {
            Axis.X -> listOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c)
            Axis.Y -> listOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c)
            Axis.Z -> listOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0)
        }
        return copy(linear = multiply(rotation, linear), source = "Manual rotation")
    }

    fun layFlat(mesh: StlMesh): ModelPlacement {
        val current = transformedPositions(mesh)
        var bestBase = 0
        var bestAreaSquared = -1.0
        var index = 0
        repeat(mesh.triangleCount) {
            val ax = current[index + 3] - current[index]
            val ay = current[index + 4] - current[index + 1]
            val az = current[index + 5] - current[index + 2]
            val bx = current[index + 6] - current[index]
            val by = current[index + 7] - current[index + 1]
            val bz = current[index + 8] - current[index + 2]
            val nx = ay * bz - az * by
            val ny = az * bx - ax * bz
            val nz = ax * by - ay * bx
            val areaSquared = nx * nx + ny * ny + nz * nz
            if (areaSquared > bestAreaSquared) {
                bestAreaSquared = areaSquared
                bestBase = index
            }
            index += 9
        }
        require(bestAreaSquared > 1e-18) { "No usable triangle face was found for lay flat" }

        val normal = triangleNormal(current, bestBase)
        val candidates = listOf(
            alignVector(normal, doubleArrayOf(0.0, 0.0, 1.0)),
            alignVector(normal, doubleArrayOf(0.0, 0.0, -1.0)),
        )
        val selected = candidates.minBy { candidate ->
            val candidateLinear = multiply(candidate, linear)
            val candidatePositions = transformedPositions(mesh, candidateLinear)
            val faceZ = (
                candidatePositions[bestBase + 2] +
                    candidatePositions[bestBase + 5] +
                    candidatePositions[bestBase + 8]
                ) / 3.0
            val candidateBounds = boundsOf(candidatePositions)
            abs(faceZ - candidateBounds.minZ)
        }
        return copy(
            linear = multiply(selected, linear),
            baseZmm = 0.0,
            source = "Laid flat on largest face",
        )
    }

    private fun transformedPositions(mesh: StlMesh, matrix: List<Double> = linear): DoubleArray {
        val output = DoubleArray(mesh.triangleCount * 9)
        var input = 0
        var out = 0
        repeat(mesh.triangleCount * 3) {
            val x = mesh.interleavedVertices[input].toDouble()
            val y = mesh.interleavedVertices[input + 1].toDouble()
            val z = mesh.interleavedVertices[input + 2].toDouble()
            output[out++] = matrix[0] * x + matrix[1] * y + matrix[2] * z
            output[out++] = matrix[3] * x + matrix[4] * y + matrix[5] * z
            output[out++] = matrix[6] * x + matrix[7] * y + matrix[8] * z
            input += 6
        }
        return output
    }

    enum class Axis { X, Y, Z }

    companion object {
        val IDENTITY = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        fun centeredOnBed(mesh: StlMesh, bedWidthMm: Double, bedDepthMm: Double): ModelPlacement = ModelPlacement(
            centerXmm = bedWidthMm / 2.0,
            centerYmm = bedDepthMm / 2.0,
            baseZmm = 0.0,
        )

        fun from3mf(
            mesh: StlMesh,
            affine: Affine3mf,
            dropToBuildPlate: Boolean,
        ): ModelPlacement {
            require(affine.linear.size == 9)

            // Cura's build-item translation is defined against the mesh stored
            // inside the 3MF. An independently imported STL may contain the same
            // geometry in a different coordinate frame (for example already
            // centred at X/Y=115). Use the final bounds calculated from the
            // embedded 3MF object instead of adding the translation to the
            // external STL's existing centre a second time.
            val targetCenterX = affine.targetCenterXmm ?: affine.translationXmm
            val targetCenterY = affine.targetCenterYmm ?: affine.translationYmm
            val targetBaseZ = affine.targetBaseZmm ?: affine.translationZmm

            return ModelPlacement(
                linear = affine.linear,
                centerXmm = targetCenterX,
                centerYmm = targetCenterY,
                baseZmm = if (dropToBuildPlate) 0.0 else targetBaseZ,
                source = if (dropToBuildPlate) "Imported Cura transform · drop to bed" else "Imported Cura transform",
            )
        }

        private fun multiply(a: List<Double>, b: List<Double>): List<Double> = List(9) { index ->
            val row = index / 3
            val column = index % 3
            a[row * 3] * b[column] +
                a[row * 3 + 1] * b[3 + column] +
                a[row * 3 + 2] * b[6 + column]
        }

        private fun alignVector(fromRaw: DoubleArray, toRaw: DoubleArray): List<Double> {
            val from = normalized(fromRaw)
            val to = normalized(toRaw)
            val vx = from[1] * to[2] - from[2] * to[1]
            val vy = from[2] * to[0] - from[0] * to[2]
            val vz = from[0] * to[1] - from[1] * to[0]
            val dot = (from[0] * to[0] + from[1] * to[1] + from[2] * to[2]).coerceIn(-1.0, 1.0)
            val crossLength = sqrt(vx * vx + vy * vy + vz * vz)
            if (crossLength < 1e-12) {
                if (dot > 0.0) return IDENTITY
                val axis = if (abs(from[0]) < 0.9) normalized(doubleArrayOf(0.0, -from[2], from[1]))
                else normalized(doubleArrayOf(-from[1], from[0], 0.0))
                return axisAngle(axis, Math.PI)
            }
            return axisAngle(doubleArrayOf(vx / crossLength, vy / crossLength, vz / crossLength), acos(dot))
        }

        private fun axisAngle(axis: DoubleArray, angle: Double): List<Double> {
            val x = axis[0]
            val y = axis[1]
            val z = axis[2]
            val c = cos(angle)
            val s = sin(angle)
            val one = 1.0 - c
            return listOf(
                c + x * x * one, x * y * one - z * s, x * z * one + y * s,
                y * x * one + z * s, c + y * y * one, y * z * one - x * s,
                z * x * one - y * s, z * y * one + x * s, c + z * z * one,
            )
        }

        private fun normalized(value: DoubleArray): DoubleArray {
            val length = sqrt(value[0] * value[0] + value[1] * value[1] + value[2] * value[2])
            require(length > 1e-12) { "Cannot normalize a zero-length vector" }
            return doubleArrayOf(value[0] / length, value[1] / length, value[2] / length)
        }

        private fun triangleNormal(values: DoubleArray, base: Int): DoubleArray {
            val ax = values[base + 3] - values[base]
            val ay = values[base + 4] - values[base + 1]
            val az = values[base + 5] - values[base + 2]
            val bx = values[base + 6] - values[base]
            val by = values[base + 7] - values[base + 1]
            val bz = values[base + 8] - values[base + 2]
            return normalized(doubleArrayOf(ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx))
        }

        private fun normal(vertices: FloatArray): FloatArray {
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
                nx /= length; ny /= length; nz /= length
            }
            return floatArrayOf(nx, ny, nz)
        }

        private fun boundsOf(values: DoubleArray): BoundsDouble {
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var minZ = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            var maxZ = Double.NEGATIVE_INFINITY
            var index = 0
            while (index < values.size) {
                val x = values[index]
                val y = values[index + 1]
                val z = values[index + 2]
                minX = minOf(minX, x); maxX = maxOf(maxX, x)
                minY = minOf(minY, y); maxY = maxOf(maxY, y)
                minZ = minOf(minZ, z); maxZ = maxOf(maxZ, z)
                index += 3
            }
            return BoundsDouble(minX, minY, minZ, maxX, maxY, maxZ)
        }

        private fun boundsOfInterleaved(values: FloatArray): MeshBounds {
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var minZ = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            var maxZ = Float.NEGATIVE_INFINITY
            var index = 0
            while (index < values.size) {
                val x = values[index]
                val y = values[index + 1]
                val z = values[index + 2]
                minX = minOf(minX, x); maxX = maxOf(maxX, x)
                minY = minOf(minY, y); maxY = maxOf(maxY, y)
                minZ = minOf(minZ, z); maxZ = maxOf(maxZ, z)
                index += 6
            }
            return MeshBounds(minX, minY, minZ, maxX, maxY, maxZ)
        }

        private data class BoundsDouble(
            val minX: Double,
            val minY: Double,
            val minZ: Double,
            val maxX: Double,
            val maxY: Double,
            val maxZ: Double,
        ) {
            val centerX: Double get() = (minX + maxX) / 2.0
            val centerY: Double get() = (minY + maxY) / 2.0
        }
    }
}
