package com.tomppi.enderslicer.calibration

import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun everyCalibrationUsesOnlyItsRequiredOverrides() {
        val original = SlicerSettings(
            supportsEnabled = true,
            supportInterfaceEnabled = true,
            adaptiveLayerHeightEnabled = true,
            arcOverhangEnabled = true,
            ironingEnabled = true,
            coastingEnabled = true,
            firmwareRetraction = false,
        )

        CalibrationTestType.entries.forEach { type ->
            CalibrationSliceState.activate(type, type.defaultStart)
            try {
                val policy = CalibrationSliceState.policyForTests(type)
                val effective = CalibrationSliceState.effective(original)
                val overrides = CalibrationSliceState.engineOverrides()

                assertFalse("$type must remain support-free", effective.supportsEnabled)
                assertFalse("$type must remain support-free", effective.supportInterfaceEnabled)
                assertEquals(!policy.disableAdaptiveLayers, effective.adaptiveLayerHeightEnabled)
                assertEquals(!policy.disableArcOverhangs, effective.arcOverhangEnabled)
                assertEquals(!policy.disableIroning, effective.ironingEnabled)
                assertEquals(!policy.disableCoasting, effective.coastingEnabled)

                if (policy.disableSmallLayerSlowdown) {
                    assertEquals("0", overrides["cool_min_layer_time"])
                    assertEquals("false", overrides["cool_lift_head"])
                } else {
                    assertNull(overrides["cool_min_layer_time"])
                    assertNull(overrides["cool_lift_head"])
                }
            } finally {
                CalibrationSliceState.clear()
            }
        }

        assertTrue(original.supportsEnabled)
        assertTrue(original.supportInterfaceEnabled)
        assertTrue(original.adaptiveLayerHeightEnabled)
        assertTrue(original.arcOverhangEnabled)
        assertTrue(original.ironingEnabled)
        assertTrue(original.coastingEnabled)
    }

    @Test
    fun retractionCalibrationPreservesCoreProfileBehavior() {
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
            assertTrue(effective.adaptiveLayerHeightEnabled)
            assertTrue(effective.arcOverhangEnabled)
            assertTrue(effective.ironingEnabled)
            assertTrue(effective.coastingEnabled)
            assertTrue(effective.firmwareRetraction)
            assertEquals("true", overrides["retraction_enable"])
            assertEquals("0", overrides["retraction_min_travel"])
            assertEquals("off", overrides["retraction_combing"])
            assertNull(overrides["cool_min_layer_time"])
        } finally {
            CalibrationSliceState.clear()
        }
    }

    @Test
    fun typeSpecificPoliciesMatchCalibrationPurpose() {
        val temperature = CalibrationSliceState.policyForTests(CalibrationTestType.TEMPERATURE)
        assertTrue(temperature.disableAdaptiveLayers)
        assertTrue(temperature.disableArcOverhangs)
        assertFalse(temperature.disableCoasting)
        assertFalse(temperature.disableSmallLayerSlowdown)

        val flow = CalibrationSliceState.policyForTests(CalibrationTestType.FLOW)
        assertTrue(flow.disableAdaptiveLayers)
        assertTrue(flow.disableIroning)
        assertTrue(flow.disableCoasting)
        assertFalse(flow.disableArcOverhangs)
        assertFalse(flow.disableSmallLayerSlowdown)

        val speed = CalibrationSliceState.policyForTests(CalibrationTestType.SPEED)
        assertTrue(speed.disableAdaptiveLayers)
        assertTrue(speed.disableSmallLayerSlowdown)
        assertFalse(speed.disableCoasting)

        val fan = CalibrationSliceState.policyForTests(CalibrationTestType.FAN)
        assertTrue(fan.disableAdaptiveLayers)
        assertTrue(fan.disableArcOverhangs)
        assertTrue(fan.disableSmallLayerSlowdown)
        assertFalse(fan.disableCoasting)

        val pressure = CalibrationSliceState.policyForTests(CalibrationTestType.PRESSURE_ADVANCE)
        assertTrue(pressure.disableAdaptiveLayers)
        assertTrue(pressure.disableCoasting)
        assertTrue(pressure.disableSmallLayerSlowdown)

        val junction = CalibrationSliceState.policyForTests(CalibrationTestType.JUNCTION_DEVIATION)
        assertTrue(junction.disableAdaptiveLayers)
        assertTrue(junction.disableSmallLayerSlowdown)
        assertFalse(junction.disableCoasting)

        assertEquals(CalibrationOverridePolicy(), CalibrationSliceState.policyForTests(CalibrationTestType.RETRACTION))
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
    fun pressureAdvanceCalibrationRestoresFirstKValue() {
        CalibrationSliceState.activate(CalibrationTestType.PRESSURE_ADVANCE, 0.02)
        try {
            assertEquals("M900 K0.02", CalibrationSliceState.pressureAdvanceRestoreCommand())
        } finally {
            CalibrationSliceState.clear()
        }
    }

    @Test
    fun junctionDeviationCalibrationRestoresFirstValue() {
        CalibrationSliceState.activate(CalibrationTestType.JUNCTION_DEVIATION, 0.005)
        try {
            assertEquals("M205 J0.005", CalibrationSliceState.junctionDeviationRestoreCommand())
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
    fun fanCalibrationDisablesOnlyCuraOwnedFanControl() {
        CalibrationSliceState.activate(CalibrationTestType.FAN, 0.0)
        try {
            val overrides = CalibrationSliceState.engineOverrides()
            assertEquals("false", overrides["cool_fan_enabled"])
            assertEquals("0", overrides["bridge_fan_speed"])
            assertEquals("0", overrides["cool_min_layer_time"])
            assertNull(overrides["retraction_combing"])
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
