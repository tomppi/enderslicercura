# Smart Infill / filaSim integration

EnderSlicerCura packages the pinned filaSim structural-analysis workspace and transports its optimized density regions into CuraEngine as infill modifier meshes.

## Workflow

1. Import and position one STL in EnderSlicerCura.
2. Open **Smart Infill**.
3. Define supports, loads and material/print properties in filaSim.
4. Check the setup, solve the reference part and run an optimization.
5. Export modifier STLs from filaSim. The Android bridge imports them directly rather than downloading a user-visible ZIP.
6. Slice normally. EnderSlicerCura applies the filaSim base density and print assumptions, then loads the regional meshes into CuraEngine with ordered `infill_mesh` and `infill_sparse_density` values.

The optimized package is bound to the SHA-256 digest of the exact transformed binary STL supplied to filaSim. Importing another model, texturing it, or changing its move/rotation/lay-flat transform invalidates the package.

## Slicing contract

While a package is active, the slice uses filaSim's:

- base infill density and pattern;
- layer height and line width;
- perimeter count;
- top and bottom shell count;
- ordered modifier densities.

Adaptive layers are disabled because a different layer-height field would no longer match the analyzed shell thickness. Modifier geometry is already in the displayed model's final printer coordinates and therefore receives only Cura's bed-origin offset, not the source object's affine transform a second time.

## Validation boundaries

The Android host rejects:

- malformed or oversized ZIP archives;
- nested paths or unexpected entries;
- duplicate, non-increasing or out-of-range densities;
- modifier densities at or below the base density;
- structurally invalid binary STL files;
- meshes above the configured triangle limit;
- packages that no longer match the displayed model fingerprint;
- unknown infill patterns instead of silently substituting another pattern.

CuraEngine and final G-code retain the app's existing build-volume, extrusion-temperature, artifact-lifecycle and export validation.

## Limitations

- One printable model and one extruder.
- The Android WebView currently uses filaSim's single-threaded WASM build.
- Cura modifier transport requires physical validation across nested regions, thin walls, supports, adaptive geometry and different infill patterns.
- filaSim results depend on accurate loads, constraints, material properties, layer adhesion and print orientation. This is an engineering aid, not a certified structural calculation.

## Source and license

The workspace is built from `CNCKitchen/smartInfillGenerator` commit `e7485ec22d4ebe8baca04190404fbb877c90e031` under `AGPL-3.0-only`. The build script, Android bridge, license and source notice are included in this repository or packaged workspace. See [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md).
