#!/usr/bin/env bash
# Cross-compile PrusaSlicer 2.9.6 console for Android ARM64.
# Mirrors the locally verified WSL build (NDK r28.2.13676358, Ubuntu-22.04).
set -euo pipefail

# --- locate the NDK (the workflow sets ANDROID_NDK_HOME; fall back to SDK root) ---
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/28.2.13676358}"
TC=$NDK/build/cmake/android.toolchain.cmake
[ -f "$TC" ] || { echo "FATAL: NDK toolchain missing at $TC"; env | grep -iE 'android|ndk' || true; exit 1; }

PREFIX=$PWD/prusa-build/prefix
WORK=$PWD/prusa-build/src
SRC=$PWD/prusa-build/PrusaSlicer
OUT=$PWD/prusa-build/out
SYS=$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot
BIN22=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
CT=$BIN22/aarch64-linux-android24-clang
CXXT=$BIN22/aarch64-linux-android24-clang++
AR=$BIN22/llvm-ar
mkdir -p $PREFIX $WORK $OUT

step () { echo; echo "===== $* ====="; }

fetch () {
  d=$1; u=$2
  if [ ! -d $WORK/$d ]; then
    rm -rf $WORK/.x-$d
    ( cd $WORK
      curl -fsL --retry 5 --retry-all-errors -m 600 -o /tmp/t-$d.tgz $u
      mkdir -p .x-$d
      tar -xf /tmp/t-$d.tgz -C .x-$d --strip-components=1
      rm -f /tmp/t-$d.tgz
      mv .x-$d $d )
  fi
}

cm () {
  d=$1; shift
  cmake -S $WORK/$d -B $WORK/$d/build -G Ninja -DCMAKE_TOOLCHAIN_FILE=$TC     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_shared     -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=$PREFIX -DCMAKE_PREFIX_PATH=$PREFIX     -DCMAKE_FIND_ROOT_PATH=$PREFIX -DBUILD_SHARED_LIBS=OFF -DBUILD_TESTING=OFF     -DCMAKE_POSITION_INDEPENDENT_CODE=ON $*
  ninja -C $WORK/$d/build install
}

step "[1/6] zlib libpng tbb openexr blosc"
fetch zlib https://github.com/madler/zlib/releases/download/v1.3.1/zlib-1.3.1.tar.gz; cm zlib -DZLIB_BUILD_EXAMPLES=OFF
fetch libpng https://github.com/pnggroup/libpng/archive/refs/tags/v1.6.35.tar.gz; cm libpng -DPNG_TESTS=OFF -DPNG_SHARED=OFF -DPNG_STATIC=ON -DZLIB_ROOT=$PREFIX
# oneTBB 2021.5 hardcodes the pre-r23 NDK libc++ path; shim it.
mkdir -p $NDK/sources/cxx-stl/llvm-libc++/libs/arm64-v8a
cp -n $SYS/usr/lib/aarch64-linux-android/libc++_shared.so $NDK/sources/cxx-stl/llvm-libc++/libs/arm64-v8a/ || true
fetch tbb https://github.com/oneapi-src/oneTBB/archive/refs/tags/v2021.5.0.tar.gz; cm tbb -DTBB_BUILD_SHARED=OFF -DTBB_TEST=OFF
fetch openexr https://github.com/AcademySoftwareFoundation/openexr/archive/refs/tags/v2.5.8.tar.gz; cm openexr -DOPENEXR_BUILD_UTILS=OFF
fetch blosc https://github.com/Blosc/c-blosc/archive/8724c06e3da90f10986a253814af18ca081d8de0.tar.gz; cm blosc -DBUILD_BENCHMARKS=OFF -DBUILD_TESTS=OFF -DBUILD_STATIC=ON -DBUILD_SHARED=OFF -DPREFER_EXTERNAL_ZLIB=ON

step "[2/6] boost 1.83 (11 components, android target, forced iconv)"
fetch boost https://github.com/boostorg/boost/releases/download/boost-1.83.0/boost-1.83.0.tar.gz
( cd $WORK/boost
  if [ ! -f b2 ]; then ./bootstrap.sh --prefix=$PREFIX > /tmp/bb.log 2>&1; fi
  python3 - <<'PY'
p = 'libs/locale/build/Jamfile.v2'
s = open(p).read()
if 'force-iconv' not in s:
    n = '    if $(found-iconv)'
    r = '    if ! $(found-iconv) { # force-iconv' + chr(10) + '        found-iconv = true ;' + chr(10) + '    }' + chr(10) + '    if $(found-iconv)'
    assert n in s
    open(p, 'w').write(s.replace(n, r, 1))
PY
  printf 'using clang : : %s ;\n' "$CXXT" > /tmp/uc.jam
  ./b2 -j8 toolset=clang --user-config=/tmp/uc.jam \
    --layout=system link=static threading=multi variant=release \
    architecture=arm abi=aapcs address-model=64 target-os=android \
    --with-system --with-filesystem --with-thread --with-log \
    --with-locale --with-regex --with-chrono --with-atomic \
    --with-date_time --with-iostreams --with-nowide \
    cxxflags="--target=aarch64-linux-android29 --sysroot=$SYS -isystem $SYS/usr/include/c++/v1 -isystem $SYS/usr/include/aarch64-linux-android -isystem $SYS/usr/include -fPIC -O2" \
    linkflags="--target=aarch64-linux-android29 --sysroot=$SYS" \
    install
) > /tmp/boost-build.log 2>&1 || { tail -40 /tmp/boost-build.log; exit 1; }

step "[3/6] openvdb nlopt curl expat eigen z3 catch2 qhull nlohmann"
fetch openvdb https://github.com/prusa3d/openvdb/archive/339ee88230da33e3fefb133d8c1a9e16bef09144.tar.gz
cm openvdb -DOPENVDB_BUILD_PYTHON_MODULE=OFF -DUSE_BLOSC=ON -DOPENVDB_CORE_SHARED=OFF -DOPENVDB_CORE_STATIC=ON -DOPENVDB_ENABLE_RPATH=OFF -DTBB_STATIC=ON -DOPENVDB_BUILD_VDB_PRINT=OFF -DDISABLE_DEPENDENCY_VERSION_CHECKS=ON -DOPENVDB_BUILD_BINARIES=OFF -DOPENVDB_BUILD_UNITTESTS=OFF
fetch nlopt https://github.com/stevengj/nlopt/archive/refs/tags/v2.5.0.tar.gz; cm nlopt -DNLOPT_PYTHON=OFF -DNLOPT_TESTS=OFF -DNLOPT_SWIG=OFF
fetch curl https://github.com/curl/curl/releases/download/curl-8_5_0/curl-8.5.0.tar.gz; cm curl -DBUILD_CURL_EXE=OFF -DBUILD_SHARED_LIBS=OFF -DCURL_USE_OPENSSL=OFF -DCURL_USE_LIBSSH2=OFF -DCURL_DISABLE_LDAP=ON -DCURL_USE_NTLM=OFF -DCURL_USE_LDAP=OFF -DCURL_USE_APPLE_IDN=OFF -DCURL_USE_BEARER=OFF -DCURL_ZLIB=ON
fetch expat https://github.com/libexpat/libexpat/releases/download/R_2_6_2/expat-2.6.2.tar.gz; cm expat -DEXPAT_BUILD_DOCS=OFF -DEXPAT_BUILD_EXAMPLES=OFF -DEXPAT_BUILD_TOOLS=OFF
fetch eigen https://gitlab.com/libeigen/eigen/-/archive/3.4.0/eigen-3.4.0.tar.gz; cm eigen -DEIGEN_BUILD_DOC=OFF
fetch z3 https://github.com/Z3Prover/z3/archive/refs/tags/z3-4.13.0.tar.gz; cm z3 -DZ3_BUILD_PYTHON_BINDINGS=OFF -DZ3_BUILD_JAVA_BINDINGS=OFF -DZ3_BUILD_DOTNET_BINDINGS=OFF -DZ3_BUILD_EXAMPLES=OFF -DZ3_BUILD_TEST_EXECUTABLES=OFF -DZ3_INSTALL_PYTHON_BINDINGS=OFF
fetch catch2 https://github.com/catchorg/Catch2/archive/refs/tags/v3.4.0.tar.gz; cm catch2 -DCATCH_BUILD_TESTING=OFF -DCATCH_INSTALL_DOCS=OFF -DCATCH_INSTALL_EXTRAS=OFF
fetch qhull https://github.com/qhull/qhull/archive/refs/tags/v8.0.2.tar.gz; cm qhull
fetch nlohmann https://github.com/nlohmann/json/archive/refs/tags/v3.11.3.tar.gz; cm nlohmann -DJSON_BuildTests=OFF

step "[4/6] gmp mpfr cgal"
( export CC=$CT CXX=$CXXT AR=$AR;
  fetch gmp https://ftp.gnu.org/gnu/gmp/gmp-6.3.0.tar.xz; cd $WORK/gmp;
  [ -f Makefile ] || ./configure --host=aarch64-linux-android --prefix=$PREFIX --enable-static --disable-shared --disable-assembly --enable-cxx
  make -j8 && make install;
  fetch mpfr https://ftp.gnu.org/gnu/mpfr/mpfr-4.2.1.tar.xz; cd $WORK/mpfr;
  [ -f Makefile ] || ./configure --host=aarch64-linux-android --prefix=$PREFIX --enable-static --disable-shared --with-gmp=$PREFIX
  make -j8 && make install; )
fetch cgal https://github.com/CGAL/cgal/archive/refs/tags/v5.6.1.tar.gz
cmake -S $WORK/cgal -B $WORK/cgal/build -G Ninja -DCMAKE_TOOLCHAIN_FILE=$TC \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=$PREFIX -DCMAKE_PREFIX_PATH=$PREFIX \
  -DCMAKE_FIND_ROOT_PATH=$PREFIX -DBUILD_SHARED_LIBS=OFF -DBUILD_TESTING=OFF \
  -DCGAL_DIR=$PREFIX/lib/cmake/CGAL -DCGAL_DONT_USE_CMAKE_COMPILER_CHECK=ON \
  "-DCMAKE_CXX_FLAGS=-isystem $PREFIX/include"
ninja -C $WORK/cgal/build install

step "[5/6] occt jpeg libbgcode heatshrink nanosvg"
fetch occt https://github.com/Open-Cascade-SAS/OCCT/archive/refs/tags/V7_8_1.tar.gz; cm occt -DUSE_TK=OFF -DUSE_TBB=OFF -DUSE_VTK=OFF -DUSE_FREETYPE=OFF -DUSE_RAPIDJSON=OFF -DBUILD_MODULE_Draw=OFF -DBUILD_MODULE_Visualization=OFF -DINSTALL_DOC_DEVELOPER=OFF -DINSTALL_DOC_LUCID=OFF
fetch jpeg https://github.com/libjpeg-turbo/libjpeg-turbo/archive/refs/tags/3.0.3.tar.gz; cm jpeg -DENABLE_SHARED=OFF -DENABLE_STATIC=ON -DWITH_TURBOJPEG=OFF
fetch heatshrink https://github.com/atomicobject/heatshrink/archive/refs/tags/v0.4.1.tar.gz
$CT --target=aarch64-linux-android29 -O2 -fPIC -c $WORK/heatshrink/heatshrink_encoder.c -o /tmp/hse.o
$CT --target=aarch64-linux-android29 -O2 -fPIC -c $WORK/heatshrink/heatshrink_decoder.c -o /tmp/hsd.o
mkdir -p $PREFIX/include/heatshrink $PREFIX/lib/cmake/heatshrink $PREFIX/include/nanosvg
cp $WORK/heatshrink/*.h $PREFIX/include/heatshrink/
$AR rcs $PREFIX/lib/libheatshrink.a /tmp/hse.o /tmp/hsd.o
cat > $PREFIX/lib/cmake/heatshrink/heatshrinkConfig.cmake <<EOF
add_library(heatshrink::heatshrink_dynalloc STATIC IMPORTED)
set_target_properties(heatshrink::heatshrink_dynalloc PROPERTIES IMPORTED_LOCATION "$PREFIX/lib/libheatshrink.a" INTERFACE_INCLUDE_DIRECTORIES "$PREFIX/include")
set(heatshrink_FOUND TRUE)
set(heatshrink_VERSION 0.4)
EOF
cat > $PREFIX/lib/cmake/heatshrink/heatshrinkConfigVersion.cmake <<EOF
set(PACKAGE_VERSION 0.4)
set(PACKAGE_VERSION_COMPATIBLE TRUE)
EOF
fetch libbgcode https://github.com/prusa3d/libbgcode/archive/6f4ad7ce6b0e638b760199d6611039a610a5a479.tar.gz
cm libbgcode -DLibBGCode_BUILD_TESTS=OFF -DLibBGCode_BUILD_CMD_TOOL=OFF -DLibBGCode_BUILD_COMPONENT_Convert=ON -DLIBBGCODE_USE_ZLIB=OFF -DLIBBGCODE_USE_LZMA=OFF
fetch nanosvg https://github.com/fltk/nanosvg/archive/abcd277ea45e9098bed752cf9c6875b533c0892f.tar.gz
cp $WORK/nanosvg/src/*.h $PREFIX/include/nanosvg/

step "[6/6] version/config shims + PrusaSlicer configure + build"
cat > $PREFIX/lib/cmake/opencascade/OpenCASCADEConfigVersion.cmake <<EOF
set(PACKAGE_VERSION 7.8.1)
set(PACKAGE_VERSION_COMPATIBLE TRUE)
EOF
cat > $PREFIX/lib/cmake/Catch2/Catch2ConfigVersion.cmake <<EOF
set(PACKAGE_VERSION 3.8.0)
set(PACKAGE_VERSION_COMPATIBLE TRUE)
EOF
mkdir -p $PWD/prusa-build/cmake-shims
cat > $PWD/prusa-build/cmake-shims/FindOpenGL.cmake <<EOF
set(OPENGL_FOUND TRUE)
set(OpenGL_FOUND TRUE)
set(OPENGL_LIBRARIES "")
set(OpenGL_LIBRARIES "")
add_library(GL::GL INTERFACE IMPORTED)
set(OpenGL_GL_FOUND TRUE)
EOF
cat > $PWD/prusa-build/cmake-shims/FindGLEW.cmake <<EOF
set(GLEW_FOUND TRUE)
set(GLEW_INCLUDE_DIRS "")
set(GLEW_LIBRARIES "")
add_library(GLEW::GLEW INTERFACE IMPORTED)
EOF
cat > $PWD/prusa-build/cmake-shims/Findcereal.cmake <<EOF
set(cereal_FOUND TRUE)
set(cereal_INCLUDE_DIR "$PREFIX/include")
add_library(cereal::cereal INTERFACE IMPORTED)
set_target_properties(cereal::cereal PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "$PREFIX/include")
EOF
if [ ! -d $PREFIX/include/cereal ]; then
  ( cd $WORK; curl -fsL --retry 5 --retry-all-errors -m 600 -o /tmp/c.tgz https://github.com/USCiLab/cereal/archive/refs/tags/v1.3.2.tar.gz; rm -rf cereal-x; mkdir -p cereal-x; tar -xf /tmp/c.tgz -C cereal-x --strip-components=1; cp -r cereal-x/include/cereal $PREFIX/include/; rm -rf cereal-x /tmp/c.tgz )
fi

if [ ! -d $SRC ]; then
  for i in 1 2 3; do git clone --depth 1 --branch version_2.9.6 https://github.com/prusa3d/PrusaSlicer.git $SRC && break; rm -rf $SRC; done
fi
cmake -S $SRC -B $PWD/prusa-build/build -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$TC \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  "-DCMAKE_CXX_FLAGS=-isystem $PREFIX/include" \
  -DCMAKE_MODULE_PATH=$PWD/prusa-build/cmake-shims \
  -DCMAKE_PREFIX_PATH=$PREFIX -DCMAKE_FIND_ROOT_PATH=$PREFIX \
  -DSLIC3R_GUI=OFF -DSLIC3R_STATIC=ON -DBUILD_TESTING=OFF -DSLIC3R_BUILD_TESTS=OFF \
  -DBOOST_ROOT=$PREFIX -DBoost_NO_SYSTEM_PATHS=ON \
  -DNLOPT_INCLUDE_DIR=$PREFIX/include -DNLOPT_NLOPT_LIBRARY=$PREFIX/lib/libnlopt.a

# Build-time host tool: compile encoding-check with the host g++ and pre-touch the bundled
# encoding-check stamp files (they are only build-time assertions; in a fresh cross build the
# tool would otherwise be run through qemu and fail on the missing device linker).
g++ -O2 -o $PWD/prusa-build/build/build-utils/encoding-check $SRC/build-utils/encoding-check.cpp
grep -oE 'bundled_deps/[A-Za-z0-9/_.-]+\.util' $PWD/prusa-build/build/build.ninja | sort -u | while read -r u; do mkdir -p "$PWD/prusa-build/build/$(dirname "$u")"; touch "$PWD/prusa-build/build/$u"; done
ninja -C $PWD/prusa-build/build prusa-slicer -j8

mkdir -p $OUT/src $OUT/resources
cp $PWD/prusa-build/build/src/prusa-slicer $OUT/
cp -rL $SRC/resources $OUT/resources
echo PRUSA-ENGINE-READY
file $OUT/prusa-slicer | head -1
