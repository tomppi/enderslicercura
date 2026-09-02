#!/usr/bin/env bash
# Cross-compile PrusaSlicer 2.9.6 console for Android ARM64 (mirrors the verified WSL build).
set -euo pipefail
NDK=$ANDROID_NDK_HOME
TC=$NDK/build/cmake/android.toolchain.cmake
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

fetch () {
  d=$1; u=$2
  if [ ! -d $WORK/$d ]; then
    ( cd $WORK; curl -sL -o /tmp/t.tgz $u; mkdir -p $d; tar -xzf /tmp/t.tgz -C $d --strip-components=1; rm -f /tmp/t.tgz )
  fi
}

cm () {
  d=$1; shift
  cmake -S $WORK/$d -B $WORK/$d/build -G Ninja -DCMAKE_TOOLCHAIN_FILE=$TC \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=$PREFIX -DCMAKE_PREFIX_PATH=$PREFIX \
    -DCMAKE_FIND_ROOT_PATH=$PREFIX -DBUILD_SHARED_LIBS=OFF -DBUILD_TESTING=OFF \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON $*
  ninja -C $WORK/$d/build install
}

echo '[1/8] zlib libpng tbb openexr blosc'
fetch zlib https://github.com/madler/zlib/releases/download/v1.3.1/zlib-1.3.1.tar.gz; cm zlib -DZLIB_BUILD_EXAMPLES=OFF
fetch libpng https://github.com/pnggroup/libpng/archive/refs/tags/v1.6.35.tar.gz; cm libpng -DPNG_TESTS=OFF -DPNG_SHARED=OFF -DPNG_STATIC=ON -DZLIB_ROOT=$PREFIX
mkdir -p $NDK/sources/cxx-stl/llvm-libc++/libs/arm64-v8a; cp -n $SYS/usr/lib/aarch64-linux-android/libc++_shared.so $NDK/sources/cxx-stl/llvm-libc++/libs/arm64-v8a/ || true
fetch tbb https://github.com/oneapi-src/oneTBB/archive/refs/tags/v2021.5.0.tar.gz; cm tbb -DTBB_BUILD_SHARED=OFF -DTBB_TEST=OFF
fetch openexr https://github.com/AcademySoftwareFoundation/openexr/archive/refs/tags/v2.5.8.tar.gz; cm openexr -DOPENEXR_BUILD_UTILS=OFF
fetch blosc https://github.com/Blosc/c-blosc/archive/8724c06e3da90f10986a253814af18ca081d8de0.tar.gz; cm blosc -DBUILD_TESTS=OFF -DBUILD_STATIC=ON -DBUILD_SHARED=OFF -DPREFER_EXTERNAL_ZLIB=ON

echo '[2/8] boost 1.83'
fetch boost https://github.com/boostorg/boost/releases/download/boost-1.83.0/boost-1.83.0.tar.gz
( cd $WORK/boost
  [ -f b2 ] || ./bootstrap.sh --prefix=$PREFIX > /tmp/bb.log 2>&1
  python3 - <<'PY'
import os
p = 'libs/locale/build/Jamfile.v2'
s = open(p).read()
if 'force-iconv' not in s:
    n = '    if $(found-iconv)'
    r = '    if ! $(found-iconv) { # force-iconv' + chr(10) + '        found-iconv = true ;' + chr(10) + '    }' + chr(10) + '    if $(found-iconv)'
    assert n in s
    open(p, 'w').write(s.replace(n, r, 1))
PY
  printf 'using clang : : %s ;\n' $CXXT > /tmp/uc.jam
  ./b2 -j8 toolset=clang --user-config=/tmp/uc.jam --layout=system link=static threading=multi variant=release
  architecture=arm abi=aapcs address-model=64 target-os=android
  --with-system --with-filesystem --with-thread --with-log --with-locale --with-regex
  --with-chrono --with-atomic --with-date_time --with-iostreams --with-nowide
  cxxflags='--target=aarch64-linux-android29 --sysroot=$SYS -isystem $SYS/usr/include/c++/v1 -isystem $SYS/usr/include/aarch64-linux-android -isystem $SYS/usr/include -fPIC -O2'
  linkflags='--target=aarch64-linux-android29 --sysroot=$SYS' install
) > /tmp/boost-build.log 2>&1 || { tail -40 /tmp/boost-build.log; exit 1; }

echo '[3/8] openvdb nlopt curl expat eigen z3 catch2'
fetch openvdb https://github.com/prusa3d/openvdb/archive/339ee88230da33e3fefb133d8c1a9e16bef09144.tar.gz
cm openvdb -DOPENVDB_BUILD_PYTHON_MODULE=OFF -DUSE_BLOSC=ON -DOPENVDB_CORE_SHARED=OFF -DOPENVDB_CORE_STATIC=ON -DOPENVDB_ENABLE_RPATH=OFF -DTBB_STATIC=ON -DOPENVDB_BUILD_VDB_PRINT=OFF -DDISABLE_DEPENDENCY_VERSION_CHECKS=ON -DOPENVDB_BUILD_BINARIES=OFF -DOPENVDB_BUILD_UNITTESTS=OFF
fetch nlopt https://github.com/stevengj/nlopt/archive/refs/tags/v2.5.0.tar.gz; cm nlopt -DNLOPT_PYTHON=OFF -DNLOPT_TESTS=OFF -DNLOPT_SWIG=OFF
fetch curl https://github.com/curl/curl/releases/download/curl-8_5_0/curl-8.5.0.tar.gz; cm curl -DBUILD_CURL_EXE=OFF -DCURL_USE_OPENSSL=OFF -DCURL_USE_LIBSSH2=OFF -DCURL_DISABLE_LDAP=ON -DCURL_ZLIB=ON
fetch expat https://github.com/libexpat/libexpat/releases/download/R_2_6_2/expat-2.6.2.tar.gz; cm expat -DEXPAT_BUILD_TOOLS=OFF
fetch eigen https://gitlab.com/libeigen/eigen/-/archive/3.4.0/eigen-3.4.0.tar.gz; cm eigen -DEIGEN_BUILD_DOC=OFF
fetch z3 https://github.com/Z3Prover/z3/archive/refs/tags/z3-4.13.0.tar.gz; cm z3 -DZ3_BUILD_PYTHON_BINDINGS=OFF -DZ3_BUILD_JAVA_BINDINGS=OFF -DZ3_BUILD_TEST_EXECUTABLES=OFF
fetch catch2 https://github.com/catchorg/Catch2/archive/refs/tags/v3.4.0.tar.gz; cm catch2 -DCATCH_BUILD_TESTING=OFF -DCATCH_INSTALL_DOCS=OFF -DCATCH_INSTALL_EXTRAS=OFF

echo '[4/8] gmp mpfr cgal'
( export CC=$CT CXX=$CXXT AR=$AR;
  fetch gmp https://gmplib.org/download/gmp/gmp-6.3.0.tar.xz; cd $WORK/gmp;
  [ -f Makefile ] || ./configure --host=aarch64-linux-android --prefix=$PREFIX --enable-static --disable-shared --disable-assembly --enable-cxx
  make -j8 && make install;
  fetch mpfr https://ftp.gnu.org/gnu/mpfr/mpfr-4.2.1.tar.xz; cd $WORK/mpfr;
  [ -f Makefile ] || ./configure --host=aarch64-linux-android --prefix=$PREFIX --enable-static --disable-shared --with-gmp=$PREFIX
  make -j8 && make install; )
fetch cgal https://github.com/CGAL/cgal/archive/refs/tags/v5.6.1.tar.gz; cm cgal -DCGAL_DONT_USE_CMAKE_COMPILER_CHECK=ON

echo '[5/8] occt jpeg libbgcode nanosvg'
fetch occt https://github.com/Open-Cascade-SAS/OCCT/archive/refs/tags/V7_8_1.tar.gz; cm occt -DUSE_TK=OFF -DUSE_TBB=OFF -DUSE_VTK=OFF -DUSE_FREETYPE=OFF -DUSE_RAPIDJSON=OFF -DBUILD_MODULE_Draw=OFF -DBUILD_MODULE_Visualization=OFF -DINSTALL_DOC_DEVELOPER=OFF
fetch jpeg https://github.com/libjpeg-turbo/libjpeg-turbo/archive/refs/tags/3.0.3.tar.gz; cm jpeg -DENABLE_SHARED=OFF -DENABLE_STATIC=ON -DWITH_TURBOJPEG=OFF
fetch heatshrink https://github.com/atomicobject/heatshrink/archive/refs/tags/v0.4.1.tar.gz
$CT --target=aarch64-linux-android29 -O2 -fPIC -c $WORK/heatshrink/heatshrink_encoder.c -o /tmp/hse.o
$CT --target=aarch64-linux-android29 -O2 -fPIC -c $WORK/heatshrink/heatshrink_decoder.c -o /tmp/hsd.o
mkdir -p $PREFIX/include/heatshrink $PREFIX/lib/cmake/heatshrink $PREFIX/include/nanosvg
cp $WORK/heatshrink/*.h $PREFIX/include/heatshrink/
$AR rcs $PREFIX/lib/libheatshrink.a /tmp/hse.o /tmp/hsd.o
cat > $PREFIX/lib/cmake/heatshrink/heatshrinkConfig.cmake <<EOF
add_library(heatshrink::heatshrink_dynalloc STATIC IMPORTED)
set_target_properties(heatshrink::heatshrink_dynalloc PROPERTIES IMPORTED_LOCATION $PREFIX/lib/libheatshrink.a INTERFACE_INCLUDE_DIRECTORIES $PREFIX/include)
set(heatshrink_FOUND TRUE)
set(heatshrink_VERSION 0.4)
EOF
cat > $PREFIX/lib/cmake/heatshrink/heatshrinkConfigVersion.cmake <<EOF
set(PACKAGE_VERSION 0.4)
set(PACKAGE_VERSION_COMPATIBLE TRUE)
EOF
fetch libbgcode https://github.com/prusa3d/libbgcode/archive/6f4ad7ce6b0e638b760199d6611039a610a5a479.tar.gz
cm libbgcode -DLibBGCode_BUILD_TESTS=OFF -DLibBGCode_BUILD_CMD_TOOL=OFF

echo '[6/8] version/config shims'
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
set(OPENGL_LIBRARIES )
add_library(GL::GL INTERFACE IMPORTED)
set(OpenGL_GL_FOUND TRUE)
EOF
cat > $PWD/prusa-build/cmake-shims/FindGLEW.cmake <<EOF
set(GLEW_FOUND TRUE)
set(GLEW_LIBRARIES )
add_library(GLEW::GLEW INTERFACE IMPORTED)
EOF
cat > $PWD/prusa-build/cmake-shims/Findcereal.cmake <<EOF
set(cereal_FOUND TRUE)
add_library(cereal::cereal INTERFACE IMPORTED)
set_target_properties(cereal::cereal PROPERTIES INTERFACE_INCLUDE_DIRECTORIES $PREFIX/include)
EOF
if [ ! -d $PREFIX/include/cereal ]; then
  ( cd $WORK; curl -sL -o /tmp/c.tgz https://github.com/USCiLab/cereal/archive/refs/tags/v1.3.2.tar.gz; mkdir -p cereal-x; tar -xzf /tmp/c.tgz -C cereal-x --strip-components=1; cp -r cereal-x/include/cereal $PREFIX/include/; rm -rf cereal-x /tmp/c.tgz )
fi

echo '[7/8] fetch + configure PrusaSlicer 2.9.6'
if [ ! -d $SRC ]; then git clone --depth 1 --branch version_2.9.6 https://github.com/prusa3d/PrusaSlicer.git $SRC; fi
cmake -S $SRC -B $PWD/prusa-build/build -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$TC \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_CXX_FLAGS='-isystem $PREFIX/include' \
  -DCMAKE_MODULE_PATH='$PWD/prusa-build/cmake-shims' \
  -DCMAKE_PREFIX_PATH=$PREFIX -DCMAKE_FIND_ROOT_PATH=$PREFIX \
  -DSLIC3R_GUI=OFF -DSLIC3R_STATIC=ON -DBUILD_TESTING=OFF -DSLIC3R_BUILD_TESTS=OFF \
  -DBOOST_ROOT=$PREFIX -DBoost_NO_SYSTEM_PATHS=ON \
  -DNLOPT_INCLUDE_DIR=$PREFIX/include -DNLOPT_NLOPT_LIBRARY=$PREFIX/lib/libnlopt.a

echo '[8/8] build prusa-slicer (arm64 console; build-time tools swapped for host)'
g++ -O2 -o $PWD/prusa-build/build/build-utils/encoding-check $SRC/build-utils/encoding-check.cpp
find $PWD/prusa-build/build/bundled_deps -name '*.util' -exec touch {} \;
ninja -C $PWD/prusa-build/build prusa-slicer -j8
mkdir -p $OUT/src $OUT/resources
cp $PWD/prusa-build/build/src/prusa-slicer $OUT/
cp -rL $SRC/resources $OUT/resources
echo PRUSA-ENGINE-READY
file $OUT/prusa-slicer | head -1