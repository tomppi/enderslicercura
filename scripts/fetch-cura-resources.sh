#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${1:-$ROOT/app/src/main/assets/cura/definitions}"
CURA_TAG="5.11.0"
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
# single Creality extruder train. CuraEngine resolves inherited definitions by
# ID from the directory passed with -d, regardless of their source subfolder.
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

# This was the first fatal missing default found by the phone-side diagnostic
# log. Verify that the complete printer base definition really contains it.
def contains_key(value, key):
    if isinstance(value, dict):
        return key in value or any(contains_key(child, key) for child in value.values())
    if isinstance(value, list):
        return any(contains_key(child, key) for child in value)
    return False

fdmprinter = json.loads(files["fdmprinter"].read_text(encoding="utf-8"))
if not contains_key(fdmprinter, "roofing_layer_count"):
    raise SystemExit("fdmprinter.def.json does not define roofing_layer_count")

print("Validated Cura definition closure: " + " -> ".join(sorted(seen)))
PY

cat > "$DEST/version.txt" <<'EOF'
Cura resources: 5.11.0
Setting version: 25
Files: fdmprinter.def.json, fdmextruder.def.json, creality_base.def.json, creality_base_extruder_0.def.json, creality_ender3.def.json
EOF

printf 'Fetched and validated Cura 5.11 definition chain into %s\n' "$DEST"
