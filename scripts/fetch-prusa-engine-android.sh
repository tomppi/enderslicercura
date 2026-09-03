#!/usr/bin/env bash
# Places the PrusaSlicer ARM64 engine (binary + resources) into the app package.
#
#   CI:           downloads the latest successful "PrusaSlicer-2.9.6-android-arm64"
#                 artifact from the prusa-engine-android workflow (needs
#                 GITHUB_TOKEN with actions:read for the repo).
#   Local:        set PRUSA_ENGINE_DIR to a directory that contains the binary and
#                 resources:  <dir>/prusa-slicer  and  <dir>/resources
#
# Outputs:
#   app/src/main/jniLibs/arm64-v8a/libprusa_slicer_exec.so  (stripped)
#   app/src/main/assets/prusa/resources/...
set -euo pipefail

REPO="${GITHUB_REPOSITORY:-tomppi/enderslicercura}"
APP_JNILIBS="app/src/main/jniLibs/arm64-v8a"
APP_ASSETS="app/src/main/assets/prusa"

if [ -n "${PRUSA_ENGINE_DIR:-}" ]; then
  echo "Using local Prusa engine directory: $PRUSA_ENGINE_DIR"
  SRC_DIR="$PRUSA_ENGINE_DIR"
else
  echo "Fetching published PrusaSlicer engine from the repo"
  BASE="https://raw.githubusercontent.com/$REPO/feature/prusa-engine-android/.build-artifacts/prusa-engine"
  rm -rf .build/prusa-engine-download
  mkdir -p .build/prusa-engine-download
  curl -fsSL -m 600 --retry 5 -o .build/prusa-engine-download/prusa-slicer "$BASE/prusa-slicer"
  curl -fsSL -m 900 --retry 5 -o .build/prusa-engine-download/resources.tar.gz "$BASE/resources.tar.gz"
  ( cd .build/prusa-engine-download && tar -xzf resources.tar.gz )
  SRC_DIR=".build/prusa-engine-download"
  echo "Engine published files at: $SRC_DIR"
fi

mkdir -p "$APP_JNILIBS" "$APP_ASSETS"
cp "$SRC_DIR/prusa-slicer" "$APP_JNILIBS/libprusa_slicer_exec.so"

# Strip with the NDK when available (CI installs it; local builds skip if missing).
for CAND in \
  "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" \
  "${ANDROID_HOME}/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"; do
  if [ -x "$CAND" ]; then
    cp "$APP_JNILIBS/libprusa_slicer_exec.so" "$APP_JNILIBS/.prusa-unstripped"
    "$CAND" -s "$APP_JNILIBS/.prusa-unstripped" -o "$APP_JNILIBS/libprusa_slicer_exec.so"
    rm -f "$APP_JNILIBS/.prusa-unstripped"
    echo "stripped with $CAND"
    break
  fi
done

rm -rf "$APP_ASSETS/resources"
cp -r "$SRC_DIR/resources" "$APP_ASSETS/resources"

echo "== packaged engine =="
ls -la "$APP_JNILIBS/libprusa_slicer_exec.so"
du -sh "$APP_ASSETS/resources"
