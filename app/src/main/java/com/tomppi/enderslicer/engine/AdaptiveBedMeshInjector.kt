package com.tomppi.enderslicer.engine

import java.io.File

/**
 * Adaptive mesh leveling (AML) for the mriscoc Ender 3 V2 / S1 Professional
 * Firmware (AML2.0 builds, 2026 and later).
 *
 * On these builds AML is not a comment protocol - there is no `; First layer
 * print ...` or `; AML ...` parsing. The firmware exposes the mesh region
 * through the custom C-code:
 *
 *   C29 L<left> R<right> F<front> B<back> N<grid points>  (mesh inset + grid)
 *   G29 P1                                                (probe only that area)
 *
 * `C29` stores the probe region in the runtime mesh settings (`meshSet`),
 * recomputes the grid spacing, invalidates the mesh and disables leveling.
 * With PROUI_EX the UBL grid positions are computed at runtime from these
 * values, so a following stock `G29 P1` probes just the model footprint.
 * `C29 A`, `C29 P1` and the bare command do nothing but print the settings.
 *
 * This injector is the free, built-in equivalent of the paid AML slicer
 * scripts: after the engine slice is final, it scans the first printable
 * layer for the real extrusion footprint, clamps it to the build volume plus
 * the configured margin and emits `C29 L.. R.. F.. B.. N9` right before the
 * bed probe (`G29 P1` when the start G-code already probes, otherwise right
 * at the leveling activation / last home).
 */
internal object AdaptiveBedMeshInjector {
    /** Idempotency marker written with every injection. */
    const val MARKER = ";ENDERSLICER_AML"

    /** Grid density used for the adaptive mesh (built-in 9x9, like the firmware default). */
    const val GRID_POINTS = 9

    /** Maximum sensible region edge in millimetres; larger values are rejected. */
    private const val MAX_REGION_MM = 10_000.0

    /**
     * Injects the AML block into [file]. Returns true when the block was
     * written, false when the file already carries the marker, has no
     * printable first layer, or the region collapses after clamping.
     */
    fun inject(file: File, envelope: PrinterEnvelope, marginMm: Double): Boolean {
        require(file.isFile && file.length() > 0L) { "Sliced G-code is unavailable" }
        require(marginMm.isFinite() && marginMm in 0.0..1000.0) { "AML margin is invalid" }

        var sawMarker = false
        var probeIndex = -1
        var activationIndex = -1
        var lastHomeIndex = -1
        var layerStartIndex = -1
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
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
                    // Only the start G-code region: track the probe/activation anchors.
                    val command = GcodeCommand.parse(line) ?: continue
                    if (command.opcode == "G28") lastHomeIndex = currentIndex
                    if (command.opcode == "G29" && isProbePhase1(command)) probeIndex = currentIndex
                    if (command.opcode == "M420" && command.value('S') == 1.0) {
                        activationIndex = currentIndex
                    } else if (command.opcode == "G29" && command.rawArguments.replace(" ", "").contains('A')) {
                        activationIndex = currentIndex
                    }
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
        if (regionMaxX - regionMinX > MAX_REGION_MM || regionMaxY - regionMinY > MAX_REGION_MM) return false

        // The firmware stores the mesh inset in millimetres as integers, so the
        // injected region is rounded to whole millimetres.
        val left = Math.round(regionMinX).toInt()
        val right = Math.round(regionMaxX).toInt()
        val front = Math.round(regionMinY).toInt()
        val back = Math.round(regionMaxY).toInt()
        if (right <= left || back <= front) return false

        // The command block. One authoritative C29 sets the region:
        //  - already probing?           -> C29 right before the existing G29 P1
        //  - activation present?        -> C29 + G29 P1 right before the activation
        //  - otherwise                  -> C29 + G29 P1 + M420 S1 after the last G28
        val commandBlock = buildString {
            appendLine(MARKER)
            appendLine("C29 L$left R$right F$front B$back N$GRID_POINTS ; AML mesh area")
            if (probeIndex < 0) {
                appendLine("G29 P1 ; probe only the model area")
                if (activationIndex < 0) appendLine("M420 S1 ; activate leveling")
            }
        }
        val anchorIndex = when {
            probeIndex >= 0 -> probeIndex
            activationIndex >= 0 -> activationIndex
            lastHomeIndex >= 0 -> lastHomeIndex + 1
            else -> layerStartIndex
        }
        if (anchorIndex < 0) return false

        // Pass 2: stream the file, inserting the block at the anchor and
        // dropping any C29 command lines from the start region - the injector
        // owns the AML mesh area and only one C29 may win.
        val temporary = File(file.parentFile, "${file.name}.aml.tmp")
        temporary.delete()
        try {
            file.bufferedReader().use { reader ->
                temporary.bufferedWriter().use { writer ->
                    var current = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (current < layerStartIndex) {
                            val command = GcodeCommand.parse(line)
                            if (command?.opcode == "C29") {
                                current++
                                continue
                            }
                        }
                        if (current == anchorIndex) writer.write(commandBlock)
                        writer.write(line)
                        writer.newLine()
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

    /** True for `G29 P1` in any compact/unspaced spelling ("G29P1", "G29 P1 C"). */
    private fun isProbePhase1(command: GcodeCommand.Parsed): Boolean {
        if (command.opcode != "G29") return false
        val compact = command.rawArguments.replace(" ", "")
        return compact.startsWith("P1")
    }
}
