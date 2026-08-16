package com.tomppi.enderslicer.viewer

import android.opengl.Matrix
import com.tomppi.enderslicer.model.PrinterDefinition
import kotlin.math.sqrt

/** CPU ray-cast from a screen point against the displayed model triangles. */
object MeshPicker {
    data class CameraSnapshot(
        val viewportWidth: Float,
        val viewportHeight: Float,
        val yaw: Float,
        val pitch: Float,
        val zoom: Float,
        val panX: Float,
        val panY: Float,
        val meshBounds: MeshBounds?,
    )

    data class Hit(
        val triangleIndex: Int,
        val x: Float,
        val y: Float,
        val z: Float,
    )

    fun pick(
        mesh: StlMesh,
        printer: PrinterDefinition,
        camera: CameraSnapshot,
        screenX: Float,
        screenY: Float,
    ): Hit? {
        if (camera.viewportWidth <= 0f || camera.viewportHeight <= 0f) return null
        val aspect = camera.viewportWidth / camera.viewportHeight
        val fit = SceneCameraFit.calculate(
            printer = printer,
            meshBounds = camera.meshBounds,
            aspect = aspect,
            zoom = camera.zoom,
            verticalFieldOfViewDegrees = FIELD_OF_VIEW_DEGREES,
        )

        val projection = FloatArray(16)
        val view = FloatArray(16)
        val scene = FloatArray(16)
        val viewScene = FloatArray(16)
        val mvp = FloatArray(16)
        val inverse = FloatArray(16)

        Matrix.perspectiveM(projection, 0, FIELD_OF_VIEW_DEGREES, aspect, fit.nearPlane, fit.farPlane)
        Matrix.setLookAtM(view, 0, 0f, -fit.distance, fit.distance * CAMERA_ELEVATION_RATIO, 0f, 0f, 0f, 0f, 0f, 1f)
        Matrix.translateM(view, 0, camera.panX, camera.panY, 0f)
        Matrix.setIdentityM(scene, 0)
        Matrix.rotateM(scene, 0, camera.pitch, 1f, 0f, 0f)
        Matrix.rotateM(scene, 0, camera.yaw, 0f, 0f, 1f)
        Matrix.translateM(scene, 0, -fit.centerX, -fit.centerY, -fit.centerZ)
        Matrix.multiplyMM(viewScene, 0, view, 0, scene, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, viewScene, 0)
        if (!Matrix.invertM(inverse, 0, mvp, 0)) return null

        val ndcX = (2f * screenX) / camera.viewportWidth - 1f
        val ndcY = 1f - (2f * screenY) / camera.viewportHeight
        val near = unproject(inverse, ndcX, ndcY, -1f)
        val far = unproject(inverse, ndcX, ndcY, 1f)
        val dx = far[0] - near[0]
        val dy = far[1] - near[1]
        val dz = far[2] - near[2]
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (length <= 1e-6f) return null
        val rx = dx / length
        val ry = dy / length
        val rz = dz / length

        val vertices = mesh.interleavedVertices
        val count = mesh.triangleCount
        var bestT = Float.POSITIVE_INFINITY
        var bestIndex = -1
        var bestX = 0f
        var bestY = 0f
        var bestZ = 0f
        for (triangle in 0 until count) {
            val base = triangle * 18
            val t = rayTriangle(
                near[0], near[1], near[2], rx, ry, rz,
                vertices[base], vertices[base + 1], vertices[base + 2],
                vertices[base + 6], vertices[base + 7], vertices[base + 8],
                vertices[base + 12], vertices[base + 13], vertices[base + 14],
            ) ?: continue
            if (t > 0f && t < bestT) {
                bestT = t
                bestIndex = triangle
                bestX = near[0] + rx * t
                bestY = near[1] + ry * t
                bestZ = near[2] + rz * t
            }
        }
        if (bestIndex < 0) return null
        return Hit(bestIndex, bestX, bestY, bestZ)
    }

    private fun unproject(inverse: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val clip = floatArrayOf(x, y, z, 1f)
        val world = FloatArray(4)
        Matrix.multiplyMV(world, 0, inverse, 0, clip, 0)
        val w = world[3]
        return if (w == 0f) {
            floatArrayOf(world[0], world[1], world[2])
        } else {
            floatArrayOf(world[0] / w, world[1] / w, world[2] / w)
        }
    }

    private fun rayTriangle(
        ox: Float, oy: Float, oz: Float,
        rx: Float, ry: Float, rz: Float,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
    ): Float? {
        val e1x = bx - ax; val e1y = by - ay; val e1z = bz - az
        val e2x = cx - ax; val e2y = cy - ay; val e2z = cz - az
        val px = ry * e2z - rz * e2y
        val py = rz * e2x - rx * e2z
        val pz = rx * e2y - ry * e2x
        val det = e1x * px + e1y * py + e1z * pz
        if (det > -EPSILON && det < EPSILON) return null
        val invDet = 1f / det
        val tx = ox - ax; val ty = oy - ay; val tz = oz - az
        val u = (tx * px + ty * py + tz * pz) * invDet
        if (u < 0f || u > 1f) return null
        val qx = ty * e1z - tz * e1y
        val qy = tz * e1x - tx * e1z
        val qz = tx * e1y - ty * e1x
        val v = (rx * qx + ry * qy + rz * qz) * invDet
        if (v < 0f || u + v > 1f) return null
        return (e2x * qx + e2y * qy + e2z * qz) * invDet
    }

    private const val FIELD_OF_VIEW_DEGREES = 42f
    private const val CAMERA_ELEVATION_RATIO = 0.62f
    private const val EPSILON = 1e-6f
}
