package com.tomppi.enderslicer.calibration

import com.tomppi.enderslicer.engine.LayerEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTowerGeneratorTest {
    @Test
    fun generatesSteppedTemperatureTowerAndEvents() {
        val result = CalibrationTowerGenerator.generate(
            CalibrationTowerSpec(
                type = CalibrationTestType.TEMPERATURE,
                startValue = 225.0,
                stepValue = -5.0,
                levels = 5,
                sectionHeightMm = 6.0,
                towerWidthMm = 18.0,
            ),
            retractionSpeedMmPerSecond = 40.0,
        )

        assertTrue(result.mesh.triangleCount > 0)
        assertEquals(5, result.plannedEvents.size)
        assertEquals(listOf(225.0, 220.0, 215.0, 210.0, 205.0), result.levelValues)
        assertTrue(result.plannedEvents.all { it.type == LayerEventType.NOZZLE_TEMPERATURE })
        assertFalse(result.requiresFirmwareRetraction)
        assertTrue(result.mesh.bounds.height > 30f)
    }

    @Test
    fun retractionTowerCarriesSpeedAndRequiresFirmwareRetraction() {
        val result = CalibrationTowerGenerator.generate(
            CalibrationTowerSpec(type = CalibrationTestType.RETRACTION, levels = 3),
            retractionSpeedMmPerSecond = 55.0,
        )
        assertTrue(result.requiresFirmwareRetraction)
        assertEquals(55.0, result.plannedEvents.first().secondaryValue ?: 0.0, 0.0)
    }
}
