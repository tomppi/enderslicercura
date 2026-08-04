package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.calibration.CalibrationSliceState
import java.io.File
import java.util.Locale

object GcodeLayerEventProcessor {
    fun resolve(
        planned: List<PlannedLayerEvent>,
        preview: GcodeLayerPreview,
    ): List<LayerEvent> {
        if (planned.isEmpty()) return emptyList()
        require(planned.zipWithNext().all { (first, second) -> second.targetZMm > first.targetZMm }) {
            "Calibration target heights must be strictly increasing"
        }
        val printableLayers = preview.layers.filter(GcodeLayerPreview.Layer::hasPrintablePaths)
        require(printableLayers.isNotEmpty()) { "The sliced model has no printable calibration layers" }

        val resolved = planned.mapIndexed { index, event ->
            val layer = printableLayers.firstOrNull { it.z + Z_EPSILON >= event.targetZMm }
                ?: throw IllegalArgumentException(
                    "Calibration target ${formatTarget(event.targetZMm)} mm is above the last printable layer",
                )
            val targetError = layer.z - event.targetZMm
            require(targetError >= -Z_EPSILON && targetError <= MAX_TARGET_ERROR_MM) {
                "Calibration target ${formatTarget(event.targetZMm)} mm resolves too far away at " +
                    "${formatTarget(layer.z)} mm"
            }
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
        }
        require(resolved.zipWithNext().all { (first, second) -> second.layerNumber > first.layerNumber }) {
            "Calibration levels collapse onto the same printable layer; use a taller model or finer layers"
        }
        return resolved
    }

    fun materialize(
        baseFile: File,
        destination: File,
        events: List<LayerEvent>,
        firmware: CalibrationFirmwareEncoder = CalibrationFirmwareEncoder.fromFlavor(
            PrinterEnvelope.DEFAULT_GCODE_FLAVOR,
        ),
    ) {
        require(baseFile.isFile && baseFile.length() > 0L) { "The original sliced G-code is unavailable" }
        val orderedEvents = LayerEventOrdering.normalize(events).onEach(::validate)
        val grouped = orderedEvents.groupBy(LayerEvent::layerNumber)
        val eventTypes = orderedEvents.mapTo(linkedSetOf(), LayerEvent::type)
        val calibrationTypes = orderedEvents
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
                commands(event, firmware).forEach { command ->
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
                        if (event.type == LayerEventType.RETRACTION && firmwareRetracted) {
                            deferredRetraction += event
                        } else {
                            writeEvent(event)
                        }
                    }
                }
            }

            deferredRetraction.forEach(::writeEvent)

            if (LayerEventType.SPEED_FACTOR in eventTypes) {
                writer.write("M220 S100 ; enderslicercura restore speed factor")
                writer.newLine()
            }
            if (LayerEventType.FLOW_FACTOR in eventTypes) {
                writer.write("M221 S100 ; enderslicercura restore flow factor")
                writer.newLine()
            }
            if (LayerEventType.RETRACTION in calibrationTypes) {
                CalibrationSliceState.retractionRestoreCommand(firmware)?.let { command ->
                    writer.write("$command ; enderslicercura restore firmware retraction")
                    writer.newLine()
                }
            }
            if (LayerEventType.PRESSURE_ADVANCE in calibrationTypes) {
                CalibrationSliceState.pressureAdvanceRestoreCommand(firmware)?.let { command ->
                    writer.write("$command ; enderslicercura restore pressure advance")
                    writer.newLine()
                }
            }
            if (LayerEventType.JUNCTION_DEVIATION in calibrationTypes) {
                CalibrationSliceState.junctionDeviationRestoreCommand(firmware)?.let { command ->
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

    fun commands(event: LayerEvent): List<String> = commands(
        event,
        CalibrationFirmwareEncoder.fromFlavor(PrinterEnvelope.DEFAULT_GCODE_FLAVOR),
    )

    internal fun commands(
        event: LayerEvent,
        firmware: CalibrationFirmwareEncoder,
    ): List<String> {
        validate(event)
        return firmware.commands(
            type = event.type,
            layerNumber = event.layerNumber,
            value = event.value,
            secondaryValue = event.secondaryValue,
            text = event.text,
        )
    }

    private fun validate(event: LayerEvent) {
        require(event.layerNumber in -100_000..1_000_000) { "Layer event number is invalid" }
        event.value?.let { require(it.isFinite()) { "Layer event value must be finite" } }
        event.secondaryValue?.let { require(it.isFinite()) { "Layer event secondary value must be finite" } }
        when (event.type) {
            LayerEventType.NOZZLE_TEMPERATURE -> requireValue(event, 0.0, 450.0)
            LayerEventType.BED_TEMPERATURE -> requireValue(event, 0.0, 200.0)
            LayerEventType.FAN_SPEED -> requireValue(event, 0.0, 100.0)
            LayerEventType.SPEED_FACTOR,
            LayerEventType.FLOW_FACTOR,
            -> requireValue(event, 1.0, 999.0)
            LayerEventType.RETRACTION -> {
                requireValue(event, 0.0, 100.0)
                event.secondaryValue?.let { require(it in 0.1..1000.0) { "Retraction speed is outside 0.1..1000 mm/s" } }
            }
            LayerEventType.PRESSURE_ADVANCE -> requireValue(event, 0.0, 10.0)
            LayerEventType.JUNCTION_DEVIATION -> requireValue(event, 0.0, 1.0)
            LayerEventType.MESSAGE -> require(event.text.isNotBlank()) { "Message event text cannot be blank" }
            LayerEventType.CUSTOM_GCODE -> {
                val lines = event.text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
                require(lines.isNotEmpty()) { "Custom G-code event is empty" }
                require(lines.size <= 16) { "Custom G-code event exceeds 16 commands" }
                lines.forEach(::validateCustomLine)
            }
            LayerEventType.PAUSE,
            LayerEventType.FILAMENT_CHANGE,
            LayerEventType.CAMERA_TRIGGER,
            -> Unit
        }
    }

    private fun requireValue(event: LayerEvent, minimum: Double, maximum: Double) {
        val value = requireNotNull(event.value) { "${event.type} requires a value" }
        require(value in minimum..maximum) { "${event.type} value is outside $minimum..$maximum" }
    }

    private fun validateCustomLine(line: String) {
        require(line.length <= 160) { "Custom G-code command is too long" }
        require(line.none { it == '\u0000' || it == '\r' || it == '\n' }) { "Custom G-code contains a control character" }
        val command = GcodeCommand.parse(line)
            ?: throw IllegalArgumentException("Custom G-code must contain a recognized command")
        GcodeCommandPolicy.requireSafeCustomEvent(command)
        if (command.opcode == "M104" || command.opcode == "M109") {
            val target = command.value('S') ?: command.value('R')
            require(target != null && target in 0.0..450.0) { "Custom nozzle temperature is invalid" }
        }
        if (command.opcode == "M140" || command.opcode == "M190") {
            val target = command.value('S') ?: command.value('R')
            require(target != null && target in 0.0..200.0) { "Custom bed temperature is invalid" }
        }
    }

    private fun safeMarker(value: String): String = value
        .replace(Regex("[\\r\\n:;]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(80)
        .ifBlank { "event" }

    private fun formatTarget(value: Float): String = String.format(Locale.US, "%.3f", value)
        .trimEnd('0')
        .trimEnd('.')

    private const val Z_EPSILON = 0.01f
    private const val MAX_TARGET_ERROR_MM = 0.6f
}
