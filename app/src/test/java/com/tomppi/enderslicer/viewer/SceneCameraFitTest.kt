package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.PrinterDefinition
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan
import kotlin.math.tan

class SceneCameraFitTest {
    private val printer = PrinterDefinition(
        id = "test",
        name = "Test printer",
        manufacturer = "Test",
        widthMm = 230.0,
        depthMm = 230.0,
        heightMm = 250.0,
        buildPlateShape = "rectangular",
        originAtCenter = false,
        heatedBed = true,
        heatedBuildVolume = false,
        gcodeFlavor = "Marlin",
        extruders = 1,
        nozzleSizeMm = 0.4,
        filamentDiameterMm = 1.75,
        printheadXMinMm = 0.0,
        printheadYMinMm = 0.0,
        printheadXMaxMm = 0.0,
        printheadYMaxMm = 0.0,
        gantryHeightMm = 25.0,
        directDrive = true,
        dualZ = true,
        zProbe = true,
        bedLeveling = "UBL",
        ublMeshSlot = 0,
    )

    @Test
    fun portraitViewportUsesHorizontalFieldOfView() {
        val fit = SceneCameraFit.calculate(
            printer = printer,
            meshBounds = null,
            aspect = 0.4f,
            zoom = 1f,
            verticalFieldOfViewDegrees = 42f,
        )
        val verticalHalf = Math.toRadians(21.0).toFloat()
        val horizontalHalf = atan(tan(verticalHalf) * 0.4f)

        assertTrue(fit.radius / fit.distance < tan(horizontalHalf))
        assertTrue(fit.nearPlane > 0f)
        assertTrue(fit.farPlane > fit.nearPlane)
    }

    @Test
    fun displacedModelChangesSceneCenterAndStillFits() {
        val bounds = MeshBounds(
            minX = 300f,
            minY = -80f,
            minZ = 5f,
            maxX = 360f,
            maxY = -20f,
            maxZ = 105f,
        )

        for (aspect in listOf(0.4f, 0.5f, 1f, 2f)) {
            val fit = SceneCameraFit.calculate(printer, bounds, aspect, 1f, 42f)
            val verticalHalf = Math.toRadians(21.0).toFloat()
            val horizontalHalf = atan(tan(verticalHalf) * aspect)
            val limitingHalf = minOf(verticalHalf, horizontalHalf)

            assertTrue(fit.centerX > 115f)
            assertTrue(fit.centerY < 115f)
            assertTrue(fit.radius / fit.distance < tan(limitingHalf))
        }
    }
}
