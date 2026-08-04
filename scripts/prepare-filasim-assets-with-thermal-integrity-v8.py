#!/usr/bin/env python3
"""Prepare format-8 filaSim Android assets with thermal-integrity support.

This wrapper deliberately composes the already-validated Android/pinch asset
preparer instead of replacing its cache and packaging contract. The thermal
solver transforms are applied to the same pinned source tree immediately before
the existing Android source transforms and production build.
"""

from __future__ import annotations

import importlib.util
import pathlib
import shutil
import subprocess
import sys

PINCH_SCRIPT = pathlib.Path(__file__).with_name("prepare-filasim-assets-with-pinch-v8-base.py")
SPEC = importlib.util.spec_from_file_location("enderslicer_filasim_pinch", PINCH_SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load filaSim Android preparer: {PINCH_SCRIPT}")
PINCH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PINCH)

BASE = PINCH.BASE
PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[1]
THERMAL_TRANSFORMS = (
    pathlib.Path(__file__).with_name("filasim-thermal-integrity-patch.py"),
    pathlib.Path(__file__).with_name("filasim-thermal-integrity-hardening.py"),
    pathlib.Path(__file__).with_name("filasim-thermal-integrity-audit-fixes.py"),
    pathlib.Path(__file__).with_name("filasim-thermal-integrity-progress.py"),
)
THERMAL_MARKERS = (
    ".enderslicer-thermal-integrity",
    ".enderslicer-thermal-integrity-hardening",
    ".enderslicer-thermal-integrity-audit-fixes",
    ".enderslicer-thermal-integrity-progress",
)
THERMAL_UI_SOURCE = PROJECT_ROOT / "app/src/main/filasim/thermal-integrity.js"
THERMAL_UI_NAME = "thermal-integrity.js"
THERMAL_WORKSPACE_SOURCE = PROJECT_ROOT / "app/src/main/filasim/thermal-integrity-workspace.js"
THERMAL_WORKSPACE_NAME = "thermal-integrity-workspace.js"
THERMAL_UI_TAG = f'<script src="./{THERMAL_UI_NAME}"></script>'
THERMAL_WORKSPACE_TAG = f'<script src="./{THERMAL_WORKSPACE_NAME}"></script>'
THERMAL_RUNTIME_TAGS = f"{THERMAL_WORKSPACE_TAG}\n  {THERMAL_UI_TAG}"
THERMAL_PACKAGE_MARKER = "thermal-integrity-version.txt"
THERMAL_PACKAGE_MARKER_TEXT = (
    "format=1\n"
    f"filasim={BASE.FILASIM_COMMIT}\n"
    "transforms=solver,hardening,audit-fixes,progress\n"
)

_BASE_PATCH_ANDROID_EXPORT = BASE.patch_android_export
_BASE_INJECT_BRIDGE = BASE.inject_bridge


def apply_thermal_transforms(source_root: pathlib.Path) -> None:
    source_root = source_root.resolve()
    marker_paths = tuple(source_root / name for name in THERMAL_MARKERS)
    marker_state = tuple(path.is_file() for path in marker_paths)

    legacy_complete = marker_state == (True, True, True, False)
    if any(marker_state) and not all(marker_state) and not legacy_complete:
        missing = [path.name for path, present in zip(marker_paths, marker_state) if not present]
        raise RuntimeError(
            "Thermal-integrity source is only partially transformed; missing markers: "
            + ", ".join(missing)
        )

    if legacy_complete:
        transform = THERMAL_TRANSFORMS[-1]
        if not transform.is_file():
            raise RuntimeError(f"Thermal-integrity transform is missing: {transform}")
        subprocess.run(
            [sys.executable, str(transform), str(source_root)],
            cwd=PROJECT_ROOT,
            check=True,
        )
    elif not all(marker_state):
        for transform in THERMAL_TRANSFORMS:
            if not transform.is_file():
                raise RuntimeError(f"Thermal-integrity transform is missing: {transform}")
            subprocess.run(
                [sys.executable, str(transform), str(source_root)],
                cwd=PROJECT_ROOT,
                check=True,
            )

    missing = [path.name for path in marker_paths if not path.is_file()]
    if missing:
        raise RuntimeError(
            "Thermal-integrity source markers are missing after transformation: "
            + ", ".join(missing)
        )

    core_module = source_root / "crates/filasim-core/src/thermal.rs"
    wasm_entry = source_root / "crates/filasim-wasm/src/lib.rs"
    worker_entry = source_root / "web/src/worker/engine.worker.ts"
    protocol_entry = source_root / "web/src/engine/EngineProtocol.ts"
    required_contracts = (
        (core_module, "solve_thermal"),
        (wasm_entry, "solve_thermal_integrity"),
        (wasm_entry, "Preparing voxel model"),
        (worker_entry, "thermalIntegrity"),
        (worker_entry, "progress: true"),
        (protocol_entry, "thermalIntegrity"),
    )
    for path, marker in required_contracts:
        if not path.is_file() or marker not in path.read_text(encoding="utf-8"):
            raise RuntimeError(f"Thermal-integrity contract {marker!r} is missing from {path}")


def patch_android_export_with_thermal_integrity(store_file: pathlib.Path) -> None:
    # store.ts lives at <source>/web/src/store.ts.
    apply_thermal_transforms(store_file.parents[2])
    _BASE_PATCH_ANDROID_EXPORT(store_file)


def copy_verified(source: pathlib.Path, target: pathlib.Path, label: str) -> None:
    if not source.is_file():
        raise RuntimeError(f"{label} is missing: {source}")
    shutil.copy2(source, target)
    if target.read_bytes() != source.read_bytes():
        raise RuntimeError(f"Copied {label} did not verify byte-for-byte")


def inject_thermal_integrity_runtime(index_file: pathlib.Path) -> None:
    _BASE_INJECT_BRIDGE(index_file)

    copy_verified(
        THERMAL_WORKSPACE_SOURCE,
        index_file.with_name(THERMAL_WORKSPACE_NAME),
        "thermal-integrity workspace runtime",
    )
    copy_verified(
        THERMAL_UI_SOURCE,
        index_file.with_name(THERMAL_UI_NAME),
        "thermal-integrity UI runtime",
    )

    text = index_file.read_text(encoding="utf-8")
    if THERMAL_WORKSPACE_TAG not in text:
        if THERMAL_UI_TAG in text:
            text = text.replace(THERMAL_UI_TAG, THERMAL_RUNTIME_TAGS, 1)
        elif "</body>" in text:
            text = text.replace("</body>", f"  {THERMAL_RUNTIME_TAGS}\n</body>", 1)
        elif "</head>" in text:
            text = text.replace("</head>", f"  {THERMAL_RUNTIME_TAGS}\n</head>", 1)
        else:
            raise RuntimeError("Unable to inject the thermal-integrity runtimes into index.html")
    elif THERMAL_UI_TAG not in text:
        text = text.replace(THERMAL_WORKSPACE_TAG, THERMAL_RUNTIME_TAGS, 1)
    index_file.write_text(text, encoding="utf-8")

    verified = index_file.read_text(encoding="utf-8")
    if THERMAL_WORKSPACE_TAG not in verified or THERMAL_UI_TAG not in verified:
        raise RuntimeError("Thermal-integrity runtime tags were not retained in index.html")
    if verified.index(THERMAL_WORKSPACE_TAG) > verified.index(THERMAL_UI_TAG):
        raise RuntimeError("Thermal-integrity workspace runtime must load before the UI runtime")

    marker = index_file.with_name(THERMAL_PACKAGE_MARKER)
    marker.write_text(THERMAL_PACKAGE_MARKER_TEXT, encoding="utf-8")
    if marker.read_text(encoding="utf-8") != THERMAL_PACKAGE_MARKER_TEXT:
        raise RuntimeError("Thermal-integrity package marker did not verify byte-for-byte")


BASE.patch_android_export = patch_android_export_with_thermal_integrity
BASE.inject_bridge = inject_thermal_integrity_runtime


if __name__ == "__main__":
    try:
        raise SystemExit(BASE.main())
    except Exception as error:
        print(f"thermal filaSim asset preparation failed: {error}", file=sys.stderr)
        raise
