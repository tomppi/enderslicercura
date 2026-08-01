#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]

models = root / "app/src/main/java/com/tomppi/enderslicer/octoprint/OctoPrintModels.kt"
text = models.read_text(encoding="utf-8")
old = '''        val match = Regex(
            pattern = "^([0-9]+(?:\\.[0-9]+)?)\\s*([KMGTPE]?I?B)$",
            option = RegexOption.IGNORE_CASE,
        ).matchEntire(value) ?: return null
'''
new = '''        val match = Regex(
            pattern = """^([0-9]+(?:\\.[0-9]+)?)\\s*([KMGTPE]?I?B)$""",
            option = RegexOption.IGNORE_CASE,
        ).matchEntire(value) ?: return null
'''
if text.count(old) != 1:
    raise RuntimeError("Unexpected byte-count regex state")
models.write_text(text.replace(old, new, 1), encoding="utf-8")

sheet = root / "app/src/main/java/com/tomppi/enderslicer/ui/OctoPrintSheet.kt"
text = sheet.read_text(encoding="utf-8")
old = '''            if (bitmap == null) {
                Text(
                    if (state.config.snapshotUrlOverride.isNotBlank() || state.webcam.snapshotUrl != null) {
                        "Waiting for a webcam snapshot…"
                    } else {
                        "No snapshot URL is available. Add one on the Setup page."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Image(
                    bitmap = bitmap.asImageBitmap(),
'''
new = '''            val currentBitmap = bitmap
            if (currentBitmap == null) {
                Text(
                    if (state.config.snapshotUrlOverride.isNotBlank() || state.webcam.snapshotUrl != null) {
                        "Waiting for a webcam snapshot…"
                    } else {
                        "No snapshot URL is available. Add one on the Setup page."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Image(
                    bitmap = currentBitmap.asImageBitmap(),
'''
if text.count(old) != 1:
    raise RuntimeError("Unexpected webcam bitmap block")
sheet.write_text(text.replace(old, new, 1), encoding="utf-8")

print("Applied audit compile fixes")
