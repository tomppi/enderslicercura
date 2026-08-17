<p align="center">
  <img src="docs/esc-icon.svg" width="128" height="128" alt="EnderSlicerCura ESC icon">
</p>

# EnderSlicerCura

EnderSlicerCura is an Android-first CuraEngine front end for importing, preparing, slicing, previewing and sending 3D prints from a phone or foldable. It is **1.0.0**, targets Android 10+ on **ARM64**, and bundles CuraEngine with Cura resources from **5.14.0-alpha.0**. Its most-tested baseline is a modified Creality Ender 3 V2.

> This is development software, not a complete Cura replacement. Inspect every model, setting and generated G-code before printing.

## Importing from Cura

The simplest way to reproduce your Cura setup is to save a **project** from Cura Desktop (**File → Save Project…**, a `.3mf`) and import it with **Menu → Import Cura project (.3mf)**. A project bundles the machine definition, quality/material settings and start/end G-code in one file, so EnderSlicerCura can resolve the same formulas and values Cura uses.

For just the print/filament settings, export a **profile** (**File → Save Profile…**, a `.curaprofile`) and use **Menu → Import Cura profile**. A profile may not include machine definitions; the app then falls back to its bundled Ender 3 V2 definitions.

Imported values are kept as a persistent baseline: they stay in effect until you override them in the app, and app overrides are tracked separately. Formula resolution is verified against the pinned **5.14.0-alpha.0** resources; projects from other Cura versions usually import, but verify the resolved settings before a critical print.

## Features

### Slicing & profiles

- Local ARM64 CuraEngine slicing with up to eight workers
- STL import plus Cura `.3mf` / `.curaprofile` import with machine/extruder inheritance and formula recalculation
- Editable printer, quality, material, supports, travel, cooling and adhesion settings
- Adaptive layers, estimated time and repaired G-code metadata; validated CRLF `.gcode` export

### Model, viewer & texturing

- Move, rotate, **scale by percentage**, center, lay flat and drop-to-bed; build-volume validation before slicing
- OpenGL model viewer, speed-colored layer preview and nozzle-path view
- Offline BumpMesh displacement texturing (planar/triplanar/cubic or cylindrical mapping, 100k–8M triangle limit)

### Print editing & calibration

- Non-destructive layer events — pause, filament change, temperature, fan, speed, flow, retraction, camera, message and guarded custom G-code — without re-slicing
- Temperature, flow, speed, fan, retraction, pressure-advance and junction-deviation calibration models with Marlin/Klipper/RepRapFirmware encoders

### Smart Infill & build-process thermal FEA (filaSim)

- Fully offline filaSim workspace: load-dependent FEA, graded/binary infill optimization via Cura modifier volumes, and layer-by-layer build simulation with warp and reaction reports
- Strict on-device validation of solver identity, units, transforms and numerical ranges; models never leave the device
- Still experimental until validated across more models and physical prints

### Experimental overhangs

- Native arc-overhang (Multiplex) and wave-overhang paths with normal bridge/skin fallback; mutually exclusive and off by default
- Smart overhang strategy: classifies the positioned mesh before slicing and decides where arc fill and CurviSlicer layers apply automatically, including a safe combined mode when CurviSlicer is enabled

### OctoPrint

- Encrypted authorization, upload/select/print, file browser, monitoring, webcam and guarded printer controls. See [`docs/octoprint-integration.md`](docs/octoprint-integration.md).

## Current limitations

- Single printable model, single extruder; no duplicate/auto-arrange workflow or Cura plugins
- Smart Infill, thermal FEA, arc/wave overhangs and the smart overhang strategy need broader physical print validation
- High-density models and fine FEA grids may exceed the Android heap; thermal FEA lacks transient conduction and creep
- Non-planar slicing (CurviSlicer and conical) buffers the full transformed G-code in memory, so very large or very dense prints can exhaust the Android heap (see "Increasing the Java heap")
- OctoPrint needs broader real-server validation; printer-specific calibration commands must be checked against the installed firmware

## Increasing the Java heap

Non-planar slicing (CurviSlicer and conical) builds the transformed G-code in memory, so very large or very dense prints can exhaust Android's default 512 MB large-heap limit and fail with an out-of-memory error.

On a rooted device the per-app heap can be raised through `dalvik.vm.heapsize`. In a root shell:

```sh
su
resetprop dalvik.vm.heapsize 1024m
resetprop dalvik.vm.heapgrowthlimit 512m
```

To persist across reboots, add the same `resetprop` lines to a Magisk boot script at `/data/adb/service.d/heap.sh`, then restart Zygote (`su -c "stop; start"`) or reboot. Verify with `getprop dalvik.vm.heapsize`. Only use larger values (for example `1536m`) on devices with 8 GB or more of RAM.

## Build

Requirements: JDK 17, Android SDK 36 + NDK `28.2.13676358`, CMake `3.22.1` / `3.31.6`, Gradle `9.4.1`, Python 3, Node.js `22.18.0+`, stable Rust (`wasm32-unknown-unknown`) and `wasm-pack 0.15.0`.

From a clean checkout:

```bash
chmod +x scripts/fetch-cura-resources.sh scripts/build-curaengine-android.sh
scripts/fetch-cura-resources.sh

export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
export APP_JNILIBS_DIR="$PWD/app/src/main/jniLibs"
scripts/build-curaengine-android.sh

gradle :app:verifyDebugApkContents
```

Gradle prepares the pinned offline BumpMesh and filaSim assets before `preBuild`; `verifyDebugApkContents` builds the debug APK and verifies the packaged ARM64 CuraEngine. GitHub Actions builds the WASM engine, runs the unit/regression and definition audits, verifies packaged assets and uploads the APK.

## Safety

Generated G-code is checked for valid extrusion temperatures, machine bounds, metadata and filename formatting before export, and remote printing requires explicit confirmation. Smart Infill and thermal FEA are engineering aids, not certified analyses — validate loads, constraints, material data, print orientation and safety factors before relying on them. Always verify the printer condition, model placement, build volume, temperatures, filament, first layer and custom G-code; terminal commands can move axes, heat the printer, modify firmware state or stop a print.

## License

EnderSlicerCura is distributed under GNU AGPL-3.0-or-later because it links to CuraEngine. The embedded BumpMesh and filaSim source are retained under `AGPL-3.0-only`. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md). UltiMaker and Cura are trademarks of their respective owners; EnderSlicerCura is not an official UltiMaker, Creality or CNC Kitchen application.
