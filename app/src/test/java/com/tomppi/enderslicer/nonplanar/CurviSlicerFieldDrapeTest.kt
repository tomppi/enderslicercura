package com.tomppi.enderslicer.nonplanar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurviSlicerFieldDrapeTest {
    private fun drapeField(): CurviSlicerField = CurviSlicerField(
        minX = 0.0, minY = 0.0, minZ = 0.0,
        maxX = 10.0, maxY = 10.0, maxZ = 10.0,
        columns = 3, rows = 3,
        relief = floatArrayOf(0f, 1f, 0f, 1f, 3f, 1f, 0f, 1f, 0f),
        strength = 1.0,
        flatBaseHeightMm = 0.6,
        uniformShift = true,
    )

    @Test
    fun drapeDisplacementScalesLinearlyWithHeight() {
        val field = drapeField()
        // displacement = (z - base) * amplitude / usableHeight
        assertEquals(3.0 * 0.4 / 9.4, field.displacement(5.0, 5.0, 1.0), 1e-9)
        assertEquals(3.0 * 8.4 / 9.4, field.displacement(5.0, 5.0, 9.0), 1e-9)
        assertEquals(0.0, field.displacement(5.0, 5.0, 0.3), 1e-9) // below the flat base
    }

    @Test
    fun drapeFlattenUnflattenRoundtrips() {
        val field = drapeField()
        for (z in listOf(1.0, 4.0, 9.9)) {
            val flat = field.flattenZ(5.0, 5.0, z)
            val restored = field.unflattenZ(5.0, 5.0, flat)
            assertEquals(z, restored, 1e-6)
        }
    }

    @Test
    fun drapeBuilderKeepsFullRequestedStrength() {
        val mesh = testMesh(
            floatArrayOf(0f, 0f, 0f, 100f, 0f, 30f, 100f, 100f, 30f),
            floatArrayOf(0f, 0f, 0f, 100f, 100f, 30f, 0f, 100f, 0f),
        )
        val result = CurviSlicerFieldBuilder.build(
            mesh = mesh,
            settings = NonPlanarSettings(
                enabled = true,
                strengthPercent = 100.0,
                drapeMode = true,
                maximumSlopeDegrees = 30.0,
                fieldResolution = 32,
            ),
            layerHeightMm = 0.2,
            nozzleDiameterMm = 0.4,
        )
        assertEquals(1.0, result.diagnostics.appliedStrength, 1e-9)
        assertTrue(result.field.uniformShift)
        assertTrue(result.diagnostics.maximumFieldSlopeDegrees <= 30.0 + 1e-6)
    }
}
