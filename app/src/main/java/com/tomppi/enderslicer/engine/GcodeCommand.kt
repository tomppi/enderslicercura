package com.tomppi.enderslicer.engine

import java.util.Locale

/**
 * Small parser for command forms Cura, Marlin and user G-code commonly emit.
 * It accepts mixed case, tabs, repeated or absent parameter whitespace,
 * optional RepRap line numbers, and trailing checksums.
 */
internal object GcodeCommand {
    data class Parsed(
        val opcode: String,
        private val parameters: Map<Char, Double>,
    ) {
        fun value(letter: Char): Double? = parameters[letter.uppercaseChar()]
    }

    fun parse(rawLine: String): Parsed? {
        var command = rawLine.substringBefore(';').substringBefore('*').trim()
        if (command.isEmpty()) return null

        LINE_NUMBER.find(command)?.takeIf { it.range.first == 0 }?.let { match ->
            command = command.substring(match.range.last + 1).trimStart()
        }
        val opcodeMatch = OPCODE.find(command)?.takeIf { it.range.first == 0 } ?: return null
        val opcode = opcodeMatch.value.uppercase(Locale.US)
        val parameters = linkedMapOf<Char, Double>()
        val remainder = command.substring(opcodeMatch.range.last + 1)
        PARAMETER.findAll(remainder).forEach { match ->
            val letter = match.groupValues[1].single().uppercaseChar()
            val value = match.groupValues[2].toDoubleOrNull()
            if (value != null && value.isFinite()) parameters[letter] = value
        }
        return Parsed(opcode, parameters)
    }

    private val LINE_NUMBER = Regex("^[Nn]\\d+\\s*")
    private val OPCODE = Regex("^[A-Za-z][+-]?\\d+(?:\\.\\d+)?")
    // Scientific notation is intentionally excluded here: in compact G-code,
    // E starts the extrusion parameter (for example Y-2E1.25), not an exponent.
    private val PARAMETER = Regex("([A-Za-z])\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))")
}
