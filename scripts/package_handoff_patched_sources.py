#!/usr/bin/env python3
from __future__ import annotations

import base64
import io
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "handoff-patched-sources.b64"
FILES = [
    "app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt",
    "app/src/main/java/com/tomppi/enderslicer/ui/EnderSlicerApp.kt",
    "app/src/main/java/com/tomppi/enderslicer/ui/IntegratedEnderSlicerApp.kt",
    "app/src/main/java/com/tomppi/enderslicer/ui/LayerPreviewView.kt",
    "app/src/main/java/com/tomppi/enderslicer/viewer/ModelSurfaceView.kt",
    "app/src/main/java/com/tomppi/enderslicer/engine/CuraEngineRunner.kt",
    "app/src/main/java/com/tomppi/enderslicer/data/WorkspaceStateStore.kt",
    "app/src/main/java/com/tomppi/enderslicer/model/PlanarPatchSelector.kt",
]

buffer = io.BytesIO()
with tarfile.open(fileobj=buffer, mode="w:gz", format=tarfile.PAX_FORMAT) as archive:
    for relative in FILES:
        source = ROOT / relative
        if not source.is_file():
            raise SystemExit(f"Missing patched source: {relative}")
        archive.add(source, arcname=relative, recursive=False)

OUTPUT.write_text(base64.b64encode(buffer.getvalue()).decode("ascii") + "\n")
print(f"Packaged {len(FILES)} patched source files into {OUTPUT.name}")
