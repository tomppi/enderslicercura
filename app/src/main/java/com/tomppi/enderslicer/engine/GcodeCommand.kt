package com.tomppi.enderslicer.engine

/**
 * Small parser for command forms Cura, Marlin and user G-code commonly emit.
 * Command identity is canonical: numeric aliases such as G01, g001 and M083
 * become G1 and M83 before any safety decision is made.
 */
internal object GcodeCommand {
    data class Identity(
        val family: Char,
        val code: Int,
    ) {
        init {
            require(family in 'A'..'Z') { "G-code command family must be uppercase ASCII" }
            require(code >= 0) { "G-code command number cannot be negative" }
        }

        val opcode: String get() = "$family$code"
    }

    data class Parsed(
        val identity: Identity,
        private val parameters: Map<Char, Double>,
        val hasLineNumber: Boolean,
        val hasChecksum: Boolean,
    ) {
        val opcode: String get() = identity.opcode
        val family: Char get() = identity.family
        val code: Int get() = identity.code
        val parameterLetters: Set<Char> get() = parameters.keys

        fun value(letter: Char): Double? = parameters[letter.uppercaseChar()]

        fun has(letter: Char): Boolean = parameters.containsKey(letter.uppercaseChar())

        fun hasOnlyParameters(allowed: Set<Char>): Boolean = parameters.keys.all(allowed::contains)
    }

    fun parse(rawLine: String): Parsed? {
        var command = rawLine.substringBefore(';').trim()
        if (command.isEmpty()) return null

        val hasChecksum = '*' in command
        command = command.substringBefore('*').trimEnd()
        val lineNumber = LINE_NUMBER.find(command)?.takeIf { it.range.first == 0 }
        if (lineNumber != null) {
            command = command.substring(lineNumber.range.last + 1).trimStart()
        }

        val opcodeMatch = OPCODE.find(command)?.takeIf { it.range.first == 0 } ?: return null
        val family = opcodeMatch.groupValues[1].single().uppercaseChar()
        val numericToken = opcodeMatch.groupValues[2]
        if ('.' in numericToken || numericToken.startsWith('-')) return null
        val numericValue = numericToken.removePrefix("+").toLongOrNull() ?: return null
        if (numericValue > Int.MAX_VALUE) return null

        val parameters = linkedMapOf<Char, Double>()
        val remainder = command.substring(opcodeMatch.range.last + 1)
        PARAMETER.findAll(remainder).forEach { match ->
            val letter = match.groupValues[1].single().uppercaseChar()
            val value = match.groupValues[2].toDoubleOrNull()
            if (value != null && value.isFinite()) parameters[letter] = value
        }
        return Parsed(
            identity = Identity(family, numericValue.toInt()),
            parameters = parameters,
            hasLineNumber = lineNumber != null,
            hasChecksum = hasChecksum,
        )
    }

    private val LINE_NUMBER = Regex("^[Nn]\\d+\\s*")
    private val OPCODE = Regex("^([A-Za-z])([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))")
    // Scientific notation is intentionally excluded here: in compact G-code,
    // E starts the extrusion parameter (for example Y-2E1.25), not an exponent.
    private val PARAMETER = Regex("([A-Za-z])\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))")
}
