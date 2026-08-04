package com.tomppi.enderslicer.engine

/** One canonical, fail-closed command policy shared by all G-code safety consumers. */
internal object GcodeCommandPolicy {
    private val LINEAR_PARAMETERS = setOf('X', 'Y', 'Z', 'E', 'F')
    private val SAFE_NON_MOTION_G = setOf(4, 10, 11, 17, 21, 90, 91, 92, 94)
    private val TRUSTED_STARTUP_G = setOf(28, 29)
    private val SAFE_CUSTOM_EVENT_COMMANDS = setOf(
        "M104",
        "M109",
        "M140",
        "M190",
        "M106",
        "M107",
        "M117",
        "M118",
        "M300",
        "M400",
    )

    fun requireLinearParameters(command: GcodeCommand.Parsed) {
        require(command.hasOnlyParameters(LINEAR_PARAMETERS)) {
            "Unsupported ${command.opcode} parameters ${command.parameterLetters.sorted().joinToString("")}"
        }
    }

    fun requireCurviSupported(command: GcodeCommand.Parsed, inPrintableLayers: Boolean) {
        requireUnframed(command, "CurviSlicer")
        when (command.family) {
            'G' -> when (command.code) {
                0, 1 -> requireLinearParameters(command)
                2, 3 -> error(
                    "CurviSlicer cannot safely interpret G2/G3 arcs; disable arc fitting and custom arc purge paths",
                )
                in SAFE_NON_MOTION_G -> Unit
                in TRUSTED_STARTUP_G -> require(!inPrintableLayers) {
                    "CurviSlicer does not allow ${command.opcode} after printable motion has started"
                }
                else -> error(
                    "CurviSlicer cannot safely interpret ${command.opcode}; remove unsupported motion or coordinate commands",
                )
            }
            'T' -> require(command.code == 0) { "CurviSlicer does not support tool changes (${command.opcode})" }
        }
    }

    fun requirePublishedSafe(
        command: GcodeCommand.Parsed,
        currentLayer: Int?,
        lineNumber: Int,
    ) {
        requireUnframed(command, "Published G-code at line $lineNumber")
        when (command.family) {
            'G' -> when (command.code) {
                0, 1 -> requireLinearParameters(command)
                2, 3 -> error(
                    "Unsupported G2/G3 arc at line $lineNumber; the G-code was not made available for export",
                )
                in SAFE_NON_MOTION_G -> Unit
                in TRUSTED_STARTUP_G -> require(currentLayer == null) {
                    "Unsafe ${command.opcode} at line $lineNumber after printable layers began"
                }
                else -> error(
                    "Unsupported printer motion/state command ${command.opcode} at line $lineNumber; " +
                        "the G-code was not made available for export",
                )
            }
            'T' -> require(command.code == 0) {
                "Unsupported tool change ${command.opcode} at line $lineNumber"
            }
        }
    }

    fun requirePreviewSafe(command: GcodeCommand.Parsed, spatialMovesSeen: Int) {
        requireUnframed(command, "Nozzle Path")
        when (command.family) {
            'G' -> when (command.code) {
                0, 1 -> requireLinearParameters(command)
                2, 3 -> error(
                    "Nozzle Path cannot safely display G2/G3 arcs; re-slice without arc commands",
                )
                in SAFE_NON_MOTION_G -> Unit
                in TRUSTED_STARTUP_G -> require(spatialMovesSeen == 0) {
                    "Nozzle Path cannot safely display ${command.opcode} after spatial motion has started"
                }
                else -> error(
                    "Nozzle Path cannot safely display ${command.opcode}; re-slice without unsupported motion commands",
                )
            }
            'T' -> require(command.code == 0) {
                "Nozzle Path cannot safely display tool change ${command.opcode}"
            }
        }
    }

    fun requireSafeCustomEvent(command: GcodeCommand.Parsed) {
        requireUnframed(command, "Custom G-code")
        require(command.opcode in SAFE_CUSTOM_EVENT_COMMANDS) {
            "Custom G-code command ${command.opcode} is not in the state-neutral safety allowlist"
        }
    }

    fun speedFactor(command: GcodeCommand.Parsed): Double? {
        if (command.opcode != "M220") return null
        val percent = requireNotNull(command.value('S')) { "M220 requires an S percentage" }
        require(command.hasOnlyParameters(setOf('S'))) { "M220 contains unsupported parameters" }
        require(percent.isFinite() && percent in 1.0..999.0) { "M220 speed factor is outside 1..999%" }
        return percent / 100.0
    }

    private fun requireUnframed(command: GcodeCommand.Parsed, consumer: String) {
        require(!command.hasLineNumber && !command.hasChecksum) {
            "$consumer does not accept line-number or checksum framing; re-slice unframed G-code"
        }
    }
}
