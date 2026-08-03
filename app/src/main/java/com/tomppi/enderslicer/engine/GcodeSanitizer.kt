package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.BuildConfig
import java.io.File
import kotlin.math.ceil

object GcodeSanitizer {
    data class Summary(
        val layerCount: Int,
        val estimatedSeconds: Int?,
        val filamentMillimeters: Double,
        val totalFilamentMillimeters: Double,
        val minX: Double?,
        val minY: Double?,
        val minZ: Double?,
        val maxX: Double?,
        val maxY: Double?,
        val maxZ: Double?,
    )

    class UnsafeGcodeException(message: String) : Exception(message)

    fun validateAndRepair(
        file: File,
        settingsTransport: String = "auto",
        printerEnvelope: PrinterEnvelope? = null,
    ): Summary {
        require('\n' !in settingsTransport && '\r' !in settingsTransport) {
            "Settings transport marker contains a line break"
        }
        require(file.isFile && file.length() > 0L) { "Generated G-code is empty" }
        val resolvedSettingsTransport = when {
            !settingsTransport.equals("auto", ignoreCase = true) -> settingsTransport
            File(file.parentFile, "resolved-settings.json").isFile -> "resolved-json"
            else -> "fallback-command"
        }

        var layerCount = 0
        var currentLayer: Int? = null
        var lastElapsed: Double? = null
        val modalState = GcodeModalState()
        var currentE = 0.0
        var modelFilament = 0.0
        var totalFilament = 0.0
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var inModelMesh = false
        var minX: Double? = null
        var minY: Double? = null
        var minZ: Double? = null
        var maxX: Double? = null
        var maxY: Double? = null
        var maxZ: Double? = null
        var explicitNozzleTarget: Double? = null
        var nozzleTargetLine: Int? = null
        var nozzleTargetLayer: Int? = null
        var lineNumber = 0
        var temperatureCalibration = false

        file.bufferedReader().useLines { lines ->
            lines.forEach { rawLine ->
                lineNumber++
                val line = rawLine.trimStart()
                when {
                    line.startsWith(";ENDERSLICER_LAYER_EVENT:") &&
                        line.contains(":NOZZLE_TEMPERATURE:CALIBRATION") -> {
                        temperatureCalibration = true
                    }
                    line.startsWith(";LAYER_COUNT:") -> {
                        line.substringAfter(':').trim().toIntOrNull()?.let { layerCount = it }
                    }
                    line.startsWith(";LAYER:") -> {
                        currentLayer = line.substringAfter(':').trim().toIntOrNull()
                    }
                    line.startsWith(";TIME_ELAPSED:") -> {
                        lastElapsed = line.substringAfter(':').trim().toDoubleOrNull() ?: lastElapsed
                    }
                    line.startsWith(";MESH:") -> {
                        val meshName = line.substringAfter(':').trim()
                        inModelMesh = meshName.isNotEmpty() && !meshName.equals("NONMESH", ignoreCase = true)
                    }
                }

                val command = GcodeCommand.parse(rawLine) ?: return@forEach
                if (modalState.apply(command)) return@forEach
                when (command.opcode) {
                    "G92" -> {
                        command.value('E')?.let { currentE = it }
                        command.value('X')?.let { x = it }
                        command.value('Y')?.let { y = it }
                        command.value('Z')?.let { z = it }
                    }
                    "M104", "M109" -> {
                        val target = command.value('S') ?: command.value('R')
                        if (target != null) {
                            explicitNozzleTarget = target
                            nozzleTargetLine = lineNumber
                            nozzleTargetLayer = currentLayer
                        }
                    }
                    "G0", "G1" -> {
                        val startX = x
                        val startY = y
                        val startZ = z
                        x = modalState.position(x, command.value('X'))
                        y = modalState.position(y, command.value('Y'))
                        z = modalState.position(z, command.value('Z'))
                        val spatialMove = startX != x || startY != y || startZ != z
                        if (spatialMove) {
                            printerEnvelope?.requireMotionMove(
                                startX = startX,
                                startY = startY,
                                startZ = startZ,
                                endX = x,
                                endY = y,
                                endZ = z,
                                lineNumber = lineNumber,
                                layerNumber = currentLayer,
                            )
                        }
                        var positiveExtrusion = 0.0
                        command.value('E')?.let { requested ->
                            val nextE = modalState.extrusion(currentE, requested)
                            val delta = nextE - currentE
                            if (delta > 0.0) {
                                positiveExtrusion = delta
                                val target = explicitNozzleTarget
                                if (target != null && target in 0.0..<MINIMUM_ACTIVE_NOZZLE_C) {
                                    val extrusionLayer = currentLayer?.let { "layer $it" } ?: "startup"
                                    val targetLocation = buildString {
                                        append("target set")
                                        nozzleTargetLine?.let { append(" at line $it") }
                                        nozzleTargetLayer?.let { append(", layer $it") }
                                    }
                                    throw UnsafeGcodeException(
                                        "Unsafe nozzle target ${format(target)} C while extruding at $extrusionLayer " +
                                            "(extrusion line $lineNumber; $targetLocation). " +
                                            "The G-code was not made available for export.",
                                    )
                                }
                                if (spatialMove) {
                                    printerEnvelope?.requireExtrusionMove(
                                        startX = startX,
                                        startY = startY,
                                        startZ = startZ,
                                        endX = x,
                                        endY = y,
                                        endZ = z,
                                        lineNumber = lineNumber,
                                        layerNumber = currentLayer,
                                    )
                                }
                            }
                            currentE = nextE
                        }

                        if (currentLayer != null && positiveExtrusion > 0.0) {
                            totalFilament += positiveExtrusion
                        }
                        if (inModelMesh && currentLayer != null && positiveExtrusion > 0.0) {
                            modelFilament += positiveExtrusion
                            minX = minX?.let { minOf(it, startX, x) } ?: minOf(startX, x)
                            minY = minY?.let { minOf(it, startY, y) } ?: minOf(startY, y)
                            minZ = minZ?.let { minOf(it, startZ, z) } ?: minOf(startZ, z)
                            maxX = maxX?.let { maxOf(it, startX, x) } ?: maxOf(startX, x)
                            maxY = maxY?.let { maxOf(it, startY, y) } ?: maxOf(startY, y)
                            maxZ = maxZ?.let { maxOf(it, startZ, z) } ?: maxOf(startZ, z)
                        }
                    }
                }
            }
        }

        val estimatedSeconds = lastElapsed?.let { ceil(it).toInt() }
        val temporary = File(file.parentFile, "${file.name}.validated")
        temporary.delete()
        temporary.outputStream().buffered().writer(Charsets.UTF_8).buffered().use { writer ->
            var insertedMarkers = false
            file.bufferedReader().useLines { lines ->
                lines.forEach { originalLine ->
                    val line = when {
                        originalLine.contains(TEMPERATURE_CALIBRATION_SHUTDOWN_COMMENT) -> return@forEach
                        originalLine.startsWith(";ENDERSLICER_VERSION:") ||
                            originalLine.startsWith(";ENDERSLICER_COORDINATE_TRANSPORT:") ||
                            originalLine.startsWith(";ENDERSLICER_SETTINGS_TRANSPORT:") -> return@forEach
                        originalLine.startsWith(";TIME:") && estimatedSeconds != null -> ";TIME:$estimatedSeconds"
                        originalLine.startsWith(";Filament used:") -> ";Filament used: ${format(totalFilament / 1000.0)}m"
                        originalLine.startsWith(";MINX:") && minX != null -> ";MINX:${format(requireNotNull(minX))}"
                        originalLine.startsWith(";MINY:") && minY != null -> ";MINY:${format(requireNotNull(minY))}"
                        originalLine.startsWith(";MINZ:") && minZ != null -> ";MINZ:${format(requireNotNull(minZ))}"
                        originalLine.startsWith(";MAXX:") && maxX != null -> ";MAXX:${format(requireNotNull(maxX))}"
                        originalLine.startsWith(";MAXY:") && maxY != null -> ";MAXY:${format(requireNotNull(maxY))}"
                        originalLine.startsWith(";MAXZ:") && maxZ != null -> ";MAXZ:${format(requireNotNull(maxZ))}"
                        else -> originalLine
                    }
                    writer.write(line)
                    writer.write(PRINTER_LINE_ENDING)
                    if (!insertedMarkers) {
                        writer.write(";ENDERSLICER_VERSION:${BuildConfig.VERSION_NAME}")
                        writer.write(PRINTER_LINE_ENDING)
                        writer.write(";ENDERSLICER_COORDINATE_TRANSPORT:original-stl-full-affine-pre-round")
                        writer.write(PRINTER_LINE_ENDING)
                        writer.write(";ENDERSLICER_SETTINGS_TRANSPORT:$resolvedSettingsTransport")
                        writer.write(PRINTER_LINE_ENDING)
                        insertedMarkers = true
                    }
                }
            }
            if (temperatureCalibration) {
                writer.write(TEMPERATURE_CALIBRATION_SHUTDOWN_COMMAND)
                writer.write(PRINTER_LINE_ENDING)
            }
        }
        check(temporary.length() > 0L) { "Validated G-code output is empty" }
        if (file.exists()) file.delete()
        check(temporary.renameTo(file) || temporary.copyTo(file, overwrite = true).let { temporary.delete(); true }) {
            "Unable to replace generated G-code with the validated output"
        }
        if (temperatureCalibration && finalNozzleTarget(file) != 0.0) {
            throw UnsafeGcodeException(
                "Temperature calibration did not finish with the hotend target disabled. " +
                    "The G-code was not made available for export.",
            )
        }

        return Summary(
            layerCount = layerCount,
            estimatedSeconds = estimatedSeconds,
            filamentMillimeters = modelFilament,
            totalFilamentMillimeters = totalFilament,
            minX = minX,
            minY = minY,
            minZ = minZ,
            maxX = maxX,
            maxY = maxY,
            maxZ = maxZ,
        )
    }

    private fun finalNozzleTarget(file: File): Double? {
        var target: Double? = null
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val command = GcodeCommand.parse(line) ?: return@forEach
                if (command.opcode == "M104" || command.opcode == "M109") {
                    target = command.value('S') ?: command.value('R') ?: target
                }
            }
        }
        return target
    }

    private fun format(value: Double): String = "%.5f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')

    private const val TEMPERATURE_CALIBRATION_SHUTDOWN_COMMENT =
        "enderslicercura temperature calibration safety shutdown"
    private const val TEMPERATURE_CALIBRATION_SHUTDOWN_COMMAND =
        "M104 S0 ; $TEMPERATURE_CALIBRATION_SHUTDOWN_COMMENT"
    private const val MINIMUM_ACTIVE_NOZZLE_C = 150.0
    private const val PRINTER_LINE_ENDING = "\r\n"
}
