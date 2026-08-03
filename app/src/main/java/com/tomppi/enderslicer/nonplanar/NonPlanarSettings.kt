package com.tomppi.enderslicer.nonplanar

import android.content.Context

/** User-facing controls for the Android CurviSlicer pipeline. */
data class NonPlanarSettings(
    val enabled: Boolean = false,
    val strengthPercent: Double = 70.0,
    val smoothingRadiusMm: Double = 3.0,
    val maximumSlopeDegrees: Double = 30.0,
    val nozzleClearanceAngleDegrees: Double = 45.0,
    val nozzleClearanceHeightMm: Double = 50.0,
    val flatBaseLayers: Int = 3,
    val fieldResolution: Int = 96,
    val maximumSegmentLengthMm: Double = 0.8,
    val maximumZSpeedMmPerSecond: Double = 5.0,
    val compensateExtrusion: Boolean = true,
    val warpSmartInfillModifiers: Boolean = true,
) {
    fun validated(): NonPlanarSettings = copy(
        strengthPercent = strengthPercent.coerceIn(0.0, 100.0),
        smoothingRadiusMm = smoothingRadiusMm.coerceIn(0.4, 20.0),
        maximumSlopeDegrees = maximumSlopeDegrees.coerceIn(5.0, 55.0),
        nozzleClearanceAngleDegrees = nozzleClearanceAngleDegrees.coerceIn(15.0, 80.0),
        nozzleClearanceHeightMm = nozzleClearanceHeightMm.coerceIn(5.0, 150.0),
        flatBaseLayers = flatBaseLayers.coerceIn(1, 20),
        fieldResolution = fieldResolution.coerceIn(32, 192),
        maximumSegmentLengthMm = maximumSegmentLengthMm.coerceIn(0.2, 3.0),
        maximumZSpeedMmPerSecond = maximumZSpeedMmPerSecond.coerceIn(0.5, 20.0),
    )

    val effectiveSlopeLimitDegrees: Double
        get() = minOf(maximumSlopeDegrees, nozzleClearanceAngleDegrees - CLEARANCE_MARGIN_DEGREES)
            .coerceAtLeast(5.0)

    companion object {
        private const val CLEARANCE_MARGIN_DEGREES = 5.0
    }
}

data class CurviSlicerSnapshot(
    val settings: NonPlanarSettings,
    val generation: Long,
)

/** Process-wide immutable snapshot used to keep one slice internally consistent. */
object CurviSlicerRuntime {
    private val lock = Any()

    @Volatile
    private var snapshot = CurviSlicerSnapshot(NonPlanarSettings(), 0L)

    fun activate(settings: NonPlanarSettings) {
        val safe = settings.validated()
        synchronized(lock) {
            if (snapshot.settings != safe) snapshot = CurviSlicerSnapshot(safe, snapshot.generation + 1L)
        }
    }

    fun snapshot(): CurviSlicerSnapshot? = snapshot.takeIf { it.settings.enabled }

    fun current(): NonPlanarSettings = snapshot.settings
}

class NonPlanarSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): NonPlanarSettings = NonPlanarSettings(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        strengthPercent = number(KEY_STRENGTH, 70.0),
        smoothingRadiusMm = number(KEY_SMOOTHING, 3.0),
        maximumSlopeDegrees = number(KEY_MAX_SLOPE, 30.0),
        nozzleClearanceAngleDegrees = number(KEY_CLEARANCE_ANGLE, 45.0),
        nozzleClearanceHeightMm = number(KEY_CLEARANCE_HEIGHT, 50.0),
        flatBaseLayers = preferences.getInt(KEY_FLAT_BASE_LAYERS, 3),
        fieldResolution = preferences.getInt(KEY_FIELD_RESOLUTION, 96),
        maximumSegmentLengthMm = number(KEY_SEGMENT_LENGTH, 0.8),
        maximumZSpeedMmPerSecond = number(KEY_MAX_Z_SPEED, 5.0),
        compensateExtrusion = preferences.getBoolean(KEY_EXTRUSION_COMPENSATION, true),
        warpSmartInfillModifiers = preferences.getBoolean(KEY_WARP_SMART_INFILL, true),
    ).validated().also(CurviSlicerRuntime::activate)

    fun save(settings: NonPlanarSettings) {
        val safe = settings.validated()
        preferences.edit()
            .putBoolean(KEY_ENABLED, safe.enabled)
            .putString(KEY_STRENGTH, safe.strengthPercent.toString())
            .putString(KEY_SMOOTHING, safe.smoothingRadiusMm.toString())
            .putString(KEY_MAX_SLOPE, safe.maximumSlopeDegrees.toString())
            .putString(KEY_CLEARANCE_ANGLE, safe.nozzleClearanceAngleDegrees.toString())
            .putString(KEY_CLEARANCE_HEIGHT, safe.nozzleClearanceHeightMm.toString())
            .putInt(KEY_FLAT_BASE_LAYERS, safe.flatBaseLayers)
            .putInt(KEY_FIELD_RESOLUTION, safe.fieldResolution)
            .putString(KEY_SEGMENT_LENGTH, safe.maximumSegmentLengthMm.toString())
            .putString(KEY_MAX_Z_SPEED, safe.maximumZSpeedMmPerSecond.toString())
            .putBoolean(KEY_EXTRUSION_COMPENSATION, safe.compensateExtrusion)
            .putBoolean(KEY_WARP_SMART_INFILL, safe.warpSmartInfillModifiers)
            .apply()
        CurviSlicerRuntime.activate(safe)
    }

    private fun number(key: String, fallback: Double): Double =
        preferences.getString(key, null)?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: fallback

    companion object {
        const val BACKEND_NAME = "CurviSlicer Android surface-field backend"
        const val BACKEND_VERSION = 1

        private const val PREFERENCES = "enderslicer-non-planar-v2"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_STRENGTH = "strength-percent"
        private const val KEY_SMOOTHING = "smoothing-radius-mm"
        private const val KEY_MAX_SLOPE = "maximum-slope-degrees"
        private const val KEY_CLEARANCE_ANGLE = "clearance-angle-degrees"
        private const val KEY_CLEARANCE_HEIGHT = "clearance-height-mm"
        private const val KEY_FLAT_BASE_LAYERS = "flat-base-layers"
        private const val KEY_FIELD_RESOLUTION = "field-resolution"
        private const val KEY_SEGMENT_LENGTH = "maximum-segment-length-mm"
        private const val KEY_MAX_Z_SPEED = "maximum-z-speed-mm-s"
        private const val KEY_EXTRUSION_COMPENSATION = "compensate-extrusion"
        private const val KEY_WARP_SMART_INFILL = "warp-smart-infill"
    }
}
