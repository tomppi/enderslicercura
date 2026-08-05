#!/usr/bin/env python3
"""Make the filaSim Thermal and Anneal React workspaces mutually exclusive.

The original integration kept one boolean per workspace and relied on two
separate synchronous CustomEvents for every tab switch. If the deactivation
event was missed, both booleans remained true and StepPanel kept rendering the
first matching workspace. Each activation handler now clears the other state
itself, so the true event is sufficient to select the requested view.
"""
from __future__ import annotations

import pathlib

MARKER = "EnderSlicer mutually exclusive Thermal and Anneal tabs v1"
THERMAL_EVENT = "enderslicer-thermal-workspace"
ANNEALING_EVENT = "enderslicer-annealing-workspace"

THERMAL_OLD = f'''    const onThermalWorkspace = (event: Event) =>
      setThermalActive(Boolean((event as CustomEvent<boolean>).detail));
    window.addEventListener("{THERMAL_EVENT}", onThermalWorkspace);
'''
THERMAL_NEW = f'''    const onThermalWorkspace = (event: Event) => {{
      const active = Boolean((event as CustomEvent<boolean>).detail);
      setThermalActive(active);
      if (active) setAnnealingActive(false);
    }};
    window.addEventListener("{THERMAL_EVENT}", onThermalWorkspace);
'''
ANNEALING_OLD = f'''    const onAnnealingWorkspace = (event: Event) =>
      setAnnealingActive(Boolean((event as CustomEvent<boolean>).detail));
    window.addEventListener("{ANNEALING_EVENT}", onAnnealingWorkspace);
'''
ANNEALING_NEW = f'''    const onAnnealingWorkspace = (event: Event) => {{
      const active = Boolean((event as CustomEvent<boolean>).detail);
      setAnnealingActive(active);
      if (active) setThermalActive(false);
    }};
    window.addEventListener("{ANNEALING_EVENT}", onAnnealingWorkspace);
'''


def replace_once(path: pathlib.Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one {label} in {path}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def apply(source_root: pathlib.Path) -> None:
    source_root = source_root.resolve()
    rail = source_root / "web/src/ui/StepRail.tsx"
    panel = source_root / "web/src/ui/StepPanel.tsx"
    for path in (rail, panel):
        if not path.is_file():
            raise RuntimeError(f"Workspace-tab state target is missing: {path}")
        replace_once(path, THERMAL_OLD, THERMAL_NEW, "mutually exclusive Thermal handler")
        replace_once(path, ANNEALING_OLD, ANNEALING_NEW, "mutually exclusive Anneal handler")
        text = path.read_text(encoding="utf-8")
        for contract in (
            "if (active) setAnnealingActive(false);",
            "if (active) setThermalActive(false);",
        ):
            if contract not in text:
                raise RuntimeError(f"Workspace-tab contract {contract!r} is missing from {path}")

    marker = source_root / ".enderslicer-workspace-tab-state-fix-v1"
    marker.write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
