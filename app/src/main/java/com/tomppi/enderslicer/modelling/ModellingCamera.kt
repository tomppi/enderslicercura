package com.tomppi.enderslicer.modelling

import org.json.JSONObject
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** Who is allowed to move the camera right now. */
enum class CameraOwner {
    /** The modelling agent. This is the default, and the state work happens in. */
    AGENT,

    /** The user, who has taken the camera to look at something. */
    USER,
    ;

    val wire: String get() = name.lowercase()
}

/**
 * The single camera the user and the modelling agent share.
 *
 * The two sides look at the same model through different renderers - the app
 * draws it in its own GL viewport, the agent renders it with Cycles inside the
 * Blender engine - so they cannot share a matrix. They can share a *spec*, and
 * the app owns the mapping: it resolves the orbit into an eye/up pair already
 * carried into the model's own frame, so the agent only has to put a camera at
 * `target + eye` looking at `target` with `up`.
 *
 * [yawDeg]/[pitchDeg]/[distanceMm] are the turntable values the viewport itself
 * uses, which is what makes a change round-trip: the agent edits those, the
 * viewport adopts them, and its own next report agrees.
 */
data class ModellingCamera(
    val yawDeg: Float,
    val pitchDeg: Float,
    val distanceMm: Float,
    val targetX: Float,
    val targetY: Float,
    val targetZ: Float,
    val fovDeg: Float = DEFAULT_FOV_DEGREES,
    val owner: CameraOwner = CameraOwner.AGENT,
    /** Bumped on every write, so a reader can tell a new camera from a stale one. */
    val rev: Long = 0L,
) {

    /**
     * Camera position relative to the model centre, in the model's own frame.
     *
     * The viewport does not orbit a camera: it holds one at
     * `(0, -d, 0.62d)` looking at the origin with +Z up, and rotates the model
     * in front of it (pitch about X, then yaw about Z). The equivalent camera
     * for an *unrotated* model is that same eye carried through the same
     * rotation - which is what a renderer that moves the camera instead needs.
     */
    fun eyeOffset(): FloatArray {
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        // Eye in viewport space.
        val x = 0.0
        val y = -distanceMm.toDouble()
        val z = distanceMm.toDouble() * EYE_HEIGHT_RATIO
        return rotate(x, y, z, yaw, pitch)
    }

    /** Viewport up vector, carried into the same frame. */
    fun upVector(): FloatArray = rotate(0.0, 0.0, 1.0, Math.toRadians(yawDeg.toDouble()), Math.toRadians(pitchDeg.toDouble()))

    private fun rotate(x: Double, y: Double, z: Double, yaw: Double, pitch: Double): FloatArray {
        // Yaw about Z first, then pitch about X - the order the viewport applies.
        val x1 = x * cos(yaw) - y * sin(yaw)
        val y1 = x * sin(yaw) + y * cos(yaw)
        val z1 = z
        val y2 = y1 * cos(pitch) - z1 * sin(pitch)
        val z2 = y1 * sin(pitch) + z1 * cos(pitch)
        return floatArrayOf(x1.toFloat(), y2.toFloat(), z2.toFloat())
    }

    fun withOwner(owner: CameraOwner): ModellingCamera = copy(owner = owner, rev = rev + 1)

    fun toJson(): JSONObject {
        val eye = eyeOffset()
        val up = upVector()
        return JSONObject()
            .put("yawDeg", yawDeg.toDouble())
            .put("pitchDeg", pitchDeg.toDouble())
            .put("distanceMm", distanceMm.toDouble())
            .put("fovDeg", fovDeg.toDouble())
            .put("target", doubleArrayOf(targetX.toDouble(), targetY.toDouble(), targetZ.toDouble()).toJson())
            .put("eye", eye.map(::toDouble).toJson())
            .put("up", up.map(::toDouble).toJson())
            .put("owner", owner.wire)
            .put("rev", rev)
    }

    companion object {
        const val DEFAULT_FOV_DEGREES = 42f

        /** Eye height as a fraction of the orbit distance; matches the viewport. */
        const val EYE_HEIGHT_RATIO = 0.62

        fun fromJson(json: JSONObject): ModellingCamera {
            val target = json.optJSONArray("target")
            return ModellingCamera(
                yawDeg = json.optDouble("yawDeg", 0.0).toFloat(),
                pitchDeg = json.optDouble("pitchDeg", 0.0).toFloat(),
                distanceMm = json.optDouble("distanceMm", 0.0).toFloat(),
                targetX = target?.optDouble(0, 0.0)?.toFloat() ?: 0f,
                targetY = target?.optDouble(1, 0.0)?.toFloat() ?: 0f,
                targetZ = target?.optDouble(2, 0.0)?.toFloat() ?: 0f,
                fovDeg = json.optDouble("fovDeg", DEFAULT_FOV_DEGREES.toDouble()).toFloat(),
                owner = if (json.optString("owner") == CameraOwner.USER.wire) CameraOwner.USER else CameraOwner.AGENT,
                rev = json.optLong("rev", 0L),
            )
        }

        private fun toDouble(value: Float): Double = value.toDouble()

        private fun DoubleArray.toJson(): org.json.JSONArray {
            val array = org.json.JSONArray()
            forEach(array::put)
            return array
        }

        private fun List<Double>.toJson(): org.json.JSONArray {
            val array = org.json.JSONArray()
            forEach(array::put)
            return array
        }
    }
}

/**
 * Reads and writes [ModellingCamera] where the agent can reach it.
 *
 * The file lives in the engine's own directory tree on purpose: the Blender
 * addon runs in this process as the same uid, so `bpy` code can read and write
 * it with plain `open()`, with no bridge and no protocol.
 */
object ModellingCameraStore {

    /** `files/blender/camera.json`, next to the engine's imports and exports. */
    fun fileFor(blenderDir: File): File = File(blenderDir, "camera.json")

    fun read(blenderDir: File): ModellingCamera? = runCatching {
        val file = fileFor(blenderDir)
        if (!file.isFile) return null
        ModellingCamera.fromJson(JSONObject(file.readText()))
    }.getOrNull()

    fun write(blenderDir: File, camera: ModellingCamera) {
        runCatching {
            blenderDir.mkdirs()
            val file = fileFor(blenderDir)
            // Write beside and rename: the agent polls this file, and a torn
            // read of a half-written camera is worse than no camera at all.
            val temp = File(blenderDir, "camera.json.tmp")
            temp.writeText(camera.toJson().toString())
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }
}
