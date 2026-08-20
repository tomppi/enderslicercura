package com.tomppi.enderslicer.nonplanar

import android.content.Context
import java.io.File

/** User-facing controls for the Android CurviSlicer pipeline. */
data class NonPlanarSettings(
    val enabled: Boolean = false,
    val strengthPercent: Double = 70.0,
    val smoothingRadiusMm: Double = 3.0,
    val maximumSlopeDegrees: Double = 30.0,
    // Clearance to the nearest obstacle, measured from horizontal like every
    // other angle (90 = straight up). 45 degrees is identical in both frames.
    val nozzleClearanceAngleDegrees: Double = 45.0,
    val nozzleClearanceHeightMm: Double = 50.0,
    // Taper angle of the nozzle's own cone, measured from horizontal (the
    // build plate): 90 degrees would be a vertical wall, 75 degrees is a
    // thin cone. The old 30-degree-from-vertical convention is 60 here.
    val nozzleAngleDegrees: Double = 60.0,
    val nozzleProtrusionMm: Double = 5.0,
    val heatingBlockWidthMm: Double = 20.0,
    val heatingBlockDepthMm: Double = 16.0,
    val heatingBlockOffsetXmm: Double = 0.0,
    val heatingBlockOffsetYmm: Double = 0.0,
    val maximumLiftMm: Double = 5.0,
    val flatBaseLayers: Int = 3,
    val fieldResolution: Int = 96,
    val maximumSegmentLengthMm: Double = 0.8,
    val maximumZSpeedMmPerSecond: Double = 5.0,
    val compensateExtrusion: Boolean = true,
    val warpSmartInfillModifiers: Boolean = true,
    val pauseAfterProbe: Boolean = false,
    val drapeMode: Boolean = false,
    val fadeStartPercent: Double = 0.0,
    // True non-planar printing (Ahlers' method): the top layers of the sliced
    // model are projected onto the original 3D surface instead of warping the
    // whole mesh. The nozzle then follows the surface continuously, diving
    // below the layer plane down to the thinnest part of the model.
    val conformalMode: Boolean = false,
    // How many top planar layers are replaced by conformal shells riding
    // conformalShellLayers * layerHeight below the surface.
    val conformalShellLayers: Int = 3,
) {
    fun validated(): NonPlanarSettings = copy(
        strengthPercent = strengthPercent.coerceIn(MIN_STRENGTH_PERCENT, MAX_STRENGTH_PERCENT),
        smoothingRadiusMm = smoothingRadiusMm.coerceIn(MIN_SMOOTHING_RADIUS_MM, MAX_SMOOTHING_RADIUS_MM),
        maximumSlopeDegrees = maximumSlopeDegrees.coerceIn(MIN_SLOPE_DEGREES, MAX_SLOPE_DEGREES),
        nozzleClearanceAngleDegrees = nozzleClearanceAngleDegrees.coerceIn(
            MIN_CLEARANCE_ANGLE_DEGREES,
            MAX_CLEARANCE_ANGLE_DEGREES,
        ),
        nozzleClearanceHeightMm = nozzleClearanceHeightMm.coerceIn(
            MIN_CLEARANCE_HEIGHT_MM,
            MAX_CLEARANCE_HEIGHT_MM,
        ),
        nozzleAngleDegrees = nozzleAngleDegrees.coerceIn(MIN_NOZZLE_ANGLE_DEGREES, MAX_NOZZLE_ANGLE_DEGREES),
        nozzleProtrusionMm = nozzleProtrusionMm.coerceIn(MIN_NOZZLE_PROTRUSION_MM, MAX_NOZZLE_PROTRUSION_MM),
        heatingBlockWidthMm = heatingBlockWidthMm.coerceIn(MIN_BLOCK_SIZE_MM, MAX_BLOCK_SIZE_MM),
        heatingBlockDepthMm = heatingBlockDepthMm.coerceIn(MIN_BLOCK_SIZE_MM, MAX_BLOCK_SIZE_MM),
        heatingBlockOffsetXmm = heatingBlockOffsetXmm.coerceIn(-MAX_BLOCK_OFFSET_MM, MAX_BLOCK_OFFSET_MM),
        heatingBlockOffsetYmm = heatingBlockOffsetYmm.coerceIn(-MAX_BLOCK_OFFSET_MM, MAX_BLOCK_OFFSET_MM),
        maximumLiftMm = maximumLiftMm.coerceIn(MIN_MAXIMUM_LIFT_MM, MAX_MAXIMUM_LIFT_MM),
        flatBaseLayers = flatBaseLayers.coerceIn(MIN_FLAT_BASE_LAYERS, MAX_FLAT_BASE_LAYERS),
        fieldResolution = fieldResolution.coerceIn(MIN_FIELD_RESOLUTION, MAX_FIELD_RESOLUTION),
        maximumSegmentLengthMm = maximumSegmentLengthMm.coerceIn(
            MIN_SEGMENT_LENGTH_MM,
            MAX_SEGMENT_LENGTH_MM,
        ),
        maximumZSpeedMmPerSecond = maximumZSpeedMmPerSecond.coerceIn(
            MIN_Z_SPEED_MM_PER_SECOND,
            MAX_Z_SPEED_MM_PER_SECOND,
        ),
        fadeStartPercent = fadeStartPercent.coerceIn(MIN_FADE_START_PERCENT, MAX_FADE_START_PERCENT),
        conformalShellLayers = conformalShellLayers.coerceIn(MIN_CONFORMAL_SHELL_LAYERS, MAX_CONFORMAL_SHELL_LAYERS),
    )

    // Both the surface slope and the clearance angle are measured from
    // horizontal, so the path may never climb steeper than the obstacle cone.
    val effectiveSlopeLimitDegrees: Double
        get() = minOf(maximumSlopeDegrees, nozzleClearanceAngleDegrees - CLEARANCE_MARGIN_DEGREES)
            .coerceAtLeast(MIN_SLOPE_DEGREES)

    /** Tip to holding object: the nozzle cone plus the block cone height. */
    val holderHeightMm: Double
        get() = nozzleProtrusionMm + nozzleClearanceHeightMm

    companion object {
        const val MIN_STRENGTH_PERCENT = 0.0
        const val MAX_STRENGTH_PERCENT = 100.0
        const val MIN_SMOOTHING_RADIUS_MM = 0.4
        const val MAX_SMOOTHING_RADIUS_MM = 20.0
        const val MIN_SLOPE_DEGREES = 5.0
        const val MAX_SLOPE_DEGREES = 55.0
        const val MIN_CLEARANCE_ANGLE_DEGREES = 15.0
        const val MAX_CLEARANCE_ANGLE_DEGREES = 89.0
        const val MIN_CLEARANCE_HEIGHT_MM = 5.0
        const val MAX_CLEARANCE_HEIGHT_MM = 150.0
        const val MIN_MAXIMUM_LIFT_MM = 0.2
        const val MAX_MAXIMUM_LIFT_MM = 25.0
        const val MIN_NOZZLE_ANGLE_DEGREES = 5.0
        const val MAX_NOZZLE_ANGLE_DEGREES = 89.0
        const val MIN_NOZZLE_PROTRUSION_MM = 0.5
        const val MAX_NOZZLE_PROTRUSION_MM = 30.0
        const val MIN_BLOCK_SIZE_MM = 2.0
        const val MAX_BLOCK_SIZE_MM = 80.0
        const val MAX_BLOCK_OFFSET_MM = 40.0
        const val MIN_FLAT_BASE_LAYERS = 1
        const val MAX_FLAT_BASE_LAYERS = 20
        const val MIN_FIELD_RESOLUTION = 32
        const val MAX_FIELD_RESOLUTION = 192
        const val MIN_SEGMENT_LENGTH_MM = 0.2
        // Effectively unlimited: a huge value keeps the engine's native path
        // segmentation instead of splitting moves. The transformer still
        // slows the feed wherever the configured Z speed would be exceeded.
        const val MAX_SEGMENT_LENGTH_MM = 1000.0
        const val MIN_Z_SPEED_MM_PER_SECOND = 0.5
        const val MAX_Z_SPEED_MM_PER_SECOND = 20.0
        const val MIN_FADE_START_PERCENT = 0.0
        const val MAX_FADE_START_PERCENT = 95.0
        const val MIN_CONFORMAL_SHELL_LAYERS = 1
        const val MAX_CONFORMAL_SHELL_LAYERS = 8
        private const val CLEARANCE_MARGIN_DEGREES = 5.0
    }
}

data class CurviSlicerSnapshot(
    val settings: NonPlanarSettings,
    val generation: Long,
)

/** Process-wide immutable snapshot used to keep one slice internally consistent. */
object CurviSlicerRuntime {
    const val MACHINE_END_SENTINEL = ";ENDERSLICER_MACHINE_END_BEGIN"

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

    /** Marks the exact boundary where Cura begins executing the machine end script. */
    fun markMachineEndGcode(gcode: String): String {
        if (snapshot() == null) return gcode
        if (gcode.lineSequence().any { it.trim() == MACHINE_END_SENTINEL }) return gcode
        return if (gcode.isBlank()) MACHINE_END_SENTINEL else "$MACHINE_END_SENTINEL\n$gcode"
    }
}

class NonPlanarSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): NonPlanarSettings = NonPlanarSettings(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        strengthPercent = number(KEY_STRENGTH, 70.0),
        smoothingRadiusMm = number(KEY_SMOOTHING, 3.0),
        maximumSlopeDegrees = number(KEY_MAX_SLOPE, 30.0),
        nozzleClearanceAngleDegrees = number(KEY_CLEARANCE_ANGLE, 45.0),
        nozzleClearanceHeightMm = number(KEY_CLEARANCE_HEIGHT, 50.0),
        maximumLiftMm = number(KEY_MAXIMUM_LIFT, 5.0),
        nozzleAngleDegrees = number(KEY_NOZZLE_ANGLE, 30.0),
        nozzleProtrusionMm = number(KEY_NOZZLE_PROTRUSION, 5.0),
        heatingBlockWidthMm = number(KEY_BLOCK_WIDTH, 20.0),
        heatingBlockDepthMm = number(KEY_BLOCK_DEPTH, 16.0),
        heatingBlockOffsetXmm = number(KEY_BLOCK_OFFSET_X, 0.0),
        heatingBlockOffsetYmm = number(KEY_BLOCK_OFFSET_Y, 0.0),
        flatBaseLayers = preferences.getInt(KEY_FLAT_BASE_LAYERS, 3),
        fieldResolution = preferences.getInt(KEY_FIELD_RESOLUTION, 96),
        maximumSegmentLengthMm = number(KEY_SEGMENT_LENGTH, 0.8),
        maximumZSpeedMmPerSecond = number(KEY_MAX_Z_SPEED, 5.0),
        compensateExtrusion = preferences.getBoolean(KEY_EXTRUSION_COMPENSATION, true),
        warpSmartInfillModifiers = preferences.getBoolean(KEY_WARP_SMART_INFILL, true),
        pauseAfterProbe = preferences.getBoolean(KEY_PAUSE_AFTER_PROBE, false),
        drapeMode = preferences.getBoolean(KEY_DRAPE_MODE, false),
        fadeStartPercent = number(KEY_FADE_START, 0.0),
        conformalMode = preferences.getBoolean(KEY_CONFORMAL_MODE, false),
        conformalShellLayers = preferences.getInt(KEY_CONFORMAL_SHELLS, 3),
    ).validated().also(CurviSlicerRuntime::activate)

    fun save(settings: NonPlanarSettings) {
        val safe = settings.validated()
        val changed = CurviSlicerRuntime.current() != safe
        preferences.edit()
            .putBoolean(KEY_ENABLED, safe.enabled)
            .putString(KEY_STRENGTH, safe.strengthPercent.toString())
            .putString(KEY_SMOOTHING, safe.smoothingRadiusMm.toString())
            .putString(KEY_MAX_SLOPE, safe.maximumSlopeDegrees.toString())
            .putString(KEY_CLEARANCE_ANGLE, safe.nozzleClearanceAngleDegrees.toString())
            .putString(KEY_CLEARANCE_HEIGHT, safe.nozzleClearanceHeightMm.toString())
            .putString(KEY_MAXIMUM_LIFT, safe.maximumLiftMm.toString())
            .putString(KEY_NOZZLE_ANGLE, safe.nozzleAngleDegrees.toString())
            .putString(KEY_NOZZLE_PROTRUSION, safe.nozzleProtrusionMm.toString())
            .putString(KEY_BLOCK_WIDTH, safe.heatingBlockWidthMm.toString())
            .putString(KEY_BLOCK_DEPTH, safe.heatingBlockDepthMm.toString())
            .putString(KEY_BLOCK_OFFSET_X, safe.heatingBlockOffsetXmm.toString())
            .putString(KEY_BLOCK_OFFSET_Y, safe.heatingBlockOffsetYmm.toString())
            .putInt(KEY_FLAT_BASE_LAYERS, safe.flatBaseLayers)
            .putInt(KEY_FIELD_RESOLUTION, safe.fieldResolution)
            .putString(KEY_SEGMENT_LENGTH, safe.maximumSegmentLengthMm.toString())
            .putString(KEY_MAX_Z_SPEED, safe.maximumZSpeedMmPerSecond.toString())
            .putBoolean(KEY_EXTRUSION_COMPENSATION, safe.compensateExtrusion)
            .putBoolean(KEY_WARP_SMART_INFILL, safe.warpSmartInfillModifiers)
            .putBoolean(KEY_PAUSE_AFTER_PROBE, safe.pauseAfterProbe)
            .putBoolean(KEY_DRAPE_MODE, safe.drapeMode)
            .putString(KEY_FADE_START, safe.fadeStartPercent.toString())
            .putBoolean(KEY_CONFORMAL_MODE, safe.conformalMode)
            .putInt(KEY_CONFORMAL_SHELLS, safe.conformalShellLayers)
            .commit()
        CurviSlicerRuntime.activate(safe)
        if (changed) invalidatePublishedSlices()
    }

    private fun invalidatePublishedSlices() {
        val root = File(appContext.filesDir, "slice-results")
        if (!root.exists()) return
        check(root.deleteRecursively()) { "Unable to invalidate G-code created with previous CurviSlicer settings" }
        check(root.mkdirs() || root.isDirectory) { "Unable to recreate the slice artifact directory" }
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
        private const val KEY_MAXIMUM_LIFT = "maximum-lift-mm"
        private const val KEY_NOZZLE_ANGLE = "nozzle-angle-degrees"
        private const val KEY_NOZZLE_PROTRUSION = "nozzle-protrusion-mm"
        private const val KEY_BLOCK_WIDTH = "heating-block-width-mm"
        private const val KEY_BLOCK_DEPTH = "heating-block-depth-mm"
        private const val KEY_BLOCK_OFFSET_X = "heating-block-offset-x-mm"
        private const val KEY_BLOCK_OFFSET_Y = "heating-block-offset-y-mm"
        private const val KEY_FLAT_BASE_LAYERS = "flat-base-layers"
        private const val KEY_FIELD_RESOLUTION = "field-resolution"
        private const val KEY_SEGMENT_LENGTH = "maximum-segment-length-mm"
        private const val KEY_MAX_Z_SPEED = "maximum-z-speed-mm-s"
        private const val KEY_EXTRUSION_COMPENSATION = "compensate-extrusion"
        private const val KEY_WARP_SMART_INFILL = "warp-smart-infill"
        private const val KEY_PAUSE_AFTER_PROBE = "pause-after-probe"
        private const val KEY_DRAPE_MODE = "drape-mode"
        private const val KEY_FADE_START = "fade-start-percent"
        private const val KEY_CONFORMAL_MODE = "conformal-mode"
        private const val KEY_CONFORMAL_SHELLS = "conformal-shell-layers"
    }
}
