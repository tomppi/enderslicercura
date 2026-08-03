package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.engine.GcodeCommand
import com.tomppi.enderslicer.engine.GcodeModalState
import com.tomppi.enderslicer.engine.PrinterEnvelope
import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.StlMeshWriter
import com.tomppi.enderslicer.viewer.StlParser
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Android-native CurviSlicer implementation.
 *
 * The desktop research prototype obtains a volumetric deformation from a
 * tetrahedral optimization. On Android we construct an equivalent monotone
 * height field directly from the displayed triangle mesh, flatten the STL,
 * slice that flattened solid with CuraEngine, then apply the inverse field to
 * every print move. The field is slope-limited for nozzle clearance, fades in
 * above flat base layers, preserves layer ordering, and compensates extrusion
 * for the longer 3D paths.
 */
internal object CurviSlicerPipeline {
    data class Prepared(
        val field: CurviSlicerField,
        val diagnostics: Diagnostics,
        val settings: NonPlanarSettings,
    ) {
        fun warpModifier(file: File) {
            warpStl(file, file, field)
        }

        fun curveGcode(file: File, printerEnvelope: PrinterEnvelope): GcodeDiagnostics =
            CurviGcodeTransformer.transform(file, field, settings, printerEnvelope)
    }

    data class Diagnostics(
        val gridColumns: Int,
        val gridRows: Int,
        val requestedStrength: Double,
        val appliedStrength: Double,
        val maximumRawReliefMm: Double,
        val maximumAppliedDisplacementMm: Double,
        val maximumFieldSlopeDegrees: Double,
        val sourceTriangles: Int,
    )

    data class GcodeDiagnostics(
        val sourceMoves: Int,
        val emittedMoves: Int,
        val subdividedMoves: Int,
        val extrusionMoves: Int,
        val travelMoves: Int,
        val minimumZmm: Double,
        val maximumZmm: Double,
        val maximumObservedSlopeDegrees: Double,
        val maximumObservedZSpeedMmPerSecond: Double,
    )

    fun prepareAndWarp(
        modelFile: File,
        settings: NonPlanarSettings,
        layerHeightMm: Double,
        nozzleDiameterMm: Double,
    ): Prepared {
        require(modelFile.isFile && modelFile.length() > 0L) { "CurviSlicer input STL is missing" }
        val safe = settings.validated()
        require(safe.enabled) { "CurviSlicer is not enabled" }
        require(layerHeightMm.isFinite() && layerHeightMm in 0.04..1.2) { "Invalid CurviSlicer layer height" }
        require(nozzleDiameterMm.isFinite() && nozzleDiameterMm in 0.1..2.0) { "Invalid CurviSlicer nozzle diameter" }

        val mesh = StlParser.parse(modelFile, modelFile.name)
        require(mesh.bounds.height > layerHeightMm * (safe.flatBaseLayers + 2)) {
            "The model is too short for ${safe.flatBaseLayers} flat CurviSlicer base layers"
        }
        val built = CurviSlicerFieldBuilder.build(mesh, safe, layerHeightMm, nozzleDiameterMm)
        warpStl(modelFile, modelFile, built.field)
        return Prepared(built.field, built.diagnostics, safe)
    }

    private fun warpStl(source: File, destination: File, field: CurviSlicerField) {
        val mesh = StlParser.parse(source, source.name)
        val transformed = mesh.interleavedVertices.copyOf()
        val bounds = MutableBounds()
        var offset = 0
        repeat(mesh.triangleCount) {
            val x0 = transformed[offset].toDouble()
            val y0 = transformed[offset + 1].toDouble()
            val z0 = field.flattenZ(x0, y0, transformed[offset + 2].toDouble()).toFloat()
            val x1 = transformed[offset + 6].toDouble()
            val y1 = transformed[offset + 7].toDouble()
            val z1 = field.flattenZ(x1, y1, transformed[offset + 8].toDouble()).toFloat()
            val x2 = transformed[offset + 12].toDouble()
            val y2 = transformed[offset + 13].toDouble()
            val z2 = field.flattenZ(x2, y2, transformed[offset + 14].toDouble()).toFloat()

            transformed[offset + 2] = z0
            transformed[offset + 8] = z1
            transformed[offset + 14] = z2

            val ax = x1.toFloat() - x0.toFloat()
            val ay = y1.toFloat() - y0.toFloat()
            val az = z1 - z0
            val bx = x2.toFloat() - x0.toFloat()
            val by = y2.toFloat() - y0.toFloat()
            val bz = z2 - z0
            var nx = ay * bz - az * by
            var ny = az * bx - ax * bz
            var nz = ax * by - ay * bx
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            if (length > 1e-12f) {
                nx /= length
                ny /= length
                nz /= length
            } else {
                nx = 0f
                ny = 0f
                nz = 0f
            }
            for (vertex in 0..2) {
                val base = offset + vertex * 6
                transformed[base + 3] = nx
                transformed[base + 4] = ny
                transformed[base + 5] = nz
                bounds.include(transformed[base], transformed[base + 1], transformed[base + 2])
            }
            offset += 18
        }

        val warped = StlMesh(
            displayName = mesh.displayName,
            interleavedVertices = transformed,
            triangleCount = mesh.triangleCount,
            bounds = bounds.finish(),
        )
        val temporary = File(destination.parentFile, "${destination.name}.curvislicer.tmp")
        temporary.delete()
        try {
            StlMeshWriter.writeBinary(warped, temporary)
            check(temporary.isFile && temporary.length() > 84L) { "CurviSlicer did not produce a warped STL" }
            if (destination.exists()) check(destination.delete()) { "Unable to replace the CurviSlicer STL" }
            check(temporary.renameTo(destination) || temporary.copyTo(destination, overwrite = false).let { temporary.delete(); true }) {
                "Unable to publish the CurviSlicer STL"
            }
        } finally {
            temporary.delete()
        }
    }

    private class MutableBounds {
        private var minX = Float.POSITIVE_INFINITY
        private var minY = Float.POSITIVE_INFINITY
        private var minZ = Float.POSITIVE_INFINITY
        private var maxX = Float.NEGATIVE_INFINITY
        private var maxY = Float.NEGATIVE_INFINITY
        private var maxZ = Float.NEGATIVE_INFINITY

        fun include(x: Float, y: Float, z: Float) {
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            minZ = minOf(minZ, z)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
            maxZ = maxOf(maxZ, z)
        }

        fun finish(): MeshBounds = MeshBounds(minX, minY, minZ, maxX, maxY, maxZ)
    }
}

internal data class CurviSlicerField(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
    val columns: Int,
    val rows: Int,
    val relief: FloatArray,
    val strength: Double,
    val flatBaseHeightMm: Double,
) {
    init {
        require(columns >= 2 && rows >= 2 && relief.size == columns * rows)
        require(maxX > minX && maxY > minY && maxZ > minZ)
        require(strength in 0.0..1.0)
    }

    val modelHeightMm: Double get() = maxZ - minZ
    val maximumDisplacementMm: Double
        get() = relief.maxOf { abs(it.toDouble() * strength) }

    fun displacement(x: Double, y: Double, originalZ: Double): Double {
        val u = ((originalZ - minZ - flatBaseHeightMm) / (modelHeightMm - flatBaseHeightMm))
            .coerceIn(0.0, 1.0)
        val weight = u * u * (3.0 - 2.0 * u)
        return sampleRelief(x, y) * strength * weight
    }

    fun flattenZ(x: Double, y: Double, originalZ: Double): Double =
        originalZ - displacement(x, y, originalZ)

    fun unflattenZ(x: Double, y: Double, flatZ: Double): Double {
        var original = flatZ
        repeat(6) {
            original = flatZ + displacement(x, y, original)
        }
        return original.coerceIn(minZ - maximumDisplacementMm, maxZ + maximumDisplacementMm)
    }

    fun sampleRelief(x: Double, y: Double): Double {
        val gx = ((x - minX) / (maxX - minX) * (columns - 1)).coerceIn(0.0, (columns - 1).toDouble())
        val gy = ((y - minY) / (maxY - minY) * (rows - 1)).coerceIn(0.0, (rows - 1).toDouble())
        val x0 = floor(gx).toInt().coerceIn(0, columns - 1)
        val y0 = floor(gy).toInt().coerceIn(0, rows - 1)
        val x1 = min(x0 + 1, columns - 1)
        val y1 = min(y0 + 1, rows - 1)
        val tx = gx - x0
        val ty = gy - y0
        val a = relief[y0 * columns + x0].toDouble()
        val b = relief[y0 * columns + x1].toDouble()
        val c = relief[y1 * columns + x0].toDouble()
        val d = relief[y1 * columns + x1].toDouble()
        return (a + (b - a) * tx) + ((c + (d - c) * tx) - (a + (b - a) * tx)) * ty
    }
}

internal object CurviSlicerFieldBuilder {
    data class Result(
        val field: CurviSlicerField,
        val diagnostics: CurviSlicerPipeline.Diagnostics,
    )

    fun build(
        mesh: StlMesh,
        settings: NonPlanarSettings,
        layerHeightMm: Double,
        nozzleDiameterMm: Double,
    ): Result {
        val bounds = mesh.bounds
        require(bounds.width > 0f && bounds.depth > 0f && bounds.height > 0f) { "CurviSlicer requires a three-dimensional model" }
        val aspect = bounds.width.toDouble() / bounds.depth.toDouble()
        val columns: Int
        val rows: Int
        if (aspect >= 1.0) {
            columns = settings.fieldResolution
            rows = max(32, (settings.fieldResolution / aspect).toInt()).coerceAtMost(settings.fieldResolution)
        } else {
            rows = settings.fieldResolution
            columns = max(32, (settings.fieldResolution * aspect).toInt()).coerceAtMost(settings.fieldResolution)
        }
        val top = FloatArray(columns * rows) { Float.NaN }
        val vertices = mesh.interleavedVertices
        var offset = 0
        repeat(mesh.triangleCount) {
            val x0 = vertices[offset]
            val y0 = vertices[offset + 1]
            val z0 = vertices[offset + 2]
            val x1 = vertices[offset + 6]
            val y1 = vertices[offset + 7]
            val z1 = vertices[offset + 8]
            val x2 = vertices[offset + 12]
            val y2 = vertices[offset + 13]
            val z2 = vertices[offset + 14]
            deposit(top, columns, rows, bounds, x0, y0, z0)
            deposit(top, columns, rows, bounds, x1, y1, z1)
            deposit(top, columns, rows, bounds, x2, y2, z2)
            deposit(top, columns, rows, bounds, (x0 + x1 + x2) / 3f, (y0 + y1 + y2) / 3f, (z0 + z1 + z2) / 3f)
            deposit(top, columns, rows, bounds, (x0 + x1) / 2f, (y0 + y1) / 2f, (z0 + z1) / 2f)
            deposit(top, columns, rows, bounds, (x1 + x2) / 2f, (y1 + y2) / 2f, (z1 + z2) / 2f)
            deposit(top, columns, rows, bounds, (x2 + x0) / 2f, (y2 + y0) / 2f, (z2 + z0) / 2f)
            offset += 18
        }
        fillNearest(top, columns, rows)

        val cellX = bounds.width.toDouble() / (columns - 1)
        val cellY = bounds.depth.toDouble() / (rows - 1)
        val cellSize = max(cellX, cellY)
        val clearanceRadius = settings.nozzleClearanceHeightMm * tan(Math.toRadians(settings.nozzleClearanceAngleDegrees))
        val smoothingRadius = maxOf(
            settings.smoothingRadiusMm,
            nozzleDiameterMm * 1.5,
            min(clearanceRadius * 0.04, 8.0),
        )
        val sigmaCells = (smoothingRadius / cellSize).coerceIn(0.75, 10.0)
        val smoothed = gaussianBlur(top, columns, rows, sigmaCells)
        val relief = FloatArray(top.size)
        var sum = 0.0
        for (index in relief.indices) {
            relief[index] = top[index] - smoothed[index]
            sum += relief[index]
        }
        val mean = sum / relief.size
        val clearanceAmplitudeLimit = (settings.nozzleClearanceHeightMm * tan(Math.toRadians(settings.effectiveSlopeLimitDegrees))).toFloat()
        val amplitudeLimit = min(bounds.height * 0.28f, clearanceAmplitudeLimit)
        var maximumRawRelief = 0.0
        for (index in relief.indices) {
            relief[index] = (relief[index] - mean.toFloat()).coerceIn(-amplitudeLimit, amplitudeLimit)
            maximumRawRelief = max(maximumRawRelief, abs(relief[index].toDouble()))
        }

        val requestedStrength = settings.strengthPercent / 100.0
        val flatBaseHeight = settings.flatBaseLayers * layerHeightMm
        val usableHeight = max(bounds.height.toDouble() - flatBaseHeight, layerHeightMm)
        val monotonicStrength = if (maximumRawRelief <= 1e-9) 1.0 else {
            (0.75 * usableHeight / (1.5 * maximumRawRelief)).coerceAtMost(1.0)
        }
        val maximumGradient = maximumGradient(relief, columns, rows, cellX, cellY)
        val slopeLimit = tan(Math.toRadians(settings.effectiveSlopeLimitDegrees))
        val slopeStrength = if (maximumGradient <= 1e-9) 1.0 else (slopeLimit / maximumGradient).coerceAtMost(1.0)
        val appliedStrength = min(requestedStrength, min(monotonicStrength, slopeStrength)).coerceIn(0.0, 1.0)
        val appliedSlope = Math.toDegrees(kotlin.math.atan(maximumGradient * appliedStrength))

        val field = CurviSlicerField(
            minX = bounds.minX.toDouble(),
            minY = bounds.minY.toDouble(),
            minZ = bounds.minZ.toDouble(),
            maxX = bounds.maxX.toDouble(),
            maxY = bounds.maxY.toDouble(),
            maxZ = bounds.maxZ.toDouble(),
            columns = columns,
            rows = rows,
            relief = relief,
            strength = appliedStrength,
            flatBaseHeightMm = flatBaseHeight,
        )
        return Result(
            field,
            CurviSlicerPipeline.Diagnostics(
                gridColumns = columns,
                gridRows = rows,
                requestedStrength = requestedStrength,
                appliedStrength = appliedStrength,
                maximumRawReliefMm = maximumRawRelief,
                maximumAppliedDisplacementMm = field.maximumDisplacementMm,
                maximumFieldSlopeDegrees = appliedSlope,
                sourceTriangles = mesh.triangleCount,
            ),
        )
    }

    private fun deposit(
        grid: FloatArray,
        columns: Int,
        rows: Int,
        bounds: MeshBounds,
        x: Float,
        y: Float,
        z: Float,
    ) {
        val gx = (((x - bounds.minX) / bounds.width) * (columns - 1)).toInt().coerceIn(0, columns - 1)
        val gy = (((y - bounds.minY) / bounds.depth) * (rows - 1)).toInt().coerceIn(0, rows - 1)
        val index = gy * columns + gx
        if (!grid[index].isFinite() || z > grid[index]) grid[index] = z
    }

    private fun fillNearest(values: FloatArray, columns: Int, rows: Int) {
        val queue = ArrayDeque<Int>()
        val distance = IntArray(values.size) { Int.MAX_VALUE }
        for (index in values.indices) {
            if (values[index].isFinite()) {
                queue.add(index)
                distance[index] = 0
            }
        }
        require(queue.isNotEmpty()) { "CurviSlicer could not sample the model surface" }
        val directions = intArrayOf(-1, 0, 1, 0, 0, -1, 0, 1)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val cx = current % columns
            val cy = current / columns
            var direction = 0
            while (direction < directions.size) {
                val nx = cx + directions[direction]
                val ny = cy + directions[direction + 1]
                direction += 2
                if (nx !in 0 until columns || ny !in 0 until rows) continue
                val next = ny * columns + nx
                if (distance[next] <= distance[current] + 1) continue
                distance[next] = distance[current] + 1
                values[next] = values[current]
                queue.add(next)
            }
        }
    }

    private fun gaussianBlur(source: FloatArray, columns: Int, rows: Int, sigma: Double): FloatArray {
        val radius = ceil(sigma * 2.5).toInt().coerceIn(1, 24)
        val weights = DoubleArray(radius * 2 + 1)
        var weightSum = 0.0
        for (offset in -radius..radius) {
            val weight = exp(-(offset * offset) / (2.0 * sigma * sigma))
            weights[offset + radius] = weight
            weightSum += weight
        }
        for (index in weights.indices) weights[index] /= weightSum

        val horizontal = FloatArray(source.size)
        for (y in 0 until rows) {
            for (x in 0 until columns) {
                var sum = 0.0
                for (offset in -radius..radius) {
                    val sx = (x + offset).coerceIn(0, columns - 1)
                    sum += source[y * columns + sx] * weights[offset + radius]
                }
                horizontal[y * columns + x] = sum.toFloat()
            }
        }
        val output = FloatArray(source.size)
        for (y in 0 until rows) {
            for (x in 0 until columns) {
                var sum = 0.0
                for (offset in -radius..radius) {
                    val sy = (y + offset).coerceIn(0, rows - 1)
                    sum += horizontal[sy * columns + x] * weights[offset + radius]
                }
                output[y * columns + x] = sum.toFloat()
            }
        }
        return output
    }

    private fun maximumGradient(
        field: FloatArray,
        columns: Int,
        rows: Int,
        cellX: Double,
        cellY: Double,
    ): Double {
        var maximum = 0.0
        for (y in 0 until rows) {
            for (x in 0 until columns) {
                val left = field[y * columns + max(0, x - 1)].toDouble()
                val right = field[y * columns + min(columns - 1, x + 1)].toDouble()
                val down = field[max(0, y - 1) * columns + x].toDouble()
                val up = field[min(rows - 1, y + 1) * columns + x].toDouble()
                val dx = (right - left) / (if (x in 1 until columns - 1) 2.0 * cellX else cellX)
                val dy = (up - down) / (if (y in 1 until rows - 1) 2.0 * cellY else cellY)
                maximum = max(maximum, hypot(dx, dy))
            }
        }
        return maximum
    }
}

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
                                    output.appendLine(rawLine)
                                    planarX = nextPlanarX
                                    planarY = nextPlanarY
                                    planarZ = nextPlanarZ
                                    planarE = nextPlanarE
                                    curvedX = nextPlanarX
                                    curvedY = nextPlanarY
                                    curvedZ = if (inPrintableLayers) field.unflattenZ(nextPlanarX, nextPlanarY, nextPlanarZ) else nextPlanarZ
                                    curvedE = if (command.has('E')) nextPlanarE else curvedE
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
                                    val horizontalDistance = hypot(to.x - from.x, to.y - from.y)
                                    val slope = if (horizontalDistance > EPSILON) abs(to.z - from.z) / horizontalDistance else 0.0
                                    maximumSlope = max(maximumSlope, Math.toDegrees(kotlin.math.atan(slope)))
                                    val requestedSpeed = (logicalFeed / 60.0).coerceAtLeast(0.0)
                                    val zSpeed = if (lengths[segment] > EPSILON) requestedSpeed * abs(to.z - from.z) / lengths[segment] else 0.0
                                    val safeSpeed = if (zSpeed > settings.maximumZSpeedMmPerSecond && zSpeed > EPSILON) {
                                        requestedSpeed * settings.maximumZSpeedMmPerSecond / zSpeed
                                    } else requestedSpeed
                                    maximumZSpeed = max(maximumZSpeed, min(zSpeed, settings.maximumZSpeedMmPerSecond))
                                    val safeFeed = safeSpeed * 60.0

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
