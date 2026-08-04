#!/usr/bin/env python3
"""Final physical Thermal Integrity preparer with report-schema compatibility."""
from __future__ import annotations
import importlib.util
import pathlib
import sys

V11 = pathlib.Path(__file__).with_name("prepare-filasim-assets-with-thermal-integrity-v11.py")
CONTRACT_FIX = pathlib.Path(__file__).with_name(
    "filasim-thermal-integrity-physical-contract-fix.py"
)
for path in (V11, CONTRACT_FIX):
    if not path.is_file():
        raise RuntimeError(f"Thermal Integrity v12 component is missing: {path}")

spec = importlib.util.spec_from_file_location("enderslicer_thermal_v11", V11)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Unable to load {V11}")
v11 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v11)
thermal = v11.thermal

marker = ".enderslicer-thermal-integrity-physical-contract-fix-v1"
if CONTRACT_FIX not in thermal.THERMAL_TRANSFORMS:
    thermal.THERMAL_TRANSFORMS = (*thermal.THERMAL_TRANSFORMS, CONTRACT_FIX)
if marker not in thermal.THERMAL_MARKERS:
    thermal.THERMAL_MARKERS = (*thermal.THERMAL_MARKERS, marker)

_base_ui = thermal.patch_thermal_ui_runtime


def patch_ui_v12(target: pathlib.Path) -> None:
    _base_ui(target)
    text = target.read_text(encoding="utf-8")
    # Keep the persistent Android report schema compatible in this feature
    # round. The package transform marker records the physical-model revision.
    physical = 'solverModel: "voxel-finite-volume-contact-heater-thermomechanical-v2",'
    compatible = 'solverModel: "voxel-finite-volume-implicit-thermomechanical",'
    if physical in text:
        text = text.replace(physical, compatible, 1)
    elif compatible not in text:
        raise RuntimeError("Thermal report solver-model contract is missing")
    target.write_text(text, encoding="utf-8")


thermal.patch_thermal_ui_runtime = patch_ui_v12
thermal.THERMAL_PACKAGE_MARKER_TEXT = (
    "format=1\n"
    f"filasim={thermal.BASE.FILASIM_COMMIT}\n"
    "transforms=solver,hardening,audit-fixes,progress-v2,react-tab-v1,"
    "bugfix-round1,bugfix-round2,linear-fast-path-v1,physical-model-v1,"
    "physical-contract-fix-v1\n"
)

if __name__ == "__main__":
    try:
        raise SystemExit(thermal.BASE.main())
    except Exception as error:
        print(f"thermal filaSim v12 asset preparation failed: {error}", file=sys.stderr)
        raise
