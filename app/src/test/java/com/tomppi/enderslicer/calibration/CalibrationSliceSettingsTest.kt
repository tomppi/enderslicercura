package com.tomppi.enderslicer.calibration

import com.tomppi.enderslicer.model.SlicerSettings
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
    fun ordinarySliceIsUnchanged() {
        val original = SlicerSettings(supportsEnabled = true, supportInterfaceEnabled = true)
        assertTrue(original === original.forCalibrationSlice(active = false, requiresFirmwareRetraction = false))
    }
}
