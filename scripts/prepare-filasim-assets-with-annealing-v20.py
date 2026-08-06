#!/usr/bin/env python3
"""Zoned engine bay plus movable printed-object world placement."""
from __future__ import annotations

import importlib.util
import pathlib
import subprocess
import sys

V19 = pathlib.Path(__file__).with_name("prepare-filasim-assets-with-annealing-v19.py")
PLACEMENT_TRANSFORM = pathlib.Path(__file__).with_name(
    "filasim-engine-bay-part-placement-viewer.py"
)
PLACEMENT_TEST = pathlib.Path(__file__).with_name("test-engine-bay-part-placement.mjs")
PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[1]
PLACEMENT_RUNTIME = (
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-02p-engine-bay-part-placement.js"
)
for path in (V19, PLACEMENT_TRANSFORM, PLACEMENT_TEST, PLACEMENT_RUNTIME):
    if not path.is_file():
        raise RuntimeError(f"Engine-bay part-placement component is missing: {path}")

spec = importlib.util.spec_from_file_location("enderslicer_annealing_v19", V19)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Unable to load {V19}")
v19 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v19)
thermal = v19.thermal

if PLACEMENT_TRANSFORM not in thermal.THERMAL_TRANSFORMS:
    thermal.THERMAL_TRANSFORMS = (*thermal.THERMAL_TRANSFORMS, PLACEMENT_TRANSFORM)
marker = ".enderslicer-engine-bay-part-placement-viewer-v1"
if marker not in thermal.THERMAL_MARKERS:
    thermal.THERMAL_MARKERS = (*thermal.THERMAL_MARKERS, marker)

_base_ui = thermal.patch_thermal_ui_runtime


def patch_part_placement_runtime(target: pathlib.Path) -> None:
    _base_ui(target)
    subprocess.run(
        [
            "node",
            str(PLACEMENT_TEST),
            str(PLACEMENT_RUNTIME),
            str(PLACEMENT_TRANSFORM),
        ],
        check=True,
    )
    text = target.read_text(encoding="utf-8")
    runtime = PLACEMENT_RUNTIME.read_text(encoding="utf-8")
    anchor = "  // Preserve installUi callback name for MutationObserver guard."
    if runtime not in text:
        if text.count(anchor) != 1:
            raise RuntimeError("Expected one observer-safe installer anchor in Nearby Hot Object runtime")
        target.write_text(text.replace(anchor, runtime + anchor, 1), encoding="utf-8")
    subprocess.run(["node", "--check", str(target)], check=True)
    verified = target.read_text(encoding="utf-8")
    for contract in (
        "Move plastic object X/Y",
        "Move plastic object Z",
        "partPlacementXMm",
        "fixed-voxel-grid-inverse-world-transform-v1",
        "complete-object-bounding-box-inside-closed-envelope-v1",
        "thermalPointFromViewer",
        "solverEnvelopeOffsets",
        "EnderSlicerPartPlacementTestApi",
        "installUi = function installUi()",
    ):
        if contract not in verified:
            raise RuntimeError(f"Engine-bay part-placement runtime is missing {contract!r}")


thermal.patch_thermal_ui_runtime = patch_part_placement_runtime
thermal.THERMAL_PACKAGE_MARKER_TEXT = v19.thermal.THERMAL_PACKAGE_MARKER_TEXT.replace(
    "engine-bay-zoned-result-compat-v1\n",
    "engine-bay-zoned-result-compat-v1,"
    "engine-bay-part-placement-viewer-v1,"
    "engine-bay-part-placement-runtime-v1\n",
)

if __name__ == "__main__":
    try:
        raise SystemExit(thermal.BASE.main())
    except Exception as error:
        print(f"engine-bay part-placement filaSim v20 asset preparation failed: {error}", file=sys.stderr)
        raise
