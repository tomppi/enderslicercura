#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
path = root / "app/src/main/java/com/tomppi/enderslicer/octoprint/OctoPrintRepository.kt"
text = path.read_text(encoding="utf-8")

old = '''        if (_state.value.isUploading) {
            setError(IllegalStateException("A G-code upload is already in progress"))
            return
        }
        val remoteName = sanitizeGcodeName(suggestedName.ifBlank { source.name })
        scope.launch {
            _state.update {
                it.copy(
                    isUploading = true,
                    uploadProgress = 0f,
                    uploadFileName = remoteName,
                    statusMessage = "Uploading $remoteName to OctoPrint…",
                    errorMessage = null,
                )
            }
            runCatching {
'''
new = '''        if (_state.value.isUploading) {
            val message = "A G-code upload is already in progress"
            _state.update { it.copy(statusMessage = message, errorMessage = message) }
            return
        }
        val remoteName = sanitizeGcodeName(suggestedName.ifBlank { source.name })
        _state.update {
            it.copy(
                isUploading = true,
                uploadProgress = 0f,
                uploadFileName = remoteName,
                statusMessage = "Uploading $remoteName to OctoPrint…",
                errorMessage = null,
            )
        }
        scope.launch {
            runCatching {
'''
if text.count(old) != 1:
    raise RuntimeError("Unexpected upload state block")
text = text.replace(old, new, 1)

old = '''        _state.update {
            it.copy(
                isRefreshing = false,
                isFileListRefreshing = false,
                isUploading = false,
                statusMessage = message,
                errorMessage = message,
            )
        }
'''
new = '''        _state.update {
            it.copy(
                statusMessage = message,
                errorMessage = message,
            )
        }
'''
if text.count(old) != 1:
    raise RuntimeError("Unexpected generic error state block")
text = text.replace(old, new, 1)
path.write_text(text, encoding="utf-8")

doc = root / "docs/bug-audit.md"
doc_text = doc.read_text(encoding="utf-8")
needle = "- Reject duplicate upload requests while a transfer is already active.\n"
replacement = "- Claim upload state before launching the transfer, reject duplicate requests, and keep unrelated command errors from clearing active upload or refresh indicators.\n"
if doc_text.count(needle) != 1:
    raise RuntimeError("Unexpected audit documentation")
doc.write_text(doc_text.replace(needle, replacement, 1), encoding="utf-8")

print("Applied operation state ownership fix")
