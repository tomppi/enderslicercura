#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENGINE_ROOT="${ENGINE_ROOT:-$ROOT/.build/CuraEngine}"
OUTPUT_ROOT="${OUTPUT_ROOT:-$ROOT/.build/curaengine-host-tests}"
ARTIFACT="$OUTPUT_ROOT/artifacts/CuraEngine"

if [[ ! -d "$ENGINE_ROOT/.git" ]]; then
  echo "Patched CuraEngine source is missing. Run scripts/build-curaengine-android.sh first." >&2
  exit 2
fi

conan profile detect --force --name default
rm -rf "$OUTPUT_ROOT"
mkdir -p "$OUTPUT_ROOT"

COMMON_ARGS=(
  -pr:h default
  -pr:b default
  -of "$OUTPUT_ROOT"
  --build=missing
  -s build_type=Release
  -s compiler.cppstd=gnu20
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
if [[ -z "$ENGINE_BINARY" ]]; then
  echo "Host CuraEngine build completed without an executable" >&2
  find "$OUTPUT_ROOT" -maxdepth 6 -type f | sort >&2
  exit 3
fi

mkdir -p "$(dirname "$ARTIFACT")"
cp -v "$ENGINE_BINARY" "$ARTIFACT"
chmod 755 "$ARTIFACT"

file "$ARTIFACT"
"$ARTIFACT" help >/dev/null
printf '%s\n' "$ARTIFACT"
