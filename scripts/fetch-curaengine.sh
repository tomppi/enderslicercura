#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/third_party/CuraEngine"

if [[ -e "$DEST/.git" ]]; then
  echo "CuraEngine already exists at $DEST"
  exit 0
fi

mkdir -p "$ROOT/third_party"
git clone --depth 1 https://github.com/Ultimaker/CuraEngine.git "$DEST"
git -C "$DEST" fetch --depth 1 origin a27787d68548bef9725e1126468394fb8a661e1b
git -C "$DEST" checkout FETCH_HEAD
echo "Fetched CuraEngine 5.14.0-alpha.0. Read docs/CURAENGINE_ANDROID.md before wiring it into the APK."
