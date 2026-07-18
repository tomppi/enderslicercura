package com.tomppi.enderslicer.engine

import java.io.File
import kotlin.math.ceil

object GcodeSanitizer {
    data class Summary(
        val layerCount: Int,
        val estimatedSeconds: Int?,
        val filamentMillimeters: Double,
        val minX: Double?,
        val minY: Double?,
        val minZ: Double?,
        val maxX: Double?,
        val maxY: Double?,
        val maxZ: Double?,
    )

    class UnsafeGcodeException(message: String) : Exception(message)

    fun validateAndRepair(file: File): Summary {
        val original = file.readLines()
        require(original.isNotEmpty()) { "Generated G-code is empty" }

        val layerCount = original.firstNotNullOfOrNull { line ->
            line.takeIf { it.startsWith(";LAYER_COUNT:") }
                ?.substringAfter(':')
                ?.trim()
                ?.toIntOrNull()
        } ?: 0

        var currentLayer: Int? = null
        var lastElapsed: Double? = null
        var absoluteExtrusion = true
        var currentE = 0.0
        var filament = 0.0
        var absolutePosition = true
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var inModelMesh = false
        var minX: Double? = null
        var minY: Double? = null
        var minZ: Double? = null
        var maxX: Double? = null
        var maxY: Double? = null
        var maxZ: Double? = null

        original.forEachIndexed { index, line ->
            when {
                line.startsWith(";LAYER:") -> {
                    currentLayer = line.substringAfter(':').trim().toIntOrNull()
                }

                line.startsWith(";TIME_ELAPSED:") -> {
                    lastElapsed = line.substringAfter(':').trim().toDoubleOrNull() ?: lastElapsed
                }

                line.startsWith(";MESH:") -> {
                    val meshName = line.substringAfter(':').trim()
                    inModelMesh = meshName.isNotEmpty() && !meshName.equals("NONMESH", ignoreCase = true)
                }
            }

            val command = line.substringBefore(';').trim()
            if (command.isEmpty()) return@forEachIndexed
            val opcode = command.substringBefore(' ').uppercase()

            if (opcode == "M82") absoluteExtrusion = true
            if (opcode == "M83") absoluteExtrusion = false
            if (opcode == "G90") absolutePosition = true
            if (opcode == "G91") absolutePosition = false

            if (opcode == "G92") {
                value(command, 'E')?.let { currentE = it }
                value(command, 'X')?.let { x = it }
                value(command, 'Y')?.let { y = it }
                value(command, 'Z')?.let { z = it }
            }

            if (opcode == "G0" || opcode == "G1") {
                value(command, 'X')?.let { x = if (absolutePosition) it else x + it }
                value(command, 'Y')?.let { y = if (absolutePosition) it else y + it }
                value(command, 'Z')?.let { z = if (absolutePosition) it else z + it }
                value(command, 'E')?.let { requested ->
                    val nextE = if (absoluteExtrusion) requested else currentE + requested
                    val delta = nextE - currentE
                    if (inModelMesh && currentLayer != null && delta > 0.0) filament += delta
                    currentE = nextE
                }

                if (inModelMesh) {
                    minX = minX?.let { minOf(it, x) } ?: x
                    minY = minY?.let { minOf(it, y) } ?: y
                    minZ = minZ?.let { minOf(it, z) } ?: z
                    maxX = maxX?.let { maxOf(it, x) } ?: x
                    maxY = maxY?.let { maxOf(it, y) } ?: y
                    maxZ = maxZ?.let { maxOf(it, z) } ?: z
                }
            }

            if (opcode == "M104" || opcode == "M109") {
                val target = value(command, 'S') ?: return@forEachIndexed
                val layer = currentLayer
                val activePrintLayer = layer != null && (layerCount <= 0 || layer < layerCount - 1)
                if (activePrintLayer && target in 0.0..<MINIMUM_ACTIVE_NOZZLE_C) {
                    throw UnsafeGcodeException(
                        "Unsafe nozzle target ${format(target)} C at layer $layer (line ${index + 1}). " +
                            "The G-code was not made available for export.",
                    )
                }
            }
        }

        val estimatedSeconds = lastElapsed?.let { ceil(it).toInt() }
        val resolvedMinX = minX
        val resolvedMinY = minY
        val resolvedMinZ = minZ
        val resolvedMaxX = maxX
        val resolvedMaxY = maxY
        val resolvedMaxZ = maxZ
        val repaired = original.map { line ->
            when {
                line.startsWith(";TIME:") && estimatedSeconds != null -> ";TIME:$estimatedSeconds"
                line.startsWith(";Filament used:") -> ";Filament used: ${format(filament / 1000.0)}m"
                line.startsWith(";MINX:") && resolvedMinX != null -> ";MINX:${format(resolvedMinX)}"
                line.startsWith(";MINY:") && resolvedMinY != null -> ";MINY:${format(resolvedMinY)}"
                line.startsWith(";MINZ:") && resolvedMinZ != null -> ";MINZ:${format(resolvedMinZ)}"
                line.startsWith(";MAXX:") && resolvedMaxX != null -> ";MAXX:${format(resolvedMaxX)}"
                line.startsWith(";MAXY:") && resolvedMaxY != null -> ";MAXY:${format(resolvedMaxY)}"
                line.startsWith(";MAXZ:") && resolvedMaxZ != null -> ";MAXZ:${format(resolvedMaxZ)}"
                else -> line
            }
        }

        val temporary = File(file.parentFile, "${file.name}.validated")
        temporary.bufferedWriter().use { writer -> repaired.forEach { line -> writer.appendLine(line) } }
        check(temporary.length() > 0L) { "Validated G-code output is empty" }
        check(temporary.renameTo(file) || temporary.copyTo(file, overwrite = true).let { temporary.delete(); true }) {
            "Unable to replace generated G-code with the validated output"
        }

        return Summary(
            layerCount = layerCount,
            estimatedSeconds = estimatedSeconds,
            filamentMillimeters = filament,
            minX = minX,
            minY = minY,
            minZ = minZ,
            maxX = maxX,
            maxY = maxY,
            maxZ = maxZ,
        )
    }

    private fun value(command: String, letter: Char): Double? {
        val pattern = Regex("(?:^|\\s)${letter.uppercaseChar()}(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)")
        return pattern.find(command)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun format(value: Double): String = "%.5f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')

    private const val MINIMUM_ACTIVE_NOZZLE_C = 150.0
}
