package com.tomppi.enderslicer.engine

import java.io.File

/**
 * Finalizes CuraEngine output while preserving the original event-processing
 * pipeline whenever user or calibration layer events are requested.
 */
internal object CuraEnginePostProcessor {
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
    ): Result {
        val baseSummary = GcodeSanitizer.validateAndRepair(
            outputFile,
            settingsTransport = settingsTransport,
        )
        outputFile.copyTo(baseGcodeFile, overwrite = true)
        check(baseGcodeFile.isFile && baseGcodeFile.length() > 0L) {
            "Unable to retain original sliced G-code"
        }

        if (layerEvents.isEmpty() && plannedLayerEvents.isEmpty()) {
            val previewResult = runCatching { GcodeLayerPreviewParser.parse(outputFile) }
            return Result(
                summary = baseSummary,
                layerPreview = previewResult.getOrNull(),
                previewFailure = previewResult.exceptionOrNull(),
                layerEvents = emptyList(),
                usedZeroEventFastPath = true,
            )
        }

        val basePreview = GcodeLayerPreviewParser.parse(baseGcodeFile)
        val validLayerNumbers = basePreview.layers.mapTo(hashSetOf()) { it.number }
        val resolvedEvents = (
            layerEvents.filter { it.layerNumber in validLayerNumbers } +
                GcodeLayerEventProcessor.resolve(plannedLayerEvents, basePreview)
            )
            .distinctBy(LayerEvent::id)
            .sortedWith(compareBy(LayerEvent::layerNumber, LayerEvent::source, LayerEvent::id))

        GcodeLayerEventProcessor.materialize(baseGcodeFile, outputFile, resolvedEvents)
        val summary = GcodeSanitizer.validateAndRepair(
            outputFile,
            settingsTransport = "$settingsTransport+layer-events",
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
}
