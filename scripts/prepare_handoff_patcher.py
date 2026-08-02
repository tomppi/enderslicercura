#!/usr/bin/env python3
from pathlib import Path

path = Path(__file__).with_name("apply_handoff_lifecycle_patch.py")
content = path.read_text()
old = '''    content = replace_once(
        content,
        """                    current.copy(
                        gcodePath = result.gcodeFile.absolutePath,
""",
        """                    current.copy(
                        sliceResultId = result.artifactId,
                        gcodePath = result.gcodeFile.absolutePath,
""",
        "Slice result identity",
    )
'''
new = '''    content = replace_once(
        content,
        """                    val printTime = result.estimatedPrintSeconds?.let(::formatPrintTime)
                    current.copy(
                        gcodePath = result.gcodeFile.absolutePath,
                        baseGcodePath = result.baseGcodeFile.absolutePath,
""",
        """                    val printTime = result.estimatedPrintSeconds?.let(::formatPrintTime)
                    current.copy(
                        sliceResultId = result.artifactId,
                        gcodePath = result.gcodeFile.absolutePath,
                        baseGcodePath = result.baseGcodeFile.absolutePath,
""",
        "Slice result identity",
    )
'''
count = content.count(old)
if count != 1:
    raise RuntimeError(f"Slice-result patcher normalization expected one match, found {count}")
path.write_text(content.replace(old, new, 1))
print("Normalized the slice-result patch rule")
