# CuraEngine Android integration plan

The current APK deliberately contains only a JNI boundary. It does **not** generate fake G-code.

## Pin

- CuraEngine: `5.14.0-alpha.0`
- Cura project compatibility target: Cura `5.14.0-alpha.0`, setting version `25`
- Android ABI: `arm64-v8a`
- NDK: `28.2.13676358`
- C++ standard: C++20

## Planned native configuration

For the first Android build, disable components that are not required for local one-printer slicing:

- `ENABLE_ARCUS=OFF`
- `ENABLE_PLUGINS=OFF`
- `ENABLE_REMOTE_PLUGINS=OFF`
- `ENABLE_SENTRY=OFF`
- `ENABLE_TESTING=OFF`
- `ENABLE_BENCHMARKS=OFF`

CuraEngine still needs its geometry and utility dependencies. The next implementation step is an Android/Conan profile that builds those dependencies for `armv8` and then links the CuraEngine library into `enderslicer_native`.

## Adapter contract

The JNI adapter will accept:

1. A locally materialized STL path.
2. A locally materialized output G-code path.
3. A fully resolved JSON settings snapshot.
4. The built-in Ender 3 V2 start/end G-code.

It will return structured progress and errors. Export remains through Android's Storage Access Framework, so the app never needs broad storage permissions.

## Packaged shared libraries

CuraEngine 5.14 links dynamically against cura-formulae-engine: the engine
binary records a `NEEDED` entry on `libcura-formulae-engine.so`, so the APK
ships it next to `libcuraengine_exec.so` in `arm64-v8a` jniLibs.

The Conan cache of a development machine can hold several builds of that
library at once — the AArch64 copy from the Android cross-build and an x86-64
copy from the host test build (`scripts/build-curaengine-host-tests.sh`).
`scripts/build-curaengine-android.sh` selects the candidate whose ELF machine
is AArch64 via the NDK `llvm-readelf` and fails the build when none exists.
Do not replace this with a first-match `find`: packaging the x86-64 library
makes the Android linker refuse to load the engine on-device
(`EM_X86_64 (62) instead of EM_AARCH64 (183)`).
