package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.engine.GcodeCommand
import com.tomppi.enderslicer.engine.GcodeCommandPolicy
import com.tomppi.enderslicer.engine.GcodeModalState
import com.tomppi.enderslicer.engine.PrinterEnvelope
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal object CurviGcodeTransformer {
    private const val EPSILON = 1e-8
    private const val MAX_EMITTED_MOVES = 8_000_000
    private const val SLOPE_TOLERANCE_DEGREES = 0.05

    fun transform(
        file: File,
        field: CurviSlicerField,
        settings: NonPlanarSettings,
        printerEnvelope: PrinterEnvelope,
    ): CurviSlicerPipeline.GcodeDiagnostics {
        require(file.isFile && file.length() > 0L) { "CurviSlicer G-code is missing" }
        val temporary = File(file.parentFile, "${file.name}.curvislicer.tmp")
        temporary.delete()

        val modal = GcodeModalState()
        var planarX = 0.0
        var planarY = 0.0
        var planarZ = 0.0
        var planarE = 0.0
        var curvedX = 0.0
        var curvedY = 0.0
        var curvedZ = 0.0
        var curvedE = 0.0
        var idealCurvedE = 0.0
        var logicalFeed = 0.0
        var emittedFeed = Double.NaN
        var inPrintableLayers = false
        var afterMachineEnd = false
        var currentLayer: Int? = null
        var currentPathType: String? = null
        var currentOverhangOffset: Double? = null
        var pendingOverhang: MutableList<Pair<Int, String>>? = null
        var overhangExitTravel = false
        var lineNumber = 0
        var sourceMoves = 0
        var emittedMoves = 0
        var subdividedMoves = 0
        var extrusionMoves = 0
        var travelMoves = 0
        var minimumZ = Double.POSITIVE_INFINITY
        var maximumZ = Double.NEGATIVE_INFINITY
        var maximumSlope = 0.0
        var maximumSlopeContext: String? = null
        var maximumZSpeed = 0.0
        var additionalTimeSeconds = 0.0
        var metadataWritten = false

        fun writeMetadata(output: Appendable) {
            if (metadataWritten) return
            metadataWritten = true
            output.appendLine(";ENDERSLICER_NON_PLANAR:CurviSlicer-Android-v${NonPlanarSettingsStore.BACKEND_VERSION}")
            output.appendLine(";ENDERSLICER_CURVI_STRENGTH:${format(field.strength * 100.0)}")
            output.appendLine(";ENDERSLICER_CURVI_MAX_DISPLACEMENT:${format(field.maximumDisplacementMm)}")
            output.appendLine(";ENDERSLICER_CURVI_GRID:${field.columns}x${field.rows}")
        }

        try {
            file.bufferedReader().use { input ->
                temporary.bufferedWriter().use { output ->
                    fun processLine(rawLine: String) {
                        val trimmed = rawLine.trimStart()
                        if (afterMachineEnd) {
                            require(trimmed != CurviSlicerRuntime.MACHINE_END_SENTINEL) {
                                "CurviSlicer machine-end sentinel appears more than once"
                            }
                            output.appendLine(rawLine)
                            return
                        }
                        if (trimmed == CurviSlicerRuntime.MACHINE_END_SENTINEL) {
                            // This is a monotonic phase boundary. No later comment or command may
                            // re-enable deformation of the user-authored machine-end script.
                            afterMachineEnd = true
                            inPrintableLayers = false
                            currentLayer = null
                            output.appendLine(rawLine)
                            if (abs(curvedE - planarE) > EPSILON) {
                                output.appendLine(
                                    "G92 E${format(planarE)} ; restore Cura E coordinate before machine end G-code",
                                )
                            }
                            curvedE = planarE
                            idealCurvedE = planarE
                            planarX = curvedX
                            planarY = curvedY
                            planarZ = curvedZ
                            return
                        }
                        if (trimmed.startsWith(";TIME_ELAPSED:")) {
                            val originalElapsed = trimmed.substringAfter(':').trim().toDoubleOrNull()
                            if (originalElapsed != null) {
                                output.appendLine(";TIME_ELAPSED:${format(originalElapsed + additionalTimeSeconds)}")
                                return
                            }
                        }
                        if (!metadataWritten && (trimmed.startsWith(";Generated with Cura") || trimmed.startsWith(";FLAVOR:"))) {
                            output.appendLine(rawLine)
                            writeMetadata(output)
                            return
                        }
                        if (trimmed.startsWith(";LAYER:")) {
                            currentLayer = trimmed.substringAfter(':').trim().toIntOrNull()
                            inPrintableLayers = true
                        }
                        if (trimmed.startsWith(";TYPE:")) {
                            currentPathType = trimmed.substringAfter(":").trim()
                            currentOverhangOffset = null
                        }
                        if (trimmed.startsWith(";End of Gcode", ignoreCase = true) || trimmed.startsWith(";END_OF_PRINT")) {
                            inPrintableLayers = false
                            currentLayer = null
                        }

                        val command = GcodeCommand.parse(rawLine)
                        if (command == null) {
                            output.appendLine(rawLine)
                            return
                        }
                        GcodeCommandPolicy.requireCurviSupported(command, inPrintableLayers)
                        if (modal.apply(command)) {
                            output.appendLine(rawLine)
                            return
                        }
                        when (command.opcode) {
                            "G92" -> {
                                require(!inPrintableLayers || (!command.has('X') && !command.has('Y') && !command.has('Z'))) {
                                    "CurviSlicer does not support G92 coordinate resets inside printable layers"
                                }
                                command.value('X')?.let { planarX = it; curvedX = it }
                                command.value('Y')?.let { planarY = it; curvedY = it }
                                command.value('Z')?.let {
                                    planarZ = it
                                    curvedZ = if (inPrintableLayers) field.unflattenZ(planarX, planarY, it) else it
                                }
                                command.value('E')?.let {
                                    planarE = it
                                    curvedE = it
                                    idealCurvedE = it
                                }
                                output.appendLine(rawLine)
                            }
                            "G0", "G1" -> {
                                val nextPlanarX = modal.position(planarX, command.value('X'))
                                val nextPlanarY = modal.position(planarY, command.value('Y'))
                                val nextPlanarZ = modal.position(planarZ, command.value('Z'))
                                val nextPlanarE = modal.extrusion(planarE, command.value('E'))
                                command.value('F')?.let { logicalFeed = it }
                                val deltaE = nextPlanarE - planarE
                                val spatial = abs(nextPlanarX - planarX) > EPSILON ||
                                    abs(nextPlanarY - planarY) > EPSILON || abs(nextPlanarZ - planarZ) > EPSILON
                                val isOverhangSection = currentPathType == "ARC-OVERHANG" ||
                                    currentPathType == "WAVE-OVERHANG"
                                val isOverhangExtrusion = isOverhangSection && command.has('E')
                                val isOverhangPath = isOverhangExtrusion ||
                                    (isOverhangSection && !command.has('E') && !overhangExitTravel)
                                if (!spatial || !inPrintableLayers) {
                                    if (inPrintableLayers && command.has('E')) {
                                        val builder = StringBuilder(command.opcode)
                                        idealCurvedE += deltaE
                                        val emittedE = if (modal.absoluteExtrusion) {
                                            quantize(idealCurvedE)
                                        } else {
                                            quantize(idealCurvedE - curvedE)
                                        }
                                        builder.append(" E").append(format(emittedE))
                                        command.value('F')?.let { builder.append(" F").append(format(it)) }
                                        rawLine.substringAfter(';', "").takeIf { ';' in rawLine }
                                            ?.let { builder.append(" ;").append(it) }
                                        output.appendLine(builder.toString())
                                        curvedE = if (modal.absoluteExtrusion) emittedE else curvedE + emittedE
                                    } else {
                                        output.appendLine(rawLine)
                                        if (command.has('E')) {
                                            curvedE = nextPlanarE
                                            idealCurvedE = nextPlanarE
                                        }
                                    }
                                    command.value('F')?.let { emittedFeed = it }
                                    planarX = nextPlanarX
                                    planarY = nextPlanarY
                                    planarZ = nextPlanarZ
                                    planarE = nextPlanarE
                                    curvedX = nextPlanarX
                                    curvedY = nextPlanarY
                                    curvedZ = if (isOverhangPath && inPrintableLayers) {
                                        nextPlanarZ + (currentOverhangOffset
                                            ?: (field.unflattenZ(nextPlanarX, nextPlanarY, nextPlanarZ) - nextPlanarZ))
                                    } else if (inPrintableLayers) {
                                        field.unflattenZ(nextPlanarX, nextPlanarY, nextPlanarZ)
                                    } else {
                                        nextPlanarZ
                                    }
                                    return
                                }

                                sourceMoves++
                                val startPlanarX = planarX
                                val startPlanarY = planarY
                                val startPlanarZ = planarZ
                                val startCurvedX = curvedX
                                val startCurvedY = curvedY
                                val startCurvedZ = curvedZ
                                val startIdealCurvedE = idealCurvedE
                                if (isOverhangPath && currentOverhangOffset == null) {
                                    currentOverhangOffset =
                                        field.unflattenZ(nextPlanarX, nextPlanarY, nextPlanarZ) - nextPlanarZ
                                }
                                val overhangOffset = if (isOverhangPath) currentOverhangOffset!! else 0.0
                                val endCurvedZ = if (isOverhangPath) {
                                    nextPlanarZ + overhangOffset
                                } else {
                                    field.unflattenZ(nextPlanarX, nextPlanarY, nextPlanarZ)
                                }
                                val planarLength = distance3(
                                    startPlanarX, startPlanarY, startPlanarZ,
                                    nextPlanarX, nextPlanarY, nextPlanarZ,
                                )
                                val curvedLength = distance3(
                                    startCurvedX, startCurvedY, startCurvedZ,
                                    nextPlanarX, nextPlanarY, endCurvedZ,
                                )
                                val requestedSpeed = (logicalFeed / 60.0).coerceAtLeast(0.0)
                                val requestedMoveSeconds = if (requestedSpeed > EPSILON) planarLength / requestedSpeed else 0.0
                                var actualMoveSeconds = 0.0
                                val segmentCount = max(
                                    1,
                                    ceil(
                                        (if (isOverhangPath) planarLength else max(planarLength, curvedLength)) /
                                            settings.maximumSegmentLengthMm,
                                    ).toInt(),
                                ).coerceAtMost(20_000)
                                if (segmentCount > 1) subdividedMoves++
                                check(emittedMoves + segmentCount <= MAX_EMITTED_MOVES) {
                                    "CurviSlicer path subdivision exceeded $MAX_EMITTED_MOVES moves; increase maximum segment length"
                                }

                                val points = ArrayList<Point>(segmentCount + 1)
                                points += Point(startCurvedX, startCurvedY, startCurvedZ)
                                for (segment in 1..segmentCount) {
                                    val t = segment.toDouble() / segmentCount
                                    val px = lerp(startPlanarX, nextPlanarX, t)
                                    val py = lerp(startPlanarY, nextPlanarY, t)
                                    val pz = lerp(startPlanarZ, nextPlanarZ, t)
                                    points += if (isOverhangPath) {
                                        Point(px, py, pz + overhangOffset)
                                    } else {
                                        Point(px, py, field.unflattenZ(px, py, pz))
                                    }
                                }
                                val lengths = DoubleArray(segmentCount)
                                var totalCurvedLength = 0.0
                                for (segment in 0 until segmentCount) {
                                    lengths[segment] = points[segment].distanceTo(points[segment + 1])
                                    totalCurvedLength += lengths[segment]
                                }
                                val compensatedDeltaE = if (
                                    deltaE > EPSILON && settings.compensateExtrusion && planarLength > EPSILON
                                ) {
                                    deltaE * (totalCurvedLength / planarLength).coerceIn(0.5, 2.0)
                                } else {
                                    deltaE
                                }
                                val comment = rawLine.substringAfter(';', "").takeIf { ';' in rawLine }
                                var emittedCurvedE = curvedE
                                var cumulativeLength = 0.0
                                var previousRelativeX = 0.0
                                var previousRelativeY = 0.0
                                var previousRelativeZ = 0.0
                                for (segment in 0 until segmentCount) {
                                    val from = points[segment]
                                    val to = points[segment + 1]
                                    cumulativeLength += lengths[segment]
                                    val fraction = if (totalCurvedLength > EPSILON) {
                                        cumulativeLength / totalCurvedLength
                                    } else {
                                        (segment + 1).toDouble() / segmentCount
                                    }
                                    val idealTargetE = startIdealCurvedE + compensatedDeltaE * fraction
                                    val emittedE = if (modal.absoluteExtrusion) {
                                        quantize(idealTargetE)
                                    } else {
                                        quantize(idealTargetE - emittedCurvedE)
                                    }
                                    emittedCurvedE = if (modal.absoluteExtrusion) emittedE else emittedCurvedE + emittedE
                                    val horizontal = hypot(to.x - from.x, to.y - from.y)
                                    val slope = if (horizontal > EPSILON) abs(to.z - from.z) / horizontal else 0.0
                                    val slopeDegrees = Math.toDegrees(kotlin.math.atan(slope))
                                    if (slopeDegrees > maximumSlope) {
                                        maximumSlope = slopeDegrees
                                        maximumSlopeContext = "slope=${format(slopeDegrees)}° at line $lineNumber " +
                                            "layer=$currentLayer type=$currentPathType " +
                                            "from=(${format(from.x)},${format(from.y)},${format(from.z)}) " +
                                            "to=(${format(to.x)},${format(to.y)},${format(to.z)}) " +
                                            "horizontal=${format(horizontal)} segment=$segment/$segmentCount " +
                                            "feed=${format(logicalFeed)}"
                                    }
                                    val zSpeed = if (lengths[segment] > EPSILON) {
                                        requestedSpeed * abs(to.z - from.z) / lengths[segment]
                                    } else {
                                        0.0
                                    }
                                    val safeSpeed = if (zSpeed > settings.maximumZSpeedMmPerSecond && zSpeed > EPSILON) {
                                        requestedSpeed * settings.maximumZSpeedMmPerSecond / zSpeed
                                    } else {
                                        requestedSpeed
                                    }
                                    maximumZSpeed = max(maximumZSpeed, min(zSpeed, settings.maximumZSpeedMmPerSecond))
                                    val safeFeed = safeSpeed * 60.0
                                    if (safeSpeed > EPSILON) actualMoveSeconds += lengths[segment] / safeSpeed

                                    printerEnvelope.requireMotionMove(
                                        startX = from.x,
                                        startY = from.y,
                                        startZ = from.z,
                                        endX = to.x,
                                        endY = to.y,
                                        endZ = to.z,
                                        lineNumber = lineNumber,
                                        layerNumber = currentLayer,
                                    )

                                    val builder = StringBuilder(command.opcode)
                                    if (modal.absolutePosition) {
                                        builder.append(" X").append(format(to.x))
                                        builder.append(" Y").append(format(to.y))
                                        builder.append(" Z").append(format(to.z))
                                    } else {
                                        val targetRelativeX = quantize(to.x - startCurvedX)
                                        val targetRelativeY = quantize(to.y - startCurvedY)
                                        val targetRelativeZ = quantize(to.z - startCurvedZ)
                                        builder.append(" X").append(format(targetRelativeX - previousRelativeX))
                                        builder.append(" Y").append(format(targetRelativeY - previousRelativeY))
                                        builder.append(" Z").append(format(targetRelativeZ - previousRelativeZ))
                                        previousRelativeX = targetRelativeX
                                        previousRelativeY = targetRelativeY
                                        previousRelativeZ = targetRelativeZ
                                    }
                                    if (command.has('E')) builder.append(" E").append(format(emittedE))
                                    if (safeFeed > EPSILON && (!emittedFeed.isFinite() || abs(safeFeed - emittedFeed) > 0.01)) {
                                        builder.append(" F").append(format(safeFeed))
                                        emittedFeed = safeFeed
                                    }
                                    if (segment == segmentCount - 1 && comment != null) builder.append(" ;").append(comment)
                                    output.appendLine(builder.toString())
                                    emittedMoves++
                                    minimumZ = minOf(minimumZ, from.z, to.z)
                                    maximumZ = maxOf(maximumZ, from.z, to.z)
                                }
                                additionalTimeSeconds += (actualMoveSeconds - requestedMoveSeconds).coerceAtLeast(0.0)
                                if (deltaE > EPSILON) extrusionMoves += segmentCount else travelMoves += segmentCount
                                planarX = nextPlanarX
                                planarY = nextPlanarY
                                planarZ = nextPlanarZ
                                planarE = nextPlanarE
                                if (modal.absolutePosition) {
                                    curvedX = quantize(nextPlanarX)
                                    curvedY = quantize(nextPlanarY)
                                    curvedZ = quantize(endCurvedZ)
                                } else {
                                    curvedX = startCurvedX + previousRelativeX
                                    curvedY = startCurvedY + previousRelativeY
                                    curvedZ = startCurvedZ + previousRelativeZ
                                }
                                if (command.has('E')) {
                                    idealCurvedE = startIdealCurvedE + compensatedDeltaE
                                    curvedE = emittedCurvedE
                                }
                            }
                            else -> output.appendLine(rawLine)
                        }
                    }

                    fun flushPendingOverhang() {
                        val buffer = pendingOverhang ?: return
                        pendingOverhang = null
                        if (buffer.isEmpty()) return
                        var scanX = planarX
                        var scanY = planarY
                        var scanZ = planarZ
                        var lastExtrusionX = scanX
                        var lastExtrusionY = scanY
                        var lastExtrusionZ = scanZ
                        var lastExtrusionBufferIndex = -1
                        var foundExtrusion = false
                        for ((bufferIndex, pair) in buffer.withIndex()) {
                            val command = GcodeCommand.parse(pair.second) ?: continue
                            if (command.opcode != "G0" && command.opcode != "G1") continue
                            val nextX = modal.position(scanX, command.value('X'))
                            val nextY = modal.position(scanY, command.value('Y'))
                            val nextZ = modal.position(scanZ, command.value('Z'))
                            val spatial = abs(nextX - scanX) > EPSILON ||
                                abs(nextY - scanY) > EPSILON || abs(nextZ - scanZ) > EPSILON
                            if (command.has('E') && spatial) {
                                lastExtrusionX = nextX
                                lastExtrusionY = nextY
                                lastExtrusionZ = nextZ
                                lastExtrusionBufferIndex = bufferIndex
                                foundExtrusion = true
                            }
                            scanX = nextX
                            scanY = nextY
                            scanZ = nextZ
                        }
                        currentOverhangOffset = if (foundExtrusion) {
                            field.unflattenZ(lastExtrusionX, lastExtrusionY, lastExtrusionZ) - lastExtrusionZ
                        } else {
                            null
                        }
                        for ((bufferIndex, pair) in buffer.withIndex()) {
                            lineNumber = pair.first
                            overhangExitTravel = bufferIndex > lastExtrusionBufferIndex
                            processLine(pair.second)
                        }
                        overhangExitTravel = false
                    }

                    val lines = ArrayList<String>()
                    input.forEachLine { lines.add(it) }
                    var index = 0
                    while (index < lines.size) {
                        val rawLine = lines[index]
                        index++
                        lineNumber++
                        val trimmed = rawLine.trimStart()
                        val startsOverhang = trimmed.startsWith(";TYPE:") &&
                            (trimmed.contains("WAVE-OVERHANG") || trimmed.contains("ARC-OVERHANG"))
                        if (startsOverhang) {
                            flushPendingOverhang()
                            pendingOverhang = mutableListOf()
                            processLine(rawLine)
                            continue
                        }
                        if (pendingOverhang != null) {
                            val endsOverhang = trimmed.startsWith(";TYPE:") || trimmed.startsWith(";LAYER:") ||
                                trimmed.startsWith(";End of Gcode", ignoreCase = true) ||
                                trimmed.startsWith(";END_OF_PRINT") ||
                                trimmed == CurviSlicerRuntime.MACHINE_END_SENTINEL
                            if (!endsOverhang) {
                                pendingOverhang!!.add(lineNumber to rawLine)
                                continue
                            }
                            flushPendingOverhang()
                        }
                        processLine(rawLine)
                    }
                    flushPendingOverhang()
                    writeMetadata(output)
                }
            }

            require(emittedMoves > 0) { "CurviSlicer found no printable G-code moves to curve" }
            require(minimumZ >= -0.02) { "CurviSlicer generated a path below the build plate: ${format(minimumZ)} mm" }
            require(maximumZ <= printerEnvelope.heightMm + 0.02) {
                "CurviSlicer generated Z ${format(maximumZ)} mm outside the ${format(printerEnvelope.heightMm)} mm build height"
            }
            try {
                File(file.parentFile, "${file.name}.curvislope.diag.txt")
                    .writeText("maximumSlope=${format(maximumSlope)}\n$maximumSlopeContext\n")
            } catch (_: Exception) {
            }
            require(maximumSlope <= settings.effectiveSlopeLimitDegrees + SLOPE_TOLERANCE_DEGREES) {
                "CurviSlicer generated path slope ${format(maximumSlope)}° above the configured " +
                    "${format(settings.effectiveSlopeLimitDegrees)}° clearance limit" +
                    (maximumSlopeContext?.let { "; worst: $it" } ?: "")
            }
            try {
                java.nio.file.Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.io.IOException) {
                check(temporary.renameTo(file) || temporary.copyTo(file, overwrite = true).let { temporary.delete(); true }) {
                    "Unable to publish CurviSlicer G-code"
                }
            }
            return CurviSlicerPipeline.GcodeDiagnostics(
                sourceMoves = sourceMoves,
                emittedMoves = emittedMoves,
                subdividedMoves = subdividedMoves,
                extrusionMoves = extrusionMoves,
                travelMoves = travelMoves,
                minimumZmm = minimumZ,
                maximumZmm = maximumZ,
                maximumObservedSlopeDegrees = maximumSlope,
                maximumObservedZSpeedMmPerSecond = maximumZSpeed,
            )
        } finally {
            temporary.delete()
        }
    }

    private fun quantize(value: Double): Double = format(value).toDouble()

    private fun format(value: Double): String = String.format(Locale.US, "%.6f", value)
        .trimEnd('0')
        .trimEnd('.')
        .let { if (it == "-0") "0" else it }

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    private fun distance3(
        x1: Double,
        y1: Double,
        z1: Double,
        x2: Double,
        y2: Double,
        z2: Double,
    ): Double {
        val dx = x2 - x1
        val dy = y2 - y1
        val dz = z2 - z1
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private data class Point(val x: Double, val y: Double, val z: Double) {
        fun distanceTo(other: Point): Double = distance3(x, y, z, other.x, other.y, other.z)
    }
}
