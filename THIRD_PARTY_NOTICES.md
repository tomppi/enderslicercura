# Third-party notices

## filaSim / Smart Infill Generator

- Project: `CNCKitchen/smartInfillGenerator` (product name: filaSim)
- Pinned source commit: `e7485ec22d4ebe8baca04190404fbb877c90e031`
- License: GNU Affero General Public License v3.0 only (`AGPL-3.0-only`)
- Copyright: CNC Kitchen (Stefan Hermann) and contributors

EnderSlicerCura builds filaSim's single-threaded Rust/WASM engine and React interface from the pinned source, packages the resulting workspace for offline Android use, and adds an Android-only model/modifier handoff. The APK retains filaSim's license and a source notice. The complete corresponding source, Cargo lockfile and npm lockfile are the upstream repository at the pinned commit together with EnderSlicerCura's `scripts/prepare-filasim-assets.py` and `app/src/main/filasim/android-bridge.js`.

The filaSim web build includes its declared permissive or AGPL-compatible dependencies, including React/React DOM 19, Three.js 0.180, Zustand 5 and meshStep 0.1.1. Exact transitive versions and license metadata are recorded by the pinned `Cargo.lock`, `package-lock.json`, `Cargo.toml`, `package.json` and `deny.toml` files.

## Wave-overhang algorithm research and reference implementation

The native EnderSlicerCura wavefront generator is an independent CuraEngine adaptation of the propagation method documented by `dennisklappe/OrcaSlicer-WaveOverhangs`, itself based on `stmcculloch/PrusaSlicer-WaveOverhangs`. Those projects and CuraEngine are distributed under the GNU AGPL. The adapted source is retained under `native/curaengine/patches/` with attribution headers.

## BumpMesh / stlTexturizer

- Project: `CNCKitchen/stlTexturizer`
- Pinned source commit: `a6ac179149b8a17c71a9469dd4cb6f866c0c01d1`
- License: GNU Affero General Public License v3.0 only (`AGPL-3.0-only`)
- Copyright: CNC Kitchen (Stefan Hermann) and contributors

The Android build downloads the pinned source archive, retains its license file in the packaged workspace, replaces network module imports with local copies, and adds a small Android host bridge. The original project source remains available from its upstream GitHub repository.

## Three.js

- BumpMesh workspace version: r170 / 0.170.0
- filaSim workspace version: 0.180.x
- License: MIT
- Copyright: Three.js authors

The BumpMesh build retains the upstream license at `assets/bumpmesh/vendor/three/LICENSE`. filaSim's exact dependency version is recorded in its pinned npm lockfile.

## fflate

- Version: 0.8.2
- License: MIT
- Copyright: 101arrowz

The official npm package supplies the browser ESM build used by BumpMesh. Its package metadata, README and license are packaged beside the module.

## meshStep

- BumpMesh workspace version: 0.1.0
- filaSim workspace version: 0.1.1
- License: GNU Affero General Public License v3.0 only (`AGPL-3.0-only`)
- Copyright: CNC Kitchen and contributors

BumpMesh packages the published TypeScript source, generated distribution, metadata, README and AGPL license under `assets/bumpmesh/vendor/meshstep/`. filaSim's corresponding source is available through its pinned dependency and source tree.

## CuraEngine and Cura resources

CuraEngine is developed by UltiMaker and contributors and is licensed under GNU AGPL-3.0-or-later. The repository pins CuraEngine and matching Cura resources to `5.14.0-alpha.0`.

UltiMaker and Cura are trademarks of their respective owners.

## Arc-overhang research and SuperPleccer

- Original research/prototype: `stmcculloch/arc-overhang`
- Native Multiplex reference: `rvmn/SuperPleccer`
- Licenses: GPL-3.0 for the original prototype and AGPL-3.0 for SuperPleccer

EnderSlicerCura contains a CuraEngine-oriented native reimplementation of the Multiplex arc-overhang path-generation behavior. Attribution and implementation details are retained in `native/curaengine/patches/ARC_OVERHANG_NOTICE.md` and the native source headers.

## EasyConical conical slicing

- Project: `DigitalGrin/EasyConical`
- License: GNU General Public License v3.0 (`GPL-3.0`)
- Copyright: Alex Herskovitz and contributors

The Android conical-slicing backend is a Kotlin port of EasyConical's forward cone
transformation (`Transformation_MiniLibrary.py`) and G-code back-transformation
(`Backtransformation_MiniLibrary.py`), integrated into the native slicing pipeline
under `app/src/main/java/com/tomppi/enderslicer/conical/`. The underlying
conical-slicing strategy is derived from `CNCKitchen/ConicalSlicer` and the paper
"A Novel Slicing Strategy to Print Overhangs without Support Material" (Wüthrich et
al., Applied Sciences, 2021). The original project source remains available from
its upstream GitHub repository.

## Android Open Source Project and AndroidX

The application uses Android platform APIs and AndroidX libraries under their respective licenses.
