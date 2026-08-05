#!/usr/bin/env python3
"""Thermal Integrity v12 plus geometry-aware oven annealing support."""
from __future__ import annotations

import importlib.util
import pathlib
import subprocess
import sys

V12 = pathlib.Path(__file__).with_name("prepare-filasim-assets-with-thermal-integrity-v12.py")
ANNEALING_TRANSFORM = pathlib.Path(__file__).with_name("filasim-annealing-cycle.py")
PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[1]
ANNEALING_UI_PARTS = tuple(
    PROJECT_ROOT / f"app/src/main/filasim/annealing-calculator-0{index}-{name}.js"
    for index, name in ((1, "core"), (2, "ui"), (3, "cycle"), (4, "report"))
)
for path in (V12, ANNEALING_TRANSFORM, *ANNEALING_UI_PARTS):
    if not path.is_file():
        raise RuntimeError(f"Annealing v13 component is missing: {path}")

spec = importlib.util.spec_from_file_location("enderslicer_thermal_v12", V12)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Unable to load {V12}")
v12 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v12)
thermal = v12.thermal

marker = ".enderslicer-annealing-cycle-v1"
if ANNEALING_TRANSFORM not in thermal.THERMAL_TRANSFORMS:
    thermal.THERMAL_TRANSFORMS = (*thermal.THERMAL_TRANSFORMS, ANNEALING_TRANSFORM)
if marker not in thermal.THERMAL_MARKERS:
    thermal.THERMAL_MARKERS = (*thermal.THERMAL_MARKERS, marker)

ANNEALING_UI_NAME = "annealing-calculator.js"
ANNEALING_UI_TAG = f'<script src="./{ANNEALING_UI_NAME}"></script>'
_base_inject = thermal.BASE.inject_bridge


def build_annealing_runtime(target: pathlib.Path) -> None:
    source = "\n".join(path.read_text(encoding="utf-8").rstrip() for path in ANNEALING_UI_PARTS) + "\n"
    target.write_text(source, encoding="utf-8")
    subprocess.run(["node", "--check", str(target)], check=True)
    verified = target.read_text(encoding="utf-8")
    for contract in (
        "coldest material voxel",
        "readinessTemperatureC",
        "Calculate Complete Oven Cycle",
        "Spool-specific dimensional calibration",
        "thermalOnly: true",
    ):
        if contract not in verified:
            raise RuntimeError(f"Generated annealing runtime is missing {contract!r}")


def inject_annealing_runtime(index_file: pathlib.Path) -> None:
    _base_inject(index_file)
    target = index_file.with_name(ANNEALING_UI_NAME)
    build_annealing_runtime(target)

    text = index_file.read_text(encoding="utf-8")
    text = text.replace(f"  {ANNEALING_UI_TAG}\n", "").replace(ANNEALING_UI_TAG, "")
    if thermal.THERMAL_UI_TAG in text:
        text = text.replace(
            thermal.THERMAL_UI_TAG,
            f"{thermal.THERMAL_UI_TAG}\n  {ANNEALING_UI_TAG}",
            1,
        )
    elif "</body>" in text:
        text = text.replace("</body>", f"  {ANNEALING_UI_TAG}\n</body>", 1)
    else:
        raise RuntimeError("Unable to inject annealing runtime into index.html")
    index_file.write_text(text, encoding="utf-8")

    verified = index_file.read_text(encoding="utf-8")
    if verified.count(ANNEALING_UI_TAG) != 1:
        raise RuntimeError("Annealing runtime tag was not retained exactly once")
    if verified.index(ANNEALING_UI_TAG) <= verified.index(thermal.THERMAL_UI_TAG):
        raise RuntimeError("Annealing runtime must load after Thermal Integrity runtime")


thermal.BASE.inject_bridge = inject_annealing_runtime
# The v12 physical contract-fix transform only corrects an obsolete test
# expectation. It does not change the packaged runtime identity, so the marker
# remains physical-model-v1 plus this annealing runtime revision.
thermal.THERMAL_PACKAGE_MARKER_TEXT = (
    "format=1\n"
    f"filasim={thermal.BASE.FILASIM_COMMIT}\n"
    "transforms=solver,hardening,audit-fixes,progress-v2,react-tab-v1,"
    "bugfix-round1,bugfix-round2,linear-fast-path-v1,physical-model-v1,annealing-v1\n"
)

if __name__ == "__main__":
    try:
        raise SystemExit(thermal.BASE.main())
    except Exception as error:
        print(f"annealing filaSim v13 asset preparation failed: {error}", file=sys.stderr)
        raise
