package com.tomppi.enderslicer.engine

/**
 * Marlin-style motion and extrusion modes shared by every G-code interpreter.
 * G90/G91 select both XYZ and E mode; M82/M83 then override E independently
 * until a later G90/G91 resets it again.
 */
internal class GcodeModalState {
    var absolutePosition: Boolean = true
        private set

    var absoluteExtrusion: Boolean = true
        private set

    /** Returns true when [command] was a handled modal-state command. */
    fun apply(command: GcodeCommand.Parsed): Boolean = when (command.opcode) {
        "G90" -> {
            absolutePosition = true
            absoluteExtrusion = true
            true
        }
        "G91" -> {
            absolutePosition = false
            absoluteExtrusion = false
            true
        }
        "M82" -> {
            absoluteExtrusion = true
            true
        }
        "M83" -> {
            absoluteExtrusion = false
            true
        }
        else -> false
    }

    fun position(current: Double, requested: Double?): Double = requested?.let {
        if (absolutePosition) it else current + it
    } ?: current

    fun extrusion(current: Double, requested: Double?): Double = requested?.let {
        if (absoluteExtrusion) it else current + it
    } ?: current
}
