package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.engine.GcodeCommand
import com.tomppi.enderslicer.engine.GcodeCommandPolicy
import com.tomppi.enderslicer.engine.GcodeModalState
import com.tomppi.enderslicer.engine.MAX_EMITTED_MOVES
import com.tomppi.enderslicer.engine.PrinterEnvelope
import com.tomppi.enderslicer.engine.checkCancellation
import com.tomppi.enderslicer.engine.formatGcode
import com.tomppi.enderslicer.engine.publishAtomic
import com.tomppi.enderslicer.engine.quantizeGcode
import java.io.File
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Projects the planar slice onto the conformal surface regions (Ahlers'
 * method at the G-code level):
 *
 * 1. Every extrusion move whose planar height lies within [shellLayers]
 *    layer heights of the surface is removed from its planar layer (the
 *    stair step) and reassigned to the conformal shell
 *    k = floor((surfaceZ - planarZ) / layerHeight). Shell k rides
 *    surfaceZ - k * layerHeight, so the nozzle follows the true surface
 *    from the thinnest part (diving below the layer plane) to the thickest.
 * 2. Shell moves are printed at the end of the shell's "home layer" - the
 *    topmost planar layer at or below surfaceMax - k * layerHeight - with
 *    retraction, rise, travel and descent around each disconnected piece.
 * 3. Deeper planar material (k >= shellLayers) stays planar as interior
 *    support, and the first layer is always kept planar for bed adhesion.
 */
internal object ConformalGcodeTransformer {
    private const val EPSILON = 1e-8
    private const val MAX_SPLIT_DEPTH = 24
    private const val MIN_SEGMENT_MM = 0.05
    private const val CONNECT_TOLERANCE_MM = 0.7
    // A direct (travel-free) connection between shell pieces is only emitted
    // when the jump stays shallow enough to be a plausible surface move.
    private const val CONNECT_SLOPE_LIMIT = 0.6
    private const val TRAVEL_CLEARANCE_MM = 0.3
    private const val NEIGHBOR_CELL_MM = 3.0

    data class Diagnostics(
        val regionCount: Int,
        val sourceExtrusionMoves: Int,
        val stairMovesRemoved: Int,
        val skinMovesEmitted: Int,
        val emittedMoves: Int,
        val minimumZmm: Double,
        val maximumZmm: Double,
        val maximumDiveMm: Double,
        val maximumObservedSlopeDegrees: Double,
        val maximumObservedZSpeedMmPerSecond: Double,
    )

    private class LayerState(
        val number: Int,
        val baseZ: Double,
        var lastX: Double,
        var lastY: Double,
        var lastZ: Double,
        val skins: ArrayList<Piece> = ArrayList(),
    )

    private class Piece(
        val regionIndex: Int,
        val shell: Int,
        val x1: Double,
        val y1: Double,
        val z1: Double,
        val x2: Double,
        val y2: Double,
        val z2: Double,
        val deltaE: Double,
        val feed: Double,
        val order: Int,
    )

    private class Classification(
        val regionIndex: Int,
        val shell: Int,
        val surfaceZ: Double,
    )

    private class Segment(
        val x1: Double,
        val y1: Double,
        val x2: Double,
        val y2: Double,
        val deltaE: Double,
        val depth: Int,
    )

    fun transform(
        file: File,
        surface: ConformalSurface,
        layerHeightMm: Double,
        maximumZSpeedMmPerSecond: Double,
        conformalShellLayers: Int,
        printerEnvelope: PrinterEnvelope,
    ): Diagnostics {
        require(file.isFile && file.length() > 0L) { "Conformal G-code is missing" }
        require(surface.regions.isNotEmpty()) { "Conformal surface regions are missing" }
        require(layerHeightMm.isFinite() && layerHeightMm in 0.04..1.2) { "Invalid conformal layer height" }
        val shellLayers = conformalShellLayers.coerceIn(1, 8)
        val temporary = File(file.parentFile, file.name + ".conformal.tmp")
        temporary.delete()

        // ---- walk 1: parse, classify and collect shell pieces ----
        val modal = GcodeModalState()
        var planarX = 0.0
        var planarY = 0.0
        var planarZ = 0.0
        var planarE = 0.0
        var logicalFeed = 0.0
        var inPrintableLayers = false
        var afterMachineEnd = false
        var currentLayer: Int? = null
        var firstLayerNumber: Int? = null
        var lineNumber = 0
        var sourceMoves = 0
        var pieceOrder = 0
        var fileUsesFirmwareRetract = false
        val layerStates = LinkedHashMap<Int, LayerState>()

        fun layerState(number: Int, z: Double): LayerState = layerStates.getOrPut(number) {
            LayerState(number, z, planarX, planarY, z)
        }

        fun surfaceAt(x: Double, y: Double): Pair<Int, Double>? {
            var best: Pair<Int, Double>? = null
            for (index in surface.regions.indices) {
                val z = surface.regions[index].surfaceZ(x, y) ?: continue
                if (best == null || z > best.second) best = index to z
            }
            return best
        }

        fun classify(x: Double, y: Double, planarHeight: Double): Classification? {
            val hit = surfaceAt(x, y) ?: return null
            val layer = currentLayer ?: return null
            if (firstLayerNumber != null && layer == firstLayerNumber) return null
            val band = floor((hit.second - planarHeight) / layerHeightMm + 1e-9).toInt()
            if (band < 0 || band >= shellLayers) return null
            return Classification(hit.first, band, hit.second)
        }

        fun collectPieces(
            startX: Double,
            startY: Double,
            endX: Double,
            endY: Double,
            planarHeight: Double,
            deltaE: Double,
            feed: Double,
            state: LayerState,
        ) {
            val queue = ArrayDeque<Segment>()
            queue.addLast(Segment(startX, startY, endX, endY, deltaE, 0))
            while (queue.isNotEmpty()) {
                val segment = queue.removeFirst()
                val c1 = classify(segment.x1, segment.y1, planarHeight)
                val c2 = classify(segment.x2, segment.y2, planarHeight)
                val same = c1?.regionIndex == c2?.regionIndex && c1?.shell == c2?.shell
                if (same) {
                    val classification = c1 ?: c2
                    if (classification != null) {
                        val z1 = (surfaceAt(segment.x1, segment.y1)?.second ?: classification.surfaceZ) -
                            classification.shell * layerHeightMm
                        val z2 = (surfaceAt(segment.x2, segment.y2)?.second ?: classification.surfaceZ) -
                            classification.shell * layerHeightMm
                        state.skins += Piece(
                            regionIndex = classification.regionIndex,
                            shell = classification.shell,
                            x1 = segment.x1, y1 = segment.y1, z1 = z1,
                            x2 = segment.x2, y2 = segment.y2, z2 = z2,
                            deltaE = segment.deltaE,
                            feed = feed,
                            order = pieceOrder++,
                        )
                    }
                    continue
                }
                // A boundary point classifies as inside (on-edge), so pure
                // bisection would never settle: once the piece is shorter than
                // the machine's resolution, classify its midpoint and stop.
                val length = hypot(segment.x2 - segment.x1, segment.y2 - segment.y1)
                if (segment.depth >= MAX_SPLIT_DEPTH || length < MIN_SEGMENT_MM) {
                    val midX = (segment.x1 + segment.x2) * 0.5
                    val midY = (segment.y1 + segment.y2) * 0.5
                    val classification = classify(midX, midY, planarHeight) ?: continue
                    // Keep the boundary sliver flat at the surface height:
                    // the segment is shorter than the machine's resolution,
                    // and one endpoint may already sit outside the region.
                    val z = classification.surfaceZ - classification.shell * layerHeightMm
                    state.skins += Piece(
                        regionIndex = classification.regionIndex,
                        shell = classification.shell,
                        x1 = segment.x1, y1 = segment.y1, z1 = z,
                        x2 = segment.x2, y2 = segment.y2, z2 = z,
                        deltaE = segment.deltaE,
                        feed = feed,
                        order = pieceOrder++,
                    )
                    continue
                }
                val midX = (segment.x1 + segment.x2) * 0.5
                val midY = (segment.y1 + segment.y2) * 0.5
                queue.addLast(Segment(segment.x1, segment.y1, midX, midY, segment.deltaE * 0.5, segment.depth + 1))
                queue.addLast(Segment(midX, midY, segment.x2, segment.y2, segment.deltaE * 0.5, segment.depth + 1))
            }
        }

        try {
            file.bufferedReader().useLines { lines ->
                for (rawLine in lines) {
                    lineNumber++
                    checkCancellation(lineNumber, "Conformal processing")
                    val trimmed = rawLine.trimStart()
                    if (afterMachineEnd) continue
                    if (trimmed == NonPlanarRuntime.MACHINE_END_SENTINEL) {
                        afterMachineEnd = true
                        inPrintableLayers = false
                        continue
                    }
                    if (trimmed.startsWith("G10") || trimmed.startsWith("G11")) {
                        fileUsesFirmwareRetract = true
                        continue
                    }
                    if (trimmed.startsWith(";LAYER:")) {
                        val number = trimmed.substringAfter(':').trim().toIntOrNull()
                        if (number != null) {
                            currentLayer = number
                            if (firstLayerNumber == null) firstLayerNumber = number
                            inPrintableLayers = true
                        }
                        continue
                    }
                    if (trimmed.startsWith(";End of Gcode", ignoreCase = true) || trimmed.startsWith(";END_OF_PRINT")) {
                        inPrintableLayers = false
                        continue
                    }
                    val command = GcodeCommand.parse(rawLine) ?: continue
                    if (modal.apply(command)) continue
                    when (command.opcode) {
                        "G92" -> {
                            command.value('X')?.let { planarX = it }
                            command.value('Y')?.let { planarY = it }
                            command.value('Z')?.let { planarZ = it }
                            command.value('E')?.let { planarE = it }
                        }
                        "G0", "G1" -> {
                            val nextX = modal.position(planarX, command.value('X'))
                            val nextY = modal.position(planarY, command.value('Y'))
                            val nextZ = modal.position(planarZ, command.value('Z'))
                            val nextE = modal.extrusion(planarE, command.value('E'))
                            command.value('F')?.let { logicalFeed = it }
                            val deltaE = nextE - planarE
                            val spatial = abs(nextX - planarX) > EPSILON ||
                                abs(nextY - planarY) > EPSILON || abs(nextZ - planarZ) > EPSILON
                            if (spatial && inPrintableLayers && currentLayer != null) {
                                val state = layerState(currentLayer!!, nextZ)
                                state.lastX = nextX
                                state.lastY = nextY
                                state.lastZ = nextZ
                            }
                            if (!spatial || !inPrintableLayers || !command.has('E') || abs(deltaE) <= EPSILON) {
                                planarX = nextX
                                planarY = nextY
                                planarZ = nextZ
                                planarE = nextE
                                continue
                            }
                            sourceMoves++
                            val state = layerStates[currentLayer] ?: layerState(currentLayer!!, nextZ)
                            collectPieces(
                                startX = planarX,
                                startY = planarY,
                                endX = nextX,
                                endY = nextY,
                                // The extrusion lands at the move's own height:
                                // the layer-transition move carries the new layer Z.
                                planarHeight = nextZ,
                                deltaE = deltaE,
                                feed = logicalFeed,
                                state = state,
                            )
                            planarX = nextX
                            planarY = nextY
                            planarZ = nextZ
                            planarE = nextE
                        }
                        else -> Unit
                    }
                }
            }
        } catch (closed: java.nio.channels.ClosedByInterruptException) {
            throw InterruptedException("Conformal processing was cancelled")
        }

        val totalSkins = layerStates.values.sumOf { it.skins.size }
        require(totalSkins > 0) {
            "Conformal surface mode found no toolpath on the printable surface regions; " +
                "raise the maximum path slope or the maximum lift so a surface region matches the model"
        }

        // Assign each shell to its home layer: the topmost layer at or below
        // surfaceMax - shell * layerHeight, then move every piece there.
        val homeByRegionAndShell = Array(surface.regions.size) { regionIndex ->
            val maxZ = surface.regions[regionIndex].maxZ
            IntArray(shellLayers) { shell ->
                val target = maxZ - shell * layerHeightMm
                val candidates = layerStates.values.filter { it.baseZ <= target + EPSILON }
                candidates.maxByOrNull { it.baseZ }?.number ?: layerStates.values.minOf { it.number }
            }
        }
        for (state in layerStates.values) {
            val pieces = state.skins.toList()
            state.skins.clear()
            for (piece in pieces) {
                val homeNumber = homeByRegionAndShell[piece.regionIndex][piece.shell]
                layerStates[homeNumber]?.skins?.add(piece) ?: state.skins.add(piece)
            }
        }

        // ---- walk 2: emit the rebuilt stream ----
        var emittedX = 0.0
        var emittedY = 0.0
        var emittedZ = 0.0
        // The source stream's own E coordinate (advances on every original E
        // move, removed or not) drives per-move deltas; runningE is what the
        // emitted stream actually extruded.
        var sourceE = 0.0
        var runningE = 0.0
        var emittedFeed = Double.NaN
        var emittedMoves = 0
        var stairMovesRemoved = 0
        var skinMovesEmitted = 0
        var minimumZ = Double.POSITIVE_INFINITY
        var maximumZ = Double.NEGATIVE_INFINITY
        var maximumSlope = 0.0
        var maximumZSpeed = 0.0
        var maximumDive = 0.0
        var metadataWritten = false
        val emissionModal = GcodeModalState()
        var emissionLayer: Int? = null
        var emissionAfterMachineEnd = false
        var emissionInPrintable = false

        fun writeMetadata(output: Appendable) {
            if (metadataWritten) return
            metadataWritten = true
            output.appendLine(";ENDERSLICER_NON_PLANAR:ConformalSurface-Android-v1")
            output.appendLine(";ENDERSLICER_CONFORMAL_REGIONS:" + surface.regions.size)
            output.appendLine(";ENDERSLICER_CONFORMAL_SHELLS:" + shellLayers)
        }

        fun emitCommand(output: Appendable, line: String) {
            GcodeCommand.parse(line)?.let { emissionModal.apply(it) }
            output.appendLine(line)
        }

        fun emitMove(
            output: Appendable,
            targetX: Double,
            targetY: Double,
            targetZ: Double,
            deltaE: Double?,
            feed: Double?,
            opcode: String = "G1",
        ) {
            val dx = targetX - emittedX
            val dy = targetY - emittedY
            val dz = targetZ - emittedZ
            if (deltaE == null && abs(dx) < 1e-6 && abs(dy) < 1e-6 && abs(dz) < 1e-6) return
            check(emittedMoves + 1 <= MAX_EMITTED_MOVES) { "Conformal output exceeded " + MAX_EMITTED_MOVES + " moves" }
            val builder = StringBuilder(opcode)
            if (emissionModal.absolutePosition) {
                builder.append(" X").append(format(quantize(targetX)))
                builder.append(" Y").append(format(quantize(targetY)))
                builder.append(" Z").append(format(quantize(targetZ)))
            } else {
                builder.append(" X").append(format(quantize(dx)))
                builder.append(" Y").append(format(quantize(dy)))
                builder.append(" Z").append(format(quantize(dz)))
            }
            if (deltaE != null) {
                runningE += deltaE
                builder.append(" E").append(
                    format(quantize(if (emissionModal.absoluteExtrusion) runningE else deltaE)),
                )
            }
            if (feed != null && feed > 0.0 && (!emittedFeed.isFinite() || abs(feed - emittedFeed) > 0.01)) {
                builder.append(" F").append(format(feed))
                emittedFeed = feed
            }
            emitCommand(output, builder.toString())
            emittedX = targetX
            emittedY = targetY
            emittedZ = targetZ
            emittedMoves++
            minimumZ = minOf(minimumZ, targetZ)
            maximumZ = maxOf(maximumZ, targetZ)
        }

        fun emitSkinPiece(output: Appendable, piece: Piece, state: LayerState) {
            val horizontal = hypot(piece.x2 - piece.x1, piece.y2 - piece.y1)
            val dz = abs(piece.z2 - piece.z1)
            val slope = if (horizontal > EPSILON) dz / horizontal else 0.0
            val slopeDegrees = Math.toDegrees(kotlin.math.atan(slope))
            if (slopeDegrees > maximumSlope) maximumSlope = slopeDegrees
            val requestedSpeed = (piece.feed / 60.0).coerceAtLeast(0.0)
            val length = sqrt(
                (piece.x2 - piece.x1) * (piece.x2 - piece.x1) +
                    (piece.y2 - piece.y1) * (piece.y2 - piece.y1) + dz * dz,
            )
            val zSpeed = if (length > EPSILON) requestedSpeed * dz / length else 0.0
            val safeSpeed = if (zSpeed > maximumZSpeedMmPerSecond && zSpeed > EPSILON) {
                requestedSpeed * maximumZSpeedMmPerSecond / zSpeed
            } else {
                requestedSpeed
            }
            maximumZSpeed = max(maximumZSpeed, min(zSpeed, maximumZSpeedMmPerSecond))
            val safeFeed = safeSpeed * 60.0
            printerEnvelope.requireMotionMove(
                startX = piece.x1,
                startY = piece.y1,
                startZ = piece.z1,
                endX = piece.x2,
                endY = piece.y2,
                endZ = piece.z2,
                lineNumber = piece.order,
                layerNumber = state.number,
            )
            emitMove(output, piece.x2, piece.y2, piece.z2, piece.deltaE, safeFeed, "G1")
            skinMovesEmitted++
            maximumDive = max(maximumDive, max(0.0, state.baseZ - min(piece.z1, piece.z2)))
        }

        fun flushSkins(output: Appendable, layerNumber: Int) {
            val state = layerStates[layerNumber] ?: return
            if (state.skins.isEmpty()) return
            // The pieces of one shell were collected layer by layer, so their
            // source order fragments the surface into thousands of unrelated
            // slivers. Chain them nearest-neighbor (grid accelerated) so each
            // shell becomes a continuous surface pass with only the occasional
            // retract/rise/travel/descend between runs.
            val byShell = state.skins.groupBy { it.shell }.toSortedMap()
            var maxPieceZ = state.baseZ
            for ((shell, pieces) in byShell) {
                checkCancellation(shell, "Conformal processing")
                var previous: Piece? = null
                for (piece in chainNearestNeighbor(pieces, state.lastX, state.lastY)) {
                    checkCancellation(piece.order, "Conformal processing")
                    maxPieceZ = max(maxPieceZ, max(piece.z1, piece.z2))
                    val horizontal = previous?.let { hypot(piece.x1 - it.x2, piece.y1 - it.y2) }
                        ?: Double.POSITIVE_INFINITY
                    val vertical = previous?.let { abs(piece.z1 - it.z2) } ?: 0.0
                    val direct = previous != null && horizontal <= CONNECT_TOLERANCE_MM &&
                        (horizontal < 1e-4 || vertical / horizontal < CONNECT_SLOPE_LIMIT)
                    if (!direct) {
                        // Rise ABOVE everything printed by this flush, travel
                        // at that safe height, then descend vertically onto
                        // the surface at the next piece's start.
                        val travelZ = max(state.baseZ, maxPieceZ) + TRAVEL_CLEARANCE_MM
                        if (fileUsesFirmwareRetract) emitCommand(output, "G10")
                        emitMove(output, emittedX, emittedY, travelZ, null, null, "G0")
                        emitMove(output, piece.x1, piece.y1, travelZ, null, null, "G0")
                        emitMove(output, piece.x1, piece.y1, piece.z1, null, null, "G0")
                        if (fileUsesFirmwareRetract) emitCommand(output, "G11")
                    }
                    emitSkinPiece(output, piece, state)
                    previous = piece
                }
            }
            state.skins.clear()
            // Rise above the flush, return to the layer's last planar
            // position, then drop back to the layer plane so the original
            // layer-change sequence continues from a known state.
            val travelZ = max(state.baseZ, maxPieceZ) + TRAVEL_CLEARANCE_MM
            emitMove(output, emittedX, emittedY, travelZ, null, null, "G0")
            emitMove(output, state.lastX, state.lastY, travelZ, null, null, "G0")
            emitMove(output, state.lastX, state.lastY, state.baseZ, null, null, "G0")
        }

        try {
            file.bufferedReader().use { input ->
                temporary.bufferedWriter().use { output ->
                    fun processLine(rawLine: String) {
                        val trimmed = rawLine.trimStart()
                        if (emissionAfterMachineEnd) {
                            require(trimmed != NonPlanarRuntime.MACHINE_END_SENTINEL) {
                                "Conformal machine-end sentinel appears more than once"
                            }
                            output.appendLine(rawLine)
                            return
                        }
                        if (trimmed == NonPlanarRuntime.MACHINE_END_SENTINEL) {
                            emissionLayer?.let { flushSkins(output, it) }
                            emissionAfterMachineEnd = true
                            emissionInPrintable = false
                            output.appendLine(rawLine)
                            return
                        }
                        if (!metadataWritten && (trimmed.startsWith(";Generated with Cura") || trimmed.startsWith(";FLAVOR:"))) {
                            output.appendLine(rawLine)
                            writeMetadata(output)
                            return
                        }
                        if (trimmed.startsWith(";LAYER:")) {
                            emissionLayer?.let { flushSkins(output, it) }
                            val number = trimmed.substringAfter(':').trim().toIntOrNull()
                            if (number != null) emissionLayer = number
                            emissionInPrintable = true
                        }
                        if (trimmed.startsWith(";End of Gcode", ignoreCase = true) || trimmed.startsWith(";END_OF_PRINT")) {
                            emissionLayer?.let { flushSkins(output, it) }
                            emissionInPrintable = false
                        }
                        val command = GcodeCommand.parse(rawLine)
                        if (command == null) {
                            output.appendLine(rawLine)
                            return
                        }
                        GcodeCommandPolicy.requireNonPlanarSupported(command, emissionInPrintable)
                        if (emissionModal.apply(command)) {
                            output.appendLine(rawLine)
                            return
                        }
                        when (command.opcode) {
                            "G92" -> {
                                command.value('X')?.let { emittedX = it }
                                command.value('Y')?.let { emittedY = it }
                                command.value('Z')?.let { emittedZ = it }
                                command.value('E')?.let {
                                    sourceE = it
                                    runningE = it
                                }
                                output.appendLine(rawLine)
                            }
                            "G0", "G1" -> {
                                val nextX = emissionModal.position(emittedX, command.value('X'))
                                val nextY = emissionModal.position(emittedY, command.value('Y'))
                                val nextZ = emissionModal.position(emittedZ, command.value('Z'))
                                val nextSourceE = emissionModal.extrusion(sourceE, command.value('E'))
                                val deltaE = nextSourceE - sourceE
                                val spatial = abs(nextX - emittedX) > EPSILON ||
                                    abs(nextY - emittedY) > EPSILON || abs(nextZ - emittedZ) > EPSILON
                                val extruding = command.has('E')
                                if (extruding) sourceE = nextSourceE
                                if (!spatial) {
                                    if (extruding) {
                                        runningE += deltaE
                                        val builder = StringBuilder(command.opcode)
                                        if (emissionModal.absoluteExtrusion) {
                                            builder.append(" E").append(format(quantize(runningE)))
                                        } else {
                                            builder.append(" E").append(format(quantize(deltaE)))
                                        }
                                        command.value('F')?.let { builder.append(" F").append(format(it)) }
                                        rawLine.substringAfter(';', "").takeIf { ';' in rawLine }
                                            ?.let { builder.append(" ;").append(it) }
                                        output.appendLine(builder.toString())
                                    } else {
                                        output.appendLine(rawLine)
                                    }
                                    emittedX = nextX
                                    emittedY = nextY
                                    emittedZ = nextZ
                                    return
                                }
                                val isStair = extruding && emissionInPrintable && emissionLayer != null &&
                                    firstLayerNumber != null && emissionLayer != firstLayerNumber &&
                                    isSkinSource(nextX, nextY, nextZ, surface, layerHeightMm, shellLayers)
                                if (isStair) {
                                    // The stair step becomes a travel; its plastic is
                                    // printed on the conformal shell instead, so the E
                                    // coordinate only advances when that shell extrudes.
                                    stairMovesRemoved++
                                    emitMove(output, nextX, nextY, nextZ, null, command.value('F'), "G0")
                                    return
                                }
                                emitMove(
                                    output, nextX, nextY, nextZ,
                                    if (extruding) deltaE else null,
                                    command.value('F'),
                                    command.opcode,
                                )
                            }
                            else -> output.appendLine(rawLine)
                        }
                    }

                    val lines = ArrayList<String>()
                    input.forEachLine { lines.add(it) }
                    for (rawLine in lines) {
                        checkCancellation(emittedMoves, "Conformal processing")
                        processLine(rawLine)
                    }
                    emissionLayer?.let { flushSkins(output, it) }
                    writeMetadata(output)
                }
            }

            require(emittedMoves > 0) { "Conformal processing found no printable G-code moves" }
            require(minimumZ >= -0.02) { "Conformal G-code descends below the build plate: " + format(minimumZ) + " mm" }
            require(maximumZ <= printerEnvelope.heightMm + 0.02) {
                "Conformal G-code rises to Z " + format(maximumZ) + " mm outside the " +
                    format(printerEnvelope.heightMm) + " mm build height"
            }
            publishAtomic(temporary, file, "conformal G-code")
            return Diagnostics(
                regionCount = surface.regions.size,
                sourceExtrusionMoves = sourceMoves,
                stairMovesRemoved = stairMovesRemoved,
                skinMovesEmitted = skinMovesEmitted,
                emittedMoves = emittedMoves,
                minimumZmm = minimumZ,
                maximumZmm = maximumZ,
                maximumDiveMm = maximumDive,
                maximumObservedSlopeDegrees = maximumSlope,
                maximumObservedZSpeedMmPerSecond = maximumZSpeed,
            )
        } finally {
            temporary.delete()
        }
    }

    private fun isSkinSource(
        x: Double,
        y: Double,
        z: Double,
        surface: ConformalSurface,
        layerHeightMm: Double,
        shellLayers: Int,
    ): Boolean {
        for (region in surface.regions) {
            val surfaceZ = region.surfaceZ(x, y) ?: continue
            val band = floor((surfaceZ - z) / layerHeightMm + 1e-9).toInt()
            if (band in 0 until shellLayers) return true
        }
        return false
    }

    /**
     * Orders shell pieces into a space-filling chain: starting from the piece
     * nearest to (startX, startY), repeatedly continue with the unused piece
     * whose start point lies closest to the current end point. A coarse grid
     * keeps each step cheap; the result turns fragmented slivers into long
     * continuous surface passes.
     */
    private fun chainNearestNeighbor(pieces: List<Piece>, startX: Double, startY: Double): List<Piece> {
        if (pieces.size <= 1) return pieces
        val cell = NEIGHBOR_CELL_MM
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (piece in pieces) {
            minX = min(minX, piece.x1); maxX = max(maxX, piece.x1)
            minY = min(minY, piece.y1); maxY = max(maxY, piece.y1)
        }
        val columns = max(1, ((maxX - minX) / cell + 1).toInt())
        val rows = max(1, ((maxY - minY) / cell + 1).toInt())
        val buckets = HashMap<Int, ArrayList<Int>>(pieces.size)
        fun cellOf(x: Double, y: Double): Int {
            val gx = ((x - minX) / cell).toInt().coerceIn(0, columns - 1)
            val gy = ((y - minY) / cell).toInt().coerceIn(0, rows - 1)
            return gy * columns + gx
        }
        for (index in pieces.indices) {
            buckets.getOrPut(cellOf(pieces[index].x1, pieces[index].y1)) { ArrayList(4) }.add(index)
        }
        val used = BooleanArray(pieces.size)
        val result = ArrayList<Piece>(pieces.size)
        var currentIndex = 0
        var currentDistance = hypot(pieces[0].x1 - startX, pieces[0].y1 - startY)
        for (index in 1 until pieces.size) {
            val distance = hypot(pieces[index].x1 - startX, pieces[index].y1 - startY)
            if (distance < currentDistance) {
                currentDistance = distance
                currentIndex = index
            }
        }
        used[currentIndex] = true
        var current = pieces[currentIndex]
        result += current
        var remaining = pieces.size - 1
        while (remaining > 0) {
            val cx = ((current.x2 - minX) / cell).toInt()
            val cy = ((current.y2 - minY) / cell).toInt()
            var bestIndex = -1
            var bestDistance = Double.MAX_VALUE
            val maxRadius = max(columns, rows) + 1
            for (radius in 0..maxRadius) {
                var candidateInRing = false
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        if (max(abs(dx), abs(dy)) != radius) continue
                        val gx = cx + dx
                        val gy = cy + dy
                        if (gx !in 0 until columns || gy !in 0 until rows) continue
                        val bucket = buckets[gy * columns + gx] ?: continue
                        for (index in bucket) {
                            if (used[index]) continue
                            candidateInRing = true
                            val piece = pieces[index]
                            val distance = hypot(piece.x1 - current.x2, piece.y1 - current.y2)
                            if (distance < bestDistance) {
                                bestDistance = distance
                                bestIndex = index
                            }
                        }
                    }
                }
                // The first non-empty ring holds the nearest candidate.
                if (candidateInRing) break
            }
            if (bestIndex < 0) bestIndex = used.indexOfFirst { !it }
            used[bestIndex] = true
            current = pieces[bestIndex]
            result += current
            remaining--
        }
        return result
    }

    private fun quantize(value: Double): Double = quantizeGcode(value)

    private fun format(value: Double): String = formatGcode(value)
}
