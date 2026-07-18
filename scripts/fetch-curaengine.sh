#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/third_party/CuraEngine"

if [[ -e "$DEST/.git" ]]; then
  echo "CuraEngine already exists at $DEST"
  exit 0
fi

mkdir -p "$ROOT/third_party"
git clone --depth 1 --branch 5.11.0 https://github.com/Ultimaker/CuraEngine.git "$DEST"
echo "Fetched CuraEngine 5.11.0. Read docs/CURAENGINE_ANDROID.md before wiring it into the APK."
