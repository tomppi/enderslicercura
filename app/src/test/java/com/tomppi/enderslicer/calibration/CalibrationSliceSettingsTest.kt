package com.tomppi.enderslicer.calibration

import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationSliceSettingsTest {
    @Test
    fun calibrationSliceDisablesGeneratedSupportWithoutChangingStoredOverrides() {
        val original = SlicerSettings(
            supportsEnabled = true,
            supportInterfaceEnabled = true,
            firmwareRetraction = false,
            overriddenSettingKeys = setOf(SlicerSettings.Keys.SUPPORTS_ENABLED),
        )

        val effective = original.forCalibrationSlice(
            active = true,
            requiresFirmwareRetraction = false,
        )

        assertFalse(effective.supportsEnabled)
        assertFalse(effective.supportInterfaceEnabled)
        assertFalse(effective.firmwareRetraction)
        assertTrue(original.supportsEnabled)
        assertTrue(original.supportInterfaceEnabled)
        assertTrue(effective.overriddenSettingKeys == original.overriddenSettingKeys)
    }

    @Test
    fun retractionCalibrationEnablesFirmwareRetractionForSliceOnly() {
        val original = SlicerSettings(firmwareRetraction = false)
        val effective = original.forCalibrationSlice(
            active = true,
            requiresFirmwareRetraction = true,
        )

        assertTrue(effective.firmwareRetraction)
        assertFalse(original.firmwareRetraction)
    }

    @Test
    fun ordinarySliceIsUnchanged() {
        val original = SlicerSettings(supportsEnabled = true, supportInterfaceEnabled = true)
        assertTrue(original === original.forCalibrationSlice(active = false, requiresFirmwareRetraction = false))
    }
}
