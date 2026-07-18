# CuraEngine Android integration plan

The current APK deliberately contains only a JNI boundary. It does **not** generate fake G-code.

## Pin

- CuraEngine: `5.11.0`
- Cura project compatibility target: Cura `5.11.0-beta.1`, setting version `25`
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
