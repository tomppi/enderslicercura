package com.tomppi.enderslicer.calibration

import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationSliceSettingsTest {
    @Test
    fun calibrationSliceForcesSupportKeysWithoutChangingStoredSettings() {
        val original = SlicerSettings(
            supportsEnabled = true,
            supportInterfaceEnabled = true,
            firmwareRetraction = false,
            overriddenSettingKeys = setOf(SlicerSettings.Keys.LAYER_HEIGHT),
        )

        val effective = original.forCalibrationSlice(active = true, requiresFirmwareRetraction = false)

        assertFalse(effective.supportsEnabled)
        assertFalse(effective.supportInterfaceEnabled)
        assertFalse(effective.firmwareRetraction)
        assertTrue(effective.isOverridden(SlicerSettings.Keys.SUPPORTS_ENABLED))
        assertTrue(effective.isOverridden(SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED))
        assertFalse(original.isOverridden(SlicerSettings.Keys.SUPPORTS_ENABLED))
        assertTrue(original.supportsEnabled)
        assertTrue(original.supportInterfaceEnabled)
    }

    @Test
    fun retractionCalibrationEnablesFirmwareRetractionForSliceOnly() {
        val original = SlicerSettings(firmwareRetraction = false)
        val effective = original.forCalibrationSlice(active = true, requiresFirmwareRetraction = true)

        assertTrue(effective.firmwareRetraction)
        assertTrue(effective.isOverridden(SlicerSettings.Keys.FIRMWARE_RETRACTION))
        assertFalse(original.firmwareRetraction)
    }

    @Test
    fun activeCalibrationDisablesUnrelatedProcessingAndSmallLayerSlowdown() {
        val original = SlicerSettings(
            supportsEnabled = true,
            adaptiveLayerHeightEnabled = true,
            arcOverhangEnabled = true,
            ironingEnabled = true,
            coastingEnabled = true,
            firmwareRetraction = false,
        )
        CalibrationSliceState.activate(CalibrationTestType.RETRACTION, 0.5)
        try {
            val effective = CalibrationSliceState.effective(original)
            val overrides = CalibrationSliceState.engineOverrides()
            assertFalse(effective.supportsEnabled)
            assertFalse(effective.adaptiveLayerHeightEnabled)
            assertFalse(effective.arcOverhangEnabled)
            assertFalse(effective.ironingEnabled)
            assertFalse(effective.coastingEnabled)
            assertTrue(effective.firmwareRetraction)
            assertEquals("0", overrides["cool_min_layer_time"])
            assertEquals("true", overrides["retraction_enable"])
            assertEquals("0", overrides["retraction_min_travel"])
            assertEquals("off", overrides["retraction_combing"])
            assertTrue(original.supportsEnabled)
            assertFalse(original.firmwareRetraction)
        } finally {
            CalibrationSliceState.clear()
        }
    }

    @Test
    fun retractionCalibrationCanRestoreConfiguredFirmwareSettings() {
        val original = SlicerSettings(retractionDistanceMm = 1.5, retractionSpeedMmPerSecond = 42.0)
        CalibrationSliceState.activate(CalibrationTestType.RETRACTION, 0.5)
        try {
            CalibrationSliceState.effective(original)
            assertEquals("M207 S1.5 F2520", CalibrationSliceState.retractionRestoreCommand())
        } finally {
            CalibrationSliceState.clear()
        }
    }

    @Test
    fun temperatureCalibrationStartsAtItsFirstRequestedTemperature() {
        val original = SlicerSettings(nozzleTemperatureC = 210, initialNozzleTemperatureC = 235)
        CalibrationSliceState.activate(CalibrationTestType.TEMPERATURE, 225.0)
        try {
            val effective = CalibrationSliceState.effective(original)
            assertEquals(225, effective.nozzleTemperatureC)
            assertEquals(225, effective.initialNozzleTemperatureC)
        } finally {
            CalibrationSliceState.clear()
        }
    }

    @Test
    fun fanCalibrationDisablesCuraOwnedFanControl() {
        CalibrationSliceState.activate(CalibrationTestType.FAN, 0.0)
        try {
            val overrides = CalibrationSliceState.engineOverrides()
            assertEquals("false", overrides["cool_fan_enabled"])
            assertEquals("0", overrides["bridge_fan_speed"])
            assertEquals("0", overrides["cool_min_layer_time"])
        } finally {
            CalibrationSliceState.clear()
        }
    }

    @Test
    fun ordinarySliceIsUnchanged() {
        val original = SlicerSettings(supportsEnabled = true, supportInterfaceEnabled = true)
        assertTrue(original === original.forCalibrationSlice(active = false, requiresFirmwareRetraction = false))
    }
}
