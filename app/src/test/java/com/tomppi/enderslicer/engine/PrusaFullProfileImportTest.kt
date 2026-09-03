package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.PrusaConfigImporter
import com.tomppi.enderslicer.model.PrusaSliceSettings
import java.io.File
import org.junit.Test

/** Round-trips the real PC profile (gui-full.ini) through the app importer -> writer. */
class PrusaFullProfileImportTest {
    @Test
    fun importRealProfileAndRewriteConfig() {
        val source = File("..\\.build\\prusa-slicer\\gui-full.ini")
        require(source.isFile) { "gui-full.ini missing: " + source.absolutePath }
        val imported = PrusaConfigImporter.parse(source.readText())
        val printer = PrinterDefinition(
            name = "Ender 3 V2", widthMm = 230.0, depthMm = 230.0, heightMm = 250.0,
            buildPlateShape = "rectangular",
            originAtCenter = false, heatedBed = true, heatedBuildVolume = false,
            gcodeFlavor = "Marlin", extruders = 1,
            nozzleSizeMm = imported.nozzleSizeMm ?: 0.4,
            filamentDiameterMm = imported.filamentDiameterMm ?: 1.75,
            printheadXMinMm = -26.0, printheadYMinMm = -32.0,
            printheadXMaxMm = 32.0, printheadYMaxMm = 34.0,
            gantryHeightMm = 25.0,
        )
        val config = PrusaConfigWriter.render(imported.settings, printer, imported.startGcode, imported.endGcode)
        val out = File("build/imported-full.ini")
        out.parentFile?.mkdirs()
        out.writeText(config)
        println("IMPORTED-SUMMARY")
        println("layer=" + imported.settings.layerHeightMm)
        println("first=" + imported.settings.firstLayerHeightMm)
        println("perimeters=" + imported.settings.perimeters)
        println("topSolid=" + imported.settings.topSolidLayers)
        println("bottomSolid=" + imported.settings.bottomSolidLayers)
        println("fill=" + imported.settings.fillDensityPercent + " " + imported.settings.fillPattern)
        println("speed=" + imported.settings.printSpeedMmPerSecond)
        println("t=" + imported.settings.nozzleTemperatureC + "/" + imported.settings.bedTemperatureC)
        println("startKeyCount=" + imported.unusedKeyCount)
        println("WROTE " + out.absolutePath + " bytes=" + out.length())
    }
}
