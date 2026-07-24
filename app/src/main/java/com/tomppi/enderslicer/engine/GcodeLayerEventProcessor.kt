package com.tomppi.enderslicer.engine

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
            val layer = preview.layers.firstOrNull { it.z + Z_EPSILON >= event.targetZMm }
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

        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.events.tmp")
        temporary.delete()
        temporary.bufferedWriter().use { writer ->
            baseFile.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    writer.write(line)
                    writer.newLine()
                    if (!line.startsWith(";LAYER:")) return@forEach
                    val layerNumber = line.substringAfter(':').trim().toIntOrNull() ?: return@forEach
                    grouped[layerNumber].orEmpty().forEach { event ->
                        writer.write(";ENDERSLICER_LAYER_EVENT:${safeMarker(event.id)}:${event.type.name}:${event.source.name}")
                        writer.newLine()
                        val label = event.label.takeIf(String::isNotBlank) ?: event.displayName()
                        writer.write(";ENDERSLICER_LAYER_EVENT_LABEL:${safeMarker(label)}")
                        writer.newLine()
                        commands(event).forEach { command ->
                            writer.write(command)
                            writer.newLine()
                        }
                    }
                }
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
                if (percent <= 0.0) listOf("M107") else listOf("M106 S${(percent * 2.55).roundToInt().coerceIn(0, 255)}")
            }

            LayerEventType.SPEED_FACTOR -> listOf("M220 S${format(requireNotNull(event.value))}")
            LayerEventType.FLOW_FACTOR -> listOf("M221 S${format(requireNotNull(event.value))}")
            LayerEventType.RETRACTION -> listOf(
                "M207 S${format(requireNotNull(event.value))} F${format(requireNotNull(event.secondaryValue) * 60.0)}",
            )

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
            val opcode = line.substringBefore(' ').substringBefore(';').uppercase(Locale.US)
            require(opcode !in BLOCKED_OPCODES) { "$opcode is blocked inside layer events" }
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
