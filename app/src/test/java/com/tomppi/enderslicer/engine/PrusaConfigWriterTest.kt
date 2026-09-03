package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.PrusaSliceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrusaConfigWriterTest {

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

    private val settings = PrusaSliceSettings(
        layerHeightMm = 0.2,
        firstLayerHeightMm = 0.3,
        perimeters = 3,
        topSolidLayers = 4,
        bottomSolidLayers = 5,
        fillDensityPercent = 15.0,
        fillPattern = "grid",
        supportMaterial = true,
        supportThresholdAngleDegrees = 55.0,
        supportInterface = true,
        supportInterfaceLayers = 2,
        printSpeedMmPerSecond = 60.0,
        externalPerimeterSpeedMmPerSecond = 25.0,
        infillSpeedMmPerSecond = 50.0,
        firstLayerSpeedMmPerSecond = 20.0,
        travelSpeedMmPerSecond = 150.0,
        nozzleTemperatureC = 210,
        bedTemperatureC = 60,
        retractionLengthMm = 0.8,
        retractionSpeedMmPerSecond = 45.0,
        useFirmwareRetraction = false,
        extrusionMultiplierPercent = 100.0,
    )

    @Test
    fun writesCorePrintKeysWithPrusaNames() {
        val config = PrusaConfigWriter.render(settings, printer, "G28\nG1 X10", "M84")

        assertEquals("0.2", PrusaConfigWriter.valueOf(config, "layer_height"))
        assertEquals("0.3", PrusaConfigWriter.valueOf(config, "first_layer_height"))
        assertEquals("3", PrusaConfigWriter.valueOf(config, "perimeters"))
        assertEquals("4", PrusaConfigWriter.valueOf(config, "top_solid_layers"))
        assertEquals("5", PrusaConfigWriter.valueOf(config, "bottom_solid_layers"))
        assertEquals("15%", PrusaConfigWriter.valueOf(config, "fill_density"))
        assertEquals("grid", PrusaConfigWriter.valueOf(config, "fill_pattern"))
        assertEquals("1", PrusaConfigWriter.valueOf(config, "support_material"))
        assertEquals("55", PrusaConfigWriter.valueOf(config, "support_material_threshold_angle"))
        assertEquals("1", PrusaConfigWriter.valueOf(config, "support_material_interface"))
        assertEquals("2", PrusaConfigWriter.valueOf(config, "support_material_interface_layers"))
        assertEquals("60", PrusaConfigWriter.valueOf(config, "print_speed"))
        assertEquals("25", PrusaConfigWriter.valueOf(config, "external_perimeter_speed"))
        assertEquals("50", PrusaConfigWriter.valueOf(config, "infill_speed"))
        assertEquals("20", PrusaConfigWriter.valueOf(config, "first_layer_speed"))
        assertEquals("150", PrusaConfigWriter.valueOf(config, "travel_speed"))
    }

    @Test
    fun writesMachineAndFilamentKeys() {
        val config = PrusaConfigWriter.render(settings, printer, "G28", "M84")

        assertEquals("marlin2", PrusaConfigWriter.valueOf(config, "gcode_flavor"))
        assertEquals("0x0,230x0,230x230,0x230", PrusaConfigWriter.valueOf(config, "bed_shape"))
        assertEquals("0.4", PrusaConfigWriter.valueOf(config, "nozzle_diameter"))
        assertEquals("1.75", PrusaConfigWriter.valueOf(config, "filament_diameter"))
        assertEquals("1", PrusaConfigWriter.valueOf(config, "extruder_count"))
        assertEquals("210", PrusaConfigWriter.valueOf(config, "temperature"))
        assertEquals("60", PrusaConfigWriter.valueOf(config, "bed_temperature"))
        assertEquals("100", PrusaConfigWriter.valueOf(config, "fan_speed"))
        assertEquals("0.8", PrusaConfigWriter.valueOf(config, "retraction_length"))
        assertEquals("45", PrusaConfigWriter.valueOf(config, "retraction_speed"))
    }

    @Test
    fun escapesStartGcodeNewlinesLikePrusaSlicerIni() {
        val config = PrusaConfigWriter.render(settings, printer, "G28\nG1 X10 F3000\nG1 Y10", "M84")

        val start = PrusaConfigWriter.valueOf(config, "start_gcode")
        assertNotNull(start)
        assertEquals("G28\\nG1 X10 F3000\\nG1 Y10", start)
    }

    @Test
    fun valuesMatchConsoleFlatFormat() {
        val config = PrusaConfigWriter.render(settings, printer, "G28", "M84")

        // The console honors only the flat format the PC app embeds in a 3MF footer.
        assertTrue(config.startsWith("print_settings_id ="))
        assertFalse(config.contains("[print]"))
        assertFalse(config.contains("[filament]"))
        assertFalse(config.contains("[printer]"))
        assertTrue(config.contains("layer_height = 0.2"))
        assertTrue(config.contains("fill_density = 15%"))
        assertTrue(config.contains("fill_pattern = grid"))
    }

    @Test
    fun writesExtraKeysFromAllSettingsCatalog() {
        val withExtras = settings.copy(extraKeys = mapOf("top_solid_infill_flow_ratio" to "0.9", "seam_position" to "nearest"))
        val config = PrusaConfigWriter.render(withExtras, printer, "G28", "M84")
        assertEquals("0.9", PrusaConfigWriter.valueOf(config, "top_solid_infill_flow_ratio"))
        assertEquals("nearest", PrusaConfigWriter.valueOf(config, "seam_position"))
    }

    @Test
    fun originCenteredPrinterUsesCenteredBedShape() {
        val centered = printer.copy(originAtCenter = true)
        val config = PrusaConfigWriter.render(settings, centered, "G28", "M84")
        assertEquals("-115x-115,115x-115,115x115,-115x115", PrusaConfigWriter.valueOf(config, "bed_shape"))
    }
}
