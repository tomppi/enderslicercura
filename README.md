<p align="center">
  <img src="docs/esc-icon.svg" width="128" height="128" alt="EnderSlicerCura ESC icon">
</p>

# EnderSlicerCura

EnderSlicerCura is an Android-first CuraEngine front end for slicing, inspecting, calibrating and sending prints directly from a phone or foldable. It currently has its most complete real-printer validation on a modified Creality Ender 3 V2, while keeping the machine dimensions, nozzle, filament, start/end G-code and print settings editable for other printers.

The app can import STL models and Cura configuration data, edit model placement, add displacement textures through an offline BumpMesh workspace, slice with the bundled ARM64 CuraEngine, preview the full print by layer, add non-destructive layer events, generate compact calibration models, export validated G-code and operate an OctoPrint server from inside the app.

Android application ID: `com.tomppi.enderslicercura`.

## Project status

The current development line is **0.9.0-dev** and uses CuraEngine and matching Cura resources from **5.11.0-beta.1**. BumpMesh is pinned to upstream commit `a6ac179149b8a17c71a9469dd4cb6f866c0c01d1`.

EnderSlicerCura already produces output very close to Cura Desktop for the current reference profile, but it is still development software rather than a complete Cura replacement. The OctoPrint subsystem is implemented, hardened and covered by automated tests; validation against a real OctoPrint server and connected printer is still required before that integration should be considered production-ready.

## Highlights

### Slicing and Cura compatibility

- Bundled ARM64 CuraEngine with adaptive use of up to eight workers
- Cura `.3mf` project configuration and `.curaprofile` import
- Full machine/extruder definition inheritance and formula recalculation
- Imported settings retained as a persistent baseline with explicit app overrides
- Editable categorized print settings, printer definition and start/end G-code
- Native CuraEngine adaptive layer-height controls
- Tree and normal supports, support interfaces and support preview
- Native experimental Multiplex arc-overhang paths with bridge fallback
- Cura estimated print time and repaired G-code metadata
- CRLF printer-compatible export with unique `.gcode` filenames

### Model handling and texturing

- Binary and ASCII STL import with streamed parsing for high-density meshes
- Persisted triangle limits from 1.5 million through an experimental 8 million
- Model centering, movement, rotation, drop-to-bed and lay-flat controls
- Cura project scene-transform support for a separately imported STL
- OpenGL model viewer with unrestricted orbit, zoom and pan
- Offline BumpMesh displacement-texture workspace
- Preset and custom height maps with planar, triplanar/cubic and cylindrical mapping
- Surface/angle masks, subdivision, displacement and decimation
- Textured STL returned directly to the normal validated import workflow

### Layer preview and print editing

- Cumulative layer preview colored by commanded print speed
- High-contrast current-layer ribbons with optional dimmed build-up context
- Separate support, support-interface and adhesion highlighting
- Full-height sampled previews for very large G-code files
- Actual layer-height range display and timeline event markers
- Non-destructive layer-event editing without re-slicing
- Pause, filament change, temperature, fan, speed, flow, retraction, camera, display-message and guarded custom-G-code events

### Calibration

- Compact support-free temperature, flow, speed and fan models
- Firmware-retraction and pressure-advance test models
- Junction-deviation sharp-star calibration model
- Type-specific temporary slicing overrides that preserve normal profile behavior unless it would invalidate the selected test
- Marlin `M900 K` pressure-advance stepping and `M205 J` junction-deviation stepping

### Android integration

- Native Jetpack Compose interface
- Compact and foldable-friendly wide layouts
- Persistent printer/profile/app-setting restoration
- Startup restoration guard preventing late saved-state loads from overwriting a fast user action
- GitHub Actions pipeline for CuraEngine, tests and APK artifacts

## OctoPrint integration

Open the persistent **OctoPrint** action in the app to configure and operate a server. A server may be entered as a hostname, IP address or URL, including installations hosted below a path prefix.

### Authorization and storage

- OctoPrint Application Keys browser authorization
- Manual user API-key fallback
- API keys encrypted with Android Keystore AES-GCM
- Stored OctoPrint credentials excluded from Android backup and device transfer
- Existing working credentials retained until replacement authorization succeeds
- Approval page can be reopened while authorization is pending
- Cancellation, denial or timeout does not destroy the previous working setup
- Same-origin checks prevent API keys from being sent to unrelated hosts
- Revoked or rejected credentials stop polling and require reauthorization instead of creating an endless retry loop

### Upload and files

- Upload the current validated G-code
- Upload and select
- Upload, select and start printing after explicit confirmation
- Remote folder selection and upload progress
- Immutable upload snapshots so a re-slice cannot modify an in-flight transfer
- Duplicate-upload prevention
- Warning when OctoPrint reports that requested selection or printing was not effective
- Recursive local-file browser with free-space reporting
- Select, print, create folder, copy, move and delete operations
- Canonical remote-path validation rejecting traversal, dot segments, empty segments, backslashes and control characters

### Monitoring and control

- Printer, serial connection and job state
- Nozzle, bed and chamber temperatures
- Selected file, completion, elapsed/remaining time and current Z
- Start, pause, resume, restart and cancel controls
- Serial connect/disconnect and saved auto-connect options
- X/Y/Z jog and homing
- Tool/bed temperature targets, extrusion/retraction and feed/flow overrides
- Guarded single-line terminal commands
- Webcam snapshots with OctoPrint flip/rotation settings

Commands are serialized, and safety state is checked again immediately before execution. Reconnect, disconnect, motion, homing, extrusion and terminal commands are blocked while a print is active, paused, pausing or cancelling. Old responses are tied to a configuration generation so a previous server cannot overwrite state after the user changes or removes the configuration. Active HTTP requests are cancelled or ignored when the session changes.

Static server/user/webcam settings are cached instead of being downloaded during every active-print poll. Webcam polling runs only while the Status page is visible, and images are bounds-checked and downsampled before full bitmap allocation.

See [`docs/octoprint-integration.md`](docs/octoprint-integration.md) for the implementation and security notes and [`docs/bug-audit.md`](docs/bug-audit.md) for the completed audit.

## BumpMesh texturing

Import and position an STL, then open **Menu → Texture model (BumpMesh)**. The app stages the displayed mesh as a temporary binary STL and opens the packaged BumpMesh workspace. Choose a preset or custom height map, adjust mapping, amplitude, masking, output resolution and triangle count, then use **Export STL**.

The Android bridge captures the binary STL locally, validates its exact triangle count and file length, and returns it to the normal model-import path. No model or texture is uploaded to a server. The embedded workspace uses packaged copies of BumpMesh, Three.js, fflate and meshStep rather than a live website or CDN modules.

Open **Menu → Mesh triangle limit** to choose Compatible (1.5 million), High detail (3 million), Very high detail (5 million), Extreme (8 million), or a custom value between 100,000 and 8 million triangles. The persisted limit is shared by normal STL imports, BumpMesh and Android validation of textured exports.

High-density STL parsing streams the staged file from disk instead of retaining the complete STL byte array beside the parsed vertex buffer. A five-million-triangle parsed mesh alone is roughly 343 MiB, so rendering, transforms, export and WebView processing can still exceed the application heap. The 8-million preset remains explicitly experimental.

## Adaptive layers, layer events and calibration

Adaptive layer height uses CuraEngine's native `adaptive_layer_height_*` settings. Enable it under **Print settings → Quality**, then tune variation, variation step and surface-detail threshold or select a preset. The layer viewer reports the actual minimum and maximum heights found in the generated G-code.

After slicing, select a layer and open **Add event**. Events are inserted after Cura's layer marker and rebuilt from an untouched base G-code file, so editing events does not run CuraEngine again. Unsafe layer-event commands such as homing, emergency stop, EEPROM writes and motor release are blocked.

Open **Menu → Calibration generator** to create a compact test. Temperature models combine grounded posts, bridges, stepped overhangs and a thin fin. Flow models use a grounded thin-wall tube, bridge coupons and measurement ribs. Speed and junction-deviation tests use a continuously stacked sharp star. Fan models use bridge posts and stepped 45-degree brackets. Retraction and pressure-advance tests use isolated grounded posts that force travel moves and expose restart/stringing behavior.

Calibration slicing applies minimal type-specific overrides instead of disabling all advanced settings. These overrides never modify the saved profile.

## Native arc overhangs

Enable **Print settings → Experimental → Arc overhangs (Multiplex)** to replace eligible bridge-classified bottom skin with expanding native arc paths. The port runs inside CuraEngine, uses the sliced bottom-skin polygon and material from the preceding layer, retains one center for a connected island, clips radii to the island and inserts the ordered paths into the `LayerPlan`.

The implementation is derived from Steven McCulloch's arc-overhang research and SuperPleccer's Multiplex C++ implementation. It is experimental rather than a promise of universal support-free printing. Floating islands, regions above the configured radius/area limits and geometry without a safe supported anchor fall back to Cura's bridge generator. Arc overhangs do not automatically remove supports.

## Reference printer

The most-tested built-in baseline is:

- Modified Creality Ender 3 V2
- 230 × 230 × 250 mm build volume
- 0.4 mm nozzle
- 1.75 mm filament
- Marlin G-code
- Direct-drive extruder
- Dual-Z drive
- Z probe using UBL mesh slot 0
- Heated bed
- User-editable start and end G-code

## Cura comparison

The following comparison used the same STL and Cura `5.11.0-beta.1` project/profile data in Cura Desktop and EnderSlicerCura.

### Overall result

| Metric | EnderSlicerCura | Cura Desktop | Difference |
|---|---:|---:|---:|
| Layers | 115 | 115 | Exact |
| Layer height | 0.20 mm | 0.20 mm | Exact |
| Initial layer height | 0.28 mm | 0.28 mm | Exact |
| X bounds | 65.213–164.783 mm | 65.213–164.783 mm | Exact |
| Y bounds | 65.213–164.783 mm | 65.213–164.783 mm | Exact |
| Printed Z bounds | 0.28–23.08 mm | 0.28–23.08 mm | Exact |
| Filament metadata | 2535.9 mm | 2536.3 mm | −0.4 mm (−0.0158%) |
| Extruding XY path | 73061.74 mm | 73074.24 mm | −12.50 mm (−0.0171%) |
| Travel XY path | 76720.01 mm | 80292.77 mm | −3572.75 mm (−4.45%) |
| Estimated time | 4806.07 s | 4883.65 s | −77.58 s (−1.59%) |
| Firmware retracts (`G10`) | 1352 | 1352 | Exact |
| Firmware unretracts (`G11`) | 1351 | 1351 | Exact |

The larger travel/time difference is concentrated mainly in early-layer skin travel. Printed geometry and extrusion destinations remain extremely close; Cura Desktop takes several long segmented combing detours that EnderSlicerCura avoids. More models are needed to determine how often this path-ordering difference appears.

### Feature extrusion

| Feature | EnderSlicerCura | Cura Desktop | Difference |
|---|---:|---:|---:|
| Inner walls | 469.29038 mm | 469.29721 mm | −0.00683 mm (−0.00146%) |
| Outer walls | 621.63546 mm | 621.63765 mm | −0.00219 mm (−0.00035%) |
| Skin | 1352.49266 mm | 1352.58103 mm | −0.08837 mm (−0.00653%) |
| Infill | 29.26724 mm | 29.51388 mm | −0.24664 mm (−0.83567%) |
| Support | 47.90181 mm | 47.92595 mm | −0.02414 mm (−0.05037%) |
| Support interface | 8.32455 mm | 8.35116 mm | −0.02661 mm (−0.31864%) |

The files contained identical counts of wall, skin, infill, support, support-interface and skirt sections. This is a strong parity result for one reference model, not proof that every Cura setting and geometry case is identical.

## Remaining validation and limitations

Additional Cura comparisons should cover large/small models, disconnected islands, tree and normal supports, dense interfaces, different infill patterns, adhesion modes, transforms, textured meshes, overhangs, bridges, thin walls, long previews, and multiple printer/material profiles.

Current slicing limitations include single-model and single-extruder workflows. Duplicate/auto-arrange workflows and full Cura plugin compatibility are not implemented. The first BumpMesh integration returns the textured STL through the normal import path, so unusual manual XY placement should be rechecked after texturing.

Layer events and calibration currently target Marlin-compatible commands. Verify support for commands such as `M600`, `M240`, `M207`, `M220`, `M221`, `M900 K` and `M205 J`. Junction-deviation calibration only applies when Marlin is built without `CLASSIC_JERK`, and pressure-advance calibration requires Marlin Linear Advance or compatible FT Motion support.

OctoPrint still requires real-server and real-printer validation covering initial and replacement authorization, cancellation rollback, manual-key fallback, revoked-key recovery, upload/select/print effective-result handling, file operations, webcam behavior, controls during state transitions, configuration removal during an upload and one deliberately small first print.

## Large layer previews

The exported G-code is never shortened by the preview system. For very large files, the viewer retains up to 800,000 representative extrusion paths distributed across the complete print. Every layer and the full printed height remain available even when the preview is marked as capped.

## Build

Requirements:

- JDK 17
- Android SDK platform 36
- Android NDK `28.2.13676358`
- CMake `3.22.1` for the Android project
- CMake `3.31.6` for the CuraEngine cross-build pipeline
- Gradle `9.4.1`
- ARM64 Android device (`arm64-v8a`)
- Network access on the first clean build to fetch pinned Cura and BumpMesh sources

From a clean checkout, prepare the pinned Cura resources and ARM64 CuraEngine before asking Gradle to package the app:

```bash
chmod +x scripts/fetch-cura-resources.sh scripts/build-curaengine-android.sh
scripts/fetch-cura-resources.sh

export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
export APP_JNILIBS_DIR="$PWD/app/src/main/jniLibs"
scripts/build-curaengine-android.sh

gradle :app:verifyDebugApkContents
```

`verifyDebugApkContents` assembles the debug APK and verifies that it contains a non-empty ARM64 CuraEngine entry. Direct Gradle assembly also validates the staged native executable and fails early with the required native-build command when the file is missing, empty, not an ELF, or not AArch64.

The Gradle project prepares the pinned BumpMesh workspace before `preBuild`. Generated assets are stored under `app/src/main/assets/bumpmesh/`, ignored by Git and reused while the source marker remains unchanged.

GitHub Actions follows the same native preparation and APK-verification contract, runs unit/regression and complete-definition audits, and uploads the APK and build logs.

## Safety

Generated G-code is validated before export. The validator checks active nozzle targets during extrusion, repairs key metadata, uses CRLF line endings and ensures `.gcode` is the final filename extension.

BumpMesh output is accepted only when it is a valid binary STL whose header triangle count exactly matches its file length and remains within the configured mesh limit.

Remote printing always requires explicit confirmation. Verify printer condition, bed, filament, temperatures, clearances, first-layer setup and the selected G-code before starting through OctoPrint. Raw terminal commands can move axes, heat the printer, change EEPROM settings or stop a print; send only commands you understand.

Always inspect machine dimensions, model placement, textured geometry, settings and custom start/end G-code before printing. This remains development software and has not yet been validated across the full range of Cura-supported printers, profiles or OctoPrint installations.

## License

EnderSlicerCura is intended to be distributed under GNU AGPL-3.0-or-later because it links to CuraEngine, which is AGPL-licensed. The embedded BumpMesh source is retained under `AGPL-3.0-only`. See `THIRD_PARTY_NOTICES.md` and the generated BumpMesh dependency license files for details.

UltiMaker and Cura are trademarks of their respective owners. EnderSlicerCura is not an official UltiMaker, Creality or CNC Kitchen application.
