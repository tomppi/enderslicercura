#!/usr/bin/env python3
"""Compatibility entry point for the spatial viewer transform.

The v1 transform inserted its drag handlers before the axis-gizmo section, but
looked for the constructor's listener registration as the end boundary. The
constructor is earlier in SceneManager, so that boundary can never follow the
handler. Patch only that boundary while retaining the reviewed implementation.
"""
from __future__ import annotations

import importlib.util
import pathlib

BASE = pathlib.Path(__file__).with_name(
    "filasim-nearby-hot-object-spatial-environment-viewer.py"
)
if not BASE.is_file():
    raise RuntimeError(f"Spatial viewer transform is missing: {BASE}")

spec = importlib.util.spec_from_file_location("enderslicer_spatial_viewer_v1", BASE)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Unable to load {BASE}")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

_original_replace_between = module.replace_between


def replace_between_fixed(path, start, end, replacement, label):
    if label == "XY/Z constrained heat-source drag handlers":
        end = "  // ---------- axis gizmo ----------\n"
    return _original_replace_between(path, start, end, replacement, label)


module.replace_between = replace_between_fixed


def apply(source_root: pathlib.Path) -> None:
    module.apply(source_root)


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
