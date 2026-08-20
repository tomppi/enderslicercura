package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.conical.ConicalRuntime
import com.tomppi.enderslicer.conical.ConicalStorage
import com.tomppi.enderslicer.nonplanar.ConformalSurfaceStorage
import com.tomppi.enderslicer.nonplanar.NonPlanarRuntime
import com.tomppi.enderslicer.nonplanar.NozzleCollisionAlert
import com.tomppi.enderslicer.nonplanar.NozzleCollisionScanner
import java.io.File

/** Finalizes staged engine output before it is eligible for immutable publication. */
internal object CuraEnginePostProcessor {
    private fun gcodeRequestsNonPlanar(file: File): Boolean = file.bufferedReader().useLines { lines ->
        lines.any { it.trim() == NonPlanarRuntime.MACHINE_END_SENTINEL }
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
        val nozzleCollisionAlert: NozzleCollisionAlert? = null,
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
            !(ConformalSurfaceStorage.isPrepared(workspace) &&
                ConicalStorage.isPrepared(workspace)),
        ) {
            "Non-planar and conical slicing cannot both be prepared for a single slice"
        }
        val conformalDiagnostics = ConformalSurfaceStorage.conformalStagedGcode(outputFile, effectiveEnvelope)
        if (conformalDiagnostics == null && gcodeRequestsNonPlanar(outputFile)) {
            throw IllegalStateException(
                "Non-planar printing was requested for this slice but its surface data is missing; " +
                    "refusing to publish a planar G-code for a non-planar request",
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
            (conformalDiagnostics != null && NonPlanarRuntime.current().pauseAfterProbe) ||
            (conicalDiagnostics != null && ConicalRuntime.current().pauseAfterProbe)
        ) {
            GcodeProbePauseInjector.inject(outputFile)
        } else {
            false
        }
        val effectiveTransport = when {
            conformalDiagnostics != null -> "$settingsTransport+conformal-surface-android-v1"
            conicalDiagnostics != null -> "$settingsTransport+conical-android-v1"
            else -> settingsTransport
        }.let { if (probePauseInjected) "$it+probe-pause" else it }
        // Sweep the user-measured collision volume (nozzle cone + heating
        // block cone + whole-plate cutoff) along the curved toolpath so the
        // slice result can warn before a nozzle scrape happens on the printer.
        val nozzleCollisionAlert = if (conformalDiagnostics != null) {
            runCatching {
                NozzleCollisionScanner.scan(
                    gcode = outputFile,
                    settings = NonPlanarRuntime.current(),
                    buildPlateHalfWidthMm = effectiveEnvelope.widthMm / 2.0,
                    buildPlateHalfDepthMm = effectiveEnvelope.depthMm / 2.0,
                )
            }.getOrNull()
        } else {
            null
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
            layerEvents.filter { it.layerNumber in validLayerNumbers },
        )

        if (resolvedEvents.isEmpty()) {
            return Result(
                summary = baseSummary,
                layerPreview = basePreview,
                previewFailure = null,
                layerEvents = emptyList(),
                usedZeroEventFastPath = true,
                nozzleCollisionAlert = nozzleCollisionAlert,
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
            nozzleCollisionAlert = nozzleCollisionAlert,
        )
    }

    private fun resolvedEnvelope(directory: File?): PrinterEnvelope? {
        val file = directory?.let { File(it, PrinterEnvelope.METADATA_FILE_NAME) } ?: return null
        return file.takeIf(File::isFile)?.let(PrinterEnvelope::readFrom)
    }
}
