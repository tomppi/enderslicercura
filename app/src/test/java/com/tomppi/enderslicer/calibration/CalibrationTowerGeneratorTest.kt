package com.tomppi.enderslicer.calibration

import com.tomppi.enderslicer.engine.LayerEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTowerGeneratorTest {
    @Test
    fun generatesSteppedTemperatureTowerAndEvents() {
        val levels = 5
        val result = CalibrationTowerGenerator.generate(
            CalibrationTowerSpec(
                type = CalibrationTestType.TEMPERATURE,
                startValue = 225.0,
                stepValue = -5.0,
                levels = levels,
                sectionHeightMm = 6.0,
                towerWidthMm = 18.0,
            ),
            retractionSpeedMmPerSecond = 40.0,
        )

        assertEquals(12 * (1 + levels * 2), result.mesh.triangleCount)
        assertEquals(5, result.plannedEvents.size)
        assertEquals(listOf(225.0, 220.0, 215.0, 210.0, 205.0), result.levelValues)
        assertTrue(result.plannedEvents.all { it.type == LayerEventType.NOZZLE_TEMPERATURE })
        assertFalse(result.requiresFirmwareRetraction)
        assertTrue(result.mesh.bounds.height > 30f)
        assertEveryTriangleHasArea(result.mesh.interleavedVertices, result.mesh.triangleCount)
    }

    @Test
    fun retractionTowerCarriesSpeedAndRequiresFirmwareRetraction() {
        val result = CalibrationTowerGenerator.generate(
            CalibrationTowerSpec(type = CalibrationTestType.RETRACTION, levels = 3),
            retractionSpeedMmPerSecond = 55.0,
        )
        assertTrue(result.requiresFirmwareRetraction)
        assertEquals(55.0, result.plannedEvents.first().secondaryValue ?: 0.0, 0.0)
        assertEveryTriangleHasArea(result.mesh.interleavedVertices, result.mesh.triangleCount)
    }

    private fun assertEveryTriangleHasArea(vertices: FloatArray, triangleCount: Int) {
        assertEquals(triangleCount * 18, vertices.size)
        repeat(triangleCount) { triangleIndex ->
            val base = triangleIndex * 18
            val ax = vertices[base]
            val ay = vertices[base + 1]
            val az = vertices[base + 2]
            val bx = vertices[base + 6]
            val by = vertices[base + 7]
            val bz = vertices[base + 8]
            val cx = vertices[base + 12]
            val cy = vertices[base + 13]
            val cz = vertices[base + 14]

            val abx = bx - ax
            val aby = by - ay
            val abz = bz - az
            val acx = cx - ax
            val acy = cy - ay
            val acz = cz - az
            val crossX = aby * acz - abz * acy
            val crossY = abz * acx - abx * acz
            val crossZ = abx * acy - aby * acx
            val doubledAreaSquared = crossX * crossX + crossY * crossY + crossZ * crossZ
            assertTrue(
                "Triangle $triangleIndex is degenerate",
                doubledAreaSquared > 1e-8f,
            )
        }
    }
}
