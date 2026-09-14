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

## Blender engine (`libblender_exec.so`) and its bundled libraries

The embedded engine is **Blender 3.6.22**, built for Android arm64 from the
`epai` / `APP-android_arm64` port, then patched — see
[`native/blender/patches/`](native/blender/patches/README.md), which publishes
every modified file in full.

**Blender is GPL-2.0-or-later.** Porting it produces a derivative work, so the
port's modifications carry the same licence; nobody can relicense a port of
Blender as their own. DuoSlicer is AGPL-3.0-or-later, and the two combine
lawfully because Blender's "or later" allows it to be taken as GPLv3, which
AGPLv3 section 13 permits linking with.

**Corresponding source.** Blender's source is at
<https://projects.blender.org/blender/blender>, the Android port at
<https://github.com/dshawshank/APP-android_arm64>, and this project's
modifications are published in full under `native/blender/patches/`.

### Bundled libraries

`libblender_exec.so` statically links, and the engine ships alongside, 120
shared libraries. Most are permissive; the copyleft ones are all compatible with
a GPL/AGPL whole, but they are copyleft and their notices must travel with a
binary.

| licence | libraries |
|---|---|
| GPL-2.0-or-later | Blender itself; FFTW (`libfftw3`); Potrace (`libpotrace`) |
| LGPL-2.1-or-later | FFmpeg (`libav*`, `libsw*`) — **verify the build's configuration**, which decides LGPL vs GPL |
| LGPL-2.0-or-later | OpenAL (`libopenal`); GMP (`libgmp`, `libgmpxx`) |
| Apache-2.0 | Cycles; OpenImageIO; OpenImageDenoise; Embree; OpenUSD; oneDNN; Draco; OpenPGL; TBB; OpenSSL |
| BSD-3-Clause | Alembic; OpenEXR and Imath (`libIex*`, `libIlmThread*`, `libImath*`); OpenColorIO; zstd |
| MPL-2.0 | OpenVDB |
| Zlib / libpng / MIT / BSL-1.0 / public domain | SDL2; libpng; Brotli, Expat, libxml2, OpenCOLLADA; Boost; SQLite |
| PSF-2.0 / Unicode-3.0 | CPython (`libcpython`); ICU (`libicuc`) |

Identical list in machine-readable form: `native/blender/blender-jniLibs/`.

### Licence texts

**Shipped with the engine.** Blender's canonical set is in
`assets/blender/licenses/blender/` — GPL-2.0, GPL-3.0, LGPL-2.1, Apache-2.0,
BSD-2-Clause, BSD-3-Clause, MIT, Zlib, the logo and trademark licence, and the
SPDX identifier list. Beside it, `assets/blender/licenses/deps/` carries 125
per-dependency texts mirroring their source packages: Boost, CPython, FFTW,
HarfBuzz, ICU, OpenAL, OpenBLAS, OpenCOLLADA, OpenImageIO, OpenPGL, OpenSubdiv,
OpenUSD, OpenVDB, libpng, PugiXML, SDL, TBB, TIFF and zstd.

The tracked copies are at [`native/blender/assets/licenses/`](native/blender/assets/licenses).
`app/src/main/assets/blender` is gitignored — it is delivery, not source — so
that directory is where they survive a clean clone.

**Still missing: FFmpeg.** It is the one bundled library with no licence text
anywhere in the source packages, and it is also the one whose obligations depend
on how it was configured: LGPL-2.1-or-later built without `--enable-gpl`,
GPL-2.0-or-later with it. **That configuration has not been checked**, and it
should be before a built engine is redistributed.

Libraries without a per-package text — OpenEXR and Imath, Alembic, Embree,
OpenColorIO, OpenImageDenoise, oneDNN, Draco, OpenSSL and the rest — are covered
by licence *type* in Blender's canonical set above, but a distributor should ship
the per-library text, not rely on the type.

The table above remains an inventory to work from rather than legal advice:
several entries are dual-licensed, and where so, the permissive option should be
taken and recorded.
