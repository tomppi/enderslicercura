#!/usr/bin/env python3
"""Final filaSim asset entry point including thermal-integrity hardening."""

from __future__ import annotations

import importlib.util
import pathlib


SCRIPT_DIRECTORY = pathlib.Path(__file__).resolve().parent


def load_module(name: str, path: pathlib.Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Unable to load {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


PINCH = load_module(
    "enderslicer_filasim_pinch_thermal",
    SCRIPT_DIRECTORY / "prepare-filasim-assets-with-pinch.py",
)
HARDENING = load_module(
    "enderslicer_filasim_thermal_hardening",
    SCRIPT_DIRECTORY / "filasim-thermal-integrity-hardening.py",
)
AUDIT_FIXES = load_module(
    "enderslicer_filasim_thermal_audit_fixes",
    SCRIPT_DIRECTORY / "filasim-thermal-integrity-audit-fixes.py",
)

_BASE_STARTUP = PINCH.BASE.patch_android_startup


def patch_android_startup_with_hardened_thermal_integrity(app_file: pathlib.Path) -> None:
    _BASE_STARTUP(app_file)
    source_root = app_file.resolve().parents[2]
    HARDENING.apply(source_root)
    AUDIT_FIXES.apply(source_root)


PINCH.BASE.patch_android_startup = patch_android_startup_with_hardened_thermal_integrity


if __name__ == "__main__":
    try:
        raise SystemExit(PINCH.BASE.main())
    except Exception as error:
        print(f"filaSim asset preparation failed: {error}", file=PINCH.BASE.sys.stderr)
        raise
