package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.engine.GcodeCommand
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
    private const val MAX_EMITTED_MOVES = 3_000_000
    private val TOKEN = Regex("([A-Za-z])\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))")

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
        var logicalFeed = 0.0
        var emittedFeed = Double.NaN
        var inPrintableLayers = false
        var sourceMoves = 0
        var emittedMoves = 0
        var subdividedMoves = 0
        var extrusionMoves = 0
        var travelMoves = 0
        var minimumZ = Double.POSITIVE_INFINITY
        var maximumZ = Double.NEGATIVE_INFINITY
        var maximumSlope = 0.0
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
                    input.forEachLine { rawLine ->
                        val trimmed = rawLine.trimStart()
                        if (trimmed.startsWith(";TIME_ELAPSED:")) {
                            val originalElapsed = trimmed.substringAfter(':').trim().toDoubleOrNull()
                            if (originalElapsed != null) {
                                output.appendLine(";TIME_ELAPSED:${format(originalElapsed + additionalTimeSeconds)}")
                                return@forEachLine
                            }
                        }
                        if (!metadataWritten && (trimmed.startsWith(";Generated with Cura") || trimmed.startsWith(";FLAVOR:"))) {
                            output.appendLine(rawLine)
                            writeMetadata(output)
                            return@forEachLine
                        }
                        if (trimmed.startsWith(";LAYER:")) inPrintableLayers = true
                        if (trimmed.startsWith(";End of Gcode", ignoreCase = true) || trimmed.startsWith(";END_OF_PRINT")) {
                            inPrintableLayers = false
                        }

                        val command = GcodeCommand.parse(rawLine)
                        if (command == null) {
                            output.appendLine(rawLine)
                            return@forEachLine
                        }
                        if (modal.apply(command)) {
                            output.appendLine(rawLine)
                            return@forEachLine
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
                                command.value('E')?.let { planarE = it; curvedE = it }
                                output.appendLine(rawLine)
                            }
                            "G2", "G3" -> {
                                if (inPrintableLayers) {
                                    error("CurviSlicer requires linear G0/G1 paths; disable arc fitting before slicing")
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
                                if (!spatial || !inPrintableLayers) {
                                    if (inPrintableLayers && command.has('E')) {
                                        // Preserve the requested retraction/prime delta after
                                        // compensated extrusion has shifted the absolute E axis.
                                        val builder = StringBuilder(command.opcode)
                                        val curvedDeltaE = nextPlanarE - planarE
                                        builder.append(" E").append(
                                            format(if (modal.absoluteExtrusion) curvedE + curvedDeltaE else curvedDeltaE),
                                        )
                                        command.value('F')?.let { builder.append(" F").append(format(it)) }
                                        val unknown = unknownTokens(rawLine)
                                        if (unknown.isNotBlank()) builder.append(' ').append(unknown)
                                        rawLine.substringAfter(';', "").takeIf { ';' in rawLine }
                                            ?.let { builder.append(" ;").append(it) }
                                        output.appendLine(builder.toString())
                                        curvedE += curvedDeltaE
                                    } else {
                                        output.appendLine(rawLine)
                                        if (command.has('E')) curvedE = nextPlanarE
                                    }
                                    command.value('F')?.let { emittedFeed = it }
                                    planarX = nextPlanarX
                                    planarY = nextPlanarY
                                    planarZ = nextPlanarZ
                                    planarE = nextPlanarE
                                    curvedX = nextPlanarX
                                    curvedY = nextPlanarY
                                    curvedZ = if (inPrintableLayers) field.unflattenZ(nextPlanarX, nextPlanarY, nextPlanarZ) else nextPlanarZ
                                    return@forEachLine
                                }

                                sourceMoves++
                                val startPlanarX = planarX
                                val startPlanarY = planarY
                                val startPlanarZ = planarZ
                                val startCurvedX = curvedX
                                val startCurvedY = curvedY
                                val startCurvedZ = curvedZ
                                val endCurvedZ = field.unflattenZ(nextPlanarX, nextPlanarY, nextPlanarZ)
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
                                    ceil(max(planarLength, curvedLength) / settings.maximumSegmentLengthMm).toInt(),
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
                                    points += Point(px, py, field.unflattenZ(px, py, pz))
                                }
                                val lengths = DoubleArray(segmentCount)
                                var totalCurvedLength = 0.0
                                for (segment in 0 until segmentCount) {
                                    lengths[segment] = points[segment].distanceTo(points[segment + 1])
                                    totalCurvedLength += lengths[segment]
                                }
                                val compensatedDeltaE = if (deltaE > EPSILON && settings.compensateExtrusion && planarLength > EPSILON) {
                                    deltaE * (totalCurvedLength / planarLength).coerceIn(0.5, 2.0)
                                } else {
                                    deltaE
                                }
                                val unknownTokens = unknownTokens(rawLine)
                                val comment = rawLine.substringAfter(';', "").takeIf { ';' in rawLine }
                                var emittedCurvedE = curvedE
                                for (segment in 0 until segmentCount) {
                                    val from = points[segment]
                                    val to = points[segment + 1]
                                    val share = if (totalCurvedLength > EPSILON) lengths[segment] / totalCurvedLength else 1.0 / segmentCount
                                    val segmentDeltaE = compensatedDeltaE * share
                                    emittedCurvedE += segmentDeltaE
                                    val slope = if (hypot(to.x - from.x, to.y - from.y) > EPSILON) {
                                        abs(to.z - from.z) / hypot(to.x - from.x, to.y - from.y)
                                    } else 0.0
                                    maximumSlope = max(maximumSlope, Math.toDegrees(kotlin.math.atan(slope)))
                                    val zSpeed = if (lengths[segment] > EPSILON) requestedSpeed * abs(to.z - from.z) / lengths[segment] else 0.0
                                    val safeSpeed = if (zSpeed > settings.maximumZSpeedMmPerSecond && zSpeed > EPSILON) {
                                        requestedSpeed * settings.maximumZSpeedMmPerSecond / zSpeed
                                    } else requestedSpeed
                                    maximumZSpeed = max(maximumZSpeed, min(zSpeed, settings.maximumZSpeedMmPerSecond))
                                    val safeFeed = safeSpeed * 60.0
                                    if (safeSpeed > EPSILON) actualMoveSeconds += lengths[segment] / safeSpeed

                                    val builder = StringBuilder(command.opcode)
                                    if (modal.absolutePosition) {
                                        builder.append(" X").append(format(to.x))
                                        builder.append(" Y").append(format(to.y))
                                        builder.append(" Z").append(format(to.z))
                                    } else {
                                        builder.append(" X").append(format(to.x - from.x))
                                        builder.append(" Y").append(format(to.y - from.y))
                                        builder.append(" Z").append(format(to.z - from.z))
                                    }
                                    if (command.has('E')) {
                                        builder.append(" E").append(
                                            format(if (modal.absoluteExtrusion) emittedCurvedE else segmentDeltaE),
                                        )
                                    }
                                    if (safeFeed > EPSILON && (!emittedFeed.isFinite() || abs(safeFeed - emittedFeed) > 0.01)) {
                                        builder.append(" F").append(format(safeFeed))
                                        emittedFeed = safeFeed
                                    }
                                    if (segment == 0 && unknownTokens.isNotBlank()) builder.append(' ').append(unknownTokens)
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
                                curvedX = nextPlanarX
                                curvedY = nextPlanarY
                                curvedZ = endCurvedZ
                                curvedE = if (command.has('E')) emittedCurvedE else curvedE
                            }
                            else -> output.appendLine(rawLine)
                        }
                    }
                    writeMetadata(output)
                }
            }

            require(emittedMoves > 0) { "CurviSlicer found no printable G-code moves to curve" }
            require(minimumZ >= -0.02) { "CurviSlicer generated a path below the build plate: ${format(minimumZ)} mm" }
            require(maximumZ <= printerEnvelope.heightMm + 0.02) {
                "CurviSlicer generated Z ${format(maximumZ)} mm outside the ${format(printerEnvelope.heightMm)} mm build height"
            }
            check(file.delete()) { "Unable to replace planar G-code with CurviSlicer output" }
            check(temporary.renameTo(file) || temporary.copyTo(file, overwrite = false).let { temporary.delete(); true }) {
                "Unable to publish CurviSlicer G-code"
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

    private fun unknownTokens(rawLine: String): String {
        val code = rawLine.substringBefore(';')
        val opcodeEnd = code.indexOfFirst(Char::isWhitespace).let { if (it < 0) code.length else it }
        val remainder = code.substring(opcodeEnd)
        return TOKEN.findAll(remainder)
            .filter { it.groupValues[1].single().uppercaseChar() !in setOf('X', 'Y', 'Z', 'E', 'F') }
            .joinToString(" ") { it.value.trim() }
    }

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
