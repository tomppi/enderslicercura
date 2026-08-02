<p align="center">
  <img src="docs/esc-icon.svg" width="128" height="128" alt="EnderSlicerCura ESC icon">
</p>

# EnderSlicerCura

EnderSlicerCura is an Android-first CuraEngine front end for importing, preparing, slicing, previewing and sending 3D prints from a phone or foldable.

The app is currently **0.9.0-dev**, targets Android 10+ on **ARM64**, and bundles CuraEngine with matching Cura resources from **5.11.0-beta.1**. Its most complete real-printer testing has been on a modified Creality Ender 3 V2, but machine dimensions, nozzle, filament, start/end G-code and print settings are editable.

> This is development software, not a complete Cura replacement. Inspect every model, setting and generated G-code before printing.

## Current features

### Slicing and profiles

- Local ARM64 CuraEngine slicing with up to eight workers
- STL import plus Cura `.3mf` configuration and `.curaprofile` import
- Cura machine/extruder inheritance and formula recalculation
- Persistent imported baseline with explicit app overrides
- Editable printer, quality, walls, infill, support, travel, material, cooling and adhesion settings
- Normal and tree supports with support-interface controls
- Adaptive layer height, estimated time and repaired G-code metadata
- Validated CRLF `.gcode` export with unique filenames

### Model and layer viewers

- Binary and ASCII STL support with streamed parsing for large meshes
- Move, rotate, center, lay flat and drop-to-bed tools
- Model-focused OpenGL camera with orbit, pan and zoom
- Build-volume and transformed-model validation before slicing
- Cumulative layer preview colored by print speed
- High-contrast selected-layer ribbons with dark outlines
- Separate support, support-interface, adhesion, arc-overhang and wave-overhang colors
- Full-height sampled previews for very large G-code files without shortening the exported G-code

### Print editing and calibration

- Non-destructive layer events without re-running CuraEngine
- Pause, filament change, temperature, fan, speed, flow, retraction, camera, message and guarded custom-G-code events
- Compact support-free temperature, flow, speed, fan, retraction, pressure-advance and junction-deviation tests
- Firmware-aware command validation for supported Marlin, Klipper and RepRapFirmware calibration modes
- Unsafe or unsupported command combinations are rejected instead of guessed

### Experimental overhangs

- **Arc overhangs (Multiplex):** expanding clipped arc paths with normal bridge fallback
- **Wave overhangs:** expanding wavefronts seeded from previous-layer model material
- Smart, monotonic and zigzag wave traversal
- Adjustable spacing, flow, speed, fan, overlap, minimum width and iteration limit
- Exact preview markers and dedicated layer colors

Arc and Wave overhangs are mutually exclusive, disabled by default and still require physical print testing. If generation cannot safely cover a complete skin island, Cura's normal bridge/skin path is retained.

### Offline model texturing

The packaged BumpMesh workspace can apply preset or custom displacement textures using planar, triplanar/cubic or cylindrical mapping. Processing stays on the device, and the returned binary STL is validated before it re-enters the normal import workflow.

The configurable triangle limit ranges from 100,000 to 8 million. Very large meshes can still exceed the Android application heap during rendering, transformation or WebView processing.

### OctoPrint

The integrated OctoPrint client includes:

- Browser Application Keys authorization and manual API-key fallback
- Android Keystore encrypted credentials
- Upload, select and explicitly confirmed print start
- File browsing and common file operations
- Printer/job/temperature monitoring and webcam snapshots
- Start, pause, resume, restart and cancel controls
- Connection, homing, jog, temperature, extrusion and feed/flow controls
- Guarded single-line terminal commands

Commands are serialized and rechecked against printer state immediately before execution. OctoPrint is covered by automated tests but still needs broader validation against real servers and connected printers.

See [`docs/octoprint-integration.md`](docs/octoprint-integration.md) for implementation and security details.

## Reference printer

The built-in baseline is a modified Ender 3 V2 with:

- 230 × 230 × 250 mm build volume
- 0.4 mm nozzle and 1.75 mm filament
- Marlin G-code
- Direct drive, dual Z and a Z probe
- Heated bed and editable start/end G-code

## Cura comparison

One reference model sliced from the same Cura `5.11.0-beta.1` project data produced:

| Metric | EnderSlicerCura | Cura Desktop | Difference |
|---|---:|---:|---:|
| Layers | 115 | 115 | Exact |
| Printed bounds | 65.213–164.783 × 65.213–164.783 × 0.28–23.08 mm | Same | Exact |
| Filament metadata | 2535.9 mm | 2536.3 mm | −0.0158% |
| Extruding XY path | 73061.74 mm | 73074.24 mm | −0.0171% |
| Travel XY path | 76720.01 mm | 80292.77 mm | −4.45% |
| Estimated time | 4806.07 s | 4883.65 s | −1.59% |
| Firmware retracts | 1352 / 1351 | 1352 / 1351 | Exact |

Printed geometry and extrusion destinations were extremely close. The larger travel difference came mainly from Cura Desktop taking longer segmented combing detours. This is a useful reference result, not proof of parity for every model or Cura setting.

## Current limitations

- Single-model and single-extruder slicing workflow
- No duplicate/auto-arrange workflow or Cura plugin compatibility
- Arc and Wave overhangs remain experimental
- High-density models may exceed the Android heap
- OctoPrint still needs broader real-server and real-printer validation
- Printer-specific calibration commands must be verified against the installed firmware

## Build

Requirements:

- JDK 17
- Android SDK 36
- Android NDK `28.2.13676358`
- CMake `3.22.1` and `3.31.6`
- Gradle `9.4.1`

From a clean checkout:

```bash
chmod +x scripts/fetch-cura-resources.sh scripts/build-curaengine-android.sh
scripts/fetch-cura-resources.sh

export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
export APP_JNILIBS_DIR="$PWD/app/src/main/jniLibs"
scripts/build-curaengine-android.sh

gradle :app:verifyDebugApkContents
```

`verifyDebugApkContents` builds the debug APK and verifies that the ARM64 CuraEngine executable is packaged. GitHub Actions runs the native build, unit/regression tests, Cura definition audits and APK-content checks, then uploads the APK and logs as workflow artifacts.

## Safety

Generated G-code is checked for valid extrusion temperatures, machine bounds, metadata and filename formatting before export. Remote printing requires explicit confirmation.

Always verify the printer condition, model placement, build volume, temperatures, filament, first layer and custom G-code. Terminal commands can move axes, heat the printer, modify firmware state or stop a print.

## License

EnderSlicerCura is distributed under GNU AGPL-3.0-or-later because it links to CuraEngine. The embedded BumpMesh source is retained under `AGPL-3.0-only`.

See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for attribution and dependency details. UltiMaker and Cura are trademarks of their respective owners. EnderSlicerCura is not an official UltiMaker, Creality or CNC Kitchen application.