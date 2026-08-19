# CuraEngine Android integration

The APK ships the CuraEngine 5.14 executable built for Android and runs it as a
child process: CuraEngineRunner stages the request in an isolated workspace,
CuraEngineCommand builds the CLI invocation, and OwnedProcessRunner executes
the packaged binary with a bounded timeout. There is no JNI boundary.

## Pin

- CuraEngine: `5.14.0-alpha.0`
- Cura project compatibility target: Cura `5.14.0-alpha.0`, setting version `27`
- Android ABI: `arm64-v8a`
- NDK: `28.2.13676358`
- C++ standard: C++20

## Native configuration

The Android cross-build disables components that are not required for local
one-printer slicing:

- `ENABLE_ARCUS=OFF`
- `ENABLE_PLUGINS=OFF`
- `ENABLE_REMOTE_PLUGINS=OFF`
- `ENABLE_SENTRY=OFF`
- `ENABLE_TESTING=OFF`
- `ENABLE_BENCHMARKS=OFF`

`scripts/build-curaengine-android.sh` clones and pins CuraEngine into
`.build/CuraEngine` and produces the AArch64 executable staged in
`arm64-v8a` jniLibs; `app`'s `verifyCuraEngineExecutable` task fails any
assemble that lacks it.

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
