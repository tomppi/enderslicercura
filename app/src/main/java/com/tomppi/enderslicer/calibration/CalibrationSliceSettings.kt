package com.tomppi.enderslicer.calibration

import com.tomppi.enderslicer.model.SlicerSettings
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Tracks the generated calibration model currently loaded in the workspace.
 * Calibration overrides are temporary slice inputs; saved printer/profile
 * settings are never mutated.
 */
internal object CalibrationSliceState {
    @Volatile
    private var activeType: CalibrationTestType? = null

    @Volatile
    private var firstValue: Double? = null

    @Volatile
    private var restoreRetractionDistanceMm: Double? = null

    @Volatile
    private var restoreRetractionSpeedMmPerSecond: Double? = null

    fun activate(type: CalibrationTestType, firstLevelValue: Double) {
        activeType = type
        firstValue = firstLevelValue
        restoreRetractionDistanceMm = null
        restoreRetractionSpeedMmPerSecond = null
    }

    fun clear() {
        activeType = null
        firstValue = null
        restoreRetractionDistanceMm = null
        restoreRetractionSpeedMmPerSecond = null
    }

    fun effective(settings: SlicerSettings): SlicerSettings {
        val type = activeType ?: return settings
        if (type == CalibrationTestType.RETRACTION) {
            restoreRetractionDistanceMm = settings.retractionDistanceMm
            restoreRetractionSpeedMmPerSecond = settings.retractionSpeedMmPerSecond
        }

        val policy = policy(type)
        val forcedKeys = buildSet {
            addAll(settings.overriddenSettingKeys)
            add(SlicerSettings.Keys.SUPPORTS_ENABLED)
            add(SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED)
            if (policy.disableAdaptiveLayers) add(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_ENABLED)
            if (policy.disableArcOverhangs) add(SlicerSettings.Keys.ARC_OVERHANG_ENABLED)
            if (policy.disableIroning) add(SlicerSettings.Keys.IRONING_ENABLED)
            if (policy.disableCoasting) add(SlicerSettings.Keys.COASTING_ENABLED)
            if (type == CalibrationTestType.RETRACTION) add(SlicerSettings.Keys.FIRMWARE_RETRACTION)
            if (type == CalibrationTestType.TEMPERATURE) {
                add(SlicerSettings.Keys.NOZZLE_TEMPERATURE)
                add(SlicerSettings.Keys.INITIAL_NOZZLE_TEMPERATURE)
            }
        }

        val firstTemperature = firstValue
            ?.takeIf { type == CalibrationTestType.TEMPERATURE }
            ?.roundToInt()
            ?.coerceIn(150, 500)

        return settings.copy(
            supportsEnabled = false,
            supportInterfaceEnabled = false,
            adaptiveLayerHeightEnabled = if (policy.disableAdaptiveLayers) false else settings.adaptiveLayerHeightEnabled,
            arcOverhangEnabled = if (policy.disableArcOverhangs) false else settings.arcOverhangEnabled,
            ironingEnabled = if (policy.disableIroning) false else settings.ironingEnabled,
            coastingEnabled = if (policy.disableCoasting) false else settings.coastingEnabled,
            firmwareRetraction = settings.firmwareRetraction || type == CalibrationTestType.RETRACTION,
            nozzleTemperatureC = firstTemperature ?: settings.nozzleTemperatureC,
            initialNozzleTemperatureC = firstTemperature ?: settings.initialNozzleTemperatureC,
            overriddenSettingKeys = forcedKeys,
        )
    }

    /**
     * The engine override map follows the same minimal policy: ordinary profile
     * behavior is retained unless it would prevent the requested variable from
     * being exercised or would replace the feature being inspected.
     */
    fun engineOverrides(): Map<String, String> {
        val type = activeType ?: return emptyMap()
        val policy = policy(type)
        return linkedMapOf<String, String>().apply {
            if (policy.disableSmallLayerSlowdown) {
                put("cool_min_layer_time", "0")
                put("cool_lift_head", "false")
            }
            if (type == CalibrationTestType.FAN) {
                // Post-slice calibration events own the fan after the base.
                put("cool_fan_enabled", "false")
                put("cool_fan_speed", "0")
                put("cool_fan_speed_min", "0")
                put("cool_fan_speed_max", "0")
                put("bridge_fan_speed", "0")
                put("bridge_fan_speed_2", "0")
                put("bridge_fan_speed_3", "0")
            }
            if (type == CalibrationTestType.RETRACTION) {
                // A retraction calibration is invalid if Cura decides not to
                // retract. Force eligibility for every post-to-post travel, but
                // retain cooling, coasting, wipe, hop and normal travel speeds.
                put("retraction_enable", "true")
                put("retraction_min_travel", "0")
                put("retraction_combing", "off")
            }
        }
    }

    fun retractionRestoreCommand(): String? {
        if (activeType != CalibrationTestType.RETRACTION) return null
        val distance = restoreRetractionDistanceMm ?: return null
        val speed = restoreRetractionSpeedMmPerSecond ?: return null
        return "M207 S${format(distance)} F${format(speed * 60.0)}"
    }

    fun pressureAdvanceRestoreCommand(): String? {
        if (activeType != CalibrationTestType.PRESSURE_ADVANCE) return null
        val baseline = firstValue ?: return null
        return "M900 K${format(baseline)}"
    }

    fun junctionDeviationRestoreCommand(): String? {
        if (activeType != CalibrationTestType.JUNCTION_DEVIATION) return null
        val baseline = firstValue ?: return null
        return "M205 J${format(baseline)}"
    }

    internal fun policyForTests(type: CalibrationTestType): CalibrationOverridePolicy = policy(type)

    private fun policy(type: CalibrationTestType): CalibrationOverridePolicy = when (type) {
        CalibrationTestType.TEMPERATURE -> CalibrationOverridePolicy(
            disableAdaptiveLayers = true,
            disableArcOverhangs = true,
        )
        CalibrationTestType.FLOW -> CalibrationOverridePolicy(
            disableAdaptiveLayers = true,
            disableIroning = true,
            disableCoasting = true,
        )
        CalibrationTestType.SPEED -> CalibrationOverridePolicy(
            disableAdaptiveLayers = true,
            disableSmallLayerSlowdown = true,
        )
        CalibrationTestType.FAN -> CalibrationOverridePolicy(
            disableAdaptiveLayers = true,
            disableArcOverhangs = true,
            disableSmallLayerSlowdown = true,
        )
        CalibrationTestType.RETRACTION -> CalibrationOverridePolicy()
        CalibrationTestType.PRESSURE_ADVANCE -> CalibrationOverridePolicy(
            disableAdaptiveLayers = true,
            disableCoasting = true,
            disableSmallLayerSlowdown = true,
        )
        CalibrationTestType.JUNCTION_DEVIATION -> CalibrationOverridePolicy(
            disableAdaptiveLayers = true,
            disableSmallLayerSlowdown = true,
        )
    }

    private fun format(value: Double): String =
        String.format(Locale.US, "%.5f", value).trimEnd('0').trimEnd('.')
}

internal data class CalibrationOverridePolicy(
    val disableAdaptiveLayers: Boolean = false,
    val disableArcOverhangs: Boolean = false,
    val disableIroning: Boolean = false,
    val disableCoasting: Boolean = false,
    val disableSmallLayerSlowdown: Boolean = false,
)

/**
 * Small helper retained for direct unit tests and callers that only need the
 * original support/firmware-retraction subset of calibration behavior.
 */
internal fun SlicerSettings.forCalibrationSlice(
    active: Boolean,
    requiresFirmwareRetraction: Boolean,
): SlicerSettings {
    if (!active) return this
    val forcedKeys = buildSet {
        addAll(overriddenSettingKeys)
        add(SlicerSettings.Keys.SUPPORTS_ENABLED)
        add(SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED)
        if (requiresFirmwareRetraction) add(SlicerSettings.Keys.FIRMWARE_RETRACTION)
    }
    return copy(
        supportsEnabled = false,
        supportInterfaceEnabled = false,
        firmwareRetraction = firmwareRetraction || requiresFirmwareRetraction,
        overriddenSettingKeys = forcedKeys,
    )
}
