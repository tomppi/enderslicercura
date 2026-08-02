package com.tomppi.enderslicer.calibration

import com.tomppi.enderslicer.engine.CalibrationFirmwareEncoder
import com.tomppi.enderslicer.engine.LayerEventType
import com.tomppi.enderslicer.engine.PrinterEnvelope
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.smartinfill.SmartInfillPackage
import com.tomppi.enderslicer.smartinfill.SmartInfillRuntime
import com.tomppi.enderslicer.smartinfill.applyTo
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

    fun effective(
        settings: SlicerSettings,
        smartInfillPackage: SmartInfillPackage? = SmartInfillRuntime.current(),
    ): SlicerSettings {
        val baseSettings = smartInfillPackage?.applyTo(settings) ?: settings
        val type = activeType ?: return baseSettings
        if (type == CalibrationTestType.RETRACTION) {
            restoreRetractionDistanceMm = baseSettings.retractionDistanceMm
            restoreRetractionSpeedMmPerSecond = baseSettings.retractionSpeedMmPerSecond
        }

        val policy = policy(type)
        val forcedKeys = buildSet {
            addAll(baseSettings.overriddenSettingKeys)
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

        return baseSettings.copy(
            supportsEnabled = false,
            supportInterfaceEnabled = false,
            adaptiveLayerHeightEnabled = if (policy.disableAdaptiveLayers) false else baseSettings.adaptiveLayerHeightEnabled,
            arcOverhangEnabled = if (policy.disableArcOverhangs) false else baseSettings.arcOverhangEnabled,
            ironingEnabled = if (policy.disableIroning) false else baseSettings.ironingEnabled,
            coastingEnabled = if (policy.disableCoasting) false else baseSettings.coastingEnabled,
            firmwareRetraction = baseSettings.firmwareRetraction || type == CalibrationTestType.RETRACTION,
            nozzleTemperatureC = firstTemperature ?: baseSettings.nozzleTemperatureC,
            initialNozzleTemperatureC = firstTemperature ?: baseSettings.initialNozzleTemperatureC,
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

    fun retractionRestoreCommand(
        firmware: CalibrationFirmwareEncoder = defaultFirmware(),
    ): String? {
        if (activeType != CalibrationTestType.RETRACTION) return null
        val distance = restoreRetractionDistanceMm ?: return null
        val speed = restoreRetractionSpeedMmPerSecond ?: return null
        return firmware.commands(
            type = LayerEventType.RETRACTION,
            layerNumber = 0,
            value = distance,
            secondaryValue = speed,
        ).singleOrNull()
    }

    fun pressureAdvanceRestoreCommand(
        firmware: CalibrationFirmwareEncoder = defaultFirmware(),
    ): String? {
        if (activeType != CalibrationTestType.PRESSURE_ADVANCE) return null
        val baseline = firstValue ?: return null
        return firmware.commands(
            type = LayerEventType.PRESSURE_ADVANCE,
            layerNumber = 0,
            value = baseline,
        ).singleOrNull()
    }

    fun junctionDeviationRestoreCommand(
        firmware: CalibrationFirmwareEncoder = defaultFirmware(),
    ): String? {
        if (activeType != CalibrationTestType.JUNCTION_DEVIATION) return null
        val baseline = firstValue ?: return null
        return firmware.commands(
            type = LayerEventType.JUNCTION_DEVIATION,
            layerNumber = 0,
            value = baseline,
        ).singleOrNull()
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

    private fun defaultFirmware(): CalibrationFirmwareEncoder =
        CalibrationFirmwareEncoder.fromFlavor(PrinterEnvelope.DEFAULT_GCODE_FLAVOR)
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
