package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.engine.GcodeCommand
import com.tomppi.enderslicer.engine.PrinterEnvelope
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConformalGcodeTransformerTest {
    // Surface triangle (0,0,0.6)-(10,0,0.6)-(0,10,1.0): z = 0.6 + 0.04 * y.
    private fun surface(): ConformalSurface {
        val mesh = testMesh(floatArrayOf(0f, 0f, 0.6f, 10f, 0f, 0.6f, 0f, 10f, 1.0f))
        return ConformalSurfaceBuilder.build(mesh, NonPlanarSettings(enabled = true))
    }

    private fun fixtureGcode(retract: Boolean): String = listOf(
        ";FLAVOR:Marlin",
        "G90",
        "M82",
        "G92 E0",
        if (retract) "G10" else "G0 X0 Y0 F6000",
        if (retract) "G11" else "",
        "G0 X0 Y0 Z0.2 F6000",
        ";LAYER:0",
        "G1 X5 Y0 Z0.2 E1 F1200",
        "G1 X10 Y0 E2",
        ";LAYER:1",
        "G1 X10 Y0 Z0.4 E3",
        "G1 X5 Y0 E4",
        ";LAYER:2",
        "G1 X5 Y0 Z0.6 E5",
        "G1 X10 Y0 E6",
        ";LAYER:3",
        "G1 X10 Y0 Z0.8 E7",
        "G1 X5 Y0 E8",
        "G1 X20 Y0 E9",
        CurviSlicerRuntime.MACHINE_END_SENTINEL,
        ";End of Gcode",
    ).filter { it.isNotEmpty() }.joinToString("\n") + "\n"

    private fun transform(gcodeBody: String): Pair<String, ConformalGcodeTransformer.Diagnostics> {
        val directory = Files.createTempDirectory("conformal-gcode").toFile()
        try {
            val gcode = File(directory, "output.gcode")
            gcode.writeText(gcodeBody)
            val diagnostics = ConformalGcodeTransformer.transform(
                file = gcode,
                surface = surface(),
                layerHeightMm = 0.2,
                maximumZSpeedMmPerSecond = 5.0,
                conformalShellLayers = 3,
                printerEnvelope = printerEnvelope(),
            )
            return gcode.readText() to diagnostics
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun stairsAreRemovedAndReemittedAsSurfaceProjectedShells() {
        val (output, diagnostics) = transform(fixtureGcode(retract = false))
        assertEquals(4, diagnostics.stairMovesRemoved)
        assertEquals(4, diagnostics.skinMovesEmitted)
        assertEquals(1, diagnostics.regionCount)
        val lines = output.lines()
        val extrusionMoves = lines.mapNotNull { GcodeCommand.parse(it) }
            .filter { it.opcode == "G1" && it.value('E') != null }
        // Shell 1 rides surfaceZ - layerHeight = 0.4 (2 moves); shell 0 rides 0.6 (2 moves).
        assertEquals(2, extrusionMoves.count { it.value('Z') == 0.4 })
        assertEquals(2, extrusionMoves.count { it.value('Z') == 0.6 })
        // Absolute E stays continuous: 5 kept planar moves + 4 re-emitted
        // shells, and the total only advances on actually emitted extrusion.
        val eValues = extrusionMoves.mapNotNull { it.value('E') }
        assertEquals(9, eValues.size)
        assertEquals(9.0, eValues.last(), 1e-9)
        for (index in 1 until eValues.size) {
            assertTrue(eValues[index] > eValues[index - 1])
        }
    }

    @Test
    fun firstLayerAndDeepInteriorStayPlanar() {
        val (output, _) = transform(fixtureGcode(retract = false))
        val lines = output.lines()
        val layer0Extrusions = lines.filter {
            val command = GcodeCommand.parse(it) ?: return@filter false
            command.opcode == "G1" && command.value('E') != null && command.value('Z') == 0.2
        }
        assertEquals(2, layer0Extrusions.size)
        val layer3Extrusions = lines.filter {
            val command = GcodeCommand.parse(it) ?: return@filter false
            command.opcode == "G1" && command.value('E') != null && command.value('Z') == 0.8
        }
        assertEquals(3, layer3Extrusions.size)
    }

    @Test
    fun nozzleDivesFarBelowTheHomeLayerPlane() {
        // Blade surface z = 0.4 + 0.09 * y: thin tip at y=0, thick root at y=20.
        val blade = testMesh(floatArrayOf(0f, 0f, 0.4f, 20f, 0f, 0.4f, 0f, 20f, 2.2f))
        val bladeSurface = ConformalSurfaceBuilder.build(blade, NonPlanarSettings(enabled = true, maximumLiftMm = 3.0))
        val gcode = listOf(
            ";FLAVOR:Marlin",
            "G90",
            "M82",
            "G92 E0",
            ";LAYER:0",
            "G1 X5 Y0 Z0.2 E1 F1200",
            ";LAYER:1",
            "G1 X5 Y0 Z0.4 E2",
            ";LAYER:11",
            "G1 X5 Y20 Z2.2 E3",
            CurviSlicerRuntime.MACHINE_END_SENTINEL,
            ";End of Gcode",
        ).joinToString("\n") + "\n"
        val directory = Files.createTempDirectory("conformal-dive").toFile()
        try {
            val file = File(directory, "output.gcode")
            file.writeText(gcode)
            val diagnostics = ConformalGcodeTransformer.transform(
                file = file,
                surface = bladeSurface,
                layerHeightMm = 0.2,
                maximumZSpeedMmPerSecond = 5.0,
                conformalShellLayers = 3,
                printerEnvelope = printerEnvelope(),
            )
            // The shell 0 path rides the surface from the tip (z = 0.4) to the
            // root (z = 2.2): a single conformal pass that dives 1.8 mm below
            // the home layer plane at the thin end of the blade.
            val zValues = file.readLines().mapNotNull { line ->
                GcodeCommand.parse(line)
                    ?.takeIf { it.opcode == "G1" && it.value('E') != null }
                    ?.value('Z')
            }
            assertTrue("expected a skin at the thin tip", zValues.contains(0.4))
            assertTrue("expected a skin at the thick root", zValues.contains(2.2))
            assertEquals(1.8, diagnostics.maximumDiveMm, 1e-6)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun firmwareRetractionWrapsShellTravels() {
        val (output, _) = transform(fixtureGcode(retract = true))
        val lines = output.lines()
        assertTrue(lines.any { it.trim() == "G10" })
        assertTrue(lines.any { it.trim() == "G11" })
    }

    @Test
    fun missingSurfaceToolpathIsRejected() {
        val gcode = listOf(
            ";FLAVOR:Marlin",
            "G90",
            "M82",
            "G92 E0",
            ";LAYER:0",
            "G1 X20 Y20 Z0.2 E1 F1200",
            "G1 X30 Y20 E2",
            CurviSlicerRuntime.MACHINE_END_SENTINEL,
            ";End of Gcode",
        ).joinToString("\n") + "\n"
        val directory = Files.createTempDirectory("conformal-empty").toFile()
        try {
            val file = File(directory, "output.gcode")
            file.writeText(gcode)
            val failure = runCatching {
                ConformalGcodeTransformer.transform(
                    file = file,
                    surface = surface(),
                    layerHeightMm = 0.2,
                    maximumZSpeedMmPerSecond = 5.0,
                    conformalShellLayers = 3,
                    printerEnvelope = printerEnvelope(),
                )
            }.exceptionOrNull()
            assertTrue(requireNotNull(failure).message.orEmpty().contains("no toolpath"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun printerEnvelope(): PrinterEnvelope = PrinterEnvelope(
        widthMm = 220.0,
        depthMm = 220.0,
        heightMm = 250.0,
        buildPlateShape = "rectangular",
        originAtCenter = false,
        gcodeFlavor = "marlin",
    )
}
