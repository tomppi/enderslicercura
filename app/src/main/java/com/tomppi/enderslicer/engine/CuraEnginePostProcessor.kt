package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.conical.ConicalRuntime
import com.tomppi.enderslicer.conical.ConicalStorage
import com.tomppi.enderslicer.nonplanar.CurviSlicerFieldStorage
import com.tomppi.enderslicer.nonplanar.CurviSlicerRuntime
import java.io.File

/** Finalizes staged engine output before it is eligible for immutable publication. */
internal object CuraEnginePostProcessor {
    private fun gcodeRequestsCurvi(file: File): Boolean = file.bufferedReader().useLines { lines ->
        lines.any { it.trim() == CurviSlicerRuntime.MACHINE_END_SENTINEL }
    }

    private fun gcodeRequestsConical(file: File): Boolean = file.bufferedReader().useLines { lines ->
        lines.any { it.trim() == ConicalRuntime.MACHINE_END_SENTINEL }
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
        printerEnvelope: PrinterEnvelope,
    ): Result {
        val workspace = outputFile.parentFile
            ?: error("CuraEngine output path has no parent workspace")
        val effectiveEnvelope = resolvedEnvelope(workspace) ?: printerEnvelope
        val firmware = CalibrationFirmwareEncoder.fromFlavor(effectiveEnvelope.gcodeFlavor)
        require(
            !(CurviSlicerFieldStorage.isPrepared(workspace) &&
                ConicalStorage.isPrepared(workspace)),
        ) {
            "CurviSlicer and conical slicing cannot both be prepared for a single slice"
        }
        val curviDiagnostics = CurviSlicerFieldStorage.curveStagedGcode(outputFile, effectiveEnvelope)
        if (curviDiagnostics == null && gcodeRequestsCurvi(outputFile)) {
            throw IllegalStateException(
                "CurviSlicer was requested for this slice but its field data is missing; refusing to " +
                    "publish a planar G-code for a warped model",
            )
        }
        val conicalDiagnostics = ConicalStorage.backtransformStagedGcode(outputFile, effectiveEnvelope)
        if (conicalDiagnostics == null && gcodeRequestsConical(outputFile)) {
            throw IllegalStateException(
                "Conical slicing was requested for this slice but its transform data is missing; refusing " +
                    "to publish a warped-model G-code without its back-transformation",
            )
        }
        val probePauseInjected = if (
            (curviDiagnostics != null && CurviSlicerRuntime.current().pauseAfterProbe) ||
            (conicalDiagnostics != null && ConicalRuntime.current().pauseAfterProbe)
        ) {
            GcodeProbePauseInjector.inject(outputFile)
        } else {
            false
        }
        val effectiveTransport = when {
            curviDiagnostics != null -> "$settingsTransport+curvislicer-android-v1"
            conicalDiagnostics != null -> "$settingsTransport+conical-android-v1"
            else -> settingsTransport
        }.let { if (probePauseInjected) "$it+probe-pause" else it }
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
            layerEvents.filter { it.layerNumber in validLayerNumbers },
        )

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
