#!/usr/bin/env bash
# Cross-compile PrusaSlicer 3.0.0-alpha11 console (slic3r-app-launcher) for Android.
# Builds the full dependency chain through the upstream deps/ ExternalProject
# system, then the console-only PrusaSlicer. ANDROID_ABI selects the target.
set -euo pipefail
trap 'rc=$?; echo "::error::prusa3 engine build failed (exit $rc)"; if [ -f /tmp/prusa3-ninja.log ]; then echo "::error::--- ninja tail ---"; tail -20 /tmp/prusa3-ninja.log | sed "s/^/::error::/"; fi; if [ -f /tmp/prusa3-depbuild.log ]; then echo "::error::--- deps tail ---"; tail -20 /tmp/prusa3-depbuild.log | sed "s/^/::error::/"; fi; exit $rc' ERR

ABI="${ANDROID_ABI:-arm64-v8a}"
TAG="version_3.0.0-alpha11"
VER="3.0.0-alpha11"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/28.2.13676358}"
TC=$NDK/build/cmake/android.toolchain.cmake
[ -f "$TC" ] || { echo "FATAL: NDK toolchain missing at $TC ($ABI)"; exit 1; }

PREFIX=$PWD/prusa3-build
SRC=$PREFIX/PrusaSlicer
BUILD=$PREFIX/build
OUT=$PREFIX/out
DEST=$BUILD/deps/destdir/usr/local

step () { echo; echo "===== $* ====="; }

step "[1/5] fetch PrusaSlicer $TAG"
if [ ! -d "$SRC/.git" ]; then
  for i in 1 2 3; do git clone --depth 1 --branch "$TAG" https://github.com/prusa3d/PrusaSlicer.git "$SRC" && break; rm -rf "$SRC"; done
fi

step "[2/5] patch deps system for Android"
python3 - "$SRC" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1])

def rep(p, old, new, label):
    t = p.read_text()
    if new in t:
        print(label, 'already patched'); return
    if old not in t:
        raise SystemExit('patch context not found for ' + label)
    p.write_text(t.replace(old, new, 1))
    print(label, 'patched')

# On Android there is no system ZLIB: build it from source.
rep(root / 'deps/CMakeLists.txt',
    'if (UNIX)\n    # On UNIX systems (including Apple) ZLIB should be available\n    list(APPEND SYSTEM_PROVIDED_PACKAGES ZLIB)\nendif ()',
    'if (UNIX AND NOT ANDROID)\n    # On UNIX systems (including Apple) ZLIB should be available\n    list(APPEND SYSTEM_PROVIDED_PACKAGES ZLIB)\nendif ()',
    'deps: ZLIB is source-built on Android')

# OpenSSL ships no generic CMake config: its ./Configure must target android-*.
rep(root / 'deps/+OpenSSL/OpenSSL.cmake',
    'elseif (CMAKE_CROSSCOMPILING)',
    'elseif (ANDROID)\n    set(_conf_cmd "./Configure")\n    if (ANDROID_ABI STREQUAL "arm64-v8a")\n        set(_cross_arch "android-arm64")\n    elseif (ANDROID_ABI STREQUAL "x86_64")\n        set(_cross_arch "android-x86_64")\n    else ()\n        message(FATAL_ERROR "OpenSSL: unsupported Android ABI: ${ANDROID_ABI}")\n    endif ()\nelseif (CMAKE_CROSSCOMPILING)',
    'openssl: android configure target')

# gmplib.org / mpfr.org are unreachable from GitHub runners; mirror on ftp.gnu.org
# (identical tarballs, same hashes).
rep(root / 'deps/+GMP/GMP.cmake',
    'URL https://gmplib.org/download/gmp/gmp-6.2.1.tar.bz2',
    'URL https://ftp.gnu.org/gnu/gmp/gmp-6.2.1.tar.bz2',
    'gmp: ftp.gnu.org mirror')
rep(root / 'deps/+MPFR/MPFR.cmake',
    'URL https://www.mpfr.org/mpfr-4.2.1/mpfr-4.2.1.tar.bz2',
    'URL https://ftp.gnu.org/gnu/mpfr/mpfr-4.2.1.tar.bz2',
    'mpfr: ftp.gnu.org mirror')

# GMP/MPFR autotools builds default to the host compiler; target Android via the
# NDK clang wrapper + the android-* host triplet.
rep(root / 'deps/+GMP/GMP.cmake',
    '        set(_cfg_cmd env "CFLAGS=${_gmp_ccflags}" "CXXFLAGS=${_gmp_ccflags}" ./configure ${_cross_compile_arg} --enable-shared=no --enable-cxx=yes --enable-static=yes "--prefix=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}" ${_gmp_build_tgt})',
    '        set(_cfg_cmd env "CC=${CMAKE_C_COMPILER}" "CFLAGS=${_gmp_ccflags}" "CXXFLAGS=${_gmp_ccflags}" ./configure ${_cross_compile_arg} --enable-shared=no --enable-cxx=yes --enable-static=yes "--prefix=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}" ${_gmp_build_tgt})',
    'gmp: android clang compiler')
rep(root / 'deps/+GMP/GMP.cmake',
    '    set(_cross_compile_arg "")\n    if (APPLE)',
    '    set(_cross_compile_arg "")\n    if (ANDROID)\n        if (ANDROID_ABI STREQUAL "arm64-v8a")\n            set(_cross_compile_arg --host=aarch64-linux-android)\n        elseif (ANDROID_ABI STREQUAL "x86_64")\n            set(_cross_compile_arg --host=x86_64-linux-android)\n        endif ()\n        set(_gmp_build_tgt "")\n    elseif (APPLE)',
    'gmp: android host triplet')
rep(root / 'deps/+MPFR/MPFR.cmake',
    "                 CFLAGS='${_gmp_ccflags}' \\\n                 CXXFLAGS='${_gmp_ccflags}' \\",
    "                 CC='${CMAKE_C_COMPILER}' \\\n                 CFLAGS='${_gmp_ccflags}' \\\n                 CXXFLAGS='${_gmp_ccflags}' \\",
    'mpfr: android clang compiler')
rep(root / 'deps/+MPFR/MPFR.cmake',
    '    if (EMSCRIPTEN)\n        set(_cross_compile_arg --host=wasm32)\n    endif ()',
    '    if (ANDROID)\n        if (ANDROID_ABI STREQUAL "arm64-v8a")\n            set(_cross_compile_arg --host=aarch64-linux-android)\n        elseif (ANDROID_ABI STREQUAL "x86_64")\n            set(_cross_compile_arg --host=x86_64-linux-android)\n        endif ()\n    elseif (EMSCRIPTEN)\n        set(_cross_compile_arg --host=wasm32)\n    endif ()',
    'mpfr: android host triplet')
PY


step "[3/5] dependency bundle (deps/ ExternalProject chain)"
mkdir -p "$PREFIX"
cmake -S "$SRC/deps" -B "$BUILD/deps" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$TC \
  -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  -DPrusaSlicer_deps_PACKAGE_EXCLUDES='wxWidgets|GLEW|GLFW|SDL2|SDL|OpenCSG|yoga|Tracy|WebView2|Trumpeloeil|libfyaml|yamlCpp' \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON 2>&1 | tee /tmp/prusa3-depconf.log
cmake --build "$BUILD/deps" 2>&1 | tee /tmp/prusa3-depbuild.log

step "[4/5] console-only PrusaSlicer ($ABI)"
cmake -S "$SRC" -B "$BUILD/main" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$TC \
  -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DSLIC3R_GUI=OFF -DSLIC3R_STATIC=ON -DSLIC3R_RELEASE_DEBUG_SYMBOLS=OFF \
  -DBUILD_TESTING=OFF -DSLIC3R_BUILD_TESTS=OFF \
  -DCMAKE_PREFIX_PATH=$DEST -DCMAKE_FIND_ROOT_PATH=$DEST \
  "-DCMAKE_CXX_FLAGS=-isystem $DEST/include" \
  "-DCMAKE_EXE_LINKER_FLAGS=-nostdlib++ -Wl,-Bstatic -lz -lc++_static -lc++abi -Wl,-Bdynamic" \
  2>&1 | tee /tmp/prusa3-mainconf.log
ninja -C "$BUILD/main" slic3r-app-launcher -j8 2>&1 | tee /tmp/prusa3-ninja.log
# Link hygiene: the NDK sysroot provides libstdc++.so / libz.so stubs; the console
# must not depend on them. Drop -lstdc++ (static libc++ is used) and force -lz to
# resolve statically. The FIRST ninja invocation regenerates build.ninja.
BIN="$BUILD/main/build.ninja"
sed -i 's/ -lstdc++ /  /g' "$BIN"
sed -i 's/ -lz / -Wl,-Bstatic -lz -Wl,-Bdynamic /g' "$BIN"
sed -i "s#${DEST}/lib/libz.so##g" "$BIN"
rm -f "$BUILD/main/src/slic3r-app-launcher"
ninja -C "$BUILD/main" slic3r-app-launcher -j8 2>&1 | tee /tmp/prusa3-ninja.log

step "[5/5] package $ABI"
mkdir -p "$OUT"
cp -v "$BUILD/main/src/slic3r-app-launcher" "$OUT/prusa-slicer"
STRIP="${ANDROID_NDK_HOME:-$SDK/ndk/28.2.13676358}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
if [ -x "$STRIP" ]; then "$STRIP" -s "$OUT/prusa-slicer"; fi
echo "== dynamic dependency verification =="
readelf -d "$OUT/prusa-slicer" | grep NEEDED | tee "$OUT/needed.txt" || echo "no dynamic dependencies beyond the interpreter"
if grep -qE 'libc\+\+_shared|libz\.so|libz3\.so|libTK|libstdc\+\+\.so' "$OUT/needed.txt"; then
  echo "::error::engine binary has undesirable NEEDED entries:"
  sed 's/^/::error:: /' "$OUT/needed.txt"
  exit 1
fi
mkdir -p "$OUT/resources"
cp -r "$SRC/resources/." "$OUT/resources/"
tar -czf "$OUT/resources.tar.gz" -C "$OUT" resources
echo "== resources =="
ls "$OUT/resources" | tr '\n' ' '
echo
echo PRUSA-ENGINE-3-READY
file "$OUT/prusa-slicer" | head -1
