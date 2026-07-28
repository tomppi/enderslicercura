package com.tomppi.enderslicer.calibration

import com.tomppi.enderslicer.model.SlicerSettings
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

    fun activate(type: CalibrationTestType, firstLevelValue: Double) {
        activeType = type
        firstValue = firstLevelValue
    }

    fun clear() {
        activeType = null
        firstValue = null
    }

    fun effective(settings: SlicerSettings): SlicerSettings {
        val type = activeType ?: return settings
        val forcedKeys = buildSet {
            addAll(settings.overriddenSettingKeys)
            add(SlicerSettings.Keys.SUPPORTS_ENABLED)
            add(SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED)
            add(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_ENABLED)
            add(SlicerSettings.Keys.ARC_OVERHANG_ENABLED)
            add(SlicerSettings.Keys.IRONING_ENABLED)
            add(SlicerSettings.Keys.COASTING_ENABLED)
            add(SlicerSettings.Keys.ADHESION_TYPE)
            add(SlicerSettings.Keys.SKIRT_LINE_COUNT)
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
            coastingEnabled = false,
            adhesionType = "none",
            skirtLineCount = 0,
            firmwareRetraction = settings.firmwareRetraction || type == CalibrationTestType.RETRACTION,
            nozzleTemperatureC = firstTemperature ?: settings.nozzleTemperatureC,
            initialNozzleTemperatureC = firstTemperature ?: settings.initialNozzleTemperatureC,
            overriddenSettingKeys = forcedKeys,
        )
    }

    /**
     * Cura's small-layer slowdown makes compact calibration towers both slow
     * and misleading (especially speed tests). These values exist only in the
     * temporary engine snapshot.
     */
    fun engineOverrides(): Map<String, String> {
        val type = activeType ?: return emptyMap()
        return linkedMapOf<String, String>().apply {
            put("cool_min_layer_time", "0")
            put("cool_lift_head", "false")
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
        }
    }
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
