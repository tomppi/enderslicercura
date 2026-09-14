#!/usr/bin/env bash
# Stage the embedded Blender engine into the app.
#
# The engine is not in this repository and cannot be: libblender_exec.so is
# ~125 MB and the runtime assets (CPython stdlib, Blender's scripts, datafiles)
# are another ~400 MB, all of them built rather than source. Gradle will refuse
# to assemble without them.
#
# Two ways to provide it:
#
#   BLENDER_ENGINE_DIR=/path/to/blender-engine-arm64   ./scripts/fetch-blender-engine-android.sh
#   BLENDER_ENGINE_TAG=v1.2.0                          ./scripts/fetch-blender-engine-android.sh
#
# The directory form expects the layout native/blender/README.md describes:
#
#   <dir>/blender                      the arm64 ELF the build links from
#   <dir>/libs/*.a                     the 148 static libraries
#   <dir>/python/                      CPython 3.11.4 stdlib
#   <dir>/scripts/                     Blender scripts, including our MCP addon
#   <dir>/3.6/config/datafiles/        OCIO and locale datafiles
#
# The tag form downloads that package from this repository's releases. No such
# asset is published yet - this script fails with that message rather than
# pretending otherwise.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI="${ROOT}/app/src/main/jniLibs/arm64-v8a"
ASSETS="${ROOT}/app/src/main/assets/blender"

stage() {
  local dir="$1"
  [ -d "$dir" ] || { echo "no such directory: $dir" >&2; exit 1; }

  mkdir -p "${JNI}" "${ASSETS}"

  if [ -f "${dir}/blender" ]; then
    cp "${dir}/blender" "${JNI}/libblender_exec.so"
    echo "staged libblender_exec.so ($(du -h "${JNI}/libblender_exec.so" | cut -f1))"
  elif [ -f "${dir}/libblender_exec.so" ]; then
    cp "${dir}/libblender_exec.so" "${JNI}/libblender_exec.so"
    echo "staged libblender_exec.so"
  else
    echo "no engine binary in $dir (expected blender or libblender_exec.so)" >&2
    exit 1
  fi

  for part in python scripts; do
    [ -d "${dir}/${part}" ] && cp -R "${dir}/${part}" "${ASSETS}/"
  done
  [ -d "${dir}/3.6" ] && { mkdir -p "${ASSETS}/3.6"; cp -R "${dir}/3.6/." "${ASSETS}/3.6/"; }

  echo "staged assets: $(du -sh "${ASSETS}" | cut -f1)"
  echo
  echo "Next: ./gradlew :app:assembleDebug"
  echo "Note: app/src/main/assets/blender is gitignored and is delivery, not source."
  echo "      Anything you edit there must be copied back to native/blender/."
}

if [ -n "${BLENDER_ENGINE_DIR:-}" ]; then
  stage "${BLENDER_ENGINE_DIR}"
  exit 0
fi

TAG="${BLENDER_ENGINE_TAG:-v1.2.0}"
URL="https://github.com/tomppi/enderslicercura/releases/download/${TAG}/blender-engine-arm64-${TAG}.tar.zst"

if ! command -v gh >/dev/null 2>&1; then
  echo "gh is not installed, and no BLENDER_ENGINE_DIR was given." >&2
  echo "Either install gh, or point BLENDER_ENGINE_DIR at a built engine package." >&2
  exit 1
fi

if ! gh release view "${TAG}" >/dev/null 2>&1; then
  echo "release ${TAG} not found" >&2; exit 1
fi

if ! gh release view "${TAG}" --json assets --jq '.assets[].name' | grep -qx "blender-engine-arm64-${TAG}.tar.zst"; then
  cat >&2 <<'EOF'
No engine asset is published for this release yet.

The engine is built from the epai/APP-android_arm64 port and patched, which is a
build of its own rather than a download - see native/blender/README.md and
native/blender/patches/. Until that asset is published, build it locally and run
this script with BLENDER_ENGINE_DIR.
EOF
  exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
echo "downloading ${URL}"
curl -fL --progress-bar "${URL}" -o "${WORK}/engine.tar.zst"
command -v zstd >/dev/null 2>&1 || { echo "zstd is required to unpack the engine" >&2; exit 1; }
tar --zstd -xf "${WORK}/engine.tar.zst" -C "${WORK}"
stage "${WORK}/blender-engine-arm64"
