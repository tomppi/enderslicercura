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

# gmplib.org / mpfr.org are unreachable from GitHub runners; mirror on ftp.gnu.org.
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
    '    set(_cross_compile_arg "")\n    if (APPLE)',
    '    set(_cross_compile_arg "")\n    if (ANDROID)\n        if (ANDROID_ABI STREQUAL "arm64-v8a")\n            set(_cross_compile_arg --host=aarch64-linux-android)\n        elseif (ANDROID_ABI STREQUAL "x86_64")\n            set(_cross_compile_arg --host=x86_64-linux-android)\n        endif ()\n        set(_gmp_build_tgt "")\n        get_filename_component(_ndk_bin ${CMAKE_C_COMPILER} DIRECTORY)\n        if (ANDROID_ABI STREQUAL "arm64-v8a")\n            set(_ndk_cc_name aarch64-linux-android24-clang)\n            set(_ndk_cxx_name aarch64-linux-android24-clang++)\n        elseif (ANDROID_ABI STREQUAL "x86_64")\n            set(_ndk_cc_name x86_64-linux-android24-clang)\n            set(_ndk_cxx_name x86_64-linux-android24-clang++)\n        endif ()\n    elseif (APPLE)',
    'gmp: android host triplet')
rep(root / 'deps/+GMP/GMP.cmake',
    '    elseif (CMAKE_CROSSCOMPILING)\n        # TOOLCHAIN_PREFIX should be defined in the toolchain file\n        set(_cross_compile_arg --host=${TOOLCHAIN_PREFIX})',
    '    elseif (CMAKE_CROSSCOMPILING AND NOT ANDROID)\n        # TOOLCHAIN_PREFIX should be defined in the toolchain file\n        set(_cross_compile_arg --host=${TOOLCHAIN_PREFIX})',
    'gmp: keep android host triplet')
rep(root / 'deps/+GMP/GMP.cmake',
        '    set(_cross_compile_arg "")\n    if (ANDROID)\n        if (ANDROID_ABI STREQUAL "arm64-v8a")\n            set(_cross_compile_arg --host=aarch64-linux-android)\n        elseif (ANDROID_ABI STREQUAL "x86_64")\n            set(_cross_compile_arg --host=x86_64-linux-android)\n        endif ()\n        set(_gmp_build_tgt "")\n        get_filename_component(_ndk_bin ${CMAKE_C_COMPILER} DIRECTORY)\n        if (ANDROID_ABI STREQUAL "arm64-v8a")\n            set(_ndk_cc_name aarch64-linux-android24-clang)\n            set(_ndk_cxx_name aarch64-linux-android24-clang++)\n        elseif (ANDROID_ABI STREQUAL "x86_64")\n            set(_ndk_cc_name x86_64-linux-android24-clang)\n            set(_ndk_cxx_name x86_64-linux-android24-clang++)\n        endif ()\n    elseif (APPLE)',
    '    set(_cross_compile_arg "")\n    if (ANDROID)\n        if (ANDROID_ABI STREQUAL "arm64-v8a")\n            set(_cross_compile_arg --host=aarch64-linux-android)\n        elseif (ANDROID_ABI STREQUAL "x86_64")\n            set(_cross_compile_arg --host=x86_64-linux-android)\n        endif ()\n        set(_gmp_build_tgt "")\n        get_filename_component(_ndk_bin ${CMAKE_C_COMPILER} DIRECTORY)\n        if (ANDROID_ABI STREQUAL "arm64-v8a")\n            set(_ndk_cc_name aarch64-linux-android24-clang)\n            set(_ndk_cxx_name aarch64-linux-android24-clang++)\n        elseif (ANDROID_ABI STREQUAL "x86_64")\n            set(_ndk_cc_name x86_64-linux-android24-clang)\n            set(_ndk_cxx_name x86_64-linux-android24-clang++)\n        endif ()\n        if (ANDROID_ABI STREQUAL "arm64-v8a")\n            set(_gmp_ccflags "${_gmp_ccflags} --target=aarch64-linux-android24 --sysroot=${CMAKE_SYSROOT}")\n        elseif (ANDROID_ABI STREQUAL "x86_64")\n            set(_gmp_ccflags "${_gmp_ccflags} --target=x86_64-linux-android24 --sysroot=${CMAKE_SYSROOT}")\n        endif ()\n    elseif (APPLE)',
    'gmp: android target flags')
rep(root / 'deps/+GMP/GMP.cmake',
    '        set(_cfg_cmd env "CFLAGS=${_gmp_ccflags}" "CXXFLAGS=${_gmp_ccflags}" ./configure',
    '        set(_cfg_cmd env "CC=${_ndk_bin}/${_ndk_cc_name}" "CXX=${_ndk_bin}/${_ndk_cxx_name}" "CFLAGS=${_gmp_ccflags}" "CXXFLAGS=${_gmp_ccflags} -std=gnu++17" ./configure',
    'gmp: android clang wrapper')

rep(root / 'deps/+MPFR/MPFR.cmake',
    '    if (EMSCRIPTEN)\n        set(_cross_compile_arg --host=wasm32)\n    endif ()',
    '    if (ANDROID)\n        if (ANDROID_ABI STREQUAL "arm64-v8a")\n            set(_cross_compile_arg --host=aarch64-linux-android)\n        elseif (ANDROID_ABI STREQUAL "x86_64")\n            set(_cross_compile_arg --host=x86_64-linux-android)\n        endif ()\n        get_filename_component(_ndk_bin ${CMAKE_C_COMPILER} DIRECTORY)\n        if (ANDROID_ABI STREQUAL "arm64-v8a")\n            set(_ndk_cc_name aarch64-linux-android24-clang)\n        elseif (ANDROID_ABI STREQUAL "x86_64")\n            set(_ndk_cc_name x86_64-linux-android24-clang)\n        endif ()\n        if (ANDROID_ABI STREQUAL "arm64-v8a")\n            set(_gmp_ccflags "${_gmp_ccflags} --target=aarch64-linux-android24 --sysroot=${CMAKE_SYSROOT}")\n        elseif (ANDROID_ABI STREQUAL "x86_64")\n            set(_gmp_ccflags "${_gmp_ccflags} --target=x86_64-linux-android24 --sysroot=${CMAKE_SYSROOT}")\n        endif ()\n    elseif (EMSCRIPTEN)\n        set(_cross_compile_arg --host=wasm32)\n    endif ()',
    'mpfr: android host triplet + target flags')
rep(root / 'deps/+Imath/Imath.cmake',
    '-DLIBDEFLATE_BUILD_SHARED_LIB=OFF\n        -DLIBDEFLATE_BUILD_GZIP=OFF',
    '-DLIBDEFLATE_BUILD_SHARED_LIB=OFF\n        -DLIBDEFLATE_BUILD_GZIP=OFF\n        -DBUILD_TESTING=OFF',
    'imath: tests off')
rep(root / 'deps/+cpptrace/cpptrace.cmake',
    '        -DCPPTRACE_USE_EXTERNAL_LIBDWARF=ON',
    '        -DCPPTRACE_USE_EXTERNAL_LIBDWARF=OFF',
    'cpptrace: bundled libdwarf')
rep(root / 'deps/+cpptrace/cpptrace.cmake',
    'PATCH_COMMAND ${PATCH_CMD} ${CMAKE_CURRENT_LIST_DIR}/cpptrace.patch',
    'PATCH_COMMAND ${PATCH_CMD} ${CMAKE_CURRENT_LIST_DIR}/cpptrace.patch && python3 ${CMAKE_CURRENT_LIST_DIR}/strip_config_installs.py',
    'cpptrace: drop own config install (shims win)')
rep(root / 'deps/+OpenSSL/OpenSSL.cmake',
    '    BUILD_COMMAND make depend && make "-j${NPROC}"',
    '    BUILD_COMMAND make depend && make "-j${NPROC}" build_libs',
    'openssl: capture make log')
rep(root / 'deps/+Boost/Boost.cmake',
    'set(_excluded_libs contract|fiber|numpy|stacktrace|wave|test|log)',
    'set(_excluded_libs contract|fiber|numpy|stacktrace|wave|test|log|process)',
    'boost: exclude process lib')
rep(root / 'deps/+Boost/Boost.cmake',
    'add_cmake_project(Boost',
    'add_cmake_project(Boost\n    PATCH_COMMAND python3 ${CMAKE_CURRENT_LIST_DIR}/make_regex_static.py',
    'boost: static regex build hook')

rep(root / 'deps/+OpenVDB/OpenVDB.cmake',
    '        -DOPENVDB_BUILD_VDB_PRINT=OFF',
    '        -DOPENVDB_BUILD_VDB_PRINT=OFF\n        -DBoost_INCLUDE_DIR=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/include\n        -DBoost_LIBRARY_DIR=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/lib\n        -DBoost_USE_STATIC_LIBS=ON\n        -DBoost_USE_MULTITHREADED=OFF\n        -DTBB_DIR=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/lib/cmake/TBB\n        -DTBB_ROOT=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}\n        -DImath_DIR=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/lib/cmake/Imath\n        -DBlosc_INCLUDE_DIR=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/include\n        -DBlosc_LIBRARY=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/lib/libblosc.a\n        -DLog4cplus_INCLUDE_DIR=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/include\n        -DLog4cplus_LIBRARY=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/lib/liblog4cplus.a\n        -Dzstd_DIR=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/lib/cmake/zstd',
    'openvdb: explicit Boost include dir (FindBoost module skips CMAKE_PREFIX_PATH)')
rep(root / 'cmake/modules/FindBlosc.cmake',
    '  find_package(zstd REQUIRED)',
    '  # zstd cross-build config-version rejects empty-version requests; resolve via -Dzstd_DIR\n  set(zstd_FOUND TRUE)\n  if(NOT TARGET zstd::libzstd)\n    add_library(zstd::libzstd INTERFACE IMPORTED)\n    set_target_properties(zstd::libzstd PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "${Blosc_INCLUDE_DIR}")\n  message(STATUS "DBG-BLOSC INCDIR=[${Blosc_INCLUDE_DIR}] INCDIRS=[${Blosc_INCLUDE_DIRS}] PC=[${PC_Blosc_INCLUDE_DIRS}] PCO=[${PC_Blosc_CFLAGS_OTHER}] PRFX=[${CMAKE_INSTALL_PREFIX}]")\n  if(NOT EXISTS "${Blosc_INCLUDE_DIR}/blosc.h" AND DEFINED CMAKE_INSTALL_PREFIX)\n    set(Blosc_INCLUDE_DIR "${CMAKE_INSTALL_PREFIX}/include")\n    set(Blosc_INCLUDE_DIRS "${Blosc_INCLUDE_DIR}")\n  endif()\n  endif()',
    'FindBlosc: zstd shim + cross include clamp')

# OpenSSL ships no generic CMake config: its ./Configure must target android-*.
rep(root / 'deps/+OpenSSL/OpenSSL.cmake',
    'set(_conf_cmd "./config")\nset(_cross_arch "")\nset(_cross_comp_prefix_line "")\nset(_apple_target_flags "")',
    'set(_conf_cmd "./config")\nset(_cross_arch "")\nset(_cross_comp_prefix_line "")\nset(_apple_target_flags "")\nset(_openssl_tgt "")',
    'openssl: tgt var')
rep(root / 'deps/+OpenSSL/OpenSSL.cmake',
    'elseif (CMAKE_CROSSCOMPILING)',
    'elseif (ANDROID)\n    set(_conf_cmd "./Configure")\n    if (ANDROID_ABI STREQUAL "arm64-v8a")\n        set(_cross_arch "linux-aarch64")\n        set(_openssl_tgt "--target=aarch64-linux-android24")\n    elseif (ANDROID_ABI STREQUAL "x86_64")\n        set(_cross_arch "linux-x86_64")\n        set(_openssl_tgt "--target=x86_64-linux-android24")\n    else ()\n        message(FATAL_ERROR "OpenSSL: unsupported Android ABI: ${ANDROID_ABI}")\n    endif ()\n    get_filename_component(_ndk_bin ${CMAKE_C_COMPILER} DIRECTORY)\n    if (ANDROID_ABI STREQUAL "arm64-v8a")\n        set(_ndk_cc_name aarch64-linux-android24-clang)\n        set(_ndk_cxx_name aarch64-linux-android24-clang++)\n    elseif (ANDROID_ABI STREQUAL "x86_64")\n        set(_ndk_cc_name x86_64-linux-android24-clang)\n        set(_ndk_cxx_name x86_64-linux-android24-clang++)\n    endif ()\nelseif (CMAKE_CROSSCOMPILING)',
    'openssl: android configure target')
rep(root / 'deps/+OpenSSL/OpenSSL.cmake',
    '    CONFIGURE_COMMAND ${_conf_cmd} ${_cross_arch}',
    '    CONFIGURE_COMMAND env "CC=${_ndk_bin}/${_ndk_cc_name}" "CXX=${_ndk_bin}/${_ndk_cxx_name}" ${_conf_cmd} ${_cross_arch}',
    'openssl: clang wrapper')
rep(root / 'deps/+OpenSSL/OpenSSL.cmake',
    '        "--prefix=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}"\n        ${_cross_comp_prefix_line}',
    '        "--prefix=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}"\n        --libdir=lib\n        ${_cross_comp_prefix_line}',
    'openssl: libdir lib')
rep(root / 'deps/+CURL/CURL.cmake',
    '  -DHTTP_ONLY=ON',
    '  -DHTTP_ONLY=ON\n  -DOPENSSL_ROOT_DIR=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}\n  -DOPENSSL_CRYPTO_LIBRARY=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/lib/libcrypto.a\n  -DOPENSSL_SSL_LIBRARY=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/lib/libssl.a\n  -DOPENSSL_INCLUDE_DIR=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/include',
    'curl: explicit openssl paths')
rep(root / 'deps/+LibAssert/LibAssert.cmake',
    '            -DLIBASSERT_USE_EXTERNAL_CPPTRACE=ON',
    '            -DLIBASSERT_USE_EXTERNAL_CPPTRACE=OFF',
    'libassert: bundled cpptrace (no find_package)')

# cpptrace installs its own <pkg>-config.cmake which demands find_dependency(libdwarf);
# our shims provide the config, so neutralize cpptrace's config/version/targets
# installs by shipping a second patch that removes those install() blocks.

# cpptrace's own install emits a config that demands find_dependency(libdwarf),
# which the cross-build cannot satisfy; strip the config/version/targets
# install() blocks so the shim configs (write_shims) are authoritative.
(root / 'deps/+cpptrace' / 'strip_config_installs.py').write_text('''
import pathlib
p = pathlib.Path('cmake/InstallRules.cmake')
s = p.read_text(encoding='utf-8')
i = s.index('# copy config file for find_package to find')
j = s.index('# support packaging library')
s = s[:i].rstrip() + chr(10) * 2 + s[j:]
p.write_text(s, encoding='utf-8')
print('cpptrace: config installs stripped')
''')


# Boost.Regex ships only a header-only CMakeLists in 1.86 (INTERFACE lib, so no
# libboost_regex.a is produced), but CMake 3.31's FindBoost module hard-links
# Boost::iostreams -> Boost::regex and looks for a real static archive. Replace
# libs/regex/CMakeLists.txt with a static build of the b2 sources so that
# libboost_regex.a is built and installed into the deps stage dir.
(root / 'deps/+Boost' / 'make_regex_static.py').write_text('''
from pathlib import Path

cmake = '\\n'.join([
    'cmake_minimum_required(VERSION 3.5...3.16)',
    '',
    'project(boost_regex VERSION "${BOOST_SUPERPROJECT_VERSION}" LANGUAGES CXX)',
    '',
    'add_library(boost_regex',
    '  src/posix_api.cpp',
    '  src/regex.cpp',
    '  src/regex_debug.cpp',
    '  src/static_mutex.cpp',
    '  src/wide_posix_api.cpp',
    ')',
    '',
    'add_library(Boost::regex ALIAS boost_regex)',
    '',
    'target_include_directories(boost_regex PUBLIC include)',
    '',
    'target_link_libraries(boost_regex',
    '  PUBLIC',
    '    Boost::assert',
    '    Boost::config',
    '    Boost::predef',
    '    Boost::throw_exception',
    ')',
    '',
    'target_compile_definitions(boost_regex PUBLIC BOOST_REGEX_NO_LIB)',
    '',
    'if(BUILD_TESTING AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/test/CMakeLists.txt")',
    '  add_subdirectory(test)',
    'endif()',
]);
Path('libs/regex/CMakeLists.txt').write_text(cmake + '\\n', encoding='utf-8')
print('boost: static regex CMakeLists written')
''')


PY


# OpenSSL's android configuration still looks for NDK <triple>-gcc names;
# the NDK ships clang wrappers only, so provide the classic symlinks.
NDKBIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
export ANDROID_API=24
for TRIPLE in aarch64-linux-android x86_64-linux-android; do
  ln -sf clang "$NDKBIN/$TRIPLE${ANDROID_API}-gcc"
  ln -sf clang++ "$NDKBIN/$TRIPLE${ANDROID_API}-g++"
  ln -sf clang "$NDKBIN/$TRIPLE-gcc"
  ln -sf clang++ "$NDKBIN/$TRIPLE-g++"
done

# Autotools-based deps (GMP/MPFR/OpenSSL/Lua) inherit the NDK wrappers through
# the exported CC/CXX; the NDK clang wrapper configures target + sysroot itself.
TOOLBIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
if [ "$ABI" = "arm64-v8a" ]; then
  export CC=$TOOLBIN/aarch64-linux-android24-clang
  export CXX=$TOOLBIN/aarch64-linux-android24-clang++
elif [ "$ABI" = "x86_64" ]; then
  export CC=$TOOLBIN/x86_64-linux-android24-clang
  export CXX=$TOOLBIN/x86_64-linux-android24-clang++
fi

# cpptrace finds libdwarf via find_package, but the cross-installed configs are
# not self-sufficient for the bare find_package(cpptrace) that libassert issues.
# Write known-good self-contained configs BEFORE the deps build (deps may
# configure early) and AFTER it (the real installs may clobber them).
write_shims () {
  LIBDWARF_PREFIX=$BUILD/deps/destdir/usr/local
  mkdir -p "$LIBDWARF_PREFIX/lib/cmake/libdwarf" "$LIBDWARF_PREFIX/lib/cmake/cpptrace"
  cat > "$LIBDWARF_PREFIX/lib/cmake/libdwarf/libdwarfConfig.cmake" <<'CEO'
include("${CMAKE_CURRENT_LIST_DIR}/libdwarf-targets.cmake" OPTIONAL)
set(libdwarf_FOUND TRUE)
CEO
  cat > "$LIBDWARF_PREFIX/lib/cmake/libdwarf/libdwarfConfigVersion.cmake" <<'CEO'
set(PACKAGE_VERSION 0.11.1)
set(PACKAGE_VERSION_COMPATIBLE TRUE)
CEO
  cat > "$LIBDWARF_PREFIX/lib/cmake/cpptrace/cpptraceConfig.cmake" <<'CEO'
include("${CMAKE_CURRENT_LIST_DIR}/cpptrace-targets.cmake" OPTIONAL)
include("${CMAKE_CURRENT_LIST_DIR}/cpptraceTargets.cmake" OPTIONAL)
if(NOT TARGET cpptrace::cpptrace AND NOT TARGET cpptrace)
  add_library(cpptrace INTERFACE IMPORTED)
  add_library(cpptrace::cpptrace ALIAS cpptrace)
  set_target_properties(cpptrace PROPERTIES
    INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_CURRENT_LIST_DIR}/../../..;${CMAKE_CURRENT_LIST_DIR}/../../../include")
endif()
set(cpptrace_FOUND TRUE)
CEO
  cat > "$LIBDWARF_PREFIX/lib/cmake/cpptrace/cpptraceConfigVersion.cmake" <<'CEO'
set(PACKAGE_VERSION 1.0.4)
set(PACKAGE_VERSION_COMPATIBLE TRUE)
CEO
  echo "SHIM-BEGIN"; ls -R "$LIBDWARF_PREFIX/lib/cmake" 2>&1 | head -40; echo "SHIM-END"
  mkdir -p "$LIBDWARF_PREFIX/lib/cmake/zstd"
  cat > "$LIBDWARF_PREFIX/lib/cmake/zstd/zstdConfig.cmake" <<'CEO'
include("${CMAKE_CURRENT_LIST_DIR}/zstd-targets.cmake" OPTIONAL)
include("${CMAKE_CURRENT_LIST_DIR}/zstdTargets.cmake" OPTIONAL)
if(NOT TARGET zstd::libzstd)
  add_library(zstd::libzstd INTERFACE IMPORTED)
  set_target_properties(zstd::libzstd PROPERTIES
    INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_CURRENT_LIST_DIR}/../../..;${CMAKE_CURRENT_LIST_DIR}/../../../include")
endif()
set(zstd_FOUND TRUE)
CEO
  cat > "$LIBDWARF_PREFIX/lib/cmake/zstd/zstdConfigVersion.cmake" <<'CEO'
set(PACKAGE_VERSION 1.5.6)
set(PACKAGE_VERSION_COMPATIBLE TRUE)
set(PACKAGE_VERSION_UNSUITABLE FALSE)
CEO
}
write_shims

echo "SHIM-BEGIN"; ls -R "$LIBDWARF_PREFIX/lib/cmake" 2>&1 | head -40; echo "SHIM-END"

step "[3/5] dependency bundle (deps/ ExternalProject chain)"
mkdir -p "$PREFIX"
cmake -S "$SRC/deps" -B "$BUILD/deps" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$TC \
  -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  -DPrusaSlicer_deps_PACKAGE_EXCLUDES='wxWidgets|GLEW|GLFW|SDL2|SDL|OpenCSG|yoga|Tracy|WebView2|Trumpeloeil|libfyaml|yamlCpp' \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON 2>&1 | tee /tmp/prusa3-depconf.log
cmake --build "$BUILD/deps" -j 1 2>&1 | tee /tmp/prusa3-depbuild.log

# Re-write shims after the real installs (the deps chain may have clobbered them).
write_shims

step "[4/5] console-only PrusaSlicer ($ABI)"
cmake -S "$SRC" -B "$BUILD/main" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$TC \
  -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DSLIC3R_GUI=OFF -DSLIC3R_STATIC=ON -DSLIC3R_RELEASE_DEBUG_SYMBOLS=OFF \
  -DOPENSSL_ROOT_DIR=$DEST \
  -DOPENSSL_CRYPTO_LIBRARY=$DEST/lib/libcrypto.a \
  -DOPENSSL_SSL_LIBRARY=$DEST/lib/libssl.a \
  -DOPENSSL_INCLUDE_DIR=$DEST/include \
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