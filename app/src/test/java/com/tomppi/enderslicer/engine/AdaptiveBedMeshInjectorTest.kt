package com.tomppi.enderslicer.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBedMeshInjectorTest {
    private val envelope = PrinterEnvelope(
        widthMm = 220.0, depthMm = 220.0, heightMm = 250.0,
        buildPlateShape = "rectangular", originAtCenter = false,
    )

    @Test
    fun ublStartGcodeGetsBoundsAndC29AfterActivation() {
        val file = temporaryGcode(
            ";FLAVOR:Marlin\n" +
                "G28\n" +
                "G29 L0 ; load mesh\n" +
                "G29 A  ; activate UBL\n" +
                ";LAYER:0\n" +
                "G1 X10 Y20 Z0.28 F1200\n" +
                "G1 X60 Y70 E0.5\n" +
                ";LAYER:1\n" +
                "G1 X60 Y70 Z0.48 E0.5\n",
        )
        assertTrue(AdaptiveBedMeshInjector.inject(file, envelope, 5.0))
        val text = file.readText()
        val activation = text.indexOf("G29 A  ; activate UBL")
        assertTrue("bounds comments must lead the file", text.indexOf("; First layer print x min") < activation)
        assertTrue("C29 A must come after the activation line", activation < text.indexOf("C29 A"))
        assertTrue(text.contains("; First layer print x min = 5.00"))
        assertTrue(text.contains("; First layer print y min = 15.00"))
        assertTrue(text.contains("; First layer print x max = 65.00"))
        assertTrue(text.contains("; First layer print y max = 75.00"))
        assertTrue(text.contains("; AML mesh density X = auto"))
        assertTrue(text.contains("; AML margin = 5.00"))
        assertTrue(text.contains("; AML prime = 1"))
        assertTrue(text.contains("C29 A ; use AML"))
        assertTrue(text.contains(AdaptiveBedMeshInjector.MARKER))
        assertFalse("second injection must be idempotent", AdaptiveBedMeshInjector.inject(file, envelope, 5.0))
    }

    @Test
    fun m420S1StartGcodeKeepsTheExistingActivation() {
        val file = temporaryGcode(
            ";FLAVOR:Marlin\n" +
                "G28\n" +
                "M420 S1\n" +
                ";LAYER:0\n" +
                "G1 X30 Y40 Z0.2\n" +
                "G1 X90 Y100 E0.4\n" +
                ";LAYER:1\n" +
                "G1 X90 Y100 Z0.4 E0.4\n",
        )
        assertTrue(AdaptiveBedMeshInjector.inject(file, envelope, 2.0))
        val text = file.readText()
        assertFalse("no duplicate M420 when the start script already activates", text.contains("M420 S1 ; activate leveling"))
        assertTrue(text.contains("; First layer print x min = 28.00"))
        assertTrue(text.contains("; First layer print x max = 92.00"))
    }

    @Test
    fun missingActivationEmitsActivationAndC29AfterHome() {
        val file = temporaryGcode(
            "G28\n" +
                ";LAYER:0\n" +
                "G1 X10 Y10 E0.3\n" +
                ";LAYER:1\n",
        )
        assertTrue(AdaptiveBedMeshInjector.inject(file, envelope, 5.0))
        val text = file.readText()
        assertTrue(text.contains("M420 S1 ; activate leveling"))
        val home = text.indexOf("G28")
        assertTrue("block must come after G28", home in 0 until text.indexOf("C29 A"))
    }

    @Test
    fun boundsAreClampedToTheBuildVolume() {
        val file = temporaryGcode(
            "G28\n" +
                ";LAYER:0\n" +
                "G1 X-5 Y215 Z0.2\n" +
                "G1 X50 Y218 E0.3\n" +
                ";LAYER:1\n",
        )
        assertTrue(AdaptiveBedMeshInjector.inject(file, envelope, 10.0))
        val text = file.readText()
        assertTrue(text.contains("; First layer print x min = 0.00"))
        assertTrue(text.contains("; First layer print y max = 220.00"))
    }

    @Test
    fun prusaLayerMarkersWorkTheSame() {
        val file = temporaryGcode(
            ";LAYER_CHANGE\n" +
                ";Z:0.2\n" +
                "G1 X20 Y30 F1200\n" +
                "G1 X80 Y90 E0.8\n" +
                ";LAYER_CHANGE\n" +
                ";Z:0.4\n" +
                "G1 X80 Y90 E0.8\n",
        )
        assertTrue(AdaptiveBedMeshInjector.inject(file, envelope, 5.0))
        val text = file.readText()
        assertTrue(text.contains("; First layer print x min = 15.00"))
        assertTrue(text.contains("; First layer print x max = 85.00"))
        assertTrue(text.contains("C29 A ; use AML"))
    }

    @Test
    fun c29LicensedForPreviewAndPublishedAndNozzlePathParsesIt() {
        val file = temporaryGcode(
            "G28\n" +
                ";LAYER:0\n" +
                "G1 X10 Y20 E0.5\n" +
                ";LAYER:1\n" +
                "G1 X10 Y20 Z0.4 E0.5\n",
        )
        assertTrue(AdaptiveBedMeshInjector.inject(file, envelope, 5.0))
        val path = GcodeNozzlePathParser.parse(file)
        assertEquals(2, path.moveCount)
        val parsed = GcodeCommand.parse("C29 A")!!
        GcodeCommandPolicy.requirePreviewSafe(parsed, 0)
        GcodeCommandPolicy.requirePublishedSafe(parsed, null, 1)
    }

    @Test
    fun otherCCodesRemainRejected() {
        val bad = GcodeCommand.parse("C20")!!
        val preview = runCatching { GcodeCommandPolicy.requirePreviewSafe(bad, 0) }.exceptionOrNull()
        assertTrue(preview != null)
        val published = runCatching {
            GcodeCommandPolicy.requirePublishedSafe(GcodeCommand.parse("C29 B")!!, null, 1)
        }.exceptionOrNull()
        assertTrue(published != null)
    }

    private fun temporaryGcode(contents: String): File =
        File.createTempFile("aml-injector-test", ".gcode").apply {
            writeText(contents)
            deleteOnExit()
        }
}
