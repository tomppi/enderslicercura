package com.tomppi.enderslicer.engine

/**
 * Allocation-light parser for command forms Cura, Marlin and user G-code commonly emit.
 * It accepts mixed case, tabs, compact or spaced parameters, optional RepRap
 * line numbers, and trailing checksums. Scientific notation remains intentionally
 * unsupported because a compact `E` starts the extrusion parameter.
 */
internal object GcodeCommand {
    class Parsed internal constructor(
        val opcode: String,
        private val parameterMask: Int,
        private val parameterValues: DoubleArray,
    ) {
        fun value(letter: Char): Double? {
            val index = parameterIndex(letter)
            if (index < 0 || parameterMask and (1 shl index) == 0) return null
            return parameterValues[index]
        }

        fun has(letter: Char): Boolean {
            val index = parameterIndex(letter)
            return index >= 0 && parameterMask and (1 shl index) != 0
        }
    }

    fun parse(rawLine: String): Parsed? {
        val commandEnd = commandEnd(rawLine)
        var index = skipWhitespace(rawLine, 0, commandEnd)
        if (index >= commandEnd) return null

        // Optional RepRap line number. It is only consumed when N/n is followed
        // by at least one digit, matching the previous parser's behavior.
        if (rawLine[index] == 'N' || rawLine[index] == 'n') {
            var cursor = index + 1
            val digitsStart = cursor
            while (cursor < commandEnd && rawLine[cursor].isAsciiDigit()) cursor++
            if (cursor > digitsStart) index = skipWhitespace(rawLine, cursor, commandEnd)
        }
        if (index >= commandEnd || !rawLine[index].isAsciiLetter()) return null

        val opcodeStart = index
        index++
        if (index < commandEnd && (rawLine[index] == '+' || rawLine[index] == '-')) index++
        val opcodeDigitsStart = index
        while (index < commandEnd && rawLine[index].isAsciiDigit()) index++
        if (index == opcodeDigitsStart) return null
        if (index < commandEnd && rawLine[index] == '.') {
            index++
            while (index < commandEnd && rawLine[index].isAsciiDigit()) index++
        }
        val opcode = uppercaseAscii(rawLine, opcodeStart, index)

        var parameterMask = 0
        var parameterValues: DoubleArray? = null
        while (index < commandEnd) {
            val current = rawLine[index]
            if (!current.isAsciiLetter()) {
                index++
                continue
            }

            val letterIndex = parameterIndex(current)
            index = skipWhitespace(rawLine, index + 1, commandEnd)
            val numberStart = index
            if (index < commandEnd && (rawLine[index] == '+' || rawLine[index] == '-')) index++
            var digitCount = 0
            while (index < commandEnd && rawLine[index].isAsciiDigit()) {
                index++
                digitCount++
            }
            if (index < commandEnd && rawLine[index] == '.') {
                index++
                while (index < commandEnd && rawLine[index].isAsciiDigit()) {
                    index++
                    digitCount++
                }
            }
            if (digitCount == 0 || letterIndex < 0) {
                // Leave the current character available to become the next
                // compact parameter letter when no numeric value followed.
                if (index == numberStart) index++
                continue
            }

            val parsed = parseDecimal(rawLine, numberStart, index)
            if (parsed != null && parsed.isFinite()) {
                val values = parameterValues ?: DoubleArray(PARAMETER_COUNT).also {
                    parameterValues = it
                }
                values[letterIndex] = parsed
                parameterMask = parameterMask or (1 shl letterIndex)
            }
        }
        return Parsed(opcode, parameterMask, parameterValues ?: EMPTY_PARAMETERS)
    }

    private fun commandEnd(line: String): Int {
        var end = line.length
        val comment = line.indexOf(';')
        if (comment >= 0) end = minOf(end, comment)
        val checksum = line.indexOf('*')
        if (checksum >= 0) end = minOf(end, checksum)
        return end
    }

    private fun skipWhitespace(line: String, start: Int, end: Int): Int {
        var index = start
        while (index < end && line[index].isWhitespace()) index++
        return index
    }

    private fun uppercaseAscii(line: String, start: Int, end: Int): String {
        val output = CharArray(end - start)
        for (offset in output.indices) {
            val value = line[start + offset]
            output[offset] = if (value in 'a'..'z') value - ('a' - 'A') else value
        }
        return String(output)
    }

    private fun parseDecimal(line: String, start: Int, end: Int): Double? {
        if (start >= end) return null
        var index = start
        var negative = false
        when (line[index]) {
            '-' -> {
                negative = true
                index++
            }
            '+' -> index++
        }
        var value = 0.0
        var digits = 0
        while (index < end && line[index].isAsciiDigit()) {
            value = value * 10.0 + (line[index] - '0')
            index++
            digits++
        }
        if (index < end && line[index] == '.') {
            index++
            var factor = 0.1
            while (index < end && line[index].isAsciiDigit()) {
                value += (line[index] - '0') * factor
                factor *= 0.1
                index++
                digits++
            }
        }
        if (digits == 0 || index != end) return null
        return if (negative) -value else value
    }

    private fun parameterIndex(letter: Char): Int {
        val upper = if (letter in 'a'..'z') letter - ('a' - 'A') else letter
        return if (upper in 'A'..'Z') upper - 'A' else -1
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
    private fun Char.isAsciiLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'

    private const val PARAMETER_COUNT = 26
    private val EMPTY_PARAMETERS = DoubleArray(0)
}
