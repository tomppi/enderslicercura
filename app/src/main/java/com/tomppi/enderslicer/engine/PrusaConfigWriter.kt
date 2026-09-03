package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.PrusaSliceSettings
import java.io.File

/**
 * Writes a PrusaSlicer .ini configuration for the bundled console.
 *
 * The sections follow PrusaSlicer's own profile shape ([print], [filament],
 * [printer]); the CLI merges everything given via --load, so the generated
 * file is self-contained: it carries the machine description, the start/end
 * G-code and every slice option the user edited, with PrusaSlicer key names.
 */
object PrusaConfigWriter {

    private fun iniEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "")

    private fun line(key: String, value: String): String = "$key = $value"

    /**
     * Renders the complete configuration text.
     *
     * @param settings Prusa-named slice options.
     * @param printer Machine description used for the [printer] section.
     * @param startGcode Custom start G-code (already assembled for Marlin).
     * @param endGcode Custom end G-code.
     * @param filamentType e.g. PLA.
     */
    fun render(
        settings: PrusaSliceSettings,
        printer: PrinterDefinition,
        startGcode: String,
        endGcode: String,
        filamentType: String = "PLA",
    ): String {
        // NOTE: the console's --load only honors FLAT config (the same layout the PC app
        // embeds in a 3MF footer as a prusaslicer_config block). Sectioned preset files
        // ([print]/[filament]/[printer]) are ignored by the console, so every key is written
        // at the top level here.
        val out = StringBuilder()

        out.appendLine(line("print_settings_id", "EnderSlicer Prusa custom"))
        out.appendLine(line("layer_height", settings.layerHeightMm.prusaNumber()))
        out.appendLine(line("first_layer_height", settings.firstLayerHeightMm.prusaNumber()))
        out.appendLine(line("perimeters", settings.perimeters.toString()))
        out.appendLine(line("top_solid_layers", settings.topSolidLayers.toString()))
        out.appendLine(line("bottom_solid_layers", settings.bottomSolidLayers.toString()))
        out.appendLine(line("thin_walls", settings.thinWalls.prusaBool()))
        out.appendLine(line("external_perimeters_first", settings.externalPerimetersFirst.prusaBool()))
        out.appendLine(line("fill_density", settings.fillDensityPercent.prusaNumber() + "%"))
        out.appendLine(line("fill_pattern", settings.fillPattern))
        out.appendLine(line("skirts", settings.skirtLoops.toString()))
        out.appendLine(line("skirt_height", settings.skirtHeightLayers.toString()))
        out.appendLine(line("skirt_distance", settings.skirtDistanceMm.prusaNumber()))
        out.appendLine(line("overhangs", settings.overhangs.prusaBool()))
        settings.firstLayerExtrusionWidthMm?.let { out.appendLine(line("first_layer_extrusion_width", it.prusaNumber())) }
        settings.perimeterExtrusionWidthMm?.let { out.appendLine(line("perimeter_extrusion_width", it.prusaNumber())) }
        settings.externalPerimeterExtrusionWidthMm?.let { out.appendLine(line("external_perimeter_extrusion_width", it.prusaNumber())) }
        settings.infillExtrusionWidthMm?.let { out.appendLine(line("infill_extrusion_width", it.prusaNumber())) }
        settings.solidInfillExtrusionWidthMm?.let { out.appendLine(line("solid_infill_extrusion_width", it.prusaNumber())) }
        settings.topInfillExtrusionWidthMm?.let { out.appendLine(line("top_infill_extrusion_width", it.prusaNumber())) }
        out.appendLine(line("brim_width", settings.brimWidthMm.prusaNumber()))
        out.appendLine(line("support_material", settings.supportMaterial.prusaBool()))
        out.appendLine(line("support_material_threshold_angle", settings.supportThresholdAngleDegrees.prusaNumber()))
        out.appendLine(line("support_material_pattern", settings.supportPattern))
        out.appendLine(line("support_material_interface", settings.supportInterface.prusaBool()))
        out.appendLine(line("support_material_interface_layers", settings.supportInterfaceLayers.toString()))
        out.appendLine(line("print_speed", settings.printSpeedMmPerSecond.prusaNumber()))
        out.appendLine(line("external_perimeter_speed", settings.externalPerimeterSpeedMmPerSecond.prusaNumber()))
        out.appendLine(line("infill_speed", settings.infillSpeedMmPerSecond.prusaNumber()))
        out.appendLine(line("first_layer_speed", settings.firstLayerSpeedMmPerSecond.prusaNumber()))
        out.appendLine(line("travel_speed", settings.travelSpeedMmPerSecond.prusaNumber()))
        out.appendLine(line("gcode_flavor", prusaGcodeFlavor(printer.gcodeFlavor)))
        out.appendLine(line("start_gcode", iniEscape(startGcode.trim().replace("\r\n", "\n"))))
        out.appendLine(line("end_gcode", iniEscape(endGcode.trim().replace("\r\n", "\n"))))
        out.appendLine(line("filament_settings_id", "EnderSlicer Filament"))
        out.appendLine(line("filament_diameter", printer.filamentDiameterMm.prusaNumber()))
        out.appendLine(line("filament_type", filamentType))
        out.appendLine(line("temperature", settings.nozzleTemperatureC.toString()))
        out.appendLine(line("first_layer_temperature", settings.firstLayerTemperatureC.toString()))
        out.appendLine(line("bed_temperature", settings.bedTemperatureC.toString()))
        out.appendLine(line("first_layer_bed_temperature", settings.firstLayerBedTemperatureC.toString()))
        out.appendLine(line("fan_speed", settings.fanSpeedPercent.toString()))
        out.appendLine(line("extrusion_multiplier", (settings.extrusionMultiplierPercent / 100.0).prusaNumber()))
        out.appendLine(line("printer_settings_id", "EnderSlicer " + printer.name))
        out.appendLine(line("printer_model", "Ender"))
        out.appendLine(line("bed_shape", prusaBedShape(printer)))
        out.appendLine(line("nozzle_diameter", printer.nozzleSizeMm.prusaNumber()))
        out.appendLine(line("extruder_count", printer.extruders.toString()))
        out.appendLine(line("use_firmware_retraction", settings.useFirmwareRetraction.prusaBool()))
        out.appendLine(line("retraction_length", settings.retractionLengthMm.prusaNumber()))
        out.appendLine(line("retraction_speed", settings.retractionSpeedMmPerSecond.prusaNumber()))
        out.appendLine(line("retraction_min_travel", settings.retractionMinTravelMm.prusaNumber()))
        out.appendLine(line("retract_lift", settings.retractLiftMm.prusaNumber()))

        return out.toString()
    }

    /** Renders and writes the configuration to [file], returning its absolute path. */
    fun write(
        file: File,
        settings: PrusaSliceSettings,
        printer: PrinterDefinition,
        startGcode: String,
        endGcode: String,
        filamentType: String = "PLA",
    ): File {
        file.parentFile?.mkdirs()
        file.writeText(render(settings, printer, startGcode, endGcode, filamentType))
        return file
    }

    /**
     * Section-agnostic lookup used by tests and the diagnostic export:
     * returns the value of the first [key] occurrence, or null.
     */
    fun valueOf(config: String, key: String): String? =
        config.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key =") }
            ?.substringAfter("=")?.trim()

    internal fun prusaBedShape(printer: PrinterDefinition): String {
        val halfW = printer.widthMm / 2.0
        val halfD = printer.depthMm / 2.0
        return if (printer.originAtCenter) {
            coordinate(-halfW) + "x" + coordinate(-halfD) + "," +
                coordinate(halfW) + "x" + coordinate(-halfD) + "," +
                coordinate(halfW) + "x" + coordinate(halfD) + "," +
                coordinate(-halfW) + "x" + coordinate(halfD)
        } else {
            "0x0," + coordinate(printer.widthMm) + "x0," +
                coordinate(printer.widthMm) + "x" + coordinate(printer.depthMm) + "," +
                "0x" + coordinate(printer.depthMm)
        }
    }

    private fun coordinate(value: Double): String {
        val rounded = (Math.round(value * 1000) / 1000.0)
        return if (rounded == rounded.toLong().toDouble()) {
            rounded.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.3f", rounded).trimEnd('0').trimEnd('.')
        }
    }

    internal fun prusaGcodeFlavor(flavor: String): String = when (flavor.lowercase()) {
        "marlin", "ender3", "marlin2" -> "marlin2"
        "reprap" -> "reprap"
        "klipper" -> "klipper"
        else -> "marlin2"
    }

    private fun Boolean.prusaBool(): String = if (this) "1" else "0"

    private fun Double.prusaNumber(): String {
        val rounded = (Math.round(this * 1000) / 1000.0)
        return if (rounded == rounded.toLong().toDouble()) {
            rounded.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.3f", rounded).trimEnd('0').trimEnd('.')
        }
    }
}
