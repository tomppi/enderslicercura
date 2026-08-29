# Changelog

All notable changes to EnderSlicerCura are documented here. The format is based
on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Removed

- Bead-angle overhangs, wall-anchored infill and bead-chain overhangs were
  removed from the engine, the app and the settings UI; masonry-bonded walls
  remain (their generator keeps its home in BeadAngleOverhang for that).
  Nothing depends on the removed settings; presets/imports carrying the old
  keys ignore them.

### Added

- Support painting now combines with both non-planar pipelines: painted
  enforcer/blocker prisms are warped with the same transform as the model
  (relief-field flatten for CurviSlicer, cone warp around the model centre for
  conical slicing) so CuraEngine generates supports against the warped solid
  and the G-code transform restores both together.
- The OctoPrint webcam card opens the snapshot in a fullscreen viewer with
  pinch zoom (1x-6x), drag-to-pan and a double-tap reset; OctoPrint flip and
  rotation settings are preserved and the view stays live while open.
- UI polish pass: the model summary card uses label/value rows instead of a
  text dump; gesture help is dismissible and separate from status; a swatch
  legend replaces the view-mode explanation paragraph; the layer timeline
  is easier to grab; travel moves are dimmer in the path view; position
  numbers are locale-safe (no dangling decimal separator); the rotate sheet
  labels its fine step row; Start is only offered on an operational printer;
  disabled export explains itself; shared spacing tokens and a style guide
  (docs/ui-style-guide.md) standardize new work.
- The nozzle-path view is now physically based: each move is rendered as a
  3D bead whose width follows the sliced flow (deltaE x filament area /
  length / layer height) and whose height follows the layer height, so
  slicing at 0.12 mm vs 0.20 mm visibly changes the geometry. The palette
  is desaturated with shadowed side walls instead of the glowing outline,
  and the parser now captures per-move flow and layer height for this.
- Nozzle-path beads are shaded with a fixed directional light per side
  face (in the bead's own hue) plus a subtle odd-layer tint, so layers
  separate visually and angled segment joints blend instead of showing
  flat dark triangles. Side walls now use a flat, 15% darker tint of the
  bead colour instead of directional lambert variation - the directional
  range made bead rows crawl into corduroy stripes and chevrons when
  zoomed out, the flat tint keeps every zoom clean. Sub-0.05 mm micro
  segments emit with zero width (their side walls painted tiny dark
  specks) and the side tint is 0.90x.
- The nozzle-path camera now uses the model viewer's turntable controls:
  rotation/zoom/pan orbit around the printed-part centre instead of a
  touch-dependent orbit pivot, with the same sensitivity constants,
  camera fit and a double-tap reset.
- Pinch zoom in the nozzle-path view is now anchored at the point between
  both fingers instead of the first finger's touch point: the world point
  under the pinch focus stays pinned while zooming (pan compensation on
  the gesture focus plane).

### Simplified

- Single source of truth for the non-planar preparation shared by both engine
  transports (NonPlanarPreparation), shared G-code formatting/quantization,
  atomic publication, and cooperative cancellation (GcodeTransformSupport),
  a shared machine-key emission table (MachineCuraKeys), shared Smart Infill
  width keys on the contract object, a shared hex-digest helper, and one
  shared settings-field scaffold for all settings sheets (SettingsFields).
- Removed the abandoned NativeSlicer JNI bridge (Kotlin stub, C++ adapter,
  CMake target) — the APK execs the packaged CuraEngine binary — the unused
  printer metadata fields, the unreachable inward-cone branch in the conical
  transformer, dead built-in G-code/printer assets, the superseded
  fetch-curaengine script, and two low-value tests (a data-class no-op and a
  near-duplicate layer-event test; the unique tab-separated safety case moved
  to the processor suite).

### Added

- Support painting now combines with both non-planar pipelines: painted
  enforcer/blocker prisms are warped with the same transform as the model
  (relief-field flatten for CurviSlicer, cone warp around the model centre for
  conical slicing) so CuraEngine generates supports against the warped solid
  and the G-code transform restores both together.

### Fixed

- The arc/wave overhang generators never triggered on-device: the pinned
  Cura definitions default bridge detection off, and the app never enabled
  it. The standalone transport now sets bridge_settings_enabled when an
  overhang feature is on, and the resolved transport sets it at model-mesh
  scope, so unsupported bottom skins are classified as bridges and the
  overhang fills can replace them.
- Support painting could make slicing take tens of minutes or time out:
  painted triangles become eight-triangle prisms, and the per-layer support
  computation grows superlinearly with the painted region. Painted regions are
  now capped at 5,000 triangles (about 40k prism triangles, a few seconds on
  the host engine) with a clear fail-closed error, painted modifier meshes
  skip the meshfix union pass, and conical prism refinement is capped at one
  level.
- Conical slicing with supports (automatic or painted) lifted the entire model
  off the build plate: support tower bottoms back-transformed below the bed
  and dragged the whole file upward. Support moves are now anchored to the
  plate and below-plate support layers are skipped.
- Conical preparation was effectively uncancellable: the cooperative
  cancellation checks never fired (interval mismatch) and the refine/warp
  loops lacked per-triangle checks; interrupted STL reads now surface as a
  clean cancellation instead of a ClosedByInterruptException.
- Importing a new model could persist the previous model's painted supports,
  which came back as phantom enforcers/blockers on the new model after a
  restart.
- Paint changes were only persisted alongside unrelated saves; recent paint
  could be lost silently on process death. Paint is now persisted with a short
  debounce.
- Strokes painted while a slice was running silently diverged from the
  exported G-code; painting is now ignored while the app is busy.
- Conical slices now auto-select the nozzle Path preview like CurviSlicer
  slices do.
- The Curvi G-code transform is now cooperatively cancellable (per-line and
  per-segment checks), resolved Cura requests reject duplicate modifier mesh
  names, and the settings-leak contract covers painted-support x non-planar
  combinations.

## [1.0.0] - 2026-08-17

First stable release.

### Added

- EasyConical conical slicing: STL cone warp with G-code back-transform for
  tilted-nozzle 4-axis printers (OUTWARD cone direction).
- Support painting: per-region support enforcers/blockers with brush picking,
  transported to CuraEngine as modifier meshes.
- Thickness-adaptive walls: automatic wall reinforcement at tight bends via
  modifier volumes.
- CuraEngine upgraded to 5.14.0-alpha.0 with pinned Cura resources; engine-drift
  defaults seeded for imported flattened project definitions.
- Arc-overhang and wave-overhang engine paths (experimental, off by default),
  smart overhang strategy, and CurviSlicer non-planar slicing.
- Smart Infill workspace with offline filaSim thermal FEA (experimental).
- Settings-leak validation suite pinning that advanced features, when disabled,
  leave core Cura slicing untouched in both engine transports.
- Real Gradle wrapper (9.4.1); JVM unit tests now run on a clean checkout
  without the native engine, downloaded assets, or Rust/wasm-pack.

### Fixed

- Adaptive-wall modifier slabs extended 0.1 mm below the build plate (and above
  the gantry on full-height parts), tripping build-volume validation on every
  adaptive-walls slice.
- APK packaging could ship the x86-64 `libcura-formulae-engine.so` from a shared
  Conan cache; the AArch64 library is now selected by ELF machine and the build
  fails loudly when it is missing.
- Conical slicing: back-transform bed contact, Z radius computed against the
  correct centre axis, adhesion/priming handled per EasyConical requirements.

### Changed

- Conical INWARD cone direction disabled: its warp geometry dips below the
  build plate for any real model; persisted selections are coerced to OUTWARD.

### Known limitations

- Single printable model, single extruder; no duplicate/auto-arrange workflow.
- Smart Infill, thermal FEA, arc/wave overhangs and the smart overhang strategy
  are experimental and need broader physical print validation.
- Non-planar slicing buffers the full transformed G-code in memory; very large
  or dense prints may need a raised Java heap (see README).
