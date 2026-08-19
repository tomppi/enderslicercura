package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.engine.CuraEngineCommand
import com.tomppi.enderslicer.engine.PrinterEnvelope
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.model.withSettings
import com.tomppi.enderslicer.viewer.StlMeshWriter
import com.tomppi.enderslicer.viewer.StlParser
import org.junit.Test
import java.io.File

class CurviPropRepro2Test {
    private val printer = PrinterDefinition(
        name = "Modified Ender 3 V2",
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
        printheadXMinMm = -26.0,
        printheadYMinMm = -32.0,
        printheadXMaxMm = 32.0,
        printheadYMaxMm = 34.0,
        gantryHeightMm = 25.0,
    )

    @Test
    fun propPrepareAndEmitCommand() {
        CurviSlicerRuntime.activate(
            NonPlanarSettings(
                enabled = true,
                strengthPercent = 100.0,
                smoothingRadiusMm = 0.4,
                maximumSlopeDegrees = 30.0,
                nozzleClearanceAngleDegrees = 45.0,
                nozzleClearanceHeightMm = 10.0,
                flatBaseLayers = 3,
                fieldResolution = 192,
                maximumSegmentLengthMm = 0.2,
                maximumZSpeedMmPerSecond = 5.0,
            ),
        )
        val dir = File("C:/Users/FREDRIK/Documents/enderslicercura/.build/curvi-test").apply { mkdirs() }
        val model = File("C:/Users/FREDRIK/Documents/enderslicercura/.build/curvi-test/broad-dome.stl")
        check(model.isFile) { "dome.stl missing" }
        val parsed = StlParser.parse(model, model.name)
        val shifted = parsed.interleavedVertices.copyOf()
        val staged = File(dir, "prop.stl")
        StlMeshWriter.writeBinary(parsed.copy(interleavedVertices = shifted), staged)
        val probe = File(dir, "prop-probe.stl")
        StlMeshWriter.writeBinary(parsed.copy(interleavedVertices = shifted.copyOf()), probe)
        val probeResult = CurviSlicerPipeline.prepareAndWarp(
            modelFile = probe,
            settings = CurviSlicerRuntime.current(),
            layerHeightMm = 0.2,
            nozzleDiameterMm = 0.4,
        )
        val pd = probeResult.diagnostics
        println("PROP_DIAG strength=" + pd.appliedStrength + " rawRelief=" + pd.maximumRawReliefMm +
            " appliedDisp=" + pd.maximumAppliedDisplacementMm + " maxSlope=" + pd.maximumFieldSlopeDegrees)
        probe.delete()
        val output = File(dir, "prop-planar.gcode")
        val command = CuraEngineCommand.build(
            executablePath = "C:/Users/FREDRIK/Documents/enderslicercura/.build/curaengine-host-tests/artifacts/CuraEngine.exe",
            definitionsDirectory = "C:/Users/FREDRIK/Documents/enderslicercura/app/src/main/assets/cura/definitions",
            machineDefinitionPath = "C:/Users/FREDRIK/Documents/enderslicercura/app/src/main/assets/cura/definitions/creality_ender3.def.json",
            extruderDefinitionPath = "C:/Users/FREDRIK/Documents/enderslicercura/app/src/main/assets/cura/definitions/creality_base_extruder_0.def.json",
            modelPath = staged.absolutePath,
            outputPath = output.absolutePath,
            printer = printer,
            settings = SlicerSettings(),
            startGcode = "G28",
            endGcode = "M104 S0",
            threadCount = 4,
        )
        File(dir, "prop-args.txt").writeText(command.joinToString("\n"))
        val quoted = command.map { arg ->
            val sanitized = arg.replace("\n", " ")
            if (sanitized.any { it == ' ' || it == '"' }) "\"" + sanitized.replace("\"", "\\\"") + "\"" else sanitized
        }.joinToString(" ")
        val bat = File(dir, "run-prop.bat")
        bat.writeText(
            "@echo off\r\n" +
                "call \"C:\\Program Files (x86)\\Microsoft Visual Studio\\2022\\BuildTools\\VC\\Auxiliary\\Build\\vcvars64.bat\" >nul\r\n" +
                "call \"C:\\Users\\FREDRIK\\Documents\\enderslicercura\\.build\\curaengine-host-tests\\build\\Release\\generators\\conanrunenv-release-x86_64.bat\" >nul\r\n" +
                "cd /d " + dir.absolutePath + "\r\n" +
                quoted + "\r\n",
        )
        println("PROP_SIDECAR=" + File(dir, "curvislicer-field.bin").isFile)
        println("PROP_BAT=" + bat.absolutePath)
    }

    @Test
    fun propTransformAndStats() {
        val dir = File("C:/Users/FREDRIK/Documents/enderslicercura/.build/curvi-test")
        val gcode = File(dir, "prop-planar.gcode")
        check(gcode.isFile && gcode.length() > 0) { "run the engine first" }
        val diagnostics = CurviSlicerFieldStorage.curveStagedGcode(
            gcode,
            PrinterEnvelope.from(printer.withSettings(SlicerSettings())),
        ) ?: error("no sidecar")
        println("PROP_TRANSFORM zRange=" + diagnostics.minimumZmm + ".." + diagnostics.maximumZmm +
            " maxSlope=" + diagnostics.maximumObservedSlopeDegrees)
        var layer = -1
        var layerMin = Double.MAX_VALUE
        var layerMax = -Double.MAX_VALUE
        var maxIntraLayer = 0.0
        var maxIntraLayerNumber = -1
        var prevZ = Double.NaN
        var maxMoveDelta = 0.0
        for (line in gcode.readLines()) {
            if (line.startsWith(";LAYER:")) {
                if (layer >= 0 && layerMax - layerMin > maxIntraLayer) {
                    maxIntraLayer = layerMax - layerMin
                    maxIntraLayerNumber = layer
                }
                layer = line.substringAfter(":").trim().toIntOrNull() ?: layer
                layerMin = Double.MAX_VALUE
                layerMax = -Double.MAX_VALUE
                continue
            }
            if (!line.startsWith("G1") || !line.contains("E")) continue
            val z = Regex("Z([0-9.]+)").find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            layerMin = minOf(layerMin, z)
            layerMax = maxOf(layerMax, z)
            if (!prevZ.isNaN()) maxMoveDelta = maxOf(maxMoveDelta, kotlin.math.abs(z - prevZ))
            prevZ = z
        }
        println("PROP_INTRALAYER max=" + maxIntraLayer + " layer=" + maxIntraLayerNumber +
            " maxMoveDelta=" + maxMoveDelta)
    }
}
