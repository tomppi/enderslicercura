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

### Smart Infill with filaSim

The packaged **filaSim** workspace performs load-dependent FEA and density optimization entirely on the device. Select supports and loads, verify the setup, optimize the part, then return the generated density regions directly to EnderSlicerCura.

- Fixed, elastic, frictionless, displacement and cylindrical supports
- Force and pressure loading with structural verification views
- Uniform-print analysis, stress, displacement and safety-factor results
- Graded and binary infill optimization with configurable density levels
- Stiffest-at-budget, match-uniform-stiffness and strength-target goals
- Cura modifier-volume transport using regional `infill_sparse_density`
- Exact model fingerprinting so movement, rotation or geometry changes invalidate stale results
- filaSim wall, line-width, layer-height, shell, pattern and base-density assumptions applied to the Cura slice
- Fully offline pinned Rust/WASM and React workspace; the model is not uploaded

The first complete integration uses filaSim modifier volumes with CuraEngine's infill-mesh system. Smart Infill remains experimental until regional-density G-code and physical prints have been validated across more models and load cases.

### Build-process thermal FEA

The same pinned filaSim workspace exposes **Build Simulation** as a separate mode. It reuses the voxel FEA grid and multigrid structural solver with sequential layer activation and inherent thermal strain.

- Layer-by-layer bonded build simulation
- Released-part warp after removing the bed constraint
- Bed lift-traction and in-plane shear reaction fields
- Bed and chamber temperature inputs through filaSim's locking-temperature ladder
- XY-versus-Z shrink and optional as-printed density-aware stiffness
- Native, persistent Markdown reports tied to the exact analyzed STL SHA-256
- Strict Android validation of solver identity, filaSim commit, units, numerical ranges and calibration claims
- Literature provenance for printed PLA/ABS thermal-expansion seeds

After a successful build simulation, **Save Thermal FEA Report** stores an auditable report. The toolbar's **Report** button reopens or shares the report for that exact model fingerprint.

This is a build-process thermo-mechanical approximation. It does not yet solve transient heat conduction, G-code path reheating, interlayer welding, creep, service-temperature softening or an absolute probability of bed release. Bed reactions are failure drivers, not a calibrated pass/fail verdict.

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

The integrated OctoPrint client includes encrypted authorization, upload/select/print, file management, monitoring, webcam snapshots and guarded printer controls. Commands are serialized and rechecked against printer state immediately before execution.

See [`docs/octoprint-integration.md`](docs/octoprint-integration.md) for implementation and security details.

## Reference printer

The built-in baseline is a modified Ender 3 V2 with a 230 × 230 × 250 mm build volume, 0.4 mm nozzle, 1.75 mm filament, Marlin, direct drive, dual Z, a Z probe and a heated bed.

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

Printed geometry and extrusion destinations were extremely close. This is a useful reference result, not proof of parity for every model or Cura setting.

## Current limitations

- Single printable model and single extruder
- No duplicate/auto-arrange workflow or Cura plugin compatibility
- Smart Infill, thermal FEA, Arc Overhangs and Wave Overhangs still need broader physical print validation
- filaSim currently uses its single-threaded WASM fallback inside Android WebView
- High-density models and fine FEA grids may exceed the Android heap
- Thermal FEA does not yet include transient conduction, G-code thermal history, delamination, creep or service-temperature failure
- OctoPrint still needs broader real-server and real-printer validation
- Printer-specific calibration commands must be verified against the installed firmware

## Build

Requirements:

- JDK 17
- Android SDK 36 and NDK `28.2.13676358`
- CMake `3.22.1` and `3.31.6`
- Gradle `9.4.1`
- Python 3
- Node.js `22.18.0` or newer
- Stable Rust with `wasm32-unknown-unknown`
- `wasm-pack 0.15.0`

From a clean checkout:

```bash
chmod +x scripts/fetch-cura-resources.sh scripts/build-curaengine-android.sh
scripts/fetch-cura-resources.sh

export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
export APP_JNILIBS_DIR="$PWD/app/src/main/jniLibs"
scripts/build-curaengine-android.sh

gradle :app:verifyDebugApkContents
```

Gradle prepares pinned offline BumpMesh and filaSim assets before `preBuild`. The filaSim workspace includes a verified `SHA256SUMS` manifest, and Gradle tracks the complete generated directory so stale or incomplete warm-build output is regenerated. `verifyDebugApkContents` builds the debug APK and verifies the ARM64 CuraEngine package. GitHub Actions also builds filaSim's WASM engine, runs unit/regression and definition audits, verifies packaged assets, and uploads the APK and logs.

## Safety

Generated G-code is checked for valid extrusion temperatures, machine bounds, metadata and filename formatting before export. Remote printing requires explicit confirmation.

Smart Infill and thermal FEA are engineering aids, not certified analyses. Loads, constraints, material data, layer adhesion, print orientation, thermal shrink and safety factors must match the real use case. A thermal report deliberately contains no absolute bed-adhesion pass/fail threshold. Inspect the layer preview and test a non-critical part before relying on an optimized or simulated design.

Always verify the printer condition, model placement, build volume, temperatures, filament, first layer and custom G-code. Terminal commands can move axes, heat the printer, modify firmware state or stop a print.

## License

EnderSlicerCura is distributed under GNU AGPL-3.0-or-later because it links to CuraEngine. The embedded BumpMesh and filaSim source are retained under `AGPL-3.0-only`.

See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for attribution and dependency details. UltiMaker and Cura are trademarks of their respective owners. EnderSlicerCura is not an official UltiMaker, Creality or CNC Kitchen application.