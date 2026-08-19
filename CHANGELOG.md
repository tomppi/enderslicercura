# Changelog

All notable changes to EnderSlicerCura are documented here. The format is based
on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

- Support painting could make slicing take tens of minutes or time out: the
  painted prisms were fed to CuraEngine's meshfix union-all pass, which welded
  thousands of overlapping volumes. Painted modifier meshes now skip the
  union fix (they are already closed volumes), painted regions are capped at
  20,000 triangles with a clear error, and conical prism refinement is capped
  at one level.
- Slicing with painted supports while Generate supports was off silently
  produced no supports (CuraEngine only generates supports when support_enable
  is on). The app now enables supports for that slice and says so.
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
