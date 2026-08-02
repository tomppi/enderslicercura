# EnderSlicerCura

EnderSlicerCura is an Android-first CuraEngine slicer for preparing, previewing, calibrating and sending 3D prints without a desktop computer.

The application imports STL models and Cura-compatible project/profile data, exposes common and advanced print settings, runs a packaged ARM64 CuraEngine, validates the resulting G-code, presents model and layer previews, exports through Android's document framework and can upload or control jobs through OctoPrint.

## Current capabilities

### Model and project handling

- Import binary or strict ASCII STL models
- Import Cura-compatible `.3mf` projects and `.curaprofile` profiles
- Preserve imported Cura formulas and resolve them against the complete embedded definition stack
- Keep the imported profile as an immutable baseline while storing app edits as explicit overrides
- Restore the active model workspace after Android process recreation without restoring stale G-code
- Move, scale, rotate and lay models flat
- Enforce rectangular or elliptic build-volume limits with front-left or centered machine origins
- Apply complete 3MF component/source transforms and request-local affine snapshots

### Slicing and G-code

- Packaged CuraEngine 5.11.0-beta.1 for Android ARM64
- Marlin, Klipper and RepRapFirmware command encoding where supported
- Custom start and end G-code
- Supports, interfaces, adhesion, ironing, adaptive layers, coasting and firmware retraction
- Immutable per-slice artifacts and cancellation-safe CuraEngine process ownership
- Modal G-code validation for absolute/relative axes and extrusion
- Positive-extrusion build-volume checks
- Safe heater shutdown and temperature-target validation
- Estimated print time and validated output size

### Preview and editing

- Interactive model viewer with orbit, pinch zoom and two-finger pan
- Model-priority reset framing with retained build-plate context
- Layer preview with cumulative-layer selection
- Speed, support, interface, adhesion, Arc Overhang and Wave Overhang colouring
- Rare-feature retention under preview sampling limits
- Layer-specific G-code events without re-slicing
- Persistent model/layer camera and editing state

### Profiles and presets

- Cura definition inheritance and formula dependency resolution
- Global, extruder and model scope preservation
- Editable imported wall, top/bottom, support-interface, expansion, connected-infill, raft, ironing and material values
- Print and filament presets with validation and explicit override tracking
- Reset app overrides back to the immutable imported baseline
- Imported material metadata and enabled-extruder reporting

## Android storage and reliability

The app uses Android document providers rather than assuming unrestricted filesystem paths. Persistable URI permissions are retained where the provider supports them, metadata and provider work run off the main thread, and exported/uploaded G-code is leased while in use so retention cleanup cannot delete an active artifact.

CuraEngine runs as an application-owned subprocess with bounded cancellation, timeout and late-launch cleanup. Slice requests use unique workspaces and publish only validated immutable results. The normal build verifies that the packaged engine is a non-empty ARM64 ELF and checks the APK entry before accepting the build.

## OctoPrint

Configure OctoPrint from the main screen or menu. The integration provides:

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

## Native wave overhangs

Enable **Print settings → Experimental → Wave overhangs** to replace eligible open-air bottom skin with expanding, clipped wavefronts seeded on material from the previous model layer. Smart, monotonic and zigzag traversal are available. Wave paths are turquoise in the layer preview and use an absolute mm³/mm flow setting because the bead is deposited into open air.

Wave and Arc overhangs are mutually exclusive. The generator is all-or-nothing per skin island: missing anchors, incomplete propagation or iteration limits retain Cura's normal bridge/skin path. Wavefront levels always print from supported material toward unsupported material; the odd-layer option reverses path direction inside each level without reversing that safety order. The feature is disabled by default and remains experimental; maximum cooling and a small test model are strongly recommended.

## Native arc overhangs

Enable **Print settings → Experimental → Arc overhangs (Multiplex)** to replace eligible bridge-classified bottom skin with expanding native arc paths. The port runs inside CuraEngine, uses the sliced bottom-skin polygon and material from the preceding layer, retains one center for a connected island, clips radii to the island and inserts the ordered paths into the `LayerPlan`.

The implementation is derived from Steven McCulloch's arc-overhang research and SuperPleccer's Multiplex C++ implementation. It is experimental rather than a promise of universal support-free printing. Floating islands, regions above the configured radius/area limits and geometry without a safe supported anchor fall back to Cura's bridge generator. Arc overhangs do not automatically remove supports.

## Reference printer

The most-tested built-in baseline is:

- Modified Creality Ender 3 V2
- 230 × 230 × 250 mm build volume
- 0.4 mm nozzle
- 1.75 mm filament
- Direct-drive extruder
- Dual-Z drive
- Z probe with UBL

The slicer remains printer-agnostic when a compatible Cura definition/profile is imported.

## Building

The normal GitHub Actions workflow:

1. Fetches and verifies the pinned Cura definition chain.
2. Prepares and audits the offline BumpMesh workspace.
3. Builds CuraEngine 5.11.0-beta.1 for Android ARM64.
4. Verifies the engine ELF, ABI and packaged APK entry.
5. Runs unit tests and the complete Cura definition audit.
6. Assembles and inspects the debug APK.
7. Uploads the APK and build logs.

Local Android builds require Java 17, Android SDK 36, NDK 28.2.13676358, Python 3, Conan 2 and Gradle 9.4.1. Run `scripts/fetch-cura-resources.sh` before building when the pinned Cura definitions are not already present.

## Licences

EnderSlicerCura and its native CuraEngine modifications are distributed under the GNU Affero General Public License, version 3 or later. Packaged third-party components retain their own notices and licence files. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and the packaged asset licences.
