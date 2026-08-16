package com.tomppi.enderslicer.conical

import android.content.Context
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Which cone orientation the conical (EasyConical) warp uses. The forward
 * transform lifts points radially by `sign * sqrt(x^2 + y^2) * tan(theta)`;
 * `OUTWARD` radiates prints outward from the inside, `INWARD` from the outside.
 */
enum class ConeType(val sign: Double) {
    OUTWARD(1.0),
    INWARD(-1.0),
}

/** User-facing controls for the Android EasyConical (conical slicing) pipeline. */
data class ConicalSettings(
    val enabled: Boolean = false,
    val coneAngleDegrees: Double = 16.0,
    val refinementIterations: Int = 1,
    val coneType: ConeType = ConeType.OUTWARD,
    val firstLayerHeightMm: Double = 0.2,
    val xShiftMm: Double = 0.0,
    val yShiftMm: Double = 0.0,
) {
    fun validated(): ConicalSettings = copy(
        coneAngleDegrees = coneAngleDegrees.coerceIn(MIN_CONE_ANGLE_DEGREES, MAX_CONE_ANGLE_DEGREES),
        refinementIterations = refinementIterations.coerceIn(
            MIN_REFINEMENT_ITERATIONS,
            MAX_REFINEMENT_ITERATIONS,
        ),
        firstLayerHeightMm = firstLayerHeightMm.coerceIn(
            MIN_FIRST_LAYER_HEIGHT_MM,
            MAX_FIRST_LAYER_HEIGHT_MM,
        ),
        xShiftMm = xShiftMm.coerceIn(MIN_SHIFT_MM, MAX_SHIFT_MM),
        yShiftMm = yShiftMm.coerceIn(MIN_SHIFT_MM, MAX_SHIFT_MM),
    )

    val coneAngleRadians: Double get() = Math.toRadians(coneAngleDegrees)

    /** Forward cone factors: inverse cosine stretch and tangent lift. */
    val inverseCosine: Double get() = 1.0 / cos(coneAngleRadians)

    val cosine: Double get() = cos(coneAngleRadians)

    val tangent: Double get() = tan(coneAngleRadians)

    companion object {
        const val MIN_CONE_ANGLE_DEGREES = 5.0
        const val MAX_CONE_ANGLE_DEGREES = 60.0
        const val MIN_REFINEMENT_ITERATIONS = 0
        const val MAX_REFINEMENT_ITERATIONS = 3
        const val MIN_FIRST_LAYER_HEIGHT_MM = 0.0
        const val MAX_FIRST_LAYER_HEIGHT_MM = 5.0
        const val MIN_SHIFT_MM = -2000.0
        const val MAX_SHIFT_MM = 2000.0
    }
}

data class ConicalSnapshot(
    val settings: ConicalSettings,
    val generation: Long,
)

/** Process-wide immutable snapshot used to keep one slice internally consistent. */
object ConicalRuntime {
    const val MACHINE_END_SENTINEL = ";ENDERSLICER_CONICAL_MACHINE_END_BEGIN"

    private val lock = Any()

    @Volatile
    private var snapshot = ConicalSnapshot(ConicalSettings(), 0L)

    fun activate(settings: ConicalSettings) {
        val safe = settings.validated()
        synchronized(lock) {
            if (snapshot.settings != safe) snapshot = ConicalSnapshot(safe, snapshot.generation + 1L)
        }
    }

    fun snapshot(): ConicalSnapshot? = snapshot.takeIf { it.settings.enabled }

    fun current(): ConicalSettings = snapshot.settings

    /** Marks the exact boundary where Cura begins executing the machine end script. */
    fun markMachineEndGcode(gcode: String): String {
        if (snapshot() == null) return gcode
        if (gcode.lineSequence().any { it.trim() == MACHINE_END_SENTINEL }) return gcode
        return if (gcode.isBlank()) MACHINE_END_SENTINEL else "$MACHINE_END_SENTINEL\n$gcode"
    }
}

class ConicalSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): ConicalSettings = ConicalSettings(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        coneAngleDegrees = number(KEY_CONE_ANGLE, 16.0),
        refinementIterations = preferences.getInt(KEY_REFINEMENT, 1),
        coneType = preferences.getString(KEY_CONE_TYPE, null)
            ?.let { raw -> runCatching { ConeType.valueOf(raw) }.getOrNull() }
            ?: ConeType.OUTWARD,
        firstLayerHeightMm = number(KEY_FIRST_LAYER, 0.2),
        xShiftMm = number(KEY_X_SHIFT, 0.0),
        yShiftMm = number(KEY_Y_SHIFT, 0.0),
    ).validated().also(ConicalRuntime::activate)

    fun save(settings: ConicalSettings) {
        val safe = settings.validated()
        val changed = ConicalRuntime.current() != safe
        preferences.edit()
            .putBoolean(KEY_ENABLED, safe.enabled)
            .putString(KEY_CONE_ANGLE, safe.coneAngleDegrees.toString())
            .putInt(KEY_REFINEMENT, safe.refinementIterations)
            .putString(KEY_CONE_TYPE, safe.coneType.name)
            .putString(KEY_FIRST_LAYER, safe.firstLayerHeightMm.toString())
            .putString(KEY_X_SHIFT, safe.xShiftMm.toString())
            .putString(KEY_Y_SHIFT, safe.yShiftMm.toString())
            .apply()
        ConicalRuntime.activate(safe)
        if (changed) invalidatePublishedSlices()
    }

    private fun invalidatePublishedSlices() {
        val root = File(appContext.filesDir, "slice-results")
        if (!root.exists()) return
        check(root.deleteRecursively()) {
            "Unable to invalidate G-code created with previous conical slicing settings"
        }
        check(root.mkdirs() || root.isDirectory) {
            "Unable to recreate the slice artifact directory"
        }
    }

    private fun number(key: String, fallback: Double): Double =
        preferences.getString(key, null)?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: fallback

    companion object {
        const val BACKEND_NAME = "EasyConical conical slicing backend"
        const val BACKEND_VERSION = 1

        private const val PREFERENCES = "enderslicer-conical-v1"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_CONE_ANGLE = "cone-angle-degrees"
        private const val KEY_REFINEMENT = "refinement-iterations"
        private const val KEY_CONE_TYPE = "cone-type"
        private const val KEY_FIRST_LAYER = "first-layer-height-mm"
        private const val KEY_X_SHIFT = "x-shift-mm"
        private const val KEY_Y_SHIFT = "y-shift-mm"
    }
}
