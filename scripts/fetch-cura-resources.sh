#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${1:-$ROOT/app/src/main/assets/cura/definitions}"
BASE="https://raw.githubusercontent.com/Ultimaker/Cura/5.11.0/resources/definitions"

mkdir -p "$DEST"
for name in fdmprinter creality_base creality_ender3; do
  curl --fail --location --retry 3 \
    "$BASE/$name.def.json" \
    --output "$DEST/$name.def.json"
done

cat > "$DEST/version.txt" <<'EOF'
Cura resources: 5.11.0
Setting version: 25
Files: fdmprinter.def.json, creality_base.def.json, creality_ender3.def.json
EOF

printf 'Fetched Cura 5.11 definitions into %s\n' "$DEST"
