#!/usr/bin/env bash
# Places the PrusaSlicer 3.0.0-alpha11 ARM64 engine (console + resources) into the app package.
#
#   CI:    downloads the newest successful "PrusaSlicer-3.0.0-alpha11-android-arm64-v8a"
#          artifact of the prusa-engine-3 workflow, on any branch (main once the
#          engine work is merged; the workflow also runs on
#          feature/prusa-engine-android). Requires a GITHUB_TOKEN with
#          actions:read for the repository.
#   Local: set PRUSA_ENGINE_DIR to a directory that contains the console and the
#          resources:  <dir>/prusa-slicer  and  <dir>/resources
#
# Outputs:
#   app/src/main/jniLibs/arm64-v8a/libprusa_slicer_exec.so  (stripped)
#   app/src/main/assets/prusa/resources/...
set -euo pipefail

REPO="${GITHUB_REPOSITORY:-tomppi/enderslicercura}"
WORKFLOW="prusa-engine-3.yml"
ARTIFACT_NAME="PrusaSlicer-3.0.0-alpha11-android-arm64-v8a"
APP_JNILIBS="app/src/main/jniLibs/arm64-v8a"
APP_ASSETS="app/src/main/assets/prusa"
DOWNLOAD_DIR=".build/prusa-engine-download"

api () {
  curl -fsSL -H "Accept: application/vnd.github+json" \
    -H "Authorization: Bearer ${GITHUB_TOKEN:?GITHUB_TOKEN is required to download the engine artifact}" "$@"
}

if [ -n "${PRUSA_ENGINE_DIR:-}" ]; then
  echo "Using local Prusa engine directory: $PRUSA_ENGINE_DIR"
  SRC_DIR="$PRUSA_ENGINE_DIR"
else
  echo "Fetching the newest successful $ARTIFACT_NAME artifact of $WORKFLOW"
  RUN_ID=$(api "https://api.github.com/repos/$REPO/actions/workflows/$WORKFLOW/runs?status=success&per_page=1" \
    | python3 -c 'import json,sys; runs=json.load(sys.stdin)["workflow_runs"]; print(runs[0]["id"] if runs else "")')
  if [ -z "$RUN_ID" ]; then
    echo "::error::no successful $WORKFLOW run found"
    exit 1
  fi
  echo "Using workflow run $RUN_ID"

  ARTIFACT_ID=$(ARTIFACT_NAME="$ARTIFACT_NAME" api "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID/artifacts" \
    | python3 -c 'import json,os,sys; want=os.environ["ARTIFACT_NAME"]; matches=[a for a in json.load(sys.stdin)["artifacts"] if a["name"]==want]; print(matches[0]["id"] if matches else "")')
  if [ -z "$ARTIFACT_ID" ]; then
    echo "::error::run $RUN_ID has no artifact named $ARTIFACT_NAME"
    exit 1
  fi
  echo "Downloading artifact $ARTIFACT_ID"

  rm -rf "$DOWNLOAD_DIR"
  mkdir -p "$DOWNLOAD_DIR/unpacked"
  api -o "$DOWNLOAD_DIR/engine.zip" "https://api.github.com/repos/$REPO/actions/artifacts/$ARTIFACT_ID/zip"
  unzip -q -o "$DOWNLOAD_DIR/engine.zip" -d "$DOWNLOAD_DIR/unpacked"

  SRC_DIR="$DOWNLOAD_DIR/unpacked/prusa3-build/out"
  if [ ! -f "$SRC_DIR/prusa-slicer" ]; then
    echo "::error::artifact $ARTIFACT_NAME does not contain prusa3-build/out/prusa-slicer"
    find "$DOWNLOAD_DIR/unpacked" -maxdepth 3 | head -40
    exit 1
  fi
  echo "Engine published files at: $SRC_DIR"
fi

mkdir -p "$APP_JNILIBS" "$APP_ASSETS"
cp "$SRC_DIR/prusa-slicer" "$APP_JNILIBS/libprusa_slicer_exec.so"

# Strip with the NDK when available (CI installs it; local builds skip if missing).
for CAND in \
  "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" \
  "${ANDROID_HOME:-}/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"; do
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
test -s "$APP_ASSETS/resources/presets/prusa-research-fff/PrusaResearch/vendor.yaml"

echo "== packaged engine =="
ls -la "$APP_JNILIBS/libprusa_slicer_exec.so"
du -sh "$APP_ASSETS/resources"
