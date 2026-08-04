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


def require_text(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise RuntimeError(f"Generated filaSim thermal integrity source lost {label}")


def verify_hardened_thermal_source(source_root: pathlib.Path) -> None:
    thermal = (source_root / "crates/filasim-core/src/thermal.rs").read_text(encoding="utf-8")
    wasm = (source_root / "crates/filasim-wasm/src/lib.rs").read_text(encoding="utf-8")
    protocol = (source_root / "web/src/engine/EngineProtocol.ts").read_text(encoding="utf-8")
    worker = (source_root / "web/src/worker/engine.worker.ts").read_text(encoding="utf-8")

    require_text(thermal, "fn exterior_void_mask(", "exterior-connected void classification")
    require_text(
        thermal,
        "sealed_cavity_does_not_receive_ambient_boundary_faces",
        "sealed-cavity regression",
    )
    require_text(
        thermal,
        "selected_face_uses_only_the_global_extreme_not_a_step_underside",
        "global-extreme boundary regression",
    )
    require_text(
        thermal,
        "cut_cell_uses_intrinsic_conductivity_and_reduced_face_area",
        "cut-cell conduction regression",
    )
    require_text(thermal, "if boundary.cooled {", "solved cooled-boundary selection")
    if "boundary.face == options.cooled_face" in thermal:
        raise RuntimeError(
            "Generated filaSim energy accounting still uses the unhardened cooled-face comparison"
        )

    eigen_start = wasm.find("filasim_core::thermal::thermal_eigen_forces(")
    eigen_end = wasm.find(").map_err(err)?", eigen_start)
    if eigen_start < 0 or eigen_end < 0:
        raise RuntimeError("Generated filaSim thermal eigenforce call is missing")
    eigen_call = wasm[eigen_start:eigen_end]
    require_text(eigen_call, "&temperature_eps", "occupancy-scaled thermal eigenforces")
    if "&material_stiffness" in eigen_call:
        raise RuntimeError("Thermal eigenforces were incorrectly decoupled from cut-cell occupancy")

    stress_start = wasm.find("filasim_core::thermal::thermal_von_mises(")
    stress_end = wasm.find(").map_err(err)?", stress_start)
    if stress_start < 0 or stress_end < 0:
        raise RuntimeError("Generated filaSim thermal material-stress call is missing")
    stress_call = wasm[stress_start:stress_end]
    require_text(stress_call, "&material_stiffness", "occupancy-decoupled material stress")
    if "&temperature_eps" in stress_call:
        raise RuntimeError("Thermal material stress still contains geometric cut-cell occupancy")

    require_text(wasm, "relative_density[ci]", "occupancy-decoupled strength allowable")
    require_text(protocol, "thermalIntegrity", "typed thermal worker protocol")
    require_text(worker, 'case "thermalIntegrity":', "thermal worker operation")


def patch_android_startup_with_hardened_thermal_integrity(app_file: pathlib.Path) -> None:
    _BASE_STARTUP(app_file)
    source_root = app_file.resolve().parents[2]
    HARDENING.apply(source_root)
    AUDIT_FIXES.apply(source_root)
    verify_hardened_thermal_source(source_root)


PINCH.BASE.patch_android_startup = patch_android_startup_with_hardened_thermal_integrity


if __name__ == "__main__":
    try:
        raise SystemExit(PINCH.BASE.main())
    except Exception as error:
        print(f"filaSim asset preparation failed: {error}", file=PINCH.BASE.sys.stderr)
        raise
