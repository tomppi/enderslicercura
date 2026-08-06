#!/usr/bin/env python3
"""Compatibility entry point for the spatial viewer transform.

The spatial implementation follows the marker-drag transform. This entry point
adapts two anchors whose surrounding code was already changed by that earlier
transform, while retaining the reviewed spatial implementation itself.
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
_original_replace_once = module.replace_once


def replace_between_fixed(path, start, end, replacement, label):
    if label == "XY/Z constrained heat-source drag handlers":
        end = "  // ---------- axis gizmo ----------\n"
    return _original_replace_between(path, start, end, replacement, label)


def replace_once_fixed(path, old, new, label):
    if label == "spatial viewer disposal":
        old = """    this.callouts.dispose();
"""
        new = """    this.setNearbyHotObjectMarker(null);
    this.setNearbyHotObjectEnclosureBox(null);
    this.callouts.dispose();
"""
    return _original_replace_once(path, old, new, label)


module.replace_between = replace_between_fixed
module.replace_once = replace_once_fixed


def apply(source_root: pathlib.Path) -> None:
    module.apply(source_root)


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
