#!/usr/bin/env python3
"""Prepare pinned filaSim Android assets with app-specific source transforms."""

from __future__ import annotations

import importlib.util
import pathlib

BASE_SCRIPT = pathlib.Path(__file__).with_name("prepare-filasim-assets.py")
SPEC = importlib.util.spec_from_file_location("enderslicer_filasim_base", BASE_SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load base filaSim preparation script: {BASE_SCRIPT}")
BASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BASE)

# Format 9 hardens the regional-pattern metadata against stale/restored WebView
# state. A distinct source workspace prevents a previously patched tree from
# hiding the export-contract change.
BASE.ASSET_FORMAT = 9
_BASE_PATCH_ANDROID_EXPORT = BASE.patch_android_export
_BASE_PATCH_ANDROID_VIEWER = BASE.patch_android_viewer


def patch_android_export_with_pattern_contract(store_file: pathlib.Path) -> None:
    _BASE_PATCH_ANDROID_EXPORT(store_file)
    text = store_file.read_text(encoding="utf-8")
    marker = "EnderSlicer Android regional pattern metadata v2"
    if marker in text:
        return

    old = '''          pattern: state.optMode === "binary" ? state.solidPattern : state.pattern,
          mode: state.optMode,
'''
    if old not in text:
        raise RuntimeError("Unable to locate Android modifier pattern metadata for versioning")
    text = text.replace(
        old,
        '''          // EnderSlicer Android regional pattern metadata v2.
          metadataVersion: 2,
          // This pinned filaSim build has one calibrated sparse pattern. A
          // restored browser state may still contain null/retired values, so
          // export the actual supported solver contract instead of trusting it.
          basePattern: state.pattern === "cubic" ? state.pattern : "cubic",
          binarySolidPattern: state.optMode === "binary"
            ? state.solidPattern === "concentric" ? "concentric" : "rectilinear"
            : null,
          gradedFullDensityPattern: "rectilinear",
          mode: state.optMode,
''',
        1,
    )
    store_file.write_text(text, encoding="utf-8")


def patch_android_viewer_with_pinch(scene_file: pathlib.Path) -> None:
    _BASE_PATCH_ANDROID_VIEWER(scene_file)
    text = scene_file.read_text(encoding="utf-8")
    marker = "EnderSlicer Android deterministic touch pinch zoom"
    if marker in text:
        return

    fields = '''  // EnderSlicer Android deterministic touch pan: one finger keeps the custom
  // pivot orbit; two or more fingers use one manual screen-space pan path.
  private orbitPointerId: number | null = null;
  private touchPointers = new Map<number, { x: number; y: number }>();
  private touchPanLast: { x: number; y: number } | null = null;
'''
    if fields not in text:
        raise RuntimeError("Unable to locate Android touch fields for pinch-zoom patching")
    text = text.replace(
        fields,
        '''  // EnderSlicer Android deterministic touch pan: one finger keeps the custom
  // pivot orbit; two or more fingers use one manual screen-space pan path.
  // EnderSlicer Android deterministic touch pinch zoom keeps the world point
  // below the gesture centroid stationary while finger spacing changes.
  private orbitPointerId: number | null = null;
  private touchPointers = new Map<number, { x: number; y: number }>();
  private touchPanLast: { x: number; y: number } | null = null;
  private touchPinchDistance: number | null = null;
''',
        1,
    )

    move = '''      if (this.touchPanLast && this.touchPointers.size > 1) {
        const next = this.touchCentroid();
        this.panTouchCamera(next.x - this.touchPanLast.x, next.y - this.touchPanLast.y);
        this.touchPanLast = next;
        return;
      }
'''
    if move not in text:
        raise RuntimeError("Unable to locate Android multi-touch move path for pinch-zoom patching")
    text = text.replace(
        move,
        '''      if (this.touchPanLast && this.touchPointers.size > 1) {
        const next = this.touchCentroid();
        const nextDistance = this.touchDistance();
        const previousDistance = this.touchPinchDistance;
        this.panTouchCamera(next.x - this.touchPanLast.x, next.y - this.touchPanLast.y);
        if (previousDistance !== null && previousDistance > 0 && nextDistance > 0) {
          this.zoomTouchCamera(nextDistance / previousDistance, next.x, next.y);
        }
        this.touchPanLast = next;
        this.touchPinchDistance = nextDistance;
        return;
      }
''',
        1,
    )

    down = '''        this.controls.enabled = false;
        this.touchPanLast = this.touchCentroid();
        return;
'''
    if down not in text:
        raise RuntimeError("Unable to locate Android multi-touch start path for pinch-zoom patching")
    text = text.replace(
        down,
        '''        this.controls.enabled = false;
        this.touchPanLast = this.touchCentroid();
        this.touchPinchDistance = this.touchDistance();
        return;
''',
        1,
    )

    release = '''        this.touchPanLast = this.touchPointers.size > 1 ? this.touchCentroid() : null;
        if (this.touchPointers.size === 0) this.controls.enabled = true;
        return;
'''
    if release not in text:
        raise RuntimeError("Unable to locate Android multi-touch release path for pinch-zoom patching")
    text = text.replace(
        release,
        '''        if (this.touchPointers.size > 1) {
          this.touchPanLast = this.touchCentroid();
          this.touchPinchDistance = this.touchDistance();
        } else {
          this.touchPanLast = null;
          this.touchPinchDistance = null;
        }
        if (this.touchPointers.size === 0) this.controls.enabled = true;
        return;
''',
        1,
    )

    helper_anchor = '''  private panTouchCamera(dx: number, dy: number) {
'''
    if helper_anchor not in text:
        raise RuntimeError("Unable to locate Android touch-pan helper for pinch-zoom patching")
    text = text.replace(
        helper_anchor,
        '''  private touchDistance(): number {
    const points = Array.from(this.touchPointers.values());
    if (points.length < 2) return 0;
    return Math.hypot(points[1].x - points[0].x, points[1].y - points[0].y);
  }

  private zoomTouchCamera(scale: number, clientX: number, clientY: number) {
    if (!Number.isFinite(scale) || scale <= 0 || scale === 1) return;
    const rect = this.renderer.domElement.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return;
    const ndcX = ((clientX - rect.left) / rect.width) * 2 - 1;
    const ndcY = -((clientY - rect.top) / rect.height) * 2 + 1;
    this._oTmp.set(ndcX, ndcY, 0).unproject(this.camera);
    const nextZoom = Math.max(0.05, Math.min(200, this.camera.zoom * scale));
    if (nextZoom === this.camera.zoom) return;
    this.camera.zoom = nextZoom;
    this.camera.updateProjectionMatrix();
    this._oTmp2.set(ndcX, ndcY, 0).unproject(this.camera);
    this._oTmp.sub(this._oTmp2);
    this.camera.position.add(this._oTmp);
    this.controls.target.add(this._oTmp);
    this.lastOrbitPivot?.add(this._oTmp);
    this.controls.update();
  }

  private panTouchCamera(dx: number, dy: number) {
''',
        1,
    )

    scene_file.write_text(text, encoding="utf-8")


BASE.patch_android_export = patch_android_export_with_pattern_contract
BASE.patch_android_viewer = patch_android_viewer_with_pinch


if __name__ == "__main__":
    try:
        raise SystemExit(BASE.main())
    except Exception as error:
        print(f"filaSim asset preparation failed: {error}", file=BASE.sys.stderr)
        raise
