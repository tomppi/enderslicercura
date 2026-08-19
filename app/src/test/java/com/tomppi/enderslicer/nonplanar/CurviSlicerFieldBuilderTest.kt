package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurviSlicerFieldBuilderTest {
    @Test
    fun rasterizesTheInteriorOfLargeLowPolyTriangles() {
        val result = CurviSlicerFieldBuilder.build(
            mesh = tiltedPlaneMesh(),
            settings = NonPlanarSettings(
                enabled = true,
                strengthPercent = 100.0,
                smoothingRadiusMm = 0.4,
                maximumSlopeDegrees = 30.0,
                nozzleClearanceAngleDegrees = 45.0,
                fieldResolution = 32,
            ),
            layerHeightMm = 0.2,
            nozzleDiameterMm = 0.4,
        )

        val low = result.field.sampleRelief(25.0, 50.0)
        val high = result.field.sampleRelief(75.0, 50.0)
        assertTrue("A tilted low-poly surface must retain its broad gradient", high - low > 3.0)
    }

    @Test
    fun slopeLimitClampsGradientsLocallyWithoutWeakeningRequestedStrength() {
        val settings = NonPlanarSettings(
            enabled = true,
            strengthPercent = 100.0,
            smoothingRadiusMm = 0.4,
            maximumSlopeDegrees = 5.0,
            nozzleClearanceAngleDegrees = 15.0,
            fieldResolution = 32,
        )
        val result = CurviSlicerFieldBuilder.build(
            mesh = tiltedPlaneMesh(),
            settings = settings,
            layerHeightMm = 0.2,
            nozzleDiameterMm = 0.4,
        )

        assertEquals(
            "A slope-limited plane must keep the requested strength and clip only the local gradient",
            1.0,
            result.diagnostics.appliedStrength,
            1e-9,
        )
        assertTrue(
            "The conservative inverse-field slope must stay within the effective limit",
            result.diagnostics.maximumFieldSlopeDegrees <= settings.effectiveSlopeLimitDegrees + 1e-6,
        )
    }

    @Test
    fun interruptedThreadAbortsFieldConstruction() {
        Thread.currentThread().interrupt()
        try {
            val failure = runCatching {
                CurviSlicerFieldBuilder.build(
                    mesh = tiltedPlaneMesh(),
                    settings = NonPlanarSettings(enabled = true, fieldResolution = 32),
                    layerHeightMm = 0.2,
                    nozzleDiameterMm = 0.4,
                )
            }.exceptionOrNull()
            assertTrue("Expected InterruptedException", failure is InterruptedException)
        } finally {
            Thread.interrupted()
        }
    }

    private fun tiltedPlaneMesh(height: Float = 30f): StlMesh {
        fun vertex(x: Float, y: Float, z: Float): FloatArray = floatArrayOf(x, y, z, 0f, 0f, 1f)
        val values = vertex(0f, 0f, 0f) +
            vertex(100f, 0f, height) +
            vertex(100f, 100f, height) +
            vertex(0f, 0f, 0f) +
            vertex(100f, 100f, height) +
            vertex(0f, 100f, 0f)
        return StlMesh(
            displayName = "tilted-plane.stl",
            interleavedVertices = values,
            triangleCount = 2,
            bounds = MeshBounds(0f, 0f, 0f, 100f, 100f, height),
        )
    }
}
