#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${1:-$ROOT/app/src/main/assets/cura/definitions}"
CURA_TAG="5.14.0-alpha.0"
RAW_BASE="https://raw.githubusercontent.com/Ultimaker/Cura/$CURA_TAG/resources"

mkdir -p "$DEST"

fetch_resource() {
  local group="$1"
  local name="$2"
  curl --fail --location --retry 3 \
    "$RAW_BASE/$group/$name.def.json" \
    --output "$DEST/$name.def.json"
}

# Complete transitive definition chain for the Ender 3 V2 machine and its
# single Creality extruder train. Keep these resources on exactly the same Cura
# version as the embedded engine and the user's reference project.
for name in fdmprinter fdmextruder creality_base creality_ender3; do
  fetch_resource definitions "$name"
done
fetch_resource extruders creality_base_extruder_0

python3 - "$DEST" <<'PY'
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
files = {
    path.name.removesuffix(".def.json"): path
    for path in root.glob("*.def.json")
}

roots = ["creality_ender3"]
seen = set()
missing = []


def dependencies(definition_id: str):
    path = files.get(definition_id)
    if path is None:
        missing.append(definition_id)
        return []
    data = json.loads(path.read_text(encoding="utf-8"))
    result = []
    parent = data.get("inherits")
    if parent:
        result.append(parent)
    trains = data.get("metadata", {}).get("machine_extruder_trains", {})
    result.extend(str(value) for value in trains.values())
    return result


def visit(definition_id: str):
    if definition_id in seen:
        return
    seen.add(definition_id)
    for dependency in dependencies(definition_id):
        visit(dependency)


for definition_id in roots:
    visit(definition_id)

if missing:
    raise SystemExit(
        "Missing Cura definition dependencies: " + ", ".join(sorted(set(missing)))
    )

required = {
    "fdmprinter",
    "fdmextruder",
    "creality_base",
    "creality_base_extruder_0",
    "creality_ender3",
}
not_fetched = required - files.keys()
if not_fetched:
    raise SystemExit("Required definitions were not fetched: " + ", ".join(sorted(not_fetched)))


def contains_key(value, key):
    if isinstance(value, dict):
        return key in value or any(contains_key(child, key) for child in value.values())
    if isinstance(value, list):
        return any(contains_key(child, key) for child in value)
    return False

fdmprinter = json.loads(files["fdmprinter"].read_text(encoding="utf-8"))
if not contains_key(fdmprinter, "roofing_layer_count"):
    raise SystemExit("fdmprinter.def.json does not define roofing_layer_count")

# CuraEngine 5.14 main reads several settings (Inner Wall Inset + the support
# base feature) that the pinned 5.14.0-alpha.0 frontend definitions predate.
# Inject them with their safe defaults (no wall inset, support base disabled) so
# the engine can retrieve them without failing.
ENGINE_DRIFT_SETTINGS = {
    "shell": {
        "wall_x_inset": {
            "label": "Inner Wall Inset",
            "description": "Inset applied to the path of the inner wall(s).",
            "unit": "mm",
            "type": "float",
            "default_value": 0.0,
            "settable_per_mesh": True,
        },
        "wall_generator": {
            "label": "Wall Generator",
            "description": "The wall generator to use: Arachne (variable line width) or Classic (constant line width).",
            "type": "enum",
            "options": {
                "arachne": "Arachne",
                "classic": "Classic",
            },
            "default_value": "arachne",
            "settable_per_mesh": True,
        },
    },
    "support": {
        "support_base_inside_width": {
            "label": "Support Base Inside Width",
            "description": "The width of the inside support base.",
            "unit": "mm",
            "type": "float",
            "default_value": 0.0,
            "settable_per_extruder": True,
        },
        "support_base_outside_width": {
            "label": "Support Base Outside Width",
            "description": "The width of the outside support base.",
            "unit": "mm",
            "type": "float",
            "default_value": 0.0,
            "settable_per_extruder": True,
        },
        "support_outer_brim_enable": {
            "label": "Enable Outer Support Base",
            "description": "Generate a base around the support infill regions.",
            "type": "bool",
            "default_value": False,
            "settable_per_extruder": True,
        },
        "support_inside_base_curve_magnitude": {
            "label": "Support Inside Base Slope",
            "description": "The magnitude factor used for the slope of the inside support base.",
            "type": "float",
            "default_value": 4.0,
            "settable_per_extruder": True,
        },
        "support_inside_base_height": {
            "label": "Support Inside Base Height",
            "description": "The height of the inside support base.",
            "unit": "mm",
            "type": "float",
            "default_value": 0.0,
            "settable_per_extruder": True,
        },
        "support_outside_base_curve_magnitude": {
            "label": "Support Outside Base Slope",
            "description": "The magnitude factor used for the slope of the outside support base.",
            "type": "float",
            "default_value": 4.0,
            "settable_per_extruder": True,
        },
        "support_outside_base_height": {
            "label": "Support Outside Base Height",
            "description": "The height of the outside support base.",
            "unit": "mm",
            "type": "float",
            "default_value": 0.0,
            "settable_per_extruder": True,
        },
    },
}
injected = False
for category, settings in ENGINE_DRIFT_SETTINGS.items():
    children = fdmprinter.setdefault("settings", {}).setdefault(category, {}).setdefault("children", {})
    for name, definition in settings.items():
        if name not in children:
            children[name] = definition
            injected = True
if injected:
    files["fdmprinter"].write_text(json.dumps(fdmprinter, indent=4) + "\n", encoding="utf-8")

print("Validated Cura definition closure: " + " -> ".join(sorted(seen)))
PY

cat > "$DEST/version.txt" <<'EOF'
Cura resources: 5.14.0-alpha.0
Setting version: 27
Files: fdmprinter.def.json, fdmextruder.def.json, creality_base.def.json, creality_base_extruder_0.def.json, creality_ender3.def.json
EOF

printf 'Fetched and validated Cura 5.14.0-alpha.0 definition chain into %s\n' "$DEST"
