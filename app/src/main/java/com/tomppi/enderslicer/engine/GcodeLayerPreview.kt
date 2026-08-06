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
        /** Source paths before preview sampling. This remains authoritative for event placement. */
        val sourceSegmentCount: Int = segments.size / VALUES_PER_SEGMENT,
    ) {
        val segmentCount: Int get() = segments.size / VALUES_PER_SEGMENT
        val hasPrintablePaths: Boolean get() = sourceSegmentCount > 0
    }

    enum class Feature(val code: Int) {
        MODEL(0),
        SUPPORT(1),
        SUPPORT_INTERFACE(2),
        ADHESION(3),
        OTHER(4),
        ARC_OVERHANG(5),
        WAVE_OVERHANG(6),
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
    private val RARE_FEATURE_PRIORITY = listOf(
        GcodeLayerPreview.Feature.WAVE_OVERHANG,
        GcodeLayerPreview.Feature.ARC_OVERHANG,
        GcodeLayerPreview.Feature.SUPPORT_INTERFACE,
        GcodeLayerPreview.Feature.SUPPORT,
        GcodeLayerPreview.Feature.ADHESION,
    )

    fun parse(file: File): GcodeLayerPreview = parse(file, MAX_SEGMENTS)

    internal fun parse(file: File, maxSegments: Int): GcodeLayerPreview {
        require(file.isFile && file.length() > 0L) { "Generated G-code is not available for layer preview" }
        require(maxSegments > 0) { "Layer preview segment limit must be positive" }

        val source = scanSource(file)
        require(source.totalSegmentCount > 0) { "No printable layer paths were found in the G-code" }
        val reservedIndices = reserveRareFeatureSamples(source, maxSegments)
        val remainingLimit = (maxSegments - reservedIndices.size).coerceAtLeast(0)
        val remainingSourceCount = source.totalSegmentCount - reservedIndices.size

        val layers = mutableListOf<GcodeLayerPreview.Layer>()
        var currentLayerNumber: Int? = null
        var currentLayerZ = 0f
        var currentSegments = FloatAccumulator()
        var currentSupportCount = 0
        var currentSupportInterfaceCount = 0
        var feature = GcodeLayerPreview.Feature.OTHER

        val modalState = GcodeModalState()
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var e = 0.0
        var feedRateMmPerMinute = 0.0
        var speedFactor = 1.0

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var minSpeed = Float.POSITIVE_INFINITY
        var maxSpeed = Float.NEGATIVE_INFINITY
        var sourceSegmentIndex = 0
        var nonReservedSourceIndex = 0
        var retainedSegments = 0
        val truncated = source.totalSegmentCount > maxSegments

        fun finishLayer() {
            val number = currentLayerNumber ?: return
            layers += GcodeLayerPreview.Layer(
                number = number,
                z = currentLayerZ,
                height = 0f,
                segments = currentSegments.toArray(),
                supportSegmentCount = currentSupportCount,
                supportInterfaceSegmentCount = currentSupportInterfaceCount,
                sourceSegmentCount = source.layerSegmentCounts[number] ?: 0,
            )
            currentSegments = FloatAccumulator()
            currentSupportCount = 0
            currentSupportInterfaceCount = 0
        }

        file.bufferedReader().useLines { lines ->
            lines.forEach { rawLine ->
                val line = rawLine.trimStart()
                if (line.startsWith(";LAYER:")) {
                    finishLayer()
                    currentLayerNumber = line.substringAfter(':').trim().toIntOrNull()
                    currentLayerZ = z.toFloat()
                    feature = GcodeLayerPreview.Feature.OTHER
                    return@forEach
                }
                if (line.startsWith(";TYPE:")) {
                    feature = featureFromType(line.substringAfter(':').trim())
                    return@forEach
                }

                val command = GcodeCommand.parse(rawLine) ?: return@forEach
                GcodeCommandPolicy.speedFactor(command)?.let { factor ->
                    speedFactor = factor
                    return@forEach
                }
                if (modalState.apply(command)) return@forEach
                when (command.opcode) {
                    "G92" -> {
                        command.value('X')?.let { x = it }
                        command.value('Y')?.let { y = it }
                        command.value('Z')?.let { z = it }
                        command.value('E')?.let { e = it }
                    }
                    "G0", "G1" -> {
                        val startX = x
                        val startY = y
                        val nextX = modalState.position(x, command.value('X'))
                        val nextY = modalState.position(y, command.value('Y'))
                        val nextZ = modalState.position(z, command.value('Z'))
                        val nextE = modalState.extrusion(e, command.value('E'))
                        val deltaE = nextE - e
                        command.value('F')?.let { feedRateMmPerMinute = it }

                        x = nextX
                        y = nextY
                        z = nextZ
                        e = nextE

                        if (currentLayerNumber == null || deltaE <= 0.0) return@forEach
                        if (startX == nextX && startY == nextY) return@forEach

                        val speed = max(feedRateMmPerMinute / 60.0 * speedFactor, 0.0).toFloat()
                        currentLayerZ = nextZ.toFloat()
                        minX = minOf(minX, startX.toFloat(), nextX.toFloat())
                        minY = minOf(minY, startY.toFloat(), nextY.toFloat())
                        maxX = maxOf(maxX, startX.toFloat(), nextX.toFloat())
                        maxY = maxOf(maxY, startY.toFloat(), nextY.toFloat())
                        if (speed > 0f) {
                            minSpeed = minOf(minSpeed, speed)
                            maxSpeed = maxOf(maxSpeed, speed)
                        }

                        val currentSourceIndex = sourceSegmentIndex++
                        val retain = if (currentSourceIndex in reservedIndices) {
                            true
                        } else {
                            shouldRetainSegment(
                                sourceIndex = nonReservedSourceIndex++,
                                sourceCount = remainingSourceCount,
                                retainedLimit = remainingLimit,
                            )
                        }
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

    private fun scanSource(file: File): SourceScan {
        var currentLayerNumber: Int? = null
        var feature = GcodeLayerPreview.Feature.OTHER
        val modalState = GcodeModalState()
        var x = 0.0
        var y = 0.0
        var e = 0.0
        var count = 0
        val layerCounts = linkedMapOf<Int, Int>()
        val firstFeatureIndices = IntArray(GcodeLayerPreview.Feature.entries.size) { -1 }

        file.bufferedReader().useLines { lines ->
            lines.forEach { rawLine ->
                val line = rawLine.trimStart()
                if (line.startsWith(";LAYER:")) {
                    currentLayerNumber = line.substringAfter(':').trim().toIntOrNull()
                    feature = GcodeLayerPreview.Feature.OTHER
                    return@forEach
                }
                if (line.startsWith(";TYPE:")) {
                    feature = featureFromType(line.substringAfter(':').trim())
                    return@forEach
                }

                val command = GcodeCommand.parse(rawLine) ?: return@forEach
                if (modalState.apply(command)) return@forEach
                when (command.opcode) {
                    "G92" -> {
                        command.value('X')?.let { x = it }
                        command.value('Y')?.let { y = it }
                        command.value('E')?.let { e = it }
                    }
                    "G0", "G1" -> {
                        val startX = x
                        val startY = y
                        val nextX = modalState.position(x, command.value('X'))
                        val nextY = modalState.position(y, command.value('Y'))
                        val nextE = modalState.extrusion(e, command.value('E'))
                        val deltaE = nextE - e

                        x = nextX
                        y = nextY
                        e = nextE

                        val layerNumber = currentLayerNumber
                        if (layerNumber != null && deltaE > 0.0 && (startX != nextX || startY != nextY)) {
                            if (firstFeatureIndices[feature.ordinal] < 0) {
                                firstFeatureIndices[feature.ordinal] = count
                            }
                            layerCounts[layerNumber] = (layerCounts[layerNumber] ?: 0) + 1
                            count++
                        }
                    }
                }
            }
        }
        return SourceScan(count, layerCounts, firstFeatureIndices)
    }

    private fun reserveRareFeatureSamples(source: SourceScan, maxSegments: Int): Set<Int> {
        val selected = linkedSetOf<Int>()
        for (feature in RARE_FEATURE_PRIORITY) {
            if (selected.size >= maxSegments) break
            val sourceIndex = source.firstFeatureIndices[feature.ordinal]
            if (sourceIndex >= 0) selected += sourceIndex
        }
        return selected
    }

    private fun shouldRetainSegment(
        sourceIndex: Int,
        sourceCount: Int,
        retainedLimit: Int,
    ): Boolean {
        if (retainedLimit <= 0 || sourceCount <= 0) return false
        if (sourceCount <= retainedLimit) return true
        val before = sourceIndex.toLong() * retainedLimit / sourceCount
        val after = (sourceIndex.toLong() + 1L) * retainedLimit / sourceCount
        return after > before
    }

    private fun featureFromType(raw: String): GcodeLayerPreview.Feature {
        val value = raw.uppercase()
        return when {
            value.contains("ARC-OVERHANG") || value.contains("ARC_OVERHANG") -> GcodeLayerPreview.Feature.ARC_OVERHANG
            value.contains("WAVE-OVERHANG") || value.contains("WAVE_OVERHANG") -> GcodeLayerPreview.Feature.WAVE_OVERHANG
            value.contains("SUPPORT-INTERFACE") || value.contains("SUPPORT_INTERFACE") -> GcodeLayerPreview.Feature.SUPPORT_INTERFACE
            value.contains("SUPPORT") -> GcodeLayerPreview.Feature.SUPPORT
            value.contains("SKIRT") || value.contains("BRIM") || value.contains("RAFT") -> GcodeLayerPreview.Feature.ADHESION
            value.contains("WALL") || value.contains("SKIN") || value.contains("FILL") ||
                value.contains("INFILL") || value.contains("BRIDGE") -> GcodeLayerPreview.Feature.MODEL
            else -> GcodeLayerPreview.Feature.OTHER
        }
    }

    private data class SourceScan(
        val totalSegmentCount: Int,
        val layerSegmentCounts: Map<Int, Int>,
        val firstFeatureIndices: IntArray,
    )

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
