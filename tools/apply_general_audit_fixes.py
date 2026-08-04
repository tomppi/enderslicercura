from pathlib import Path
import re

ROOT = Path('.')

def read(path):
    return (ROOT / path).read_text()

def write(path, content):
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content)

def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{path}: expected one match, found {count}: {old[:160]!r}')
    write(path, text.replace(old, new, 1))

def regex_once(path, pattern, replacement, flags=0):
    text = read(path)
    new, count = re.subn(pattern, lambda _match: replacement, text, count=1, flags=flags)
    if count != 1:
        raise RuntimeError(f'{path}: expected one regex match, found {count}: {pattern[:160]!r}')
    write(path, new)

# GENERAL-001: exhaustive fail-closed publication policy, including M-family and text commands.
write('app/src/main/java/com/tomppi/enderslicer/engine/GcodeCommandPolicy.kt', r'''package com.tomppi.enderslicer.engine

import java.util.Locale

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
            'M' -> requirePublishedM(command, lineNumber = null, consumer = "CurviSlicer")
            'T' -> require(command.code == 0) { "CurviSlicer does not support tool changes (${command.opcode})" }
            else -> error("CurviSlicer cannot safely interpret command family ${command.family}")
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
            'M' -> requirePublishedM(command, lineNumber, "Published G-code")
            'T' -> require(command.code == 0) {
                "Unsupported tool change ${command.opcode} at line $lineNumber"
            }
            else -> error(
                "Unsupported command family ${command.family} at line $lineNumber; " +
                    "the G-code was not made available for export",
            )
        }
    }

    /** Rejects executable lines that the numeric parser intentionally cannot model. */
    fun requirePublishedTextSafe(rawLine: String, gcodeFlavor: String, lineNumber: Int) {
        val command = rawLine.substringBefore(';').trim()
        if (command.isEmpty()) return
        val flavor = gcodeFlavor.lowercase(Locale.US)
        val safe = "klipper" in flavor && (
            KLIPPER_PRESSURE_ADVANCE.matches(command) || KLIPPER_RETRACTION.matches(command)
        )
        require(safe) {
            "Unsupported textual or malformed command at line $lineNumber; " +
                "the G-code was not made available for export"
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
            'M' -> requirePublishedM(command, lineNumber = null, consumer = "Nozzle Path")
            'T' -> require(command.code == 0) {
                "Nozzle Path cannot safely display tool change ${command.opcode}"
            }
            else -> error("Nozzle Path cannot safely display command family ${command.family}")
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

    private fun requirePublishedM(command: GcodeCommand.Parsed, lineNumber: Int?, consumer: String) {
        val location = lineNumber?.let { " at line $it" }.orEmpty()
        fun only(vararg letters: Char) {
            require(command.hasOnlyParameters(letters.toSet())) {
                "$consumer rejects unsupported ${command.opcode} parameters$location"
            }
        }
        fun bounded(letter: Char, minimum: Double, maximum: Double, required: Boolean = false) {
            val value = command.value(letter)
            if (required) requireNotNull(value) { "$consumer requires ${command.opcode} $letter$location" }
            if (value != null) require(value.isFinite() && value in minimum..maximum) {
                "$consumer rejects ${command.opcode} $letter outside $minimum..$maximum$location"
            }
        }

        when (command.code) {
            0, 1 -> only('P', 'S')
            18, 84 -> only('S', 'X', 'Y', 'Z', 'E')
            25, 77, 82, 83, 107, 117, 118, 240, 400 -> Unit
            73 -> {
                only('P', 'R')
                bounded('P', 0.0, 100.0)
                bounded('R', 0.0, 1_000_000.0)
            }
            104, 109 -> {
                only('S', 'R', 'T')
                bounded('S', 0.0, 500.0)
                bounded('R', 0.0, 500.0)
                bounded('T', 0.0, 32.0)
            }
            106 -> {
                only('P', 'S')
                bounded('P', 0.0, 255.0)
                bounded('S', 0.0, 255.0)
            }
            140, 190 -> {
                only('S', 'R')
                bounded('S', 0.0, 200.0)
                bounded('R', 0.0, 200.0)
            }
            204 -> {
                only('P', 'R', 'S', 'T')
                command.parameterLetters.forEach { bounded(it, 0.0, 100_000.0) }
            }
            205 -> {
                only('B', 'E', 'J', 'S', 'T', 'X', 'Y', 'Z')
                command.parameterLetters.forEach { letter ->
                    bounded(letter, 0.0, if (letter == 'J') 1.0 else 100_000.0)
                }
            }
            207 -> {
                only('F', 'R', 'S', 'T', 'W', 'Z')
                command.parameterLetters.forEach { bounded(it, 0.0, 100_000.0) }
            }
            208 -> {
                only('F', 'R', 'S')
                command.parameterLetters.forEach { bounded(it, 0.0, 100_000.0) }
            }
            220, 221 -> {
                only('S', 'T')
                bounded('S', 1.0, 999.0, required = true)
                bounded('T', 0.0, 32.0)
            }
            300 -> {
                only('P', 'S')
                bounded('P', 0.0, 600_000.0)
                bounded('S', 0.0, 100_000.0)
            }
            420 -> {
                only('S', 'Z')
                bounded('S', 0.0, 1.0)
                bounded('Z', 0.0, 100.0)
            }
            572 -> {
                only('D', 'S', 'T')
                bounded('D', 0.0, 255.0)
                bounded('S', 0.0, 10.0, required = true)
                bounded('T', 0.0, 32.0)
            }
            600 -> {
                only('B', 'E', 'L', 'R', 'U', 'X', 'Y', 'Z')
                command.parameterLetters.forEach { bounded(it, -1_000.0, 1_000.0) }
            }
            900 -> {
                only('K', 'L', 'S', 'T')
                bounded('K', 0.0, 10.0)
                bounded('L', 0.0, 10.0)
                bounded('S', 0.0, 10.0)
                bounded('T', 0.0, 32.0)
            }
            else -> error(
                "$consumer rejects unmodeled persistent or machine-control command ${command.opcode}$location; " +
                    "the G-code was not made available for export",
            )
        }
    }

    private fun requireUnframed(command: GcodeCommand.Parsed, consumer: String) {
        require(!command.hasLineNumber && !command.hasChecksum) {
            "$consumer does not accept line-number or checksum framing; re-slice unframed G-code"
        }
    }

    private val KLIPPER_PRESSURE_ADVANCE = Regex(
        "^SET_PRESSURE_ADVANCE\\s+ADVANCE=[+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)$",
        RegexOption.IGNORE_CASE,
    )
    private val KLIPPER_RETRACTION = Regex(
        "^SET_RETRACTION\\s+RETRACT_LENGTH=[+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)\\s+" +
            "RETRACT_SPEED=[+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)$",
        RegexOption.IGNORE_CASE,
    )
}
''')

# Sanitizer: reject unparsed executable lines and adjust time for M220 overrides.
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/engine/GcodeSanitizer.kt',
    'import kotlin.math.ceil\n',
    'import kotlin.math.ceil\nimport kotlin.math.max\nimport kotlin.math.sqrt\n',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/engine/GcodeSanitizer.kt',
    '''        var temperatureCalibration = false\n\n        file.bufferedReader().useLines { lines ->''',
    '''        var temperatureCalibration = false\n        var feedRateMmPerMinute = 0.0\n        var speedFactor = 1.0\n        var rawMotionSeconds = 0.0\n        var effectiveMotionSeconds = 0.0\n\n        file.bufferedReader().useLines { lines ->''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/engine/GcodeSanitizer.kt',
    '''                val command = GcodeCommand.parse(rawLine) ?: return@forEach\n                GcodeCommandPolicy.requirePublishedSafe(command, currentLayer, lineNumber)\n                if (modalState.apply(command)) return@forEach\n''',
    '''                val command = GcodeCommand.parse(rawLine)\n                if (command == null) {\n                    GcodeCommandPolicy.requirePublishedTextSafe(\n                        rawLine = rawLine,\n                        gcodeFlavor = printerEnvelope?.gcodeFlavor ?: PrinterEnvelope.DEFAULT_GCODE_FLAVOR,\n                        lineNumber = lineNumber,\n                    )\n                    return@forEach\n                }\n                GcodeCommandPolicy.requirePublishedSafe(command, currentLayer, lineNumber)\n                GcodeCommandPolicy.speedFactor(command)?.let { factor ->\n                    speedFactor = factor\n                    return@forEach\n                }\n                if (modalState.apply(command)) return@forEach\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/engine/GcodeSanitizer.kt',
    '''                        x = modalState.position(x, command.value('X'))\n                        y = modalState.position(y, command.value('Y'))\n                        z = modalState.position(z, command.value('Z'))\n                        val spatialMove = startX != x || startY != y || startZ != z\n''',
    '''                        x = modalState.position(x, command.value('X'))\n                        y = modalState.position(y, command.value('Y'))\n                        z = modalState.position(z, command.value('Z'))\n                        command.value('F')?.let { feedRateMmPerMinute = it }\n                        val spatialMove = startX != x || startY != y || startZ != z\n                        if (spatialMove && feedRateMmPerMinute > 0.0) {\n                            val dx = x - startX\n                            val dy = y - startY\n                            val dz = z - startZ\n                            val distance = sqrt(dx * dx + dy * dy + dz * dz)\n                            val rawSpeed = feedRateMmPerMinute / 60.0\n                            rawMotionSeconds += distance / rawSpeed\n                            effectiveMotionSeconds += distance / (rawSpeed * speedFactor)\n                        }\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/engine/GcodeSanitizer.kt',
    '''        val estimatedSeconds = lastElapsed?.let { ceil(it).toInt() }\n''',
    '''        val adjustedElapsed = lastElapsed?.let { elapsed ->\n            max(0.0, elapsed + effectiveMotionSeconds - rawMotionSeconds)\n        }\n        val estimatedSeconds = adjustedElapsed?.let { ceil(it).toInt() }\n''',
)

# GENERAL-005: Layers preview uses the same M220 effective-feed semantics as Path.
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/engine/GcodeLayerPreview.kt',
    '''        var feedRateMmPerMinute = 0.0\n\n        var minX''',
    '''        var feedRateMmPerMinute = 0.0\n        var speedFactor = 1.0\n\n        var minX''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/engine/GcodeLayerPreview.kt',
    '''                val command = GcodeCommand.parse(rawLine) ?: return@forEach\n                if (modalState.apply(command)) return@forEach\n''',
    '''                val command = GcodeCommand.parse(rawLine) ?: return@forEach\n                GcodeCommandPolicy.speedFactor(command)?.let { factor ->\n                    speedFactor = factor\n                    return@forEach\n                }\n                if (modalState.apply(command)) return@forEach\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/engine/GcodeLayerPreview.kt',
    '''                        val speed = max(feedRateMmPerMinute / 60.0, 0.0).toFloat()\n''',
    '''                        val speed = max(feedRateMmPerMinute / 60.0 * speedFactor, 0.0).toFloat()\n''',
)

# GENERAL-004: dialect-aware G10/G11 firmware retraction semantics.
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/engine/CalibrationFirmwareEncoder.kt',
    '''    fun hotendOffCommand(): String = "M104 S0"\n\n    private fun retractionCommands''',
    '''    fun hotendOffCommand(): String = "M104 S0"\n\n    fun isFirmwareRetract(command: GcodeCommand.Parsed): Boolean = when (dialect) {\n        FirmwareDialect.REPRAP_FIRMWARE -> command.opcode == "G10" && command.parameterLetters.isEmpty()\n        FirmwareDialect.MARLIN -> command.opcode == "G10"\n        FirmwareDialect.KLIPPER, FirmwareDialect.GENERIC -> false\n    }\n\n    fun isFirmwareUnretract(command: GcodeCommand.Parsed): Boolean = when (dialect) {\n        FirmwareDialect.REPRAP_FIRMWARE -> command.opcode == "G11" && command.parameterLetters.isEmpty()\n        FirmwareDialect.MARLIN -> command.opcode == "G11"\n        FirmwareDialect.KLIPPER, FirmwareDialect.GENERIC -> false\n    }\n\n    private fun retractionCommands''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/engine/GcodeLayerEventProcessor.kt',
    '''                lines.forEach { line ->\n                    val opcode = GcodeCommand.parse(line)?.opcode\n\n                    if (fanCalibration && calibrationFanStarted && (opcode == "M106" || opcode == "M107")) {\n''',
    '''                lines.forEach { line ->\n                    val parsedCommand = GcodeCommand.parse(line)\n                    val opcode = parsedCommand?.opcode\n\n                    if (fanCalibration && calibrationFanStarted && (opcode == "M106" || opcode == "M107")) {\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/engine/GcodeLayerEventProcessor.kt',
    '''                    when (opcode) {\n                        "G10" -> firmwareRetracted = true\n                        "G11" -> {\n                            firmwareRetracted = false\n                            if (deferredRetraction.isNotEmpty()) {\n                                deferredRetraction.forEach(::writeEvent)\n                                deferredRetraction.clear()\n                            }\n                        }\n                    }\n''',
    '''                    when {\n                        parsedCommand != null && firmware.isFirmwareRetract(parsedCommand) -> firmwareRetracted = true\n                        parsedCommand != null && firmware.isFirmwareUnretract(parsedCommand) -> {\n                            firmwareRetracted = false\n                            if (deferredRetraction.isNotEmpty()) {\n                                deferredRetraction.forEach(::writeEvent)\n                                deferredRetraction.clear()\n                            }\n                        }\n                    }\n''',
)

# GENERAL-006: CurviSlicer and scalar-height calibration are explicitly incompatible.
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt',
    'import com.tomppi.enderslicer.model.SlicerSettings\n',
    'import com.tomppi.enderslicer.model.SlicerSettings\nimport com.tomppi.enderslicer.nonplanar.CurviSlicerRuntime\n',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt',
    '''        val plannedEventsSnapshot = plannedCalibrationEvents.toList()\n\n        viewModelScope.launch {''',
    '''        val plannedEventsSnapshot = plannedCalibrationEvents.toList()\n        if (plannedEventsSnapshot.isNotEmpty() && CurviSlicerRuntime.snapshot() != null) {\n            _uiState.update {\n                it.copy(\n                    isBusy = false,\n                    statusMessage = "CurviSlicer cannot be combined with height-based calibration towers; disable non-planar slicing",\n                )\n            }\n            return\n        }\n\n        viewModelScope.launch {''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/engine/CuraEnginePostProcessor.kt',
    '''        val firmware = CalibrationFirmwareEncoder.fromFlavor(effectiveEnvelope.gcodeFlavor)\n        val curviDiagnostics = CurviSlicerFieldStorage.curveStagedGcode(outputFile, effectiveEnvelope)\n''',
    '''        val firmware = CalibrationFirmwareEncoder.fromFlavor(effectiveEnvelope.gcodeFlavor)\n        require(plannedLayerEvents.isEmpty() || !CurviSlicerFieldStorage.isPrepared(outputFile.parentFile)) {\n            "CurviSlicer cannot be combined with height-based calibration events"\n        }\n        val curviDiagnostics = CurviSlicerFieldStorage.curveStagedGcode(outputFile, effectiveEnvelope)\n''',
)

# GENERAL-002: every workspace transaction is serialized and stale setting snapshots are rejected.
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/data/WorkspaceStateStore.kt',
    '''    fun save(snapshot: Snapshot) {\n''',
    '''    @Synchronized\n    fun save(snapshot: Snapshot) {\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/data/WorkspaceStateStore.kt',
    '''    fun load(): Snapshot? {\n''',
    '''    @Synchronized\n    fun load(): Snapshot? {\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/data/WorkspaceStateStore.kt',
    '''    fun clear() {\n''',
    '''    @Synchronized\n    fun clear() {\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt',
    '''    private var settingsPersistenceJob: Job? = null\n    private val layerEventSequence = AtomicLong(0L)\n''',
    '''    private var settingsPersistenceJob: Job? = null\n    private val workspaceMutationGeneration = AtomicLong(0L)\n    private val layerEventSequence = AtomicLong(0L)\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt',
    '''        persistSettings(changed)\n''',
    '''        persistSettings(changed, workspaceMutationGeneration.incrementAndGet())\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt',
    '''        persistSettings(restored)\n''',
    '''        persistSettings(restored, workspaceMutationGeneration.incrementAndGet())\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt',
    '''    private fun persistSettings(settings: SlicerSettings) {\n        val stateSnapshot = _uiState.value.copy(settings = settings)\n        val previousWrite = settingsPersistenceJob\n        settingsPersistenceJob = viewModelScope.launch(Dispatchers.IO) {\n            previousWrite?.join()\n            stateStore.saveSettings(settings)\n            persistCurrentWorkspace(stateSnapshot)\n        }\n    }\n''',
    '''    private fun persistSettings(settings: SlicerSettings, generation: Long) {\n        val stateSnapshot = _uiState.value.copy(settings = settings)\n        val previousWrite = settingsPersistenceJob\n        settingsPersistenceJob = viewModelScope.launch(Dispatchers.IO) {\n            previousWrite?.join()\n            stateStore.saveSettings(settings)\n            if (workspaceMutationGeneration.get() == generation) {\n                persistCurrentWorkspace(stateSnapshot)\n            }\n        }\n    }\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt',
    '''        if (current.isBusy) return false\n        _uiState.update { it.copy(isBusy = true, statusMessage = message) }\n''',
    '''        if (current.isBusy) return false\n        workspaceMutationGeneration.incrementAndGet()\n        _uiState.update { it.copy(isBusy = true, statusMessage = message) }\n''',
)

# GENERAL-003: queue activity/tool results while initial restoration owns the state machine.
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt',
    'import kotlinx.coroutines.flow.update\n',
    'import kotlinx.coroutines.flow.first\nimport kotlinx.coroutines.flow.update\n',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt',
    '''    private val workspaceMutationGeneration = AtomicLong(0L)\n    private val layerEventSequence = AtomicLong(0L)\n''',
    '''    private val workspaceMutationGeneration = AtomicLong(0L)\n    private val layerEventSequence = AtomicLong(0L)\n    private val deferredRestoreActions = ArrayDeque<() -> Unit>()\n    @Volatile private var restoringPersistedState = true\n''',
)
for signature in [
    '    fun importStl(uri: Uri) {\n',
    '    fun importPartTopoResult(uri: Uri) {\n',
    '    fun importCuraProfile(uri: Uri) {\n',
    '    fun importCuraProject(uri: Uri) {\n',
    '    fun exportGcode(uri: Uri) {\n',
    '    fun exportConfiguration(uri: Uri) {\n',
]:
    method = signature.strip().split('(')[0].split()[-1]
    replace_once(
        'app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt',
        signature,
        signature + f'        if (deferUntilRestoreCompletes {{ {method}(uri) }}) return\n',
    )
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt',
    '''    private fun restorePersistedState() {\n''',
    '''    fun deferUntilRestoreCompletes(action: () -> Unit): Boolean = synchronized(deferredRestoreActions) {\n        if (!restoringPersistedState) {\n            false\n        } else {\n            deferredRestoreActions.addLast(action)\n            true\n        }\n    }\n\n    private fun finishRestoreAndReplayResults() {\n        val actions = synchronized(deferredRestoreActions) {\n            restoringPersistedState = false\n            val pending = deferredRestoreActions.toList()\n            deferredRestoreActions.clear()\n            pending\n        }\n        if (actions.isEmpty()) return\n        viewModelScope.launch {\n            actions.forEach { action ->\n                if (_uiState.value.isBusy) uiState.first { state -> !state.isBusy }\n                action()\n                if (_uiState.value.isBusy) uiState.first { state -> !state.isBusy }\n            }\n        }\n    }\n\n    private fun restorePersistedState() {\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt',
    '''            result.onSuccess { restored ->\n''',
    '''            result.onSuccess { restored ->\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt',
    '''            }.onFailure { error ->\n                _uiState.update {\n                    it.copy(\n                        isBusy = false,\n                        statusMessage = "Saved Cura configuration could not be restored: ${error.message}",\n                    )\n                }\n            }\n        }\n    }\n''',
    '''            }.onFailure { error ->\n                _uiState.update {\n                    it.copy(\n                        isBusy = false,\n                        statusMessage = "Saved Cura configuration could not be restored: ${error.message}",\n                    )\n                }\n            }\n            finishRestoreAndReplayResults()\n        }\n    }\n''',
)

# GENERAL-007: crash-recoverable SAF export ownership and delete-on-failure.
write('app/src/main/java/com/tomppi/enderslicer/data/PendingDocumentExportStore.kt', r'''package com.tomppi.enderslicer.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri

/** Records a user-visible SAF destination until the complete payload is durably closed. */
class PendingDocumentExportStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun begin(uri: Uri) {
        check(preferences.edit().putString(KEY_PENDING_URI, uri.toString()).commit()) {
            "Unable to record the pending export destination"
        }
    }

    fun complete(uri: Uri) {
        if (preferences.getString(KEY_PENDING_URI, null) == uri.toString()) {
            check(preferences.edit().remove(KEY_PENDING_URI).commit()) {
                "Unable to complete the export transaction"
            }
        }
    }

    fun fail(resolver: ContentResolver, uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
        complete(uri)
    }

    fun recover(resolver: ContentResolver) {
        val raw = preferences.getString(KEY_PENDING_URI, null) ?: return
        runCatching { resolver.delete(Uri.parse(raw), null, null) }
        preferences.edit().remove(KEY_PENDING_URI).commit()
    }

    private companion object {
        const val PREFERENCES = "enderslicer-pending-exports-v1"
        const val KEY_PENDING_URI = "pending-uri"
    }
}
''')
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt',
    'import com.tomppi.enderslicer.data.PrinterDefinitionLoader\n',
    'import com.tomppi.enderslicer.data.PendingDocumentExportStore\nimport com.tomppi.enderslicer.data.PrinterDefinitionLoader\n',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt',
    '''    private val workspaceStore = WorkspaceStateStore(app)\n''',
    '''    private val workspaceStore = WorkspaceStateStore(app)\n    private val pendingExportStore = PendingDocumentExportStore(app)\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt',
    '''                withContext(Dispatchers.IO) {\n                    val saved = stateStore.savedImport()\n''',
    '''                withContext(Dispatchers.IO) {\n                    pendingExportStore.recover(app.contentResolver)\n                    val saved = stateStore.savedImport()\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt',
    '''        viewModelScope.launch {\n            runCatching {\n                withContext(Dispatchers.IO) {\n                    val source = File(sourcePath)\n                    check(SliceArtifactPublisher.isCompleteGcode(source, expectedArtifactId)) {\n                        "Generated G-code is incomplete, stale, or no longer available"\n                    }\n                    SliceArtifactPublisher.acquireLease(source, expectedArtifactId).use {\n                        app.contentResolver.openOutputStream(uri, "w")?.buffered()?.use { output ->\n                            source.inputStream().buffered().use { input -> input.copyTo(output) }\n                        } ?: error("Unable to open the G-code destination")\n                    }\n                }\n            }.onSuccess {\n''',
    '''        viewModelScope.launch {\n            runCatching {\n                withContext(Dispatchers.IO) {\n                    val source = File(sourcePath)\n                    check(SliceArtifactPublisher.isCompleteGcode(source, expectedArtifactId)) {\n                        "Generated G-code is incomplete, stale, or no longer available"\n                    }\n                    pendingExportStore.begin(uri)\n                    try {\n                        SliceArtifactPublisher.acquireLease(source, expectedArtifactId).use {\n                            val written = app.contentResolver.openOutputStream(uri, "w")?.buffered()?.use { output ->\n                                source.inputStream().buffered().use { input -> input.copyTo(output).also { output.flush() } }\n                            } ?: error("Unable to open the G-code destination")\n                            check(written == source.length()) { "The G-code export ended before every byte was written" }\n                        }\n                        pendingExportStore.complete(uri)\n                    } catch (error: Throwable) {\n                        pendingExportStore.fail(app.contentResolver, uri)\n                        throw error\n                    }\n                }\n            }.onSuccess {\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt',
    '''                withContext(Dispatchers.IO) {\n                    val snapshot = configurationJson(_uiState.value)\n                    app.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->\n                        writer.write(snapshot.toString(2))\n                    } ?: error("Unable to open the export destination")\n                }\n''',
    '''                withContext(Dispatchers.IO) {\n                    val bytes = configurationJson(_uiState.value).toString(2).toByteArray(Charsets.UTF_8)\n                    pendingExportStore.begin(uri)\n                    try {\n                        val written = app.contentResolver.openOutputStream(uri, "w")?.buffered()?.use { output ->\n                            output.write(bytes)\n                            output.flush()\n                            bytes.size.toLong()\n                        } ?: error("Unable to open the export destination")\n                        check(written == bytes.size.toLong()) { "The configuration export was incomplete" }\n                        pendingExportStore.complete(uri)\n                    } catch (error: Throwable) {\n                        pendingExportStore.fail(app.contentResolver, uri)\n                        throw error\n                    }\n                }\n''',
)

# GENERAL-008: atomically bind OctoPrint endpoint and encrypted credential.
write('app/src/main/java/com/tomppi/enderslicer/octoprint/OctoPrintSecretStore.kt', r'''package com.tomppi.enderslicer.octoprint

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class OctoPrintSecretStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadConfig(): OctoPrintConfig = OctoPrintConfig(
        baseUrl = preferences.getString(KEY_BASE_URL, "").orEmpty(),
        username = preferences.getString(KEY_USERNAME, "").orEmpty(),
        snapshotUrlOverride = preferences.getString(KEY_SNAPSHOT_URL, "").orEmpty(),
        pollIntervalSeconds = preferences.getInt(KEY_POLL_SECONDS, DEFAULT_POLL_SECONDS).coerceIn(1, 30),
    )

    fun saveConfig(config: OctoPrintConfig) {
        val oldOrigin = preferences.getString(KEY_API_KEY_ORIGIN, null)
        val editor = preferences.edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_SNAPSHOT_URL, config.snapshotUrlOverride)
            .putInt(KEY_POLL_SECONDS, config.pollIntervalSeconds.coerceIn(1, 30))
        if (oldOrigin != null && oldOrigin != config.baseUrl) {
            editor.remove(KEY_ENCRYPTED_API_KEY).remove(KEY_API_KEY_ORIGIN)
        }
        check(editor.commit()) { "Unable to persist OctoPrint configuration" }
    }

    fun saveConfiguration(config: OctoPrintConfig, apiKey: String) {
        require(config.isConfigured) { "OctoPrint server configuration is incomplete" }
        require(apiKey.isNotBlank()) { "OctoPrint API key cannot be empty" }
        check(
            preferences.edit()
                .putString(KEY_BASE_URL, config.baseUrl)
                .putString(KEY_USERNAME, config.username)
                .putString(KEY_SNAPSHOT_URL, config.snapshotUrlOverride)
                .putInt(KEY_POLL_SECONDS, config.pollIntervalSeconds.coerceIn(1, 30))
                .putString(KEY_ENCRYPTED_API_KEY, encrypt(apiKey.trim()))
                .putString(KEY_API_KEY_ORIGIN, config.baseUrl)
                .putLong(KEY_CONFIGURATION_GENERATION, preferences.getLong(KEY_CONFIGURATION_GENERATION, 0L) + 1L)
                .commit(),
        ) { "Unable to atomically persist OctoPrint credentials" }
    }

    fun hasApiKey(): Boolean = loadApiKey() != null

    fun loadApiKey(): String? {
        val encoded = preferences.getString(KEY_ENCRYPTED_API_KEY, null) ?: return null
        val origin = preferences.getString(KEY_API_KEY_ORIGIN, null)
        val configuredOrigin = preferences.getString(KEY_BASE_URL, "").orEmpty()
        if (origin.isNullOrBlank() || origin != configuredOrigin) {
            preferences.edit().remove(KEY_ENCRYPTED_API_KEY).remove(KEY_API_KEY_ORIGIN).commit()
            return null
        }
        return runCatching { decrypt(encoded) }
            .onFailure {
                preferences.edit().remove(KEY_ENCRYPTED_API_KEY).remove(KEY_API_KEY_ORIGIN).commit()
            }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
    }

    fun saveApiKey(apiKey: String) {
        val config = loadConfig()
        saveConfiguration(config, apiKey)
    }

    fun clearApiKey() {
        check(preferences.edit().remove(KEY_ENCRYPTED_API_KEY).remove(KEY_API_KEY_ORIGIN).commit()) {
            "Unable to clear the OctoPrint API key"
        }
    }

    fun clearAll() {
        check(preferences.edit().clear().commit()) { "Unable to clear OctoPrint configuration" }
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(1 + cipher.iv.size + encrypted.size)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(encrypted)
            .array()
        return FORMAT_PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        require(value.startsWith(FORMAT_PREFIX)) { "Unsupported credential format" }
        val payload = Base64.decode(value.removePrefix(FORMAT_PREFIX), Base64.NO_WRAP)
        val buffer = ByteBuffer.wrap(payload)
        val ivSize = buffer.get().toInt() and 0xff
        require(ivSize in 12..32 && buffer.remaining() > ivSize) { "Corrupt encrypted credential" }
        val iv = ByteArray(ivSize).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore()
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private companion object {
        const val PREFERENCES_NAME = "octoprint_client"
        const val KEY_ALIAS = "enderslicercura_octoprint_api_key"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_PREFIX = "v1:"
        const val KEY_BASE_URL = "base_url"
        const val KEY_USERNAME = "username"
        const val KEY_SNAPSHOT_URL = "snapshot_url"
        const val KEY_POLL_SECONDS = "poll_seconds"
        const val KEY_ENCRYPTED_API_KEY = "encrypted_api_key"
        const val KEY_API_KEY_ORIGIN = "api_key_origin"
        const val KEY_CONFIGURATION_GENERATION = "configuration_generation"
        const val DEFAULT_POLL_SECONDS = 3
    }
}
''')
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/octoprint/OctoPrintRepository.kt',
    '''        store.saveApiKey(key)\n        store.saveConfig(config)\n''',
    '''        store.saveConfiguration(config, key)\n''',
)

# GENERAL-009: global ZIP work budgets apply to accepted and ignored entries.
write('app/src/main/java/com/tomppi/enderslicer/profile/CuraArchive.kt', r'''package com.tomppi.enderslicer.profile

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

internal object CuraArchive {
    fun readTextEntries(
        input: InputStream,
        maximumEntryBytes: Int = 16 * 1024 * 1024,
        maximumAcceptedEntries: Int = 512,
        maximumTotalBytes: Long = 64L * 1024L * 1024L,
        maximumArchiveEntries: Int = 2_048,
        maximumInflatedBytes: Long = 256L * 1024L * 1024L,
        maximumCompressionRatio: Long = 2_000L,
        maximumWorkMillis: Long = 30_000L,
        accept: (String) -> Boolean = { true },
    ): Map<String, String> {
        require(maximumEntryBytes > 0) { "Archive entry limit must be positive" }
        require(maximumAcceptedEntries > 0) { "Archive accepted entry-count limit must be positive" }
        require(maximumTotalBytes > 0L) { "Archive accepted size limit must be positive" }
        require(maximumArchiveEntries > 0) { "Archive global entry-count limit must be positive" }
        require(maximumInflatedBytes > 0L) { "Archive global inflated-size limit must be positive" }
        require(maximumCompressionRatio > 0L) { "Archive compression ratio limit must be positive" }
        require(maximumWorkMillis > 0L) { "Archive work-time limit must be positive" }

        val counted = CountingInputStream(input.buffered())
        val startedNanos = System.nanoTime()
        val result = linkedMapOf<String, String>()
        var archiveEntries = 0
        var acceptedEntries = 0
        var acceptedBytes = 0L
        var inflatedBytes = 0L
        val buffer = ByteArray(16 * 1024)

        fun checkBudget() {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Cura archive parsing was cancelled")
            val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L
            require(elapsedMillis <= maximumWorkMillis) { "Archive decompression exceeded its work-time limit" }
            require(inflatedBytes <= maximumInflatedBytes) { "Archive inflated data exceeds its global safety limit" }
            if (inflatedBytes >= MIN_RATIO_CHECK_BYTES) {
                val compressed = counted.count.coerceAtLeast(1L)
                require(inflatedBytes / compressed <= maximumCompressionRatio) {
                    "Archive compression ratio exceeds its global safety limit"
                }
            }
        }

        ZipInputStream(counted).use { zip ->
            while (true) {
                checkBudget()
                val entry = zip.nextEntry ?: break
                archiveEntries++
                require(archiveEntries <= maximumArchiveEntries) {
                    "Archive contains more than $maximumArchiveEntries entries"
                }
                val accepted = !entry.isDirectory && accept(entry.name)
                val output = if (accepted) ByteArrayOutputStream() else null
                var entryBytes = 0L
                if (accepted) {
                    require(entry.name !in result) { "Archive contains a duplicate entry: ${entry.name}" }
                    acceptedEntries++
                    require(acceptedEntries <= maximumAcceptedEntries) {
                        "Archive contains more than $maximumAcceptedEntries accepted entries"
                    }
                }
                if (!entry.isDirectory) {
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        entryBytes += read.toLong()
                        inflatedBytes += read.toLong()
                        checkBudget()
                        if (accepted) {
                            acceptedBytes += read.toLong()
                            require(entryBytes <= maximumEntryBytes) {
                                "Archive entry ${entry.name} exceeds the ${maximumEntryBytes / 1024 / 1024} MiB safety limit"
                            }
                            require(acceptedBytes <= maximumTotalBytes) {
                                "Accepted archive data exceeds the ${maximumTotalBytes / 1024 / 1024} MiB safety limit"
                            }
                            output?.write(buffer, 0, read)
                        }
                    }
                }
                if (accepted) result[entry.name] = requireNotNull(output).toString(Charsets.UTF_8.name())
                zip.closeEntry()
            }
        }
        return result
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count: Long = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) count += it.toLong() }
    }

    private const val MIN_RATIO_CHECK_BYTES = 1L * 1024L * 1024L
}
''')

# GENERAL-010: bundle Cura payload and discriminator metadata in one atomic file generation.
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/data/AppStateStore.kt',
    'import java.io.File\nimport java.io.InputStream\n',
    'import java.io.BufferedInputStream\nimport java.io.BufferedOutputStream\nimport java.io.DataInputStream\nimport java.io.DataOutputStream\nimport java.io.File\nimport java.io.FileOutputStream\nimport java.io.InputStream\nimport java.security.MessageDigest\n',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/data/AppStateStore.kt',
    '''    private val importFile = File(stateDirectory, "current-cura-import.bin")\n''',
    '''    private val legacyImportFile = File(stateDirectory, "current-cura-import.bin")\n    private val importBundle = File(stateDirectory, "current-cura-import.bundle")\n    private val materializedImport = File(stateDirectory, "current-cura-import.materialized")\n''',
)
regex_once(
    'app/src/main/java/com/tomppi/enderslicer/data/AppStateStore.kt',
    r'''    fun commitImport\(staged: File, kind: String, displayName: String\) \{.*?\n    fun clearSavedSettings\(\) \{''',
    r'''    @Synchronized
    fun commitImport(staged: File, kind: String, displayName: String) {
        require(kind == KIND_PROJECT || kind == KIND_PROFILE) { "Unsupported Cura import kind: $kind" }
        require(staged.isFile && staged.length() in 1..MAX_CURA_IMPORT_BYTES) {
            "The staged Cura configuration is unavailable"
        }
        val next = File(stateDirectory, "current-cura-import.bundle.next")
        val backup = File(stateDirectory, "current-cura-import.bundle.previous")
        next.delete()
        backup.delete()
        val payloadSha = sha256(staged)
        val metadata = JSONObject()
            .put("version", IMPORT_BUNDLE_VERSION)
            .put("kind", kind)
            .put("displayName", displayName.take(MAX_IMPORT_NAME_CHARS))
            .put("payloadBytes", staged.length())
            .put("payloadSha256", payloadSha)
            .toString()
            .toByteArray(Charsets.UTF_8)
        require(metadata.size <= MAX_IMPORT_METADATA_BYTES) { "Cura import metadata is too large" }

        try {
            FileOutputStream(next).use { fileOutput ->
                val output = DataOutputStream(BufferedOutputStream(fileOutput))
                output.write(IMPORT_BUNDLE_MAGIC)
                output.writeInt(metadata.size)
                output.writeLong(staged.length())
                output.write(metadata)
                staged.inputStream().buffered().use { input -> input.copyTo(output) }
                output.flush()
                fileOutput.fd.sync()
            }
            check(next.isFile && next.length() > staged.length()) { "Unable to stage the Cura import bundle" }
            if (importBundle.exists()) {
                check(importBundle.renameTo(backup)) { "Unable to preserve the previous Cura import bundle" }
            }
            try {
                check(next.renameTo(importBundle)) { "Unable to publish the Cura import bundle" }
            } catch (error: Throwable) {
                importBundle.delete()
                if (backup.exists()) backup.renameTo(importBundle)
                throw error
            }
            backup.delete()
            materializedImport.delete()
            legacyImportFile.delete()
            staged.delete()
            preferences.edit().remove(KEY_IMPORT_KIND).remove(KEY_IMPORT_NAME).commit()
        } finally {
            next.delete()
            if (backup.exists() && importBundle.exists()) backup.delete()
        }
    }

    @Synchronized
    fun savedImport(): SavedImport? {
        recoverImportBundle()
        if (importBundle.isFile) return materializeBundle()

        // One-time compatibility with the pre-bundle format.
        val kind = preferences.getString(KEY_IMPORT_KIND, null) ?: return null
        val displayName = preferences.getString(KEY_IMPORT_NAME, null) ?: "Restored Cura configuration"
        if (!legacyImportFile.isFile || legacyImportFile.length() == 0L) return null
        return SavedImport(kind, displayName, legacyImportFile)
    }

    private fun recoverImportBundle() {
        val next = File(stateDirectory, "current-cura-import.bundle.next")
        val backup = File(stateDirectory, "current-cura-import.bundle.previous")
        if (!importBundle.exists()) {
            when {
                next.isFile -> next.renameTo(importBundle)
                backup.isFile -> backup.renameTo(importBundle)
            }
        }
        if (importBundle.isFile) {
            next.delete()
            backup.delete()
        }
    }

    private fun materializeBundle(): SavedImport {
        DataInputStream(BufferedInputStream(importBundle.inputStream())).use { input ->
            val magic = ByteArray(IMPORT_BUNDLE_MAGIC.size).also(input::readFully)
            require(magic.contentEquals(IMPORT_BUNDLE_MAGIC)) { "Saved Cura import bundle has an invalid header" }
            val metadataSize = input.readInt()
            val payloadSize = input.readLong()
            require(metadataSize in 1..MAX_IMPORT_METADATA_BYTES) { "Saved Cura import metadata is invalid" }
            require(payloadSize in 1..MAX_CURA_IMPORT_BYTES) { "Saved Cura import payload is invalid" }
            val metadata = JSONObject(ByteArray(metadataSize).also(input::readFully).toString(Charsets.UTF_8))
            require(metadata.getInt("version") == IMPORT_BUNDLE_VERSION) { "Unsupported Cura import bundle version" }
            require(metadata.getLong("payloadBytes") == payloadSize) { "Saved Cura import length metadata is inconsistent" }
            val expectedSha = metadata.getString("payloadSha256")
            require(expectedSha.matches(Regex("[0-9a-f]{64}"))) { "Saved Cura import fingerprint is invalid" }
            val kind = metadata.getString("kind")
            require(kind == KIND_PROJECT || kind == KIND_PROFILE) { "Saved Cura import kind is invalid" }
            val displayName = metadata.getString("displayName").take(MAX_IMPORT_NAME_CHARS)

            val existingMatches = materializedImport.isFile && materializedImport.length() == payloadSize &&
                runCatching { sha256(materializedImport) == expectedSha }.getOrDefault(false)
            if (!existingMatches) {
                val next = File(stateDirectory, "current-cura-import.materialized.next")
                next.delete()
                val digest = MessageDigest.getInstance("SHA-256")
                FileOutputStream(next).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var remaining = payloadSize
                    while (remaining > 0L) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        require(count > 0) { "Saved Cura import bundle ended unexpectedly" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        remaining -= count.toLong()
                    }
                    output.fd.sync()
                }
                require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == expectedSha) {
                    "Saved Cura import payload fingerprint does not match"
                }
                materializedImport.delete()
                check(next.renameTo(materializedImport)) { "Unable to materialize the saved Cura import" }
            }
            return SavedImport(kind, displayName, materializedImport)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun clearSavedSettings() {''',
    flags=re.S,
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/data/AppStateStore.kt',
    '''        const val KIND_PROJECT = "project"\n        const val KIND_PROFILE = "profile"\n''',
    '''        const val KIND_PROJECT = "project"\n        const val KIND_PROFILE = "profile"\n        private const val IMPORT_BUNDLE_VERSION = 1\n        private const val MAX_IMPORT_METADATA_BYTES = 64 * 1024\n        private const val MAX_IMPORT_NAME_CHARS = 512\n        private val IMPORT_BUNDLE_MAGIC = "ESCIMP2\\n".toByteArray(Charsets.US_ASCII)\n''',
)

# GENERAL-011: secret drafts are transient and tied to endpoint identity.
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/HardenedOctoPrintSheet.kt',
    '''    var apiKey by rememberSaveable { mutableStateOf("") }\n''',
    '''    var apiKey by remember { mutableStateOf("") }\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/HardenedOctoPrintSheet.kt',
    '''    LaunchedEffect(state.config) {\n        baseUrl = state.config.baseUrl\n        username = state.config.username\n        snapshotUrl = state.config.snapshotUrlOverride\n        pollSeconds = state.config.pollIntervalSeconds.toString()\n    }\n''',
    '''    LaunchedEffect(state.config) {\n        baseUrl = state.config.baseUrl\n        username = state.config.username\n        snapshotUrl = state.config.snapshotUrlOverride\n        pollSeconds = state.config.pollIntervalSeconds.toString()\n        apiKey = ""\n    }\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/HardenedOctoPrintSheet.kt',
    '''        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Server URL or IP address") }, placeholder = { Text("http://octopi.local") }, singleLine = true, modifier = Modifier.fillMaxWidth())\n''',
    '''        OutlinedTextField(\n            baseUrl,\n            { value ->\n                if (value != baseUrl) apiKey = ""\n                baseUrl = value\n            },\n            label = { Text("Server URL or IP address") },\n            placeholder = { Text("http://octopi.local") },\n            singleLine = true,\n            modifier = Modifier.fillMaxWidth(),\n        )\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/HardenedOctoPrintSheet.kt',
    '''                    viewModel.saveManualConfiguration(\n                        baseUrl,\n                        username,\n                        apiKey,\n                        snapshotUrl,\n                        pollSeconds.toIntOrNull() ?: 3,\n                    )\n''',
    '''                    viewModel.saveManualConfiguration(\n                        baseUrl,\n                        username,\n                        apiKey,\n                        snapshotUrl,\n                        pollSeconds.toIntOrNull() ?: 3,\n                    )\n                    apiKey = ""\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/HardenedOctoPrintSheet.kt',
    '''                viewModel.clearConfiguration()\n''',
    '''                apiKey = ""\n                viewModel.clearConfiguration()\n''',
)

# GENERAL-012: make Smart Infill validation an explicit slice gate and runtime state transition.
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/IntegratedEnderSlicerApp.kt',
    '''    var smartInfillImporting by remember { mutableStateOf(false) }\n''',
    '''    var smartInfillImporting by remember { mutableStateOf(false) }\n    var smartInfillValidating by remember { mutableStateOf(false) }\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/IntegratedEnderSlicerApp.kt',
    '''        val packageValue = smartInfillPackage ?: return@LaunchedEffect\n        val mesh = slicerState.mesh ?: return@LaunchedEffect\n        try {\n''',
    '''        val packageValue = smartInfillPackage ?: return@LaunchedEffect\n        val mesh = slicerState.mesh ?: return@LaunchedEffect\n        smartInfillValidating = true\n        SmartInfillRuntime.activate(null)\n        try {\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/IntegratedEnderSlicerApp.kt',
    '''            withContext(Dispatchers.IO) {\n                val validationFile = File(\n''',
    '''            withContext(Dispatchers.IO) {\n                val validationFile = File(\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/IntegratedEnderSlicerApp.kt',
    '''                    packageValue.requireMatchesSource(validationFile)\n                } finally {\n                    validationFile.delete()\n                }\n            }\n        } catch (cancelled: CancellationException) {\n''',
    '''                    packageValue.requireMatchesSource(validationFile)\n                } finally {\n                    validationFile.delete()\n                }\n            }\n            if (smartInfillPackage?.id == packageValue.id) SmartInfillRuntime.activate(packageValue)\n        } catch (cancelled: CancellationException) {\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/IntegratedEnderSlicerApp.kt',
    '''                ).show()\n            }\n        }\n    }\n\n    LaunchedEffect(octoPrintState.authorizationDialogLaunchNonce) {\n''',
    '''                ).show()\n            }\n        } finally {\n            if (smartInfillPackage == null || smartInfillPackage?.id == packageValue.id) {\n                smartInfillValidating = false\n            }\n        }\n    }\n\n    LaunchedEffect(octoPrintState.authorizationDialogLaunchNonce) {\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/EnderSlicerApp.kt',
    '''fun EnderSlicerApp(\n    viewModel: MainViewModel = viewModel(),\n    topBarActions: @Composable () -> Unit = {},\n) {\n''',
    '''fun EnderSlicerApp(\n    viewModel: MainViewModel = viewModel(),\n    topBarActions: @Composable () -> Unit = {},\n    sliceBlockedReason: String? = null,\n) {\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/EnderSlicerApp.kt',
    '''                nonPlanarEnabled = nonPlanarSettings.enabled,\n                onSlice = viewModel::sliceModel,\n''',
    '''                nonPlanarEnabled = nonPlanarSettings.enabled,\n                sliceBlockedReason = sliceBlockedReason,\n                onSlice = viewModel::sliceModel,\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/EnderSlicerApp.kt',
    '''private fun ActionBar(\n    state: MainUiState,\n    nonPlanarEnabled: Boolean,\n    onSlice: () -> Unit,\n''',
    '''private fun ActionBar(\n    state: MainUiState,\n    nonPlanarEnabled: Boolean,\n    sliceBlockedReason: String?,\n    onSlice: () -> Unit,\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/EnderSlicerApp.kt',
    '''            state.estimatedPrintSeconds?.takeIf { gcodeAvailable }?.let { seconds ->\n''',
    '''            sliceBlockedReason?.let { reason ->\n                Text(reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)\n            }\n            state.estimatedPrintSeconds?.takeIf { gcodeAvailable }?.let { seconds ->\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/EnderSlicerApp.kt',
    '''                    enabled = state.engineAvailable && state.modelPath != null && !state.isBusy,\n''',
    '''                    enabled = state.engineAvailable && state.modelPath != null && !state.isBusy && sliceBlockedReason == null,\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/ui/IntegratedEnderSlicerApp.kt',
    '''    EnderSlicerApp(\n        viewModel = slicerViewModel,\n        topBarActions = {\n''',
    '''    EnderSlicerApp(\n        viewModel = slicerViewModel,\n        sliceBlockedReason = when {\n            smartInfillImporting -> "Smart Infill import is still being committed"\n            smartInfillValidating -> "Smart Infill is being validated for the current model"\n            else -> null\n        },\n        topBarActions = {\n''',
)

# Refactor Smart Infill activity result into a replayable function for GENERAL-003.
text = read('app/src/main/java/com/tomppi/enderslicer/ui/IntegratedEnderSlicerApp.kt')
start = text.index('    val smartInfillLauncher = rememberLauncherForActivityResult(')
end = text.index('\n\n    fun clearBuildPlate()', start)
block = text[start:end]
body_start = block.index('    ) { result ->\n') + len('    ) { result ->\n')
body = block[body_start:]
if not body.endswith('    }'):
    raise RuntimeError('unexpected Smart Infill launcher block ending')
body = body.rsplit('\n    }', 1)[0] + '\n'
body = body.replace('return@rememberLauncherForActivityResult', 'return')
new_block = '''    fun processSmartInfillResult(result: androidx.activity.result.ActivityResult) {\n''' + body + '''    }\n\n    val smartInfillLauncher = rememberLauncherForActivityResult(\n        ActivityResultContracts.StartActivityForResult(),\n    ) { result ->\n        if (slicerViewModel.deferUntilRestoreCompletes { processSmartInfillResult(result) }) {\n            return@rememberLauncherForActivityResult\n        }\n        processSmartInfillResult(result)\n    }'''
write('app/src/main/java/com/tomppi/enderslicer/ui/IntegratedEnderSlicerApp.kt', text[:start] + new_block + text[end:])

# GENERAL-013: recover active Smart Infill pointer across every rename window.
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/smartinfill/SmartInfillPackage.kt',
    '''    private val activeFile = File(root, "active-package.txt")\n    private val loadWarningFile = File(root, "load-warning.txt")\n''',
    '''    private val activeFile = File(root, "active-package.txt")\n    private val activeNextFile = File(root, "active-package.next")\n    private val activePreviousFile = File(root, "active-package.previous")\n    private val loadWarningFile = File(root, "load-warning.txt")\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/smartinfill/SmartInfillPackage.kt',
    '''    fun loadActive(): SmartInfillPackage? {\n        if (!activeFile.isFile) return null\n''',
    '''    fun loadActive(): SmartInfillPackage? {\n        recoverActivePointer()\n        if (!activeFile.isFile) return null\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/smartinfill/SmartInfillPackage.kt',
    '''    fun clearActive() {\n        activeFile.delete()\n    }\n''',
    '''    fun clearActive() {\n        activeFile.delete()\n        activeNextFile.delete()\n        activePreviousFile.delete()\n    }\n''',
)
replace_once(
    'app/src/main/java/com/tomppi/enderslicer/smartinfill/SmartInfillPackage.kt',
    '''    fun activate(packageValue: SmartInfillPackage) {\n        require(packageValue.directory.parentFile?.canonicalFile == packagesDirectory.canonicalFile) {\n            "Smart Infill package is outside private storage"\n        }\n        validatePatternContract(packageValue.mode, packageValue.pattern, packageValue.binarySolidPattern)\n        val next = File(root, "active-package.next")\n        writeSynced(next, packageValue.id)\n        activeFile.delete()\n        check(next.renameTo(activeFile)) { "Unable to activate the Smart Infill package" }\n    }\n''',
    '''    @Synchronized\n    fun activate(packageValue: SmartInfillPackage) {\n        require(packageValue.directory.parentFile?.canonicalFile == packagesDirectory.canonicalFile) {\n            "Smart Infill package is outside private storage"\n        }\n        validatePatternContract(packageValue.mode, packageValue.pattern, packageValue.binarySolidPattern)\n        activeNextFile.delete()\n        activePreviousFile.delete()\n        writeSynced(activeNextFile, packageValue.id)\n        try {\n            if (activeFile.exists()) {\n                check(activeFile.renameTo(activePreviousFile)) { "Unable to preserve the active Smart Infill pointer" }\n            }\n            try {\n                check(activeNextFile.renameTo(activeFile)) { "Unable to activate the Smart Infill package" }\n            } catch (error: Throwable) {\n                activeFile.delete()\n                if (activePreviousFile.exists()) activePreviousFile.renameTo(activeFile)\n                throw error\n            }\n            activePreviousFile.delete()\n        } finally {\n            activeNextFile.delete()\n            if (activeFile.exists()) activePreviousFile.delete()\n        }\n    }\n\n    @Synchronized\n    private fun recoverActivePointer() {\n        if (activeFile.isFile) {\n            activeNextFile.delete()\n            activePreviousFile.delete()\n            return\n        }\n        val candidate = when {\n            activeNextFile.isFile -> activeNextFile\n            activePreviousFile.isFile -> activePreviousFile\n            else -> return\n        }\n        val id = runCatching { candidate.readText().trim() }.getOrNull()\n        val valid = id != null && SAFE_ID.matches(id) && runCatching {\n            loadPackage(File(packagesDirectory, id))\n        }.isSuccess\n        if (valid) {\n            check(candidate.renameTo(activeFile) || candidate.copyTo(activeFile, overwrite = true).let { candidate.delete(); true }) {\n                "Unable to recover the active Smart Infill pointer"\n            }\n        } else {\n            candidate.delete()\n        }\n        activeNextFile.delete()\n        activePreviousFile.delete()\n    }\n''',
)

# Tests covering the new general-audit safety and semantics.
write('app/src/test/java/com/tomppi/enderslicer/engine/GeneralAuditSafetyRegressionTest.kt', r'''package com.tomppi.enderslicer.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneralAuditSafetyRegressionTest {
    @Test
    fun finalPolicyRejectsPersistentAndMachineControlMCodes() {
        listOf("M92 X1000", "M206 X10", "M211 S0", "M428", "M500", "M502", "M42 P1 S255").forEach { line ->
            val command = requireNotNull(GcodeCommand.parse(line))
            val error = runCatching { GcodeCommandPolicy.requirePublishedSafe(command, null, 1) }.exceptionOrNull()
            assertTrue("Expected rejection for $line", error != null)
        }
    }

    @Test
    fun finalPolicyAllowsModeledCuraAndCalibrationCommands() {
        listOf(
            "M82", "M104 S200", "M109 R200", "M140 S60", "M190 S60", "M106 S128", "M107",
            "M204 P500 T1000", "M205 X8 Y8 J0.02", "M207 S1 F1500", "M220 S50", "M221 S100",
            "M420 S1 Z10", "M572 D0 S0.05", "M900 K0.04", "M84",
        ).forEach { line ->
            GcodeCommandPolicy.requirePublishedSafe(requireNotNull(GcodeCommand.parse(line)), null, 1)
        }
    }

    @Test
    fun sanitizerRejectsUnparsedMacrosButAllowsStrictKlipperCalibrationCommands() {
        val directory = Files.createTempDirectory("general-command-policy").toFile()
        try {
            val unsafe = File(directory, "unsafe.gcode").apply {
                writeText(";LAYER:0\nRUN_SHELL_COMMAND CMD=boom\nG1 X1 Y1 Z0.2 E1 F1200\n")
            }
            assertTrue(runCatching { GcodeSanitizer.validateAndRepair(unsafe) }.isFailure)

            val safe = File(directory, "safe.gcode").apply {
                writeText(
                    ";LAYER:0\nSET_PRESSURE_ADVANCE ADVANCE=0.04\n" +
                        "SET_RETRACTION RETRACT_LENGTH=1 RETRACT_SPEED=25\nG1 X1 Y1 Z0.2 E1 F1200\n",
                )
            }
            GcodeSanitizer.validateAndRepair(
                safe,
                printerEnvelope = PrinterEnvelope(220.0, 220.0, 250.0, "rectangular", false, "Klipper"),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun layerPreviewAndSanitizerHonorM220() {
        val directory = Files.createTempDirectory("general-m220").toFile()
        try {
            val file = File(directory, "speed.gcode").apply {
                writeText(
                    ";TIME_ELAPSED:3\nG90\nM83\n;LAYER:0\n" +
                        "G1 X10 E1 F600\nM220 S50\nG1 X20 E1\nM220 S200\nG1 X30 E1\n",
                )
            }
            val preview = GcodeLayerPreviewParser.parse(file)
            assertEquals(10f, preview.layers.single().segments[4], 0.001f)
            assertEquals(5f, preview.layers.single().segments[10], 0.001f)
            assertEquals(20f, preview.layers.single().segments[16], 0.001f)
            val summary = GcodeSanitizer.validateAndRepair(file)
            assertEquals(4, summary.estimatedSeconds)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun reprapToolSettingG10IsNotRetraction() {
        val firmware = CalibrationFirmwareEncoder.fromFlavor("RepRapFirmware")
        assertTrue(!firmware.isFirmwareRetract(requireNotNull(GcodeCommand.parse("G10 P0 S200 R150"))))
        assertTrue(firmware.isFirmwareRetract(requireNotNull(GcodeCommand.parse("G10"))))
        assertTrue(firmware.isFirmwareUnretract(requireNotNull(GcodeCommand.parse("G11"))))
    }
}
''')
write('app/src/test/java/com/tomppi/enderslicer/profile/CuraArchiveGlobalBudgetTest.kt', r'''package com.tomppi.enderslicer.profile

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test

class CuraArchiveGlobalBudgetTest {
    @Test
    fun ignoredEntriesConsumeGlobalInflatedBudget() {
        val archive = zip("ignored.bin" to ByteArray(4_096), "Cura/a.cfg" to "ok".toByteArray())
        val failure = runCatching {
            CuraArchive.readTextEntries(
                ByteArrayInputStream(archive),
                maximumInflatedBytes = 1_024,
                accept = { it.startsWith("Cura/") },
            )
        }.exceptionOrNull()
        assertTrue(failure != null)
    }

    @Test
    fun ignoredEntriesConsumeGlobalEntryBudget() {
        val archive = zip(*Array(5) { index -> "ignored-$index" to byteArrayOf(index.toByte()) })
        val failure = runCatching {
            CuraArchive.readTextEntries(
                ByteArrayInputStream(archive),
                maximumArchiveEntries = 3,
                accept = { false },
            )
        }.exceptionOrNull()
        assertTrue(failure != null)
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value)
                zip.closeEntry()
            }
        }
    }.toByteArray()
}
''')
write('app/src/test/java/com/tomppi/enderslicer/ui/GeneralAuditSourceContractTest.kt', r'''package com.tomppi.enderslicer.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneralAuditSourceContractTest {
    @Test
    fun curviCalibrationAndSmartInfillValidationFailClosed() {
        val viewModel = source("ui/MainViewModel.kt")
        val integrated = source("ui/IntegratedEnderSlicerApp.kt")
        assertTrue(viewModel.contains("plannedEventsSnapshot.isNotEmpty() && CurviSlicerRuntime.snapshot() != null"))
        assertTrue(integrated.contains("smartInfillValidating"))
        assertTrue(integrated.contains("SmartInfillRuntime.activate(null)"))
        assertTrue(integrated.contains("sliceBlockedReason"))
    }

    @Test
    fun pendingResultsAndExportsHaveDurableOwnership() {
        val viewModel = source("ui/MainViewModel.kt")
        val integrated = source("ui/IntegratedEnderSlicerApp.kt")
        assertTrue(viewModel.contains("deferUntilRestoreCompletes"))
        assertTrue(viewModel.contains("pendingExportStore.begin(uri)"))
        assertTrue(viewModel.contains("pendingExportStore.fail(app.contentResolver, uri)"))
        assertTrue(integrated.contains("processSmartInfillResult"))
    }

    @Test
    fun apiKeyDraftIsNotSaveable() {
        val sheet = source("ui/HardenedOctoPrintSheet.kt")
        assertTrue(sheet.contains("var apiKey by remember { mutableStateOf(\"\") }"))
        assertFalse(sheet.contains("var apiKey by rememberSaveable"))
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/tomppi/enderslicer/$relative"),
            File("app/src/main/java/com/tomppi/enderslicer/$relative"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Unable to find source file $relative")
    }
}
''')

# Documentation records the fail-closed combinations and transactional guarantees.
write('docs/general-audit-round-1-fixes.md', '''# General application audit — round 1 fixes

This revision resolves the 13 retained findings from the first whole-application audit.

Key safety changes:

- final G-code publication uses an exhaustive command-family policy and rejects unmodeled M commands and macros;
- CurviSlicer cannot run with scalar-height calibration plans;
- Layers and print-time estimates apply M220 feed factors consistently with Path;
- RepRapFirmware parameterized G10 is not treated as firmware retraction;
- workspace, Cura import, OctoPrint credential, Smart Infill pointer, and SAF export state have serialized or recoverable transactions;
- picker and tool results received during process restoration are queued for exactly-once replay;
- ignored ZIP entries consume global entry, inflation, ratio, time, and cancellation budgets;
- Smart Infill is removed from the runtime slice snapshot while source validation is in progress;
- plaintext API-key drafts are transient and cleared when endpoint identity changes.

Rejected slices and failed exports do not publish a completed artifact. The pull request remains draft pending physical printer validation.
''')

print('Applied all 13 general-audit fixes and regression tests')
