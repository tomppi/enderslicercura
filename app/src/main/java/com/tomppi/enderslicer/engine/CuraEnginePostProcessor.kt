package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.calibration.CalibrationPlacementPolicy
import com.tomppi.enderslicer.nonplanar.CurviSlicerFieldStorage
import com.tomppi.enderslicer.nonplanar.CurviSlicerRuntime
import java.io.File

/** Finalizes staged engine output before it is eligible for immutable publication. */
internal object CuraEnginePostProcessor {
    private fun gcodeRequestsCurvi(file: File): Boolean = file.bufferedReader().useLines { lines ->
        lines.any { it.trim() == CurviSlicerRuntime.MACHINE_END_SENTINEL }
    }

    data class Result(
        val summary: GcodeSanitizer.Summary,
        val layerPreview: GcodeLayerPreview?,
        val previewFailure: Throwable?,
        val layerEvents: List<LayerEvent>,
        val usedZeroEventFastPath: Boolean,
    )

    fun process(
        outputFile: File,
        baseGcodeFile: File,
        settingsTransport: String,
        layerEvents: List<LayerEvent>,
        plannedLayerEvents: List<PlannedLayerEvent>,
        printerEnvelope: PrinterEnvelope,
    ): Result {
        val effectiveEnvelope = resolvedEnvelope(outputFile.parentFile) ?: printerEnvelope
        val firmware = CalibrationFirmwareEncoder.fromFlavor(effectiveEnvelope.gcodeFlavor)
        require(plannedLayerEvents.isEmpty() || !CurviSlicerFieldStorage.isPrepared(outputFile.parentFile)) {
            "CurviSlicer cannot be combined with height-based calibration events"
        }
        val curviDiagnostics = CurviSlicerFieldStorage.curveStagedGcode(outputFile, effectiveEnvelope)
        if (curviDiagnostics == null && gcodeRequestsCurvi(outputFile)) {
            throw IllegalStateException(
                "CurviSlicer was requested for this slice but its field data is missing; refusing to " +
                    "publish a planar G-code for a warped model",
            )
        }
        val effectiveTransport = if (curviDiagnostics == null) {
            settingsTransport
        } else {
            "$settingsTransport+curvislicer-android-v1"
        }
        val baseSummary = GcodeSanitizer.validateAndRepair(
            file = outputFile,
            settingsTransport = effectiveTransport,
            printerEnvelope = effectiveEnvelope,
        )
        outputFile.copyTo(baseGcodeFile, overwrite = true)
        check(baseGcodeFile.isFile && baseGcodeFile.length() > 0L) {
            "Unable to retain original sliced G-code"
        }

        val basePreview = GcodeLayerPreviewParser.parse(baseGcodeFile)
        val validLayerNumbers = basePreview.layers.mapTo(hashSetOf()) { it.number }
        val resolvedEvents = LayerEventOrdering.normalize(
            layerEvents.filter { it.layerNumber in validLayerNumbers } +
                GcodeLayerEventProcessor.resolve(plannedLayerEvents, basePreview),
        )

        if (plannedLayerEvents.isNotEmpty()) {
            CalibrationPlacementPolicy.requireNoRaft(baseGcodeFile)
            firmware.requireDistinctCalibrationSequence(
                type = plannedLayerEvents.first().type,
                values = plannedLayerEvents.map { event ->
                    requireNotNull(event.value) { "Calibration event ${event.label} has no value" }
                },
                secondaryValue = plannedLayerEvents.first().secondaryValue,
            )
        }

        if (resolvedEvents.isEmpty()) {
            return Result(
                summary = baseSummary,
                layerPreview = basePreview,
                previewFailure = null,
                layerEvents = emptyList(),
                usedZeroEventFastPath = true,
            )
        }

        GcodeLayerEventProcessor.materialize(baseGcodeFile, outputFile, resolvedEvents, firmware)
        val summary = GcodeSanitizer.validateAndRepair(
            file = outputFile,
            settingsTransport = "$effectiveTransport+layer-events",
            printerEnvelope = effectiveEnvelope,
        )
        val previewResult = runCatching { GcodeLayerPreviewParser.parse(outputFile) }
        return Result(
            summary = summary,
            layerPreview = previewResult.getOrNull(),
            previewFailure = previewResult.exceptionOrNull(),
            layerEvents = resolvedEvents,
            usedZeroEventFastPath = false,
        )
    }

    private fun resolvedEnvelope(directory: File?): PrinterEnvelope? {
        val file = directory?.let { File(it, PrinterEnvelope.METADATA_FILE_NAME) } ?: return null
        return file.takeIf(File::isFile)?.let(PrinterEnvelope::readFrom)
    }
}
