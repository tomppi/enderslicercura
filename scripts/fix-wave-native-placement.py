#!/usr/bin/env python3
from pathlib import Path

path = Path(__file__).resolve().with_name("build-curaengine-android.sh")
text = path.read_text()
marker = "replace(\n    fff_gcode_writer_cpp,\n    '''    const bool monotonic = mesh.settings.get<bool>(\"skin_monotonic\");"
start = text.find(marker)
if start < 0:
    raise SystemExit("Wave/arc replacement block was not found")
end_marker = "''',\n)\nPY"
end = text.find(end_marker, start)
if end < 0:
    raise SystemExit("Wave/arc replacement block terminator was not found")
end += len("''',\n)")
block = text[start:end]
separator = "''',\n    '''"
first_payload_start = block.find("'''") + 3
separator_at = block.find(separator, first_payload_start)
if separator_at < 0:
    raise SystemExit("Wave/arc replacement payload separator was not found")
first_payload = block[first_payload_start:separator_at]
second_payload_start = separator_at + len(separator)
second_payload_end = block.rfind("''',\n)")
second_payload = block[second_payload_start:second_payload_end]
wave_start = first_payload.find("    if (wave_overhang_enabled && ! wave_supported_skin_regions.empty())")
wave_end = first_payload.rfind("    processSkinPrintFeature(")
if wave_start < 0 or wave_end <= wave_start:
    raise SystemExit("Wave payload could not be isolated")
wave_payload = first_payload[wave_start:wave_end]
minimal_source = '''    const bool monotonic = mesh.settings.get<bool>("skin_monotonic");
    processSkinPrintFeature('''
corrected = (
    "replace(\n"
    "    fff_gcode_writer_cpp,\n"
    "    '''" + minimal_source + "''',\n"
    "    '''" + wave_payload + second_payload + "''',\n"
    ")"
)
updated = text[:start] + corrected + text[end:]
if updated == text:
    raise SystemExit("Wave native placement repair made no changes")
path.write_text(updated)
print("Repaired Wave/Arc generated patch placement")
