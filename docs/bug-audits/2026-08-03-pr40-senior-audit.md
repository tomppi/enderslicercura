# EnderSlicerCura PR #40 senior bug audit

Date: 2026-08-03  
Audit target: pull request #40, `fix/smart-infill-audit-round`  
Initial audited head: `0b8644fc2fabff74c800a9416c40f08b2a7fa69b`  
Follow-up code head: `de1568473bba69136218266b3852b3d311578a19`  
Base: `main` at `e18275f3a89af65ede691aa171ae2d00518cf1cb`

## Severity summary

| Severity | Confirmed | Fixed in this round |
|---|---:|---:|
| Critical | 0 | 0 |
| High | 3 | 3 |
| Medium | 3 | 3 |
| Low | 1 | 1 |

## High severity

### SI-01 — Smart Infill package publication was not serialized with slicing or plate operations

**Affected path**

- `IntegratedEnderSlicerApp.kt`
- `SmartInfillPackageStore.importPackage()`
- `SmartInfillRuntime`
- `MainViewModel.sliceModel()` and `clearBuildPlate()`

**Confirmed failure mode**

Importing and validating a filaSim modifier package ran in a Compose coroutine without setting `MainUiState.isBusy`. The bottom Slice action and Plate actions therefore remained usable while private package extraction, validation and activation were still running.

This allowed several incorrect interleavings:

1. A slice could snapshot no package or the previous package while the UI later reported the newly imported package as active.
2. Clear plate could complete while package import was still running, after which the import could publish a Smart Infill package for a plate that no longer had a model.
3. Validation of the previous package could overlap publication of the new active-package pointer.

**Fix**

- Added an explicit `smartInfillImporting` operation state.
- Displayed a non-dismissible modal progress dialog that blocks all underlying actions during package import and validation.
- Disabled Plate, Smart Infill and OctoPrint top-bar actions during the handoff.
- Temporarily detached the previous process-local Smart Infill runtime package before publication, preventing stale validation from clearing the new generation.
- Restored the previous runtime package on a normal import failure.
- Kept package publication, runtime activation and UI activation in one serialized flow.

**Status:** Fixed.

### BM-01 — BumpMesh exports could consume unbounded app cache and leave open partial files

**Affected path**

- `BumpMeshActivity.kt`
- `file_paths.xml`
- the app FileProvider declaration

**Confirmed failure mode**

A completed BumpMesh STL was returned through FileProvider but never deleted after `MainViewModel` materialized it. BumpMesh also did not retain its export bridge or cancel the active stream in `onDestroy()`. Canceling the activity or losing it during export could therefore leave an open partial file.

At the configurable 8,000,000-triangle limit, one binary STL can be approximately 400,000,084 bytes. Repeated texturing or interrupted exports could exhaust app storage and then break imports, slicing, logs and package staging.

**Fix**

- Added `OneShotExportFileProvider`.
- Temporary BumpMesh and filaSim handoffs are deleted when their granted read descriptor closes.
- BumpMesh now writes to a `.part` file and publishes it only after exact STL validation and an atomic rename.
- BumpMesh retains its bridge and closes/deletes an active partial export from `onDestroy()`.
- BumpMesh prunes stale, oversized and interrupted exports before opening and before starting a new export.

**Status:** Fixed.

### BLD-01 — One-shot export provider did not satisfy Kotlin's non-null descriptor contract

**Affected path**

- `OneShotExportFileProvider.kt`
- Android unit-test compilation and APK builds

**Confirmed failure mode**

The provider override returned `ParcelFileDescriptor`, but its two ordinary FileProvider fallback branches returned the nullable platform type from `super.openFile()`. GitHub Actions therefore failed Kotlin compilation before unit tests or APK packaging could run.

**Fix**

- Added one `openNormally()` fallback that converts an unexpected null descriptor into `FileNotFoundException`.
- Applied the same explicit non-null check to the one-shot `ParcelFileDescriptor.open()` result.
- Preserved the close-listener deletion behavior for valid temporary exports.

**Status:** Fixed.

## Medium severity

### SI-02 — Clear plate removed Smart Infill before model cleanup succeeded

**Affected path**

- `IntegratedEnderSlicerApp.clearBuildPlate()`
- `MainViewModel.clearBuildPlate()`

**Confirmed failure mode**

The PR initially cleared the active-package pointer and UI package first, launched `clearAll()` without awaiting it, and only then requested ViewModel plate cleanup.

Consequences:

- A filesystem failure could leave the old model on the plate after its Smart Infill package had already been removed.
- Delayed `clearAll()` could run after a later package import and delete the newer package.
- The independent cleanup coroutine could be canceled with the composition, leaving partially cleared state.

**Fix**

- Clear plate now waits for the ViewModel operation to finish.
- Smart Infill state is removed only after the model and durable workspace are confirmed cleared.
- Cleanup targets the package captured for that plate instead of deleting every package directory later from an uncoordinated coroutine.
- On clear failure, the model and Smart Infill package remain together.

**Status:** Fixed.

### SI-03 — “Remove Smart Infill” retained the active modifier package indefinitely

**Affected path**

- `SmartInfillSheet.onRemove`
- `files/smart-infill/packages/<package-id>`

**Confirmed failure mode**

The Remove action deleted only `active-package.txt` and the process-local reference. The active package directory, manifest and all modifier STLs remained in private storage until a later successful package import happened to prune it. A user who removed Smart Infill and did not generate another package could retain hundreds of megabytes indefinitely.

**Fix**

- Remove captures the exact active package generation.
- It clears the active pointer and runtime state immediately.
- It asynchronously deletes only that immutable package directory, so a later package cannot be removed by delayed cleanup.
- Successful Part Topo replacement and Smart Infill regeneration also delete the superseded package directory.

**Status:** Fixed.

### SI-04 — Smart Infill multi-touch navigation lacked a single coherent pan and zoom path

**Affected path**

- pinned filaSim `web/src/viewer/SceneManager.ts`
- `scripts/prepare-filasim-assets.py`
- `scripts/prepare-filasim-assets-with-pinch.py`
- Android WebView touch input

**Confirmed failure mode**

filaSim uses a custom pointer-driven surface-pivot orbit while retaining Three.js `OrbitControls` for panning. On Android, the first touch armed the custom orbit. Adding a second touch also switched OrbitControls into its two-touch pan state. Pointer movement could then be processed by both camera paths, producing camera jumps, rotation during pan, unstable direction or ineffective movement.

Pinch zoom was also absent. filaSim intentionally disables OrbitControls zoom because wheel zoom is implemented manually, but the earlier Android multi-touch repair only added centroid panning and did not provide an equivalent manual pinch path.

**Fix**

- Track touch pointers and the active custom-orbit pointer explicitly.
- Keep one-finger pivot orbit unchanged.
- When a second finger lands, terminate the custom orbit before any movement delta is applied.
- Give the complete multi-touch gesture to one deterministic implementation.
- Use movement of the finger centroid for screen-space panning.
- Use the ratio between the current and previous two-finger distance for orthographic pinch zoom.
- Keep the world point below the gesture centroid stationary while zoom changes, matching filaSim's cursor-centric wheel behavior.
- Clamp pinch zoom to the same `0.05`–`200` camera zoom range as wheel zoom.
- Hold the gesture in multi-touch mode through the two-finger-to-one-finger transition and restore ordinary controls only after every touch is released.
- Handle `pointercancel` and move the stored orbit pivot with camera pan and zoom so the next orbit starts from the correct translated location.

Three.js r180's pointer-release handler was checked directly: it removes tracked pointers before evaluating control state, so temporarily disabling controls during the manual gesture does not leave stale internal touch IDs.

**Status:** Fixed in generated Android filaSim source; on-device pan and pinch feel still requires validation.

## Low severity

### SI-05 — Embedded Save Project and Load Project controls conflicted with the Android-hosted workflow

**Affected path**

- pinned filaSim `web/src/ui/TopBar.tsx`
- Android-hosted filaSim top bar

**Confirmed failure mode**

The embedded web application exposed its browser project-file persistence controls even though EnderSlicerCura owns model loading, package handoff and durable app state. The controls added clutter to the narrow Android top bar and could lead users into a second project-loading workflow that does not match the app's active build plate.

**Fix**

- Detect the existing `?android=1` host flag.
- Hide the project file input plus Save Project and Load Project buttons only in the Android build.
- Leave upstream/browser filaSim behavior and the Settings button unchanged.

**Status:** Fixed.

## Connected paths double-checked

The following PR #40 changes were reviewed with their callers, storage paths and tests:

- immutable `SmartInfillSliceSnapshot` use across a complete synchronous Cura request;
- fallback Cura CLI per-mesh setting order after `-l`;
- imported-profile resolved JSON density-dependent model settings;
- filaSim line-width, wall, skin and infill assumption enforcement;
- local filaSim modifier translation back to analyzed printer coordinates;
- source SHA validation before modifier staging;
- Part Topo import completion before removing the previous model/package;
- Android-host sample-model suppression;
- filaSim WebView retention across fold and rotation changes;
- generated filaSim asset hashing and format-specific clean-source invalidation;
- Android-only project-control suppression without changing browser filaSim;
- Three.js r180 touch start, move and pointer-release behavior;
- orthographic cursor-centric zoom math and camera/target/pivot synchronization;
- warm and clean filaSim source patching through the format-7 wrapper;
- cloud-backup exclusion of persisted Smart Infill geometry.

## Validation evidence

The pre-audit PR head passed GitHub Actions run `30790289877`, including ARM64 CuraEngine packaging, unit tests, definition tests, real host-CuraEngine graded/binary regional toolpath tests and APK content verification.

Follow-up run `30811127094` successfully downloaded, patched and compiled the pinned filaSim Rust/WASM and TypeScript/Vite application with the camera and top-bar source edits. It then stopped at stale workflow assertions that still expected asset format 5 and the previous source-cache path. Those assertions were updated for format 7.

Run `30809813682` exposed BLD-01 during Kotlin test compilation. The provider contract was corrected on code head `4d4de8cd1b372c76b31c287e5d486e5422fa362f`.

Current-head workflow run `30815375411` is validating the pinch-zoom wrapper, generated filaSim TypeScript/Vite build, native CuraEngine builds, tests and APK packaging. A complete successful workflow is still required before PR #40 is considered build-validated.

## Required on-device regression checks

1. Generate Smart Infill and verify the modal import state prevents Slice, Clear plate and other top-bar actions until validation finishes.
2. Start Smart Infill import, rotate/fold/unfold, and verify the final package is either restored from private storage or the previous package remains valid after a failed import.
3. Clear the plate with an active package and verify reopening the app shows no model and no active Smart Infill.
4. Remove Smart Infill and verify its package directory is deleted while the model remains.
5. Export a large BumpMesh STL, confirm it imports, then verify the handoff file disappears after the importer closes it.
6. Cancel BumpMesh during export and verify no `.part` file remains.
7. Repeat BumpMesh export/import several times and verify cache usage does not grow with each successful handoff.
8. In Smart Infill, verify one-finger drag still orbits around the picked surface point.
9. Verify two-finger drag pans smoothly in all directions without rotation, jumping or reversal.
10. Pinch inward and outward while stationary and while panning; verify zoom follows finger spacing and the model point beneath the midpoint stays fixed.
11. Lift one finger, move the remaining finger, release it, then begin fresh orbit, pan and pinch gestures without a jump or stuck camera state.
12. Repeat the camera tests while a brush/select tool is active, after fold/unfold and after rotating the device.
13. Verify Save Project and Load Project are absent in the Android Smart Infill top bar while Settings and result-export actions remain available.
