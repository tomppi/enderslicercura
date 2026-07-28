package com.tomppi.enderslicer.calibration

import com.tomppi.enderslicer.engine.LayerEventType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTowerGeneratorTest {
    @After
    fun clearCalibrationSliceState() {
        CalibrationSliceState.clear()
    }

    @Test
    fun generatesDedicatedTemperatureModelAndEvents() {
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

        assertTrue(result.mesh.triangleCount > levels * 50)
        assertEquals(5, result.plannedEvents.size)
        assertEquals(listOf(225.0, 220.0, 215.0, 210.0, 205.0), result.levelValues)
        assertTrue(result.plannedEvents.all { it.type == LayerEventType.NOZZLE_TEMPERATURE })
        assertEquals(CalibrationTestType.TEMPERATURE.modelFeatures, result.modelFeatures)
        assertTrue(CalibrationModelFeature.SUPPORT_FREE in result.modelFeatures)
        assertFalse(result.requiresFirmwareRetraction)
        assertTrue(result.mesh.bounds.height > 30f)
        assertEveryTriangleHasArea(result.mesh.interleavedVertices, result.mesh.triangleCount)
    }

    @Test
    fun everyCalibrationTypeProducesItsOwnSupportFreePurposeBuiltGeometry() {
        val results = CalibrationTestType.entries.associateWith { type ->
            CalibrationTowerGenerator.generate(
                CalibrationTowerSpec(type = type),
                retractionSpeedMmPerSecond = 40.0,
            )
        }

        results.forEach { (type, result) ->
            assertEquals(type.defaultLevels, result.plannedEvents.size)
            assertEquals(type.modelFeatures, result.modelFeatures)
            assertTrue(CalibrationModelFeature.SUPPORT_FREE in result.modelFeatures)
            assertTrue(type.designDescription.contains("support", ignoreCase = true))
            assertTrue(result.mesh.triangleCount > 100)
            assertEveryTriangleHasArea(result.mesh.interleavedVertices, result.mesh.triangleCount)
        }

        val geometrySignatures = results.values.map { result ->
            listOf(
                result.mesh.triangleCount.toFloat(),
                result.mesh.bounds.width,
                result.mesh.bounds.depth,
                result.mesh.bounds.height,
            )
        }.toSet()
        assertEquals(CalibrationTestType.entries.size, geometrySignatures.size)
    }

    @Test
    fun defaultCalibrationModelsStayCompact() {
        CalibrationTestType.entries.forEach { type ->
            val result = CalibrationTowerGenerator.generate(
                CalibrationTowerSpec(type = type),
                retractionSpeedMmPerSecond = 40.0,
            )
            assertTrue("${type.displayName} is unexpectedly tall", result.mesh.bounds.height < 35f)
            assertTrue("${type.displayName} is unexpectedly wide", result.mesh.bounds.width <= 26.1f)
        }
    }

    @Test
    fun fanDefaultsReachOneHundredPercentWithoutExceedingRange() {
        val result = CalibrationTowerGenerator.generate(
            CalibrationTowerSpec(type = CalibrationTestType.FAN),
            retractionSpeedMmPerSecond = 40.0,
        )

        assertEquals(listOf(0.0, 20.0, 40.0, 60.0, 80.0, 100.0), result.levelValues)
        assertTrue(result.levelValues.all { it in 0.0..100.0 })
    }

    @Test
    fun retractionModelCarriesSpeedAndUsesSeparatedTravelIslands() {
        val result = CalibrationTowerGenerator.generate(
            CalibrationTowerSpec(type = CalibrationTestType.RETRACTION, levels = 3),
            retractionSpeedMmPerSecond = 55.0,
        )
        assertFalse(result.requiresFirmwareRetraction)
        assertEquals(55.0, result.plannedEvents.first().secondaryValue ?: 0.0, 0.0)
        assertTrue(CalibrationModelFeature.SEPARATED_POSTS in result.modelFeatures)
        assertTrue(CalibrationModelFeature.TRAVEL_GAPS in result.modelFeatures)
        assertTrue(CalibrationModelFeature.SUPPORT_FREE in result.modelFeatures)
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
