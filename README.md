# EnderSlicer

Android-first CuraEngine front end for one printer: a modified Creality Ender 3 V2.

## Hardware definition in this milestone

- 230 × 230 × 250 mm build volume
- 0.4 mm nozzle, 1.75 mm filament
- Marlin G-code
- Direct-drive extruder
- Z probe with UBL mesh slot 0
- Dual-Z drive
- Heated bed
- User-provided start and end G-code

## Milestone 1 status

Implemented:

- Native Android/Jetpack Compose project
- Foldable-friendly wide and compact layouts
- Android Storage Access Framework imports and exports
- Binary and ASCII STL parsing
- Touch rotation and pinch zoom OpenGL STL preview
- Exact Ender 3 V2 bed grid and machine metadata
- `.curaprofile` ZIP parsing
- Cura project `.3mf` layered container parsing
- Correct precedence for user overrides over quality layers
- Editable essential settings
- Configuration snapshot export
- JNI/CMake boundary for CuraEngine
- GitHub Actions debug APK workflow

Not implemented yet:

- Cura expression evaluation and the complete Cura 5.11 resource tree
- CuraEngine Android dependency build
- Actual slicing and G-code export
- Layer preview
- Move, rotate, scale, duplicate and auto-arrange operations

The Slice button is intentionally disabled until real CuraEngine output is connected.

## Build

Requirements:

- JDK 17
- Android SDK platform 37
- Android NDK `28.2.13676358`
- CMake `3.31.6`
- Gradle `9.4.1`

Open the project in a current Android Studio, or install the listed Gradle version and run:

```bash
gradle :app:assembleDebug
```

GitHub Actions builds and uploads `app-debug.apk` automatically.

## CuraEngine

Run `scripts/fetch-curaengine.sh` to obtain the pinned upstream engine, then follow `docs/CURAENGINE_ANDROID.md`.

## License

This project is intended to be distributed under GNU AGPL-3.0-or-later because the completed application will link to CuraEngine, which is AGPL-licensed. UltiMaker and Cura are trademarks of their respective owners. EnderSlicer is not an official UltiMaker or Creality application.
