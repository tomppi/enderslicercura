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
        val disableCoasting = type == CalibrationTestType.FLOW || type == CalibrationTestType.PRESSURE_ADVANCE
        val forcedKeys = buildSet {
            addAll(settings.overriddenSettingKeys)
            add(SlicerSettings.Keys.SUPPORTS_ENABLED)
            add(SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED)
            add(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_ENABLED)
            add(SlicerSettings.Keys.ARC_OVERHANG_ENABLED)
            add(SlicerSettings.Keys.IRONING_ENABLED)
            if (disableCoasting) add(SlicerSettings.Keys.COASTING_ENABLED)
            if (type == CalibrationTestType.RETRACTION) {
                add(SlicerSettings.Keys.FIRMWARE_RETRACTION)
            }
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
            adaptiveLayerHeightEnabled = false,
            arcOverhangEnabled = false,
            ironingEnabled = false,
            coastingEnabled = if (disableCoasting) false else settings.coastingEnabled,
            firmwareRetraction = settings.firmwareRetraction || type == CalibrationTestType.RETRACTION,
            nozzleTemperatureC = firstTemperature ?: settings.nozzleTemperatureC,
            initialNozzleTemperatureC = firstTemperature ?: settings.initialNozzleTemperatureC,
            overriddenSettingKeys = forcedKeys,
        )
    }

    /**
     * Only tests that require commanded speed or extrusion-pressure transitions
     * bypass Cura's minimum-layer-time slowdown. Temperature, fan and retraction
     * tests keep the profile's normal cooling and small-layer behavior.
     */
    fun engineOverrides(): Map<String, String> {
        val type = activeType ?: return emptyMap()
        return linkedMapOf<String, String>().apply {
            if (
                type == CalibrationTestType.FLOW ||
                type == CalibrationTestType.SPEED ||
                type == CalibrationTestType.PRESSURE_ADVANCE ||
                type == CalibrationTestType.JUNCTION_DEVIATION
            ) {
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
                // Guarantee that even short travel gaps are eligible for a
                // firmware retract, while preserving the profile's normal
                // combing, cooling, coasting, wipe, hop and travel behavior.
                put("retraction_enable", "true")
                put("retraction_min_travel", "0")
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

    private fun format(value: Double): String =
        String.format(Locale.US, "%.5f", value).trimEnd('0').trimEnd('.')
}

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
