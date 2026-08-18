package com.tomppi.enderslicer.nonplanar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NonPlanarSettingsTest {
    @Test
    fun validationClampsEverySafetyCriticalSetting() {
        val settings = NonPlanarSettings(
            enabled = true,
            strengthPercent = 500.0,
            smoothingRadiusMm = -2.0,
            maximumSlopeDegrees = 80.0,
            nozzleClearanceAngleDegrees = 20.0,
            nozzleClearanceHeightMm = 1.0,
            flatBaseLayers = 100,
            fieldResolution = 500,
            maximumSegmentLengthMm = 0.01,
            maximumZSpeedMmPerSecond = 100.0,
            pauseAfterProbe = true,
        ).validated()

        assertEquals(100.0, settings.strengthPercent, 0.0)
        assertEquals(0.4, settings.smoothingRadiusMm, 0.0)
        assertEquals(55.0, settings.maximumSlopeDegrees, 0.0)
        assertEquals(20.0, settings.nozzleClearanceAngleDegrees, 0.0)
        assertEquals(5.0, settings.nozzleClearanceHeightMm, 0.0)
        assertEquals(20, settings.flatBaseLayers)
        assertEquals(192, settings.fieldResolution)
        assertEquals(0.2, settings.maximumSegmentLengthMm, 0.0)
        assertEquals(20.0, settings.maximumZSpeedMmPerSecond, 0.0)
        assertEquals(15.0, settings.effectiveSlopeLimitDegrees, 0.0)
        assertTrue(settings.enabled)
        assertTrue(settings.pauseAfterProbe)
    }
}
