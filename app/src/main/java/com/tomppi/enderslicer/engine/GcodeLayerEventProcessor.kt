package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.calibration.CalibrationSliceState
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

object GcodeLayerEventProcessor {
    fun resolve(
        planned: List<PlannedLayerEvent>,
        preview: GcodeLayerPreview,
    ): List<LayerEvent> {
        if (planned.isEmpty()) return emptyList()
        return planned.mapIndexed { index, event ->
            val layer = preview.layers.firstOrNull {
                it.segmentCount > 0 && it.z + Z_EPSILON >= event.targetZMm
            } ?: preview.layers.lastOrNull { it.segmentCount > 0 }
                ?: preview.layers.last()
            LayerEvent(
                id = "calibration-$index-${layer.number}",
                layerNumber = layer.number,
                zMm = layer.z,
                type = event.type,
                value = event.value,
                secondaryValue = event.secondaryValue,
                text = event.text,
                source = LayerEventSource.CALIBRATION,
                label = event.label,
            )
        }.distinctBy { event -> event.id }
    }

    fun materialize(
        baseFile: File,
        destination: File,
        events: List<LayerEvent>,
    ) {
        require(baseFile.isFile && baseFile.length() > 0L) { "The original sliced G-code is unavailable" }
        val grouped = events
            .onEach(::validate)
            .groupBy(LayerEvent::layerNumber)
            .mapValues { (_, values) -> values.sortedWith(compareBy(LayerEvent::source, LayerEvent::id)) }
        val eventTypes = events.mapTo(linkedSetOf(), LayerEvent::type)
        val calibrationTypes = events
            .filter { it.source == LayerEventSource.CALIBRATION }
            .mapTo(linkedSetOf(), LayerEvent::type)
        val fanCalibration = LayerEventType.FAN_SPEED in calibrationTypes

        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.events.tmp")
        temporary.delete()
        temporary.bufferedWriter().use { writer ->
            var firmwareRetracted = false
            var calibrationFanStarted = false
            val deferredRetraction = mutableListOf<LayerEvent>()

            fun writeEvent(event: LayerEvent) {
                writer.write(";ENDERSLICER_LAYER_EVENT:${safeMarker(event.id)}:${event.type.name}:${event.source.name}")
                writer.newLine()
                val label = event.label.takeIf(String::isNotBlank) ?: event.displayName()
                writer.write(";ENDERSLICER_LAYER_EVENT_LABEL:${safeMarker(label)}")
                writer.newLine()
                commands(event).forEach { command ->
                    writer.write(command)
                    writer.newLine()
                }
                if (event.source == LayerEventSource.CALIBRATION && event.type == LayerEventType.FAN_SPEED) {
                    calibrationFanStarted = true
                }
            }

            baseFile.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val opcode = GcodeCommand.parse(line)?.opcode

                    // Cura cannot know about fan changes inserted after slicing.
                    // Once a fan calibration begins, its events own the fan until
                    // the safety shutdown written at EOF.
                    if (fanCalibration && calibrationFanStarted && (opcode == "M106" || opcode == "M107")) {
                        return@forEach
                    }

                    writer.write(line)
                    writer.newLine()

                    when (opcode) {
                        "G10" -> firmwareRetracted = true
                        "G11" -> {
                            firmwareRetracted = false
                            if (deferredRetraction.isNotEmpty()) {
                                deferredRetraction.forEach(::writeEvent)
                                deferredRetraction.clear()
                            }
                        }
                    }

                    if (!line.startsWith(";LAYER:")) return@forEach
                    val layerNumber = line.substringAfter(':').trim().toIntOrNull() ?: return@forEach
                    grouped[layerNumber].orEmpty().forEach { event ->
                        // M207 changes the length G11 recovers. Changing M207
                        // after G10 but before its matching G11 can recover a
                        // different amount than was retracted, so wait until
                        // firmware retraction is balanced again.
                        if (event.type == LayerEventType.RETRACTION && firmwareRetracted) {
                            deferredRetraction += event
                        } else {
                            writeEvent(event)
                        }
                    }
                }
            }

            // A manual event placed at the very end may not see another G11.
            // Keep it visible in the output rather than silently dropping it.
            deferredRetraction.forEach(::writeEvent)

            // M220 and M221 are persistent printer state regardless of whether
            // they came from a generated calibration or a manually added event.
            if (LayerEventType.SPEED_FACTOR in eventTypes) {
                writer.write("M220 S100 ; enderslicercura restore speed factor")
                writer.newLine()
            }
            if (LayerEventType.FLOW_FACTOR in eventTypes) {
                writer.write("M221 S100 ; enderslicercura restore flow factor")
                writer.newLine()
            }
            if (LayerEventType.RETRACTION in calibrationTypes) {
                CalibrationSliceState.retractionRestoreCommand()?.let { command ->
                    writer.write("$command ; enderslicercura restore firmware retraction")
                    writer.newLine()
                }
            }
            if (LayerEventType.PRESSURE_ADVANCE in calibrationTypes) {
                CalibrationSliceState.pressureAdvanceRestoreCommand()?.let { command ->
                    writer.write("$command ; enderslicercura restore pressure advance")
                    writer.newLine()
                }
            }
            if (LayerEventType.JUNCTION_DEVIATION in calibrationTypes) {
                CalibrationSliceState.junctionDeviationRestoreCommand()?.let { command ->
                    writer.write("$command ; enderslicercura restore junction deviation")
                    writer.newLine()
                }
            }
            if (fanCalibration && calibrationFanStarted) {
                writer.write("M107 ; enderslicercura fan calibration safety shutdown")
                writer.newLine()
            }
        }
        check(temporary.isFile && temporary.length() > 0L) { "Layer-event G-code output is empty" }
        if (destination.exists()) destination.delete()
        check(temporary.renameTo(destination) || temporary.copyTo(destination, overwrite = true).let { temporary.delete(); true }) {
            "Unable to write layer-event G-code"
        }
    }

    fun commands(event: LayerEvent): List<String> {
        validate(event)
        return when (event.type) {
            LayerEventType.PAUSE -> listOf(
                "M117 Pause layer ${event.layerNumber}",
                "M0",
            )

            LayerEventType.FILAMENT_CHANGE -> listOf("M600")
            LayerEventType.NOZZLE_TEMPERATURE -> listOf("M104 S${format(requireNotNull(event.value))}")
            LayerEventType.BED_TEMPERATURE -> listOf("M140 S${format(requireNotNull(event.value))}")
            LayerEventType.FAN_SPEED -> {
                val percent = requireNotNull(event.value).coerceIn(0.0, 100.0)
                if (percent <= 0.0) {
                    listOf("M107")
                } else {
                    val pwm = (percent * 255.0 / 100.0).roundToInt().coerceIn(0, 255)
                    listOf("M106 S$pwm")
                }
            }

            LayerEventType.SPEED_FACTOR -> listOf("M220 S${format(requireNotNull(event.value))}")
            LayerEventType.FLOW_FACTOR -> listOf("M221 S${format(requireNotNull(event.value))}")
            LayerEventType.RETRACTION -> listOf(
                "M207 S${format(requireNotNull(event.value))} F${format(requireNotNull(event.secondaryValue) * 60.0)}",
            )
            LayerEventType.PRESSURE_ADVANCE -> listOf("M900 K${format(requireNotNull(event.value))}")
            LayerEventType.JUNCTION_DEVIATION -> listOf("M205 J${format(requireNotNull(event.value))}")

            LayerEventType.CAMERA_TRIGGER -> listOf("M240")
            LayerEventType.MESSAGE -> listOf("M117 ${safeMessage(event.text)}")
            LayerEventType.CUSTOM_GCODE -> customLines(event.text)
        }
    }

    private fun validate(event: LayerEvent) {
        when (event.type) {
            LayerEventType.PAUSE,
            LayerEventType.FILAMENT_CHANGE,
            LayerEventType.CAMERA_TRIGGER,
            -> Unit

            LayerEventType.NOZZLE_TEMPERATURE -> requireValue(event, 0.0, 500.0, "nozzle temperature")
            LayerEventType.BED_TEMPERATURE -> requireValue(event, 0.0, 200.0, "bed temperature")
            LayerEventType.FAN_SPEED -> requireValue(event, 0.0, 100.0, "fan speed")
            LayerEventType.SPEED_FACTOR -> requireValue(event, 10.0, 999.0, "speed factor")
            LayerEventType.FLOW_FACTOR -> requireValue(event, 10.0, 300.0, "flow factor")
            LayerEventType.RETRACTION -> {
                requireValue(event, 0.0, 100.0, "retraction distance")
                val speed = requireNotNull(event.secondaryValue) { "Retraction event requires a speed" }
                require(speed in 0.1..1000.0) { "Retraction speed is outside 0.1..1000 mm/s" }
            }
            LayerEventType.PRESSURE_ADVANCE -> requireValue(event, 0.0, 10.0, "pressure advance K")
            LayerEventType.JUNCTION_DEVIATION -> requireValue(event, 0.0, 1.0, "junction deviation")

            LayerEventType.MESSAGE -> require(safeMessage(event.text).isNotBlank()) { "Display message cannot be blank" }
            LayerEventType.CUSTOM_GCODE -> customLines(event.text)
        }
    }

    private fun requireValue(event: LayerEvent, minimum: Double, maximum: Double, name: String) {
        val value = requireNotNull(event.value) { "Layer event requires $name" }
        require(value in minimum..maximum) { "$name is outside $minimum..$maximum" }
    }

    private fun customLines(text: String): List<String> {
        require(text.length <= MAX_CUSTOM_TEXT) { "Custom G-code is too long" }
        val lines = text.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()
        require(lines.isNotEmpty()) { "Custom G-code cannot be blank" }
        require(lines.size <= MAX_CUSTOM_LINES) { "Custom G-code is limited to $MAX_CUSTOM_LINES lines" }
        lines.forEach { line ->
            require(line.length <= MAX_CUSTOM_LINE_LENGTH) { "A custom G-code line is too long" }
            require(line.none { it == '\u0000' || (it.code < 0x20 && it != '\t') }) {
                "Custom G-code contains a control character"
            }
            require(!line.startsWith(";LAYER:", ignoreCase = true)) { "Custom G-code cannot create layer markers" }
            require(!line.startsWith(";ENDERSLICER", ignoreCase = true)) { "Custom G-code cannot create EnderSlicer markers" }
            GcodeCommand.parse(line)?.opcode?.let { opcode ->
                require(opcode !in BLOCKED_OPCODES) { "$opcode is blocked inside layer events" }
            }
        }
        return lines
    }

    private fun safeMessage(value: String): String = value
        .replace(Regex("[\\r\\n\\u0000-\\u001f]+"), " ")
        .replace(';', ' ')
        .trim()
        .take(MAX_MESSAGE_LENGTH)

    private fun safeMarker(value: String): String = value
        .replace(Regex("[^A-Za-z0-9 ._+%°:/=-]"), "_")
        .take(120)

    private fun format(value: Double): String = String.format(Locale.US, "%.5f", value).trimEnd('0').trimEnd('.')

    private val BLOCKED_OPCODES = setOf("G28", "G29", "M18", "M84", "M112", "M500", "M501", "M502", "M997", "M999")
    private const val MAX_CUSTOM_LINES = 20
    private const val MAX_CUSTOM_LINE_LENGTH = 160
    private const val MAX_CUSTOM_TEXT = 2_400
    private const val MAX_MESSAGE_LENGTH = 48
    private const val Z_EPSILON = 0.0005f
}
