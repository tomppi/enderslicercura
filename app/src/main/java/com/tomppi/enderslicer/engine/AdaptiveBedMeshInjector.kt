package com.tomppi.enderslicer.engine

import java.io.File
import java.util.Locale

/**
 * Adaptive mesh leveling (AML) for the mriscoc Ender 3 V2 / S1 Professional
 * Firmware. The firmware exposes AML through the custom C-code `C29 A` and
 * reads the first-layer print bounds from `; First layer print x min = ...`
 * comment lines, plus optional `; AML ...` parameters (mesh density per axis,
 * margin and prime).
 *
 * This injector is the free, built-in replacement for the paid AML slicer
 * scripts: after the engine slice is final, it scans the first printable
 * layer for the real extrusion footprint, clamps it to the build volume plus
 * the configured margin and emits the bound comments + `C29 A` right after
 * the leveling activation (`M420 S1` / `G29 A`, or after G28 when the start
 * script does not activate leveling itself).
 */
internal object AdaptiveBedMeshInjector {
    /** Idempotency marker written with every injection. */
    const val MARKER = ";ENDERSLICER_AML"

    /**
     * Injects the AML block into [file]. Returns true when the block was
     * written, false when the file already carries the marker, has no
     * printable first layer, or the region collapses after clamping.
     */
    fun inject(file: File, envelope: PrinterEnvelope, marginMm: Double): Boolean {
        require(file.isFile && file.length() > 0L) { "Sliced G-code is unavailable" }
        require(marginMm.isFinite() && marginMm in 0.0..1000.0) { "AML margin is invalid" }

        var sawMarker = false
        var activationIndex = -1
        var lastHomeIndex = -1
        var layerStartIndex = -1
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        var hasC29InStart = false
        var layerCount = 0
        var inFirstLayer = false

        // Pass 1: find the first-layer footprint and the anchoring line.
        var index = 0
        file.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val currentIndex = index
                index++
                val trimmed = line.trimStart()
                if (trimmed == MARKER) sawMarker = true

                val isLayerMarker =
                    trimmed.startsWith(";LAYER:") || trimmed.startsWith(";LAYER_CHANGE")
                if (isLayerMarker) {
                    layerCount++
                    if (layerCount == 1) {
                        layerStartIndex = currentIndex
                        inFirstLayer = true
                    } else if (inFirstLayer) {
                        inFirstLayer = false
                    }
                }

                if (inFirstLayer) {
                    val command = GcodeCommand.parse(line) ?: continue
                    if (command.opcode == "G0" || command.opcode == "G1") {
                        command.value('X')?.let { x ->
                            minX = minOf(minX, x)
                            maxX = maxOf(maxX, x)
                        }
                        command.value('Y')?.let { y ->
                            minY = minOf(minY, y)
                            maxY = maxOf(maxY, y)
                        }
                    }
                } else if (layerStartIndex < 0) {
                    // Only the start G-code region: track activation and homing.
                    val command = GcodeCommand.parse(line) ?: continue
                    if (command.opcode == "G28") lastHomeIndex = currentIndex
                    if (command.opcode == "M420" && command.value('S') == 1.0) {
                        activationIndex = currentIndex
                    } else if (command.opcode == "G29" && command.rawArguments.contains('A')) {
                        activationIndex = currentIndex
                    }
                    if (command.opcode == "C29") hasC29InStart = true
                }
            }
        }

        if (sawMarker) return false
        if (!minX.isFinite() || !maxX.isFinite() || !minY.isFinite() || !maxY.isFinite()) return false

        val bedMinX = if (envelope.originAtCenter) -envelope.widthMm / 2.0 else 0.0
        val bedMaxX = if (envelope.originAtCenter) envelope.widthMm / 2.0 else envelope.widthMm
        val bedMinY = if (envelope.originAtCenter) -envelope.depthMm / 2.0 else 0.0
        val bedMaxY = if (envelope.originAtCenter) envelope.depthMm / 2.0 else envelope.depthMm
        val regionMinX = maxOf(bedMinX, minX - marginMm)
        val regionMaxX = minOf(bedMaxX, maxX + marginMm)
        val regionMinY = maxOf(bedMinY, minY - marginMm)
        val regionMaxY = minOf(bedMaxY, maxY + marginMm)
        if (regionMaxX < regionMinX || regionMaxY < regionMinY) return false

        // anchorBeforeLayer: the block is written immediately before the first
        // layer marker when the start script has no home or activation line.
        val anchorBeforeLayer = activationIndex < 0 && lastHomeIndex < 0
        val anchorIndex = when {
            activationIndex >= 0 -> activationIndex
            lastHomeIndex >= 0 -> lastHomeIndex
            else -> layerStartIndex
        }
        if (anchorIndex < 0) return false

        // The firmware reads the AML bounds from the header comments at file
        // load (the official slicer sample places them on the very first
        // lines, before any command), so the comment block goes at the top of
        // the file while the C29 A command stays anchored at the activation.
        val headerComments = buildString {
            appendLine(MARKER)
            appendLine("; First layer print x min = ${format(regionMinX)}")
            appendLine("; First layer print y min = ${format(regionMinY)}")
            appendLine("; First layer print x max = ${format(regionMaxX)}")
            appendLine("; First layer print y max = ${format(regionMaxY)}")
            appendLine("; AML mesh density X = auto")
            appendLine("; AML mesh density Y = auto")
            appendLine("; AML margin = ${format(marginMm)}")
            appendLine("; AML prime = 1")
        }
        // When the start script already carries C29 A (the official AML start
        // script), only the header comments are missing - never duplicate the
        // command, or the firmware would probe the region twice.
        val commandBlock = buildString {
            if (activationIndex < 0) appendLine("M420 S1 ; activate leveling")
            appendLine("C29 A ; use AML")
        }
        val emitCommand = !hasC29InStart

        // Pass 2: stream the file; comments first, command after the anchor.
        val temporary = File(file.parentFile, "${file.name}.aml.tmp")
        temporary.delete()
        try {
            file.bufferedReader().use { reader ->
                temporary.bufferedWriter().use { writer ->
                    var current = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (current == 0) writer.write(headerComments)
                        if (emitCommand && current == anchorIndex && anchorBeforeLayer) {
                            writer.write(commandBlock)
                        }
                        writer.write(line)
                        writer.newLine()
                        if (emitCommand && current == anchorIndex && !anchorBeforeLayer) writer.write(commandBlock)
                        current++
                    }
                }
            }
            try {
                java.nio.file.Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.io.IOException) {
                check(temporary.renameTo(file) || temporary.copyTo(file, overwrite = true).let { temporary.delete(); true }) {
                    "Unable to publish the AML G-code"
                }
            }
        } finally {
            temporary.delete()
        }
        return true
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)
}
