package com.tomppi.enderslicer.viewer

import android.opengl.Matrix
import com.tomppi.enderslicer.model.PrinterDefinition
import kotlin.math.sqrt

/**
 * CPU ray-cast from a screen point against the displayed model triangles.
 *
 * Two caches keep this cheap enough to call per paint sample:
 *
 *  - the [MeshBvh] hierarchy is built once per mesh, so a pick visits a handful
 *    of nodes instead of running a triangle test per triangle in the model;
 *  - the camera fit and MVP matrices are recomputed only when the
 *    [CameraSnapshot] changes, instead of allocating five matrices and
 *    inverting the MVP on every call.
 *
 * Picks run on the viewer's paint executor while the GL thread mutates the
 * camera, so the cached state is guarded, and steady-state picks allocate
 * nothing.
 */
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

    private val lock = Any()

    private var cachedMesh: StlMesh? = null
    private var cachedBvh: MeshBvh? = null
    private var cachedCamera: CameraSnapshot? = null

    private val inverseMvp = FloatArray(16)
    private val projectionScratch = FloatArray(16)
    private val viewScratch = FloatArray(16)
    private val sceneScratch = FloatArray(16)
    private val viewSceneScratch = FloatArray(16)
    private val mvpScratch = FloatArray(16)
    private val clipScratch = FloatArray(4)
    private val worldScratch = FloatArray(4)
    private val nearPoint = FloatArray(3)
    private val farPoint = FloatArray(3)

    /** Drops the per-mesh hierarchy; call when the model is replaced or released. */
    fun invalidate() {
        synchronized(lock) {
            cachedMesh = null
            cachedBvh = null
            cachedCamera = null
        }
    }

    fun pick(
        mesh: StlMesh,
        printer: PrinterDefinition,
        camera: CameraSnapshot,
        screenX: Float,
        screenY: Float,
    ): Hit? {
        if (camera.viewportWidth <= 0f || camera.viewportHeight <= 0f) return null
        synchronized(lock) {
            val hierarchy = hierarchyFor(mesh) ?: return null
            updateCamera(printer, camera)

            val ndcX = (2f * screenX) / camera.viewportWidth - 1f
            val ndcY = 1f - (2f * screenY) / camera.viewportHeight
            unprojectInto(ndcX, ndcY, -1f, nearPoint)
            unprojectInto(ndcX, ndcY, 1f, farPoint)

            val dx = farPoint[0] - nearPoint[0]
            val dy = farPoint[1] - nearPoint[1]
            val dz = farPoint[2] - nearPoint[2]
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            if (length <= 1e-6f) return null

            val hit = hierarchy.raycast(
                nearPoint[0], nearPoint[1], nearPoint[2],
                dx / length, dy / length, dz / length,
            ) ?: return null
            return Hit(hit.triangleIndex, hit.x, hit.y, hit.z)
        }
    }

    private fun hierarchyFor(mesh: StlMesh): MeshBvh? {
        val existing = cachedBvh
        if (existing != null && cachedMesh === mesh) return existing
        if (mesh.triangleCount <= 0) return null
        val built = MeshBvh.build(mesh)
        cachedMesh = mesh
        cachedBvh = built
        return built
    }

    /** Recomputes the fit and matrices only when the camera actually moved. */
    private fun updateCamera(printer: PrinterDefinition, camera: CameraSnapshot) {
        if (cachedCamera == camera) return
        val aspect = camera.viewportWidth / camera.viewportHeight
        val fit = SceneCameraFit.calculate(
            printer = printer,
            meshBounds = camera.meshBounds,
            aspect = aspect,
            zoom = camera.zoom,
            verticalFieldOfViewDegrees = FIELD_OF_VIEW_DEGREES,
        )

        Matrix.perspectiveM(projectionScratch, 0, FIELD_OF_VIEW_DEGREES, aspect, fit.nearPlane, fit.farPlane)
        Matrix.setLookAtM(
            viewScratch, 0,
            0f, -fit.distance, fit.distance * CAMERA_ELEVATION_RATIO,
            0f, 0f, 0f,
            0f, 0f, 1f,
        )
        Matrix.translateM(viewScratch, 0, camera.panX, camera.panY, 0f)
        Matrix.setIdentityM(sceneScratch, 0)
        Matrix.rotateM(sceneScratch, 0, camera.pitch, 1f, 0f, 0f)
        Matrix.rotateM(sceneScratch, 0, camera.yaw, 0f, 0f, 1f)
        Matrix.translateM(sceneScratch, 0, -fit.centerX, -fit.centerY, -fit.centerZ)
        Matrix.multiplyMM(viewSceneScratch, 0, viewScratch, 0, sceneScratch, 0)
        Matrix.multiplyMM(mvpScratch, 0, projectionScratch, 0, viewSceneScratch, 0)
        if (!Matrix.invertM(inverseMvp, 0, mvpScratch, 0)) {
            // Singular MVP: fall back to the identity so a pick simply misses
            // rather than reading stale matrix state.
            Matrix.setIdentityM(inverseMvp, 0)
        }
        cachedCamera = camera
    }

    private fun unprojectInto(ndcX: Float, ndcY: Float, ndcZ: Float, out: FloatArray) {
        clipScratch[0] = ndcX
        clipScratch[1] = ndcY
        clipScratch[2] = ndcZ
        clipScratch[3] = 1f
        Matrix.multiplyMV(worldScratch, 0, inverseMvp, 0, clipScratch, 0)
        val w = worldScratch[3]
        if (w == 0f) {
            out[0] = worldScratch[0]
            out[1] = worldScratch[1]
            out[2] = worldScratch[2]
        } else {
            out[0] = worldScratch[0] / w
            out[1] = worldScratch[1] / w
            out[2] = worldScratch[2] / w
        }
    }

    private const val FIELD_OF_VIEW_DEGREES = 42f
    private const val CAMERA_ELEVATION_RATIO = 0.62f
    private const val EPSILON = 1e-6f
}
