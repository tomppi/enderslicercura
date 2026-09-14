#!/usr/bin/env bash
# One-command setup: from a clean clone to a debug APK.
#
#   ./scripts/setup.sh
#
# Fails early, and says which script to run, rather than letting Gradle discover
# the problem several minutes in. Each engine is staged by its own script, in the
# same shape app/build.gradle.kts already expects:
#
#   CuraEngine    built from source      scripts/build-curaengine-android.sh
#   PrusaSlicer   fetched or built       scripts/fetch-prusa-engine-android.sh
#   Blender       staged from a package  scripts/fetch-blender-engine-android.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

fail() { echo; echo "SETUP FAILED: $*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

echo "== toolchain =="
have java || fail "java not found; JDK 17 is required"
JAVA_MAJOR="$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
[ "${JAVA_MAJOR}" = "17" ] || echo "  warning: found Java ${JAVA_MAJOR}, the build targets 17"
have git || fail "git not found"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "${SDK}" ] && [ -f local.properties ]; then
  SDK="$(sed -n 's/^sdk.dir=//p' local.properties | sed 's/\\\\/\//g')"
fi
[ -n "${SDK}" ] && [ -d "${SDK}" ] || fail "Android SDK not found; set ANDROID_HOME or write sdk.dir in local.properties"
echo "  SDK  ${SDK}"
[ -d "${SDK}/ndk/28.2.13676358" ] || echo "  warning: NDK 28.2.13676358 not found; the Cura and Prusa builds need it"

echo
echo "== engines =="
JNI="app/src/main/jniLibs/arm64-v8a"
mkdir -p "${JNI}" app/src/main/assets/blender

if [ -s "${JNI}/libcuraengine_exec.so" ]; then
  echo "  CuraEngine    already staged"
else
  echo "  CuraEngine    building (scripts/build-curaengine-android.sh)"
  ./scripts/build-curaengine-android.sh
fi

if [ -s "${JNI}/libprusa_slicer_exec.so" ]; then
  echo "  PrusaSlicer   already staged"
else
  echo "  PrusaSlicer   fetching (scripts/fetch-prusa-engine-android.sh)"
  ./scripts/fetch-prusa-engine-android.sh
fi

if [ -s "${JNI}/libblender_exec.so" ]; then
  echo "  Blender       already staged"
else
  echo "  Blender       staging (scripts/fetch-blender-engine-android.sh)"
  ./scripts/fetch-blender-engine-android.sh
fi

echo
echo "== assets =="
echo "  BumpMesh and filaSim are pinned and unpacked by Gradle before preBuild"

echo
echo "== build =="
./gradlew :app:assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
[ -f "${APK}" ] || fail "the build reported success but produced no APK"
echo
echo "APK   ${APK}  ($(du -h "${APK}" | cut -f1))"
echo "sha256 $(sha256sum "${APK}" | cut -d' ' -f1)"
