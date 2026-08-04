# EnderSlicerCura

**EnderSlicerCura** is an experimental Android slicer that packages CuraEngine, offline filaSim structural/thermal FEA, Smart Infill, non-planar overhang generation, calibration tools and OctoPrint handoff into one local workflow.

The app targets 64-bit ARM Android devices. Development testing is currently centered on an Ender 3 V2, but printer definitions, imported Cura profiles/projects and runtime settings are not hard-coded to that machine.

> This is experimental software. Inspect generated G-code, FEA assumptions and printer behavior before relying on the output.

## Current capabilities

### CuraEngine slicing

- Import STL models.
- Import Cura `.curaprofile` files and Cura `.3mf` projects.
- Preserve imported settings and user overrides.
- Resolve Cura dependencies before launching the packaged ARM64 CuraEngine.
- Preview cumulative layers, supports, interfaces and speed coloring.
- Show estimated print time.
- Export validated G-code or send it to OctoPrint.
- Generate compact calibration models for temperature, retraction, flow, fan, pressure advance and junction deviation.

### Model and toolpath workflow

- Orbit, pan and zoom the model.
- Move, rotate, scale, center and lay the model flat.
- Clear the build plate without resetting printer/profile settings.
- Inspect a start-to-finish nozzle-path viewer for generated output.
- Use BumpMesh/STL texturing and configurable triangle budgets.
- Apply arc-overhang, wave-overhang and other experimental toolpath features where supported.

### Smart Infill and structural FEA

The app packages a pinned offline build of [CNCKitchen/smartInfillGenerator](https://github.com/CNCKitchen/smartInfillGenerator), also known as **filaSim**, at commit:

```text
e7485ec22d4ebe8baca04190404fbb877c90e031
```

The workflow:

1. sends the exact displayed STL pose to filaSim;
2. defines supports and mechanical loads;
3. solves or optimizes the voxel FEA model;
4. exports graded or binary density regions;
5. imports those regions as shell-free Cura modifier meshes;
6. slices the model and modifiers together with synchronized wall, top/bottom, line-width and layer assumptions.

No STL or solver input is uploaded to a live website.

### Build-process thermal FEA

filaSim's **Build Simulation** estimates print-process thermo-mechanical behavior using sequential voxel-layer activation and an inherent/eigenstrain shrink model.

It reports:

- maximum deformation while bonded to the bed;
- deformation after release;
- peak bed lift traction;
- peak bed shear traction;
- solver/grid diagnostics;
- exact raw worker values, STL SHA-256 and cumulative solved pose.

Build Simulation asks:

> Will this geometry and orientation distort or create high bed reactions while it is printed and cooled?

It does **not** solve transient heat flow or finished-part service temperatures. Results are experimental and no universal bed-release threshold is applied.

### Thermal Integrity simulation

The Android-only **Thermal Integrity** workspace models the finished part under a service heat source, cooling environment and current mechanical load case.

It adds:

- cell-centred finite-volume heat conduction on filaSim's voxel grid;
- separate X/Y/Z conductivity for printed-material anisotropy;
- steady-state and implicit-transient modes;
- total surface heat power and volumetric heat generation;
- fixed-temperature faces;
- convection and nonlinear thermal radiation;
- density-aware conductivity, heat capacity, stiffness and strength;
- thermal expansion coupled into filaSim structural FEA;
- temperature-reduced modulus and allowable strength;
- free-expansion 3-2-1 grounding or current filaSim supports;
- temperature slices and transient temperature history;
- energy-balance diagnostics;
- maximum temperature, hotspot, thermal deformation, stress and conservative safety factor;
- native auditable reports bound to the exact STL, pose, material card, boundaries and grid.

Thermal Integrity asks:

> Will the finished printed part remain thermally and mechanically acceptable under these entered service conditions?

PLA, PETG and ABS presets are literature-seeded and fully editable. They are not certified material cards for a particular spool, print profile or environment.

See [Thermal Integrity simulation](docs/thermal-integrity.md) for equations, units, assumptions, interpretation and the recommended validation coupon.

## FEA report provenance

Build-process and service-temperature reports are intentionally separate.

Each native report includes:

- analysis and solver identity;
- pinned filaSim commit;
- exact source STL SHA-256;
- exact cumulative filaSim 3×4 pose;
- complete material/process/boundary inputs;
- grid dimensions and voxel size;
- raw worker results and solver diagnostics;
- a SHA-256 analysis fingerprint;
- explicit confidence and applicability limits.

Different orientations, scales, placements, material cards, boundary conditions or grids create separate reports instead of overwriting one another.

If exact pose capture fails, the simulation remains fail-open for on-screen inspection, but native report saving is disabled for that run.

## Thermal Integrity limitations

Thermal Integrity does not currently calculate:

- G-code/nozzle-path reheating and cooling history;
- interlayer weld kinetics or delamination probability;
- temperature-dependent creep or stress relaxation;
- fatigue, impact or certified service life;
- moisture, aging, UV or chemical exposure;
- melting, phase change or annealing;
- enclosure airflow CFD;
- certified thermal contact resistance;
- a probability of failure or regulatory verdict.

A negative temperature margin or safety factor below one is a warning under the entered assumptions. Positive margins are not proof of durability.

## OctoPrint

The OctoPrint workflow supports:

- setup validation and encrypted API-key storage;
- printer, job and connection status;
- local G-code upload;
- optional upload-and-start;
- cancel, pause and resume;
- snapshot preview when configured.

The app does not run an OctoPrint server.

## Build requirements

- Android SDK
- Java 17
- Node.js 22.18.0 or newer
- Rust toolchain
- `wasm-pack`
- Python 3
- Gradle 9.4.1 or a compatible project wrapper

The build downloads the exact pinned filaSim source, verifies the archive SHA-256, applies deterministic Android and thermal-integrity source transforms, builds the single-threaded Rust/WASM engine, compiles the React workspace, injects the Android bridges and verifies the generated asset manifest.

```bash
gradle :app:prepareFilaSimAssets
gradle :app:testDebugUnitTest :app:assembleDebug
```

Generated filaSim assets are stored under:

```text
app/src/main/assets/filasim/
```

Asset format **9** includes the service-temperature thermal solver and Android Thermal Integrity workspace.

## Branch validation

The thermal-integrity branch workflow validates:

- deterministic patching of the exact pinned filaSim commit;
- Rust finite-volume heat-solver tests;
- WASM and TypeScript compilation;
- JavaScript syntax;
- native report schema/fingerprint/tamper tests;
- complete Android unit tests;
- debug APK assembly;
- packaged asset format, source provenance, hash manifest and runtime presence.

Physical validation remains mandatory. Automated tests cannot know the real filament formulation, moisture, mount, interface pressure, airflow, heater geometry or sensor accuracy.

## Recommended first Thermal Integrity test

Use a simple rectangular bar or L-bracket.

1. Choose PLA and a coarse/normal filaSim grid.
2. Use steady state.
3. Put 1–3 W on one end face.
4. Hold the opposite face at 23 °C.
5. Use 23 °C ambient, 8 W/(m²·K) convection and emissivity 0.9.
6. Start with free expansion and no mechanical load.
7. Verify temperature falls from the heated end toward the fixed-temperature end.
8. Confirm heat rejected approximately matches heat input.
9. Repeat with a finer grid.
10. Run transient mode and reduce the time step until peak temperature converges.
11. Add real supports and loads only after the thermal-only case behaves correctly.
12. Validate a printed coupon with temperature sensors before applying the model to a critical part.

## Safety

- Review the complete G-code and nozzle path before printing.
- Begin experimental toolpaths with a small model and conservative settings.
- Keep non-planar output disabled until machine-clearance testing is complete.
- Treat FEA outputs as comparative engineering estimates.
- Verify critical temperatures and loads with physical measurements.
- Stop the printer immediately if motion, extrusion or heating differs from the preview.

## License and attribution

EnderSlicerCura is licensed under **AGPL-3.0-or-later**.

Packaged filaSim code remains under its upstream AGPL license and pinned-source attribution. CuraEngine, AndroidX and other dependencies retain their respective licenses. The APK contains source/provenance notices and exact package manifests for the generated filaSim runtime.
