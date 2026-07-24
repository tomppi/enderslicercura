# Third-party notices

## BumpMesh / stlTexturizer

- Project: `CNCKitchen/stlTexturizer`
- Pinned source commit: `a6ac179149b8a17c71a9469dd4cb6f866c0c01d1`
- License: GNU Affero General Public License v3.0 only (`AGPL-3.0-only`)
- Copyright: CNC Kitchen (Stefan Hermann) and contributors

The Android build downloads the pinned source archive, retains its license file in the packaged workspace, replaces network module imports with local copies, and adds a small Android host bridge. The original project source remains available from its upstream GitHub repository.

## Three.js

- Version: r170 / 0.170.0
- License: MIT
- Copyright: Three.js authors

The build packages `three.module.js` and the `examples/jsm` modules required by BumpMesh. The upstream license is retained at `assets/bumpmesh/vendor/three/LICENSE` inside the APK.

## fflate

- Version: 0.8.2
- License: MIT
- Copyright: 101arrowz

The official npm package supplies the browser ESM build used by BumpMesh. Its package metadata, README and license are packaged beside the module.

## meshStep

- Version: 0.1.0
- License: GNU Affero General Public License v3.0 only (`AGPL-3.0-only`)
- Copyright: CNC Kitchen and contributors

The official npm package supplies the browser module used for BumpMesh STEP import. Its complete published TypeScript source, generated distribution, package metadata, README and AGPL license are packaged under `assets/bumpmesh/vendor/meshstep/`.

EnderSlicerCura's integrated workflow supplies an STL, but meshStep is localized so the embedded BumpMesh workspace does not require a CDN if STEP import is used.

## CuraEngine and Cura resources

CuraEngine is developed by UltiMaker and contributors and is licensed under GNU AGPL-3.0-or-later. The repository pins CuraEngine and matching Cura resources to `5.11.0-beta.1`.

UltiMaker and Cura are trademarks of their respective owners.

## Android Open Source Project and AndroidX

The application uses Android platform APIs and AndroidX libraries under their respective licenses.
