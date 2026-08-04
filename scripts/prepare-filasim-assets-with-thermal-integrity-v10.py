#!/usr/bin/env python3
"""Extend the Thermal Integrity v9 preparer with the linear fast path."""

from __future__ import annotations

import importlib.util
import pathlib
import sys


V9_PREPARER = pathlib.Path(__file__).with_name(
    "prepare-filasim-assets-with-thermal-integrity-v9.py"
)
LINEAR_FAST_PATH = pathlib.Path(__file__).with_name(
    "filasim-thermal-integrity-linear-fast-path.py"
)

for required in (V9_PREPARER, LINEAR_FAST_PATH):
    if not required.is_file():
        raise RuntimeError(f"Thermal Integrity v10 component is missing: {required}")

spec = importlib.util.spec_from_file_location("enderslicer_thermal_v9", V9_PREPARER)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Unable to load Thermal Integrity v9 preparer: {V9_PREPARER}")
v9 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v9)

marker = ".enderslicer-thermal-integrity-linear-fast-path-v1"
if LINEAR_FAST_PATH not in v9.thermal.THERMAL_TRANSFORMS:
    v9.thermal.THERMAL_TRANSFORMS = (
        *v9.thermal.THERMAL_TRANSFORMS,
        LINEAR_FAST_PATH,
    )
if marker not in v9.thermal.THERMAL_MARKERS:
    v9.thermal.THERMAL_MARKERS = (*v9.thermal.THERMAL_MARKERS, marker)

v9.thermal.THERMAL_PACKAGE_MARKER_TEXT = (
    "format=1\n"
    f"filasim={v9.thermal.BASE.FILASIM_COMMIT}\n"
    "transforms=solver,hardening,audit-fixes,progress-v2,react-tab-v1,bugfix-round1,bugfix-round2,linear-fast-path-v1\n"
)

if __name__ == "__main__":
    try:
        raise SystemExit(v9.thermal.BASE.main())
    except Exception as error:
        print(f"thermal filaSim v10 asset preparation failed: {error}", file=sys.stderr)
        raise
