package com.tomppi.enderslicer.nonplanar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NozzleCollisionScannerTest {
    private fun settings() = NonPlanarSettings(
        enabled = true,
        nozzleClearanceHeightMm = 15.0,
        nozzleClearanceAngleDegrees = 45.0,
        nozzleAngleDegrees = 30.0,
        nozzleProtrusionMm = 5.0,
        heatingBlockWidthMm = 20.0,
        heatingBlockDepthMm = 16.0,
        heatingBlockOffsetXmm = 0.0,
        heatingBlockOffsetYmm = 0.0,
    )

    private fun scanGcode(body: String): NozzleCollisionAlert? {
        val directory = kotlin.io.path.createTempDirectory("nozzle-scan").toFile()
        val gcode = File(directory, "output.gcode")
        gcode.writeText(";FLAVOR:Marlin\nG90\nM82\n" + body)
        return try {
            NozzleCollisionScanner.scan(gcode, settings(), 115.0, 115.0)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun flatPrintHasNoCollisions() {
        assertNull(
            scanGcode(
                ";LAYER:0\n" +
                    "G1 X10 Y10 Z0.2 E1\n" +
                    "G1 X20 Y10 E2\n" +
                    "G1 X20 Y20 E3\n" +
                    "G1 X10 Y20 E4\n" +
                    ";LAYER:1\n" +
                    "G1 X10 Y10 Z0.4 E5\n" +
                    "G1 X20 Y10 E6\n",
            ),
        )
    }

    @Test
    fun nozzleConeViolationIsDetected() {
        // A travel at z=0.2 passes 0.5 mm beside a column whose surface is
        // 1.0 mm higher: allowed by the 30-degree nozzle cone is 0.577 mm.
        val alert = scanGcode(
            ";LAYER:0\n" +
                "G1 X10 Y10 Z0.2 E1\n" +
                "G1 X20 Y20 Z1.2 E2\n" +
                "G1 X20 Y10 E3\n" +
                "G1 X10 Y20 E4\n" +
                "G0 X19.5 Y20 Z0.2\n" +
                "G1 X10 Y10 E5\n",
        )
        assertNotNull(alert)
        assertTrue(alert!!.violatingMoves > 0)
        assertTrue(alert.maximumViolationMm > 0.05)
        assertTrue(alert.offendingLayers.isNotEmpty())
    }

    @Test
    fun blockFrustumViolationIsDetected() {
        // Surface 10 mm above the tip at 5 mm horizontal distance: inside
        // the block frustum (half-width 10 mm + 5 mm rise at 45 degrees).
        val alert = scanGcode(
            ";LAYER:0\n" +
                "G1 X10 Y10 Z0.2 E1\n" +
                "G1 X50 Y50 Z10.2 E2\n" +
                "G1 X20 Y10 E3\n" +
                "G1 X10 Y20 E4\n" +
                "G0 X45 Y50 Z0.2\n" +
                "G1 X10 Y10 E5\n",
        )
        assertNotNull(alert)
        assertTrue(alert!!.maximumViolationMm >= 9.9)
    }

    @Test
    fun cutoffAboveHoldingObjectIsDetected() {
        // A column whose top is 20 mm above the low tip: the whole-plate
        // cutoff above the 15 mm holding-object height must warn.
        val alert = scanGcode(
            ";LAYER:0\n" +
                "G1 X10 Y10 Z0.2 E1\n" +
                "G1 X50 Y50 Z20.2 E2\n" +
                "G1 X20 Y10 E3\n" +
                "G1 X10 Y20 E4\n" +
                "G0 X20 Y20 Z0.2\n" +
                "G1 X10 Y10 E5\n",
        )
        assertNotNull(alert)
        assertTrue(alert!!.cutoffViolatingMoves > 0)
    }

    @Test
    fun offsetBlockFrustumRespectsTheMeasuredNozzlePosition() {
        // Block centre at (+8, 0): a surface 8 mm to the right of the tip
        // at the junction height is inside; 6 mm to the LEFT is outside.
        val alert = scanGcode(
            ";LAYER:0\n" +
                "G1 X10 Y10 Z0.2 E1\n" +
                "G1 X18 Y10 Z5.2 E2\n" +
                "G1 X20 Y10 E3\n" +
                "G1 X10 Y20 E4\n" +
                "G0 X16 Y10 Z0.2\n" +
                "G1 X10 Y10 E5\n",
        ).let { result ->
            requireNotNull(result)
            result
        }
        assertTrue(alert.violatingMoves > 0)
    }
}
