package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartOverhangStrategyTest {

    @Test
    fun smartStrategyDisabledReturnsSettingsUnchanged() {
        val settings = SlicerSettings(smartOverhangStrategy = false, arcOverhangEnabled = true)
        val mesh = testMesh(*flatBoxTriangles(0f, 0f, 10f, 100f, 100f, 11f).toTypedArray())

        val result = SmartOverhangStrategy.resolve(settings, NonPlanarSettings(), mesh, 0.2, 0.4)

        assertNull(result.message)
        assertEquals(true, result.settings.arcOverhangEnabled)
    }

    @Test
    fun flatRoofAutoEnablesArcAndDisablesWave() {
        val settings = SlicerSettings(
            smartOverhangStrategy = true,
            arcOverhangEnabled = false,
            waveOverhangEnabled = true,
        )
        val mesh = testMesh(*flatBoxTriangles(0f, 0f, 10f, 100f, 100f, 11f).toTypedArray())

        val result = SmartOverhangStrategy.resolve(settings, NonPlanarSettings(), mesh, 0.2, 0.4)

        assertTrue("A flat roof must auto-enable arc fill", result.settings.arcOverhangEnabled)
        assertFalse("Arc and wave fill stay mutually exclusive", result.settings.waveOverhangEnabled)
        assertTrue(result.message!!.contains("arc fill"))
    }

    @Test
    fun noEvidencePreservesExplicitUserArcSetting() {
        val settings = SlicerSettings(
            smartOverhangStrategy = true,
            arcOverhangEnabled = true,
            waveOverhangEnabled = false,
        )
        val mesh = testMesh(*flatBoxTriangles(0f, 0f, 0f, 100f, 100f, 20f).toTypedArray())

        val result = SmartOverhangStrategy.resolve(settings, NonPlanarSettings(), mesh, 0.2, 0.4)

        assertTrue("Without evidence the explicit arc setting must be preserved", result.settings.arcOverhangEnabled)
    }

    @Test
    fun curviActiveWithSafeRoofsKeepsArcFill() {
        val settings = SlicerSettings(
            smartOverhangStrategy = true,
            arcOverhangEnabled = true,
            waveOverhangEnabled = false,
        )
        val triangles = mutableListOf<FloatArray>()
        triangles += flatBoxTriangles(0f, 0f, 0f, 100f, 100f, 1f)
        triangles += flatBoxTriangles(0f, 0f, 10f, 100f, 100f, 11f)
        triangles += listOf(
            floatArrayOf(0f, 0f, 11f, 30f, 0f, 11f, 30f, 30f, 14f),
            floatArrayOf(0f, 0f, 11f, 30f, 30f, 14f, 0f, 30f, 11f),
        )
        val mesh = testMesh(*triangles.toTypedArray())
        val curvi = NonPlanarSettings(enabled = true, strengthPercent = 100.0)

        val result = SmartOverhangStrategy.resolve(settings, curvi, mesh, 0.2, 0.4)

        assertTrue("Flat roofs that stay flat must keep arc fill with CurviSlicer", result.settings.arcOverhangEnabled)
        assertTrue(result.message!!.contains("together with CurviSlicer"))
    }

    @Test
    fun curviActiveWithCurvedRoofsForcesArcOff() {
        val settings = SlicerSettings(
            smartOverhangStrategy = true,
            arcOverhangEnabled = true,
            waveOverhangEnabled = false,
        )
        val triangles = mutableListOf<FloatArray>()
        triangles += flatBoxTriangles(0f, 0f, 0f, 100f, 100f, 1f)
        triangles += listOf(
            floatArrayOf(20f, 20f, 5f, 80f, 80f, 5f, 80f, 20f, 5f),
            floatArrayOf(20f, 20f, 5f, 20f, 80f, 5f, 80f, 80f, 5f),
        )
        triangles += domeTriangles(20f, 20f, 5f, 80f, 80f, 12f)
        val mesh = testMesh(*triangles.toTypedArray())
        val curvi = NonPlanarSettings(enabled = true, strengthPercent = 100.0)

        val result = SmartOverhangStrategy.resolve(settings, curvi, mesh, 0.2, 0.4)

        assertFalse("Roofs curved by non-planar layers must force arc fill off", result.settings.arcOverhangEnabled)
        assertTrue(result.message!!.contains("disabled"))
    }
}
