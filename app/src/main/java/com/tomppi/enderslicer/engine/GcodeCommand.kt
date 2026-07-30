package com.tomppi.enderslicer.engine

import java.util.Locale

/**
 * Small, allocation-bounded parser for the command forms Cura and user G-code
 * normally emit. It accepts mixed case, tabs, repeated whitespace, optional
 * RepRap line numbers and trailing checksums.
 */
internal object GcodeCommand {
    data class Parsed(
        val opcode: String,
        private val parameters: Map<Char, Double>,
    ) {
        fun value(letter: Char): Double? = parameters[letter.uppercaseChar()]
    }

    fun parse(rawLine: String): Parsed? {
        val command = rawLine.substringBefore(';').substringBefore('*').trim()
        if (command.isEmpty()) return null

        val tokens = command.split(WHITESPACE).filter(String::isNotEmpty)
        if (tokens.isEmpty()) return null
        var opcodeIndex = 0
        if (LINE_NUMBER.matches(tokens[0])) opcodeIndex++
        if (opcodeIndex >= tokens.size) return null

        val opcode = tokens[opcodeIndex].uppercase(Locale.US)
        if (!OPCODE.matches(opcode)) return null
        val parameters = linkedMapOf<Char, Double>()
        for (index in opcodeIndex + 1 until tokens.size) {
            val token = tokens[index]
            if (token.length < 2 || !token[0].isLetter()) continue
            token.substring(1).toDoubleOrNull()?.let { value ->
                if (value.isFinite()) parameters[token[0].uppercaseChar()] = value
            }
        }
        return Parsed(opcode, parameters)
    }

    private val WHITESPACE = Regex("\\s+")
    private val LINE_NUMBER = Regex("[Nn]\\d+")
    private val OPCODE = Regex("[A-Z][0-9]+(?:\\.[0-9]+)?")
}
