#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENGINE_ROOT="${ENGINE_ROOT:-$ROOT/.build/CuraEngine}"
OUTPUT_ROOT="${OUTPUT_ROOT:-$ROOT/.build/curaengine-android}"
PROFILE="$ROOT/native/curaengine/profiles/android-arm64"
NDK_PATH="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"

if [[ -z "$NDK_PATH" ]]; then
  echo "ANDROID_NDK_HOME or ANDROID_NDK_ROOT must point to Android NDK 28.2.13676358" >&2
  exit 2
fi

if [[ ! -d "$NDK_PATH/toolchains/llvm/prebuilt" ]]; then
  echo "Invalid Android NDK path: $NDK_PATH" >&2
  exit 2
fi

mkdir -p "$(dirname "$ENGINE_ROOT")" "$OUTPUT_ROOT"

if [[ ! -d "$ENGINE_ROOT/.git" ]]; then
  git clone --depth 1 --branch 5.11.0 https://github.com/Ultimaker/CuraEngine.git "$ENGINE_ROOT"
fi

python3 -m pip install --user --upgrade 'conan>=2.7,<3'
export PATH="$HOME/.local/bin:$PATH"

conan config install https://github.com/Ultimaker/conan-config.git
conan profile detect --force --name default

rm -rf "$OUTPUT_ROOT"
mkdir -p "$OUTPUT_ROOT"

COMMON_ARGS=(
  -pr:h "$PROFILE"
  -pr:b default
  -of "$OUTPUT_ROOT"
  --build=missing
  -c "tools.android:ndk_path=$NDK_PATH"
  -c tools.build:skip_test=True
  -o '&:enable_arcus=False'
  -o '&:enable_plugins=False'
  -o '&:enable_benchmarks=False'
  -o '&:enable_extensive_warnings=False'
  -o '&:with_cura_resources=False'
  -o 'boost/*:header_only=True'
  -o '*:shared=False'
)

pushd "$ENGINE_ROOT" >/dev/null
conan install . "${COMMON_ARGS[@]}"
conan build . "${COMMON_ARGS[@]}"
popd >/dev/null

ENGINE_BINARY="$(find "$OUTPUT_ROOT" -type f -name CuraEngine -perm -u+x | head -n 1 || true)"
ENGINE_LIBRARY="$(find "$OUTPUT_ROOT" -type f \( -name 'lib_CuraEngine.a' -o -name '_CuraEngine.a' \) | head -n 1 || true)"

if [[ -z "$ENGINE_BINARY" && -z "$ENGINE_LIBRARY" ]]; then
  echo "CuraEngine build completed but no engine binary or static library was found" >&2
  find "$OUTPUT_ROOT" -maxdepth 5 -type f | sort >&2
  exit 3
fi

mkdir -p "$OUTPUT_ROOT/artifacts"
[[ -n "$ENGINE_BINARY" ]] && cp -v "$ENGINE_BINARY" "$OUTPUT_ROOT/artifacts/CuraEngine-arm64-v8a"
[[ -n "$ENGINE_LIBRARY" ]] && cp -v "$ENGINE_LIBRARY" "$OUTPUT_ROOT/artifacts/libCuraEngine-arm64-v8a.a"
cp -v "$ENGINE_ROOT/LICENSE" "$OUTPUT_ROOT/artifacts/CuraEngine-LICENSE"

echo "CuraEngine Android artifacts:"
find "$OUTPUT_ROOT/artifacts" -maxdepth 1 -type f -print -exec file {} \;
