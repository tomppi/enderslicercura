#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise RuntimeError(f"Unexpected match count for {path}: {old[:100]!r}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


REPOSITORY = "app/src/main/java/com/tomppi/enderslicer/octoprint/OctoPrintRepository.kt"
replace_once(
    REPOSITORY,
    '''        val remoteName = sanitizeGcodeName(suggestedName.ifBlank { source.name })
        scope.launch {
''',
    '''        if (_state.value.isUploading) {
            setError(IllegalStateException("A G-code upload is already in progress"))
            return
        }
        val remoteName = sanitizeGcodeName(suggestedName.ifBlank { source.name })
        scope.launch {
''',
)
replace_once(
    REPOSITORY,
    '    fun disconnect() = operation("Disconnecting printer…") { disconnect() }\n',
    '''    fun disconnect() {
        val current = _state.value
        if (current.isPrinting || current.isPaused) {
            setError(IllegalStateException("Cancel or finish the active print before disconnecting the printer"))
            return
        }
        operation("Disconnecting printer…") { disconnect() }
    }
''',
)
replace_once(
    REPOSITORY,
    '    fun sendGcode(command: String) = operation("Sending G-code command…") { sendGcode(command) }\n',
    '''    fun sendGcode(command: String) {
        if (!requireIdlePrinterAction("Terminal commands")) return
        operation("Sending G-code command…") { sendGcode(command) }
    }
''',
)
replace_once(
    REPOSITORY,
    '                snapshot.outputStream().buffered().use(input::copyTo)\n',
    '                snapshot.outputStream().buffered().use { output -> input.copyTo(output) }\n',
)

SHEET = "app/src/main/java/com/tomppi/enderslicer/ui/OctoPrintSheet.kt"
replace_once(
    SHEET,
    '                        enabled = folderName.isNotBlank(),\n',
    '                        enabled = state.isReady && folderName.isNotBlank(),\n',
)
replace_once(
    SHEET,
    '                        enabled = selected != null,\n                    ) {\n                        Text("Copy")\n',
    '                        enabled = state.isReady && selected != null,\n                    ) {\n                        Text("Copy")\n',
)
replace_once(
    SHEET,
    '                        enabled = selected != null,\n                    ) {\n                        Text("Move")\n',
    '                        enabled = state.isReady && selected != null,\n                    ) {\n                        Text("Move")\n',
)

DOC = ROOT / "docs/bug-audit.md"
text = DOC.read_text(encoding="utf-8")
needle = "- Serialize printer commands and reject motion, homing, extrusion and retraction unless the printer is operational and idle.\n"
replacement = "- Serialize printer commands and reject motion, homing, extrusion, retraction and manual terminal commands unless the printer is operational and idle. Active jobs also block serial disconnects.\n- Reject duplicate upload requests while a transfer is already active.\n"
if text.count(needle) != 1:
    raise RuntimeError("Unexpected bug-audit documentation state")
DOC.write_text(text.replace(needle, replacement, 1), encoding="utf-8")

print("Applied bug audit follow-up")
