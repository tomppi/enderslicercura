#!/usr/bin/env python3
"""Extend the validated format-8 Thermal Integrity preparer with bugfix round 2."""

from __future__ import annotations

import importlib.util
import pathlib
import sys


BASE_PREPARER = pathlib.Path(__file__).with_name(
    "prepare-filasim-assets-with-thermal-integrity-v8.py"
)
ROUND2 = pathlib.Path(__file__).with_name("filasim-thermal-integrity-bugfix-round2.py")

if not BASE_PREPARER.is_file() or not ROUND2.is_file():
    raise RuntimeError("Thermal Integrity v9 preparation components are missing")

spec = importlib.util.spec_from_file_location("enderslicer_thermal_v8", BASE_PREPARER)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Unable to load Thermal Integrity preparer: {BASE_PREPARER}")
thermal = importlib.util.module_from_spec(spec)
spec.loader.exec_module(thermal)

round2_marker = ".enderslicer-thermal-integrity-bugfix-round2"
if ROUND2 not in thermal.THERMAL_TRANSFORMS:
    thermal.THERMAL_TRANSFORMS = (*thermal.THERMAL_TRANSFORMS, ROUND2)
if round2_marker not in thermal.THERMAL_MARKERS:
    thermal.THERMAL_MARKERS = (*thermal.THERMAL_MARKERS, round2_marker)

thermal.THERMAL_PACKAGE_MARKER_TEXT = (
    "format=1\n"
    f"filasim={thermal.BASE.FILASIM_COMMIT}\n"
    "transforms=solver,hardening,audit-fixes,progress-v2,react-tab-v1,bugfix-round1,bugfix-round2\n"
)

if __name__ == "__main__":
    try:
        raise SystemExit(thermal.BASE.main())
    except Exception as error:
        print(f"thermal filaSim v9 asset preparation failed: {error}", file=sys.stderr)
        raise
