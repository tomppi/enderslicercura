package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.BeadAngleOverhangSettings
import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BeadAngleEngineSettingsTest {
    @Test
    fun defaultsAreConservativeAndDisabled() {
        val values = BeadAngleEngineSettings.values(SlicerSettings())

        assertFalse(values.getValue(BeadAngleEngineSettings.ENABLED).toBoolean())
        assertEquals("3.0", values.getValue(BeadAngleEngineSettings.WAVELENGTH))
        assertEquals("10.0", values.getValue(BeadAngleEngineSettings.SPEED))
        assertEquals("105.0", values.getValue(BeadAngleEngineSettings.FLOW))
        assertEquals("100.0", values.getValue(BeadAngleEngineSettings.FAN_SPEED))
        assertEquals("4", values.getValue(BeadAngleEngineSettings.MAX_ITERATIONS))
    }

    @Test
    fun enabledSettingsAreForwardedToNativeCuraEngineKeys() {
        val values = BeadAngleEngineSettings.values(
            SlicerSettings(
                beadAngleOverhang = BeadAngleOverhangSettings(
                    enabled = true,
                    wavelengthMm = 5.0,
                    speedMmPerSecond = 10.0,
                    flowPercent = 120.0,
                    fanSpeedPercent = 80.0,
                    maxIterations = 4,
                ),
            ),
        )

        assertTrue(values.getValue(BeadAngleEngineSettings.ENABLED).toBoolean())
        assertEquals("5.0", values.getValue(BeadAngleEngineSettings.WAVELENGTH))
        assertEquals("10.0", values.getValue(BeadAngleEngineSettings.SPEED))
        assertEquals("120.0", values.getValue(BeadAngleEngineSettings.FLOW))
        assertEquals("80.0", values.getValue(BeadAngleEngineSettings.FAN_SPEED))
        assertEquals("4", values.getValue(BeadAngleEngineSettings.MAX_ITERATIONS))
    }

    @Test
    fun outOfRangeWavelengthIsRejectedBeforeSlicing() {
        assertThrows(IllegalArgumentException::class.java) {
            BeadAngleEngineSettings.values(
                SlicerSettings(
                    beadAngleOverhang = BeadAngleOverhangSettings(wavelengthMm = 20.0),
                ),
            )
        }
    }
}
