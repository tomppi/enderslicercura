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
  echo "Fetching latest PrusaSlicer engine artifact from $REPO"
  AUTH=()
  if [ -n "${GITHUB_TOKEN:-}" ]; then
    AUTH=(-H "Authorization: Bearer $GITHUB_TOKEN")
  fi
  WORKFLOW_RUNS=$(curl -fsSL -m 120 --retry 3 "${AUTH[@]}" \
    "https://api.github.com/repos/$REPO/actions/workflows/prusa-engine-android.yml/runs?status=success&per_page=1")
  RUN_ID=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["workflow_runs"][0]["id"])' <<<"$WORKFLOW_RUNS")
  echo "Latest successful build: run $RUN_ID"
  ARTIFACTS=$(curl -fsSL -m 120 --retry 3 "${AUTH[@]}" \
    "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID/artifacts")
  ARTIFACT_URL=$(python3 -c 'import json,sys; d=json.load(sys.stdin); print(next(x["archive_download_url"] for x in d["artifacts"] if x["name"]=="PrusaSlicer-2.9.6-android-arm64"))' <<<"$ARTIFACTS")
  rm -rf .build/prusa-engine-download
  mkdir -p .build/prusa-engine-download/extract
  curl -fsSL -m 600 --retry 3 "${AUTH[@]}" -o .build/prusa-engine-download/engine.zip "$ARTIFACT_URL"
  unzip -q .build/prusa-engine-download/engine.zip -d .build/prusa-engine-download/extract
  SRC_DIR=$(find .build/prusa-engine-download/extract -type f -name 'prusa-slicer' -printf '%h' | head -1)
  [ -n "$SRC_DIR" ] || { echo "prusa-slicer binary not found in the artifact"; exit 1; }
  echo "Extracted engine at: $SRC_DIR"
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
