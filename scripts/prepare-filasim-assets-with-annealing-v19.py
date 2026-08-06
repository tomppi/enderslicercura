#!/usr/bin/env python3
"""Shaped engine bay plus 12-zone high-accuracy thermal environment."""
from __future__ import annotations

import importlib.util
import pathlib
import subprocess
import sys

V18 = pathlib.Path(__file__).with_name("prepare-filasim-assets-with-annealing-v18.py")
ZONED_TRANSFORMS = (
    pathlib.Path(__file__).with_name("filasim-engine-bay-zoned-environment-core.py"),
    pathlib.Path(__file__).with_name("filasim-engine-bay-zoned-environment-api.py"),
)
ZONED_TEST = pathlib.Path(__file__).with_name("test-engine-bay-zoned-environment.mjs")
PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[1]
ZONED_RUNTIME = PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-02m-engine-bay-zoned-environment.js"
for path in (V18, *ZONED_TRANSFORMS, ZONED_TEST, ZONED_RUNTIME):
    if not path.is_file():
        raise RuntimeError(f"Zoned engine-bay filaSim component is missing: {path}")

spec = importlib.util.spec_from_file_location("enderslicer_annealing_v18", V18)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Unable to load {V18}")
v18 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v18)
thermal = v18.thermal

for transform in ZONED_TRANSFORMS:
    if transform not in thermal.THERMAL_TRANSFORMS:
        thermal.THERMAL_TRANSFORMS = (*thermal.THERMAL_TRANSFORMS, transform)
for marker in (
    ".enderslicer-engine-bay-zoned-environment-core-v1",
    ".enderslicer-engine-bay-zoned-environment-api-v1",
):
    if marker not in thermal.THERMAL_MARKERS:
        thermal.THERMAL_MARKERS = (*thermal.THERMAL_MARKERS, marker)

_base_ui = thermal.patch_thermal_ui_runtime


def patch_zoned_environment_runtime(target: pathlib.Path) -> None:
    _base_ui(target)
    subprocess.run(
        [
            "node",
            str(ZONED_TEST),
            str(ZONED_RUNTIME),
            *(str(path) for path in ZONED_TRANSFORMS),
        ],
        check=True,
    )
    text = target.read_text(encoding="utf-8")
    runtime = ZONED_RUNTIME.read_text(encoding="utf-8")
    anchor = "  // Preserve installUi callback name for MutationObserver guard."
    if runtime not in text:
        if text.count(anchor) != 1:
            raise RuntimeError("Expected one observer-safe installer anchor in Nearby Hot Object runtime")
        target.write_text(text.replace(anchor, runtime + anchor, 1), encoding="utf-8")
    subprocess.run(["node", "--check", str(target)], check=True)
    verified = target.read_text(encoding="utf-8")
    for contract in (
        "ENGINE_BAY_ZONE_COUNT = 12",
        "3x2x2-local-air-wall-network-v1",
        "ENGINE_BAY_CORRECTION_PASSES = 2",
        "High — 12 local zones, two correction passes",
        "runTransientZonedEngineBay",
        "runSteadyZonedEngineBay",
        "spatialEnvironmentEnabled",
        "EnderSlicerZonedEnvironmentTestApi",
        "installUi = function installUi()",
    ):
        if contract not in verified:
            raise RuntimeError(f"Zoned environment runtime is missing {contract!r}")


thermal.patch_thermal_ui_runtime = patch_zoned_environment_runtime
thermal.THERMAL_PACKAGE_MARKER_TEXT = v18.thermal.THERMAL_PACKAGE_MARKER_TEXT.replace(
    "engine-bay-shaped-envelope-runtime-v1\n",
    "engine-bay-shaped-envelope-runtime-v1,"
    "engine-bay-zoned-environment-core-v1,"
    "engine-bay-zoned-environment-api-v1,"
    "engine-bay-zoned-environment-runtime-v1\n",
)

if __name__ == "__main__":
    try:
        raise SystemExit(thermal.BASE.main())
    except Exception as error:
        print(f"zoned engine-bay filaSim v19 asset preparation failed: {error}", file=sys.stderr)
        raise
