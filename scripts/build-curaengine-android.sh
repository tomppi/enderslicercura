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

# CuraEngine 5.11 uses OneTBB only to cap its worker count. Android's linker
# rejects OneTBB's Linux version script, while CuraEngine's own ThreadPool works
# normally on pthreads. Keep threading enabled and remove only the TBB controller.
python3 - "$ENGINE_ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])

def replace(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"Expected Android patch context was not found in {path}: {old!r}")
    path.write_text(text.replace(old, new))

conanfile = root / "conanfile.py"
replace(
    conanfile,
    'if req.startswith("onetbb/") and self.settings.arch == "wasm" and self.settings.os == "Emscripten":\n                continue',
    'if req.startswith("onetbb/") and (\n                self.settings.os == "Android"\n                or (self.settings.arch == "wasm" and self.settings.os == "Emscripten")\n            ):\n                continue',
)
replace(
    conanfile,
    'tc.variables["ENABLE_THREADING"] = not (self.settings.arch == "wasm" and self.settings.os == "Emscripten")',
    'tc.variables["ENABLE_THREADING"] = not (self.settings.arch == "wasm" and self.settings.os == "Emscripten")\n        tc.variables["ENABLE_TBB"] = self.settings.os != "Android" and not (self.settings.arch == "wasm" and self.settings.os == "Emscripten")',
)

cmake = root / "CMakeLists.txt"
replace(
    cmake,
    'option(ENABLE_THREADING "Enable threading support" ON)',
    'option(ENABLE_THREADING "Enable threading support" ON)\noption(ENABLE_TBB "Enable OneTBB global thread controller" ON)',
)
replace(
    cmake,
    'if(NOT EMSCRIPTEN)\n    find_package(TBB REQUIRED)\nendif()',
    'if(ENABLE_TBB AND NOT EMSCRIPTEN)\n    find_package(TBB REQUIRED)\nendif()',
)
replace(
    cmake,
    '        CURA_ENGINE_HASH=\\"${CURA_ENGINE_HASH}\\"',
    '        CURA_ENGINE_HASH=\\"${CURA_ENGINE_HASH}\\"\n        $<$<NOT:$<BOOL:${ENABLE_TBB}>>:CURA_ENGINE_NO_TBB>',
)
replace(
    cmake,
    '        $<$<NOT:$<BOOL:${EMSCRIPTEN}>>:onetbb::onetbb>',
    '        $<$<AND:$<BOOL:${ENABLE_TBB}>,$<NOT:$<BOOL:${EMSCRIPTEN}>>>:onetbb::onetbb>',
)

application_h = root / "include" / "Application.h"
text = application_h.read_text()
text = text.replace(
    '#ifndef __EMSCRIPTEN__\n#include <oneapi/tbb/global_control.h>\n#endif',
    '#if !defined(__EMSCRIPTEN__) && !defined(CURA_ENGINE_NO_TBB)\n#include <oneapi/tbb/global_control.h>\n#endif',
)
text = text.replace(
    '#ifndef __EMSCRIPTEN__\n    tbb::global_control* tbb_controller_ = nullptr;\n#endif',
    '#if !defined(__EMSCRIPTEN__) && !defined(CURA_ENGINE_NO_TBB)\n    tbb::global_control* tbb_controller_ = nullptr;\n#endif',
)
application_h.write_text(text)

application_cpp = root / "src" / "Application.cpp"
text = application_cpp.read_text()
text = text.replace(
    '#ifndef __EMSCRIPTEN__\n    delete tbb_controller_;\n#endif',
    '#if !defined(__EMSCRIPTEN__) && !defined(CURA_ENGINE_NO_TBB)\n    delete tbb_controller_;\n#endif',
)
text = text.replace(
    '#ifndef __EMSCRIPTEN__\n    delete tbb_controller_;\n    tbb_controller_ = new tbb::global_control(tbb::global_control::max_allowed_parallelism, nthreads + 1);\n#endif',
    '#if !defined(__EMSCRIPTEN__) && !defined(CURA_ENGINE_NO_TBB)\n    delete tbb_controller_;\n    tbb_controller_ = new tbb::global_control(tbb::global_control::max_allowed_parallelism, nthreads + 1);\n#endif',
)
application_cpp.write_text(text)
PY

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
if [[ -n "$ENGINE_BINARY" ]]; then
  ENGINE_APK_BINARY="$OUTPUT_ROOT/artifacts/libcuraengine_exec.so"
  cp -v "$ENGINE_BINARY" "$ENGINE_APK_BINARY"
  STRIP_TOOL="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
  "$STRIP_TOOL" --strip-unneeded "$ENGINE_APK_BINARY"
  chmod 755 "$ENGINE_APK_BINARY"

  if [[ -n "${APP_JNILIBS_DIR:-}" ]]; then
    mkdir -p "$APP_JNILIBS_DIR/arm64-v8a"
    cp -v "$ENGINE_APK_BINARY" "$APP_JNILIBS_DIR/arm64-v8a/libcuraengine_exec.so"
  fi
fi
[[ -n "$ENGINE_LIBRARY" ]] && cp -v "$ENGINE_LIBRARY" "$OUTPUT_ROOT/artifacts/libCuraEngine-arm64-v8a.a"
cp -v "$ENGINE_ROOT/LICENSE" "$OUTPUT_ROOT/artifacts/CuraEngine-LICENSE"

echo "CuraEngine Android artifacts:"
find "$OUTPUT_ROOT/artifacts" -maxdepth 1 -type f -print -exec file {} \;
