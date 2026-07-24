package com.tomppi.enderslicer.engine

import java.io.File
import kotlin.math.max

/** Compact post-slice toolpath data for the interactive layer viewer. */
data class GcodeLayerPreview(
    val layers: List<Layer>,
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
    val minSpeedMmPerSecond: Float,
    val maxSpeedMmPerSecond: Float,
    val minLayerHeightMm: Float,
    val maxLayerHeightMm: Float,
    val totalSegmentCount: Int,
    val truncated: Boolean,
) {
    data class Layer(
        val number: Int,
        val z: Float,
        val height: Float,
        /** Packed x1, y1, x2, y2, speed-mm/s, feature-code values. */
        val segments: FloatArray,
        val supportSegmentCount: Int,
        val supportInterfaceSegmentCount: Int,
    ) {
        val segmentCount: Int get() = segments.size / VALUES_PER_SEGMENT
    }

    enum class Feature(val code: Int) {
        MODEL(0),
        SUPPORT(1),
        SUPPORT_INTERFACE(2),
        ADHESION(3),
        OTHER(4),
        ;

        companion object {
            fun fromCode(code: Int): Feature = entries.firstOrNull { it.code == code } ?: OTHER
        }
    }

    companion object {
        const val VALUES_PER_SEGMENT = 6
    }
}

object GcodeLayerPreviewParser {
    private const val MAX_SEGMENTS = 800_000

    fun parse(file: File): GcodeLayerPreview = parse(file, MAX_SEGMENTS)

    internal fun parse(file: File, maxSegments: Int): GcodeLayerPreview {
        require(file.isFile && file.length() > 0L) { "Generated G-code is not available for layer preview" }
        require(maxSegments > 0) { "Layer preview segment limit must be positive" }

        val sourceSegmentCount = countPrintableSegments(file)
        require(sourceSegmentCount > 0) { "No printable layer paths were found in the G-code" }

        val layers = mutableListOf<GcodeLayerPreview.Layer>()
        var currentLayerNumber: Int? = null
        var currentLayerZ = 0f
        var currentSegments = FloatAccumulator()
        var currentSupportCount = 0
        var currentSupportInterfaceCount = 0
        var feature = GcodeLayerPreview.Feature.OTHER

        var absolutePosition = true
        var absoluteExtrusion = true
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var e = 0.0
        var feedRateMmPerMinute = 0.0

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var minSpeed = Float.POSITIVE_INFINITY
        var maxSpeed = Float.NEGATIVE_INFINITY
        var sourceSegmentIndex = 0
        var retainedSegments = 0
        val truncated = sourceSegmentCount > maxSegments

        fun finishLayer() {
            val number = currentLayerNumber ?: return
            layers += GcodeLayerPreview.Layer(
                number = number,
                z = currentLayerZ,
                height = 0f,
                segments = currentSegments.toArray(),
                supportSegmentCount = currentSupportCount,
                supportInterfaceSegmentCount = currentSupportInterfaceCount,
            )
            currentSegments = FloatAccumulator()
            currentSupportCount = 0
            currentSupportInterfaceCount = 0
        }

        file.bufferedReader().useLines { lines ->
            lines.forEach { rawLine ->
                if (rawLine.startsWith(";LAYER:")) {
                    finishLayer()
                    currentLayerNumber = rawLine.substringAfter(':').trim().toIntOrNull()
                    currentLayerZ = z.toFloat()
                    feature = GcodeLayerPreview.Feature.OTHER
                    return@forEach
                }
                if (rawLine.startsWith(";TYPE:")) {
                    feature = featureFromType(rawLine.substringAfter(':').trim())
                    return@forEach
                }

                val command = rawLine.substringBefore(';').trim()
                if (command.isEmpty()) return@forEach
                val opcode = command.substringBefore(' ').uppercase()
                when (opcode) {
                    "G90" -> absolutePosition = true
                    "G91" -> absolutePosition = false
                    "M82" -> absoluteExtrusion = true
                    "M83" -> absoluteExtrusion = false
                    "G92" -> {
                        value(command, 'X')?.let { x = it }
                        value(command, 'Y')?.let { y = it }
                        value(command, 'Z')?.let { z = it }
                        value(command, 'E')?.let { e = it }
                    }
                    "G0", "G1" -> {
                        val startX = x
                        val startY = y
                        val nextX = value(command, 'X')?.let { if (absolutePosition) it else x + it } ?: x
                        val nextY = value(command, 'Y')?.let { if (absolutePosition) it else y + it } ?: y
                        val nextZ = value(command, 'Z')?.let { if (absolutePosition) it else z + it } ?: z
                        val requestedE = value(command, 'E')
                        val nextE = requestedE?.let { if (absoluteExtrusion) it else e + it } ?: e
                        val deltaE = nextE - e
                        value(command, 'F')?.let { feedRateMmPerMinute = it }

                        x = nextX
                        y = nextY
                        z = nextZ
                        e = nextE

                        if (currentLayerNumber == null || deltaE <= 0.0) return@forEach
                        if (startX == nextX && startY == nextY) return@forEach

                        val speed = max(feedRateMmPerMinute / 60.0, 0.0).toFloat()
                        currentLayerZ = nextZ.toFloat()
                        minX = minOf(minX, startX.toFloat(), nextX.toFloat())
                        minY = minOf(minY, startY.toFloat(), nextY.toFloat())
                        maxX = maxOf(maxX, startX.toFloat(), nextX.toFloat())
                        maxY = maxOf(maxY, startY.toFloat(), nextY.toFloat())
                        if (speed > 0f) {
                            minSpeed = minOf(minSpeed, speed)
                            maxSpeed = maxOf(maxSpeed, speed)
                        }

                        val retain = shouldRetainSegment(
                            sourceIndex = sourceSegmentIndex,
                            sourceCount = sourceSegmentCount,
                            retainedLimit = maxSegments,
                        )
                        sourceSegmentIndex++
                        if (!retain) return@forEach

                        currentSegments.add(
                            startX.toFloat(),
                            startY.toFloat(),
                            nextX.toFloat(),
                            nextY.toFloat(),
                            speed,
                            feature.code.toFloat(),
                        )
                        when (feature) {
                            GcodeLayerPreview.Feature.SUPPORT -> currentSupportCount++
                            GcodeLayerPreview.Feature.SUPPORT_INTERFACE -> currentSupportInterfaceCount++
                            else -> Unit
                        }
                        retainedSegments++
                    }
                }
            }
        }
        finishLayer()

        require(layers.isNotEmpty() && retainedSegments > 0) { "No printable layer paths were found in the G-code" }
        val layersWithHeights = layers.mapIndexed { index, layer ->
            val previousZ = if (index == 0) 0f else layers[index - 1].z
            layer.copy(height = (layer.z - previousZ).coerceAtLeast(0f))
        }
        val positiveLayerHeights = layersWithHeights.map(GcodeLayerPreview.Layer::height).filter { it > 0f }
        val minimumLayerHeight = positiveLayerHeights.minOrNull() ?: 0f
        val maximumLayerHeight = positiveLayerHeights.maxOrNull() ?: minimumLayerHeight
        if (!minSpeed.isFinite()) minSpeed = 0f
        if (!maxSpeed.isFinite()) maxSpeed = minSpeed
        if (maxSpeed < minSpeed) maxSpeed = minSpeed

        return GcodeLayerPreview(
            layers = layersWithHeights,
            minX = minX,
            minY = minY,
            maxX = maxX,
            maxY = maxY,
            minSpeedMmPerSecond = minSpeed,
            maxSpeedMmPerSecond = maxSpeed,
            minLayerHeightMm = minimumLayerHeight,
            maxLayerHeightMm = maximumLayerHeight,
            totalSegmentCount = retainedSegments,
            truncated = truncated,
        )
    }

    private fun countPrintableSegments(file: File): Int {
        var currentLayerNumber: Int? = null
        var absolutePosition = true
        var absoluteExtrusion = true
        var x = 0.0
        var y = 0.0
        var e = 0.0
        var count = 0

        file.bufferedReader().useLines { lines ->
            lines.forEach { rawLine ->
                if (rawLine.startsWith(";LAYER:")) {
                    currentLayerNumber = rawLine.substringAfter(':').trim().toIntOrNull()
                    return@forEach
                }

                val command = rawLine.substringBefore(';').trim()
                if (command.isEmpty()) return@forEach
                val opcode = command.substringBefore(' ').uppercase()
                when (opcode) {
                    "G90" -> absolutePosition = true
                    "G91" -> absolutePosition = false
                    "M82" -> absoluteExtrusion = true
                    "M83" -> absoluteExtrusion = false
                    "G92" -> {
                        value(command, 'X')?.let { x = it }
                        value(command, 'Y')?.let { y = it }
                        value(command, 'E')?.let { e = it }
                    }
                    "G0", "G1" -> {
                        val startX = x
                        val startY = y
                        val nextX = value(command, 'X')?.let { if (absolutePosition) it else x + it } ?: x
                        val nextY = value(command, 'Y')?.let { if (absolutePosition) it else y + it } ?: y
                        val requestedE = value(command, 'E')
                        val nextE = requestedE?.let { if (absoluteExtrusion) it else e + it } ?: e
                        val deltaE = nextE - e

                        x = nextX
                        y = nextY
                        e = nextE

                        if (currentLayerNumber != null && deltaE > 0.0 && (startX != nextX || startY != nextY)) {
                            count++
                        }
                    }
                }
            }
        }
        return count
    }

    private fun shouldRetainSegment(
        sourceIndex: Int,
        sourceCount: Int,
        retainedLimit: Int,
    ): Boolean {
        if (sourceCount <= retainedLimit) return true
        val before = sourceIndex.toLong() * retainedLimit / sourceCount
        val after = (sourceIndex.toLong() + 1L) * retainedLimit / sourceCount
        return after > before
    }

    private fun featureFromType(raw: String): GcodeLayerPreview.Feature {
        val value = raw.uppercase()
        return when {
            value.contains("SUPPORT-INTERFACE") || value.contains("SUPPORT_INTERFACE") -> {
                GcodeLayerPreview.Feature.SUPPORT_INTERFACE
            }
            value.contains("SUPPORT") -> GcodeLayerPreview.Feature.SUPPORT
            value.contains("SKIRT") || value.contains("BRIM") || value.contains("RAFT") -> {
                GcodeLayerPreview.Feature.ADHESION
            }
            value.contains("WALL") || value.contains("SKIN") || value.contains("FILL") ||
                value.contains("INFILL") || value.contains("BRIDGE") -> GcodeLayerPreview.Feature.MODEL
            else -> GcodeLayerPreview.Feature.OTHER
        }
    }

    private fun value(command: String, letter: Char): Double? {
        val target = letter.uppercaseChar()
        var index = 0
        while (index < command.length) {
            while (index < command.length && command[index].isWhitespace()) index++
            if (index >= command.length) break
            val tokenStart = index
            while (index < command.length && !command[index].isWhitespace()) index++
            if (command[tokenStart].uppercaseChar() == target && tokenStart + 1 < index) {
                return command.substring(tokenStart + 1, index).toDoubleOrNull()
            }
        }
        return null
    }

    private class FloatAccumulator(initialCapacity: Int = 6 * 2048) {
        private var values = FloatArray(initialCapacity)
        var size: Int = 0
            private set

        fun add(vararg additions: Float) {
            ensure(size + additions.size)
            additions.copyInto(values, destinationOffset = size)
            size += additions.size
        }

        fun toArray(): FloatArray = values.copyOf(size)

        private fun ensure(required: Int) {
            if (required <= values.size) return
            var capacity = values.size
            while (capacity < required) capacity *= 2
            values = values.copyOf(capacity)
        }
    }
}
