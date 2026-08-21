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
        assertEquals("45.0", values.getValue(BeadAngleEngineSettings.PRESS_ANGLE))
        assertEquals("3.0", values.getValue(BeadAngleEngineSettings.WAVELENGTH))
        assertEquals("25.0", values.getValue(BeadAngleEngineSettings.SPEED))
        assertEquals("105.0", values.getValue(BeadAngleEngineSettings.FLOW))
        assertEquals("100.0", values.getValue(BeadAngleEngineSettings.FAN_SPEED))
        assertEquals("60", values.getValue(BeadAngleEngineSettings.MAX_ITERATIONS))
    }

    @Test
    fun enabledSettingsAreForwardedToNativeCuraEngineKeys() {
        val values = BeadAngleEngineSettings.values(
            SlicerSettings(
                beadAngleOverhang = BeadAngleOverhangSettings(
                    enabled = true,
                    pressAngleDegrees = 0.0,
                    wavelengthMm = 5.0,
                    speedMmPerSecond = 10.0,
                    flowPercent = 120.0,
                    fanSpeedPercent = 80.0,
                    maxIterations = 90,
                ),
            ),
        )

        assertTrue(values.getValue(BeadAngleEngineSettings.ENABLED).toBoolean())
        assertEquals("0.0", values.getValue(BeadAngleEngineSettings.PRESS_ANGLE))
        assertEquals("5.0", values.getValue(BeadAngleEngineSettings.WAVELENGTH))
        assertEquals("10.0", values.getValue(BeadAngleEngineSettings.SPEED))
        assertEquals("120.0", values.getValue(BeadAngleEngineSettings.FLOW))
        assertEquals("80.0", values.getValue(BeadAngleEngineSettings.FAN_SPEED))
        assertEquals("90", values.getValue(BeadAngleEngineSettings.MAX_ITERATIONS))
    }

    @Test
    fun outOfRangePressAngleIsRejectedBeforeSlicing() {
        assertThrows(IllegalArgumentException::class.java) {
            BeadAngleEngineSettings.values(
                SlicerSettings(
                    beadAngleOverhang = BeadAngleOverhangSettings(pressAngleDegrees = 185.0),
                ),
            )
        }
    }
}
