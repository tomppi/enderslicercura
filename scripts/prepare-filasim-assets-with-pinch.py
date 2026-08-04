#!/usr/bin/env python3
"""Prepare the validated format-8 filaSim runtime with Thermal Integrity."""

from __future__ import annotations

import pathlib
import runpy

THERMAL_PREPARER = pathlib.Path(__file__).with_name(
    "prepare-filasim-assets-with-thermal-integrity-v8.py"
)

if not THERMAL_PREPARER.is_file():
    raise RuntimeError(f"Thermal filaSim preparer is missing: {THERMAL_PREPARER}")

runpy.run_path(str(THERMAL_PREPARER), run_name="__main__")
