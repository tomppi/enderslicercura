#!/usr/bin/env python3
"""Build the pinned single-threaded filaSim web app for Android WebView."""

from __future__ import annotations

import argparse
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

FILASIM_COMMIT = "e7485ec22d4ebe8baca04190404fbb877c90e031"
ASSET_FORMAT = 1


def run(command: list[str], cwd: pathlib.Path, env: dict[str, str] | None = None) -> None:
    print(">", " ".join(command), flush=True)
    merged = os.environ.copy()
    if env:
        merged.update(env)
    subprocess.run(command, cwd=cwd, env=merged, check=True)


def safe_extract(archive: zipfile.ZipFile, destination: pathlib.Path) -> pathlib.Path:
    roots: set[str] = set()
    destination = destination.resolve()
    for info in archive.infolist():
        parts = pathlib.PurePosixPath(info.filename).parts
        if not parts:
            continue
        roots.add(parts[0])
        target = (destination / pathlib.Path(*parts)).resolve()
        if target != destination and destination not in target.parents:
            raise RuntimeError(f"Unsafe filaSim archive entry: {info.filename}")
        if info.is_dir():
            target.mkdir(parents=True, exist_ok=True)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(info) as source, target.open("wb") as output:
                shutil.copyfileobj(source, output, 1024 * 1024)
    if len(roots) != 1:
        raise RuntimeError("Pinned filaSim archive did not contain one source root")
    root = destination / next(iter(roots))
    if not root.is_dir():
        raise RuntimeError("Pinned filaSim source root is missing")
    return root


def patch_android_export(store_file: pathlib.Path) -> None:
    text = store_file.read_text(encoding="utf-8")
    marker = "EnderSlicerBridge?.captureModifierZip"
    if marker in text:
        return
    old = '''  async downloadStls() {
    try {
      const bytes = await engine.exportStls();
      const base = (get().fileName ?? "part").replace(/\\.(stl|3mf)$/i, "");
      download(bytes, `${base}_modifiers.zip`, "application/zip");
'''
    new = '''  async downloadStls() {
    try {
      const bytes = await engine.exportStls();
      const state = get();
      const bridge = (window as any).EnderSlicerBridge;
      if (bridge?.captureModifierZip) {
        if (!state.optSummary) throw new Error("No optimized infill result is available");
        await bridge.captureModifierZip(bytes, {
          sourceName: state.fileName ?? "part.stl",
          baseDensityPercent: state.optSummary.baseDensity * 100,
          pattern: state.pattern,
          mode: state.optMode,
          perimeters: state.perimeters,
          lineWidthMm: state.lineWidth,
          topBottomLayers: state.topBottomLayers,
          layerHeightMm: state.layerHeight,
        });
        return;
      }
      const base = (state.fileName ?? "part").replace(/\\.(stl|3mf)$/i, "");
      download(bytes, `${base}_modifiers.zip`, "application/zip");
'''
    if old not in text:
        raise RuntimeError("Unable to locate filaSim modifier-export function for Android patching")
    store_file.write_text(text.replace(old, new), encoding="utf-8")


def inject_bridge(index_file: pathlib.Path) -> None:
    text = index_file.read_text(encoding="utf-8")
    script = '<script src="./android-bridge.js"></script>'
    if script in text:
        return
    if "</head>" in text:
        text = text.replace("</head>", f"  {script}\n</head>", 1)
    elif "</body>" in text:
        text = text.replace("</body>", f"  {script}\n</body>", 1)
    else:
        raise RuntimeError("Unable to inject the Android bridge into filaSim index.html")
    index_file.write_text(text, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project-root", default=pathlib.Path(__file__).resolve().parents[1], type=pathlib.Path)
    args = parser.parse_args()
    project_root = args.project_root.resolve()
    output = project_root / "app/src/main/assets/filasim"
    bridge = project_root / "app/src/main/filasim/android-bridge.js"
    marker_text = f"format={ASSET_FORMAT}\ncommit={FILASIM_COMMIT}\n"
    marker = output / ".source-version"

    if (
        marker.is_file()
        and marker.read_text(encoding="utf-8") == marker_text
        and (output / "index.html").is_file()
        and (output / "android-bridge.js").is_file()
        and (output / "LICENSE").is_file()
    ):
        print("Pinned filaSim Android assets are already prepared")
        return 0

    if not bridge.is_file():
        raise RuntimeError(f"Android filaSim bridge is missing: {bridge}")
    for executable in ("node", "npm", "wasm-pack"):
        if shutil.which(executable) is None:
            raise RuntimeError(
                f"{executable} is required to prepare filaSim assets. "
                "Install Rust, wasm-pack and Node.js before building EnderSlicerCura."
            )

    build_root = project_root / ".build/filasim-android"
    build_root.mkdir(parents=True, exist_ok=True)
    source_root = build_root / FILASIM_COMMIT
    if not source_root.is_dir():
        with tempfile.TemporaryDirectory(dir=build_root) as temporary:
            temporary_path = pathlib.Path(temporary)
            archive_path = temporary_path / "filasim.zip"
            url = f"https://github.com/CNCKitchen/smartInfillGenerator/archive/{FILASIM_COMMIT}.zip"
            request = urllib.request.Request(url, headers={"User-Agent": "EnderSlicerCura-build"})
            with urllib.request.urlopen(request, timeout=180) as response, archive_path.open("wb") as output_file:
                shutil.copyfileobj(response, output_file, 1024 * 1024)
            extract_path = temporary_path / "source"
            extract_path.mkdir()
            with zipfile.ZipFile(archive_path) as archive:
                extracted = safe_extract(archive, extract_path)
            shutil.move(str(extracted), source_root)

    web_root = source_root / "web"
    store_file = web_root / "src/store.ts"
    if not store_file.is_file():
        raise RuntimeError("Pinned filaSim source did not contain web/src/store.ts")
    patch_android_export(store_file)

    run(["npm", "install", "--no-audit", "--no-fund"], cwd=web_root)
    run(["node", "scripts/build-wasm.mjs", "st"], cwd=web_root)
    run(["npm", "run", "build"], cwd=web_root, env={"VITE_BASE": "./"})

    dist = web_root / "dist"
    if not (dist / "index.html").is_file():
        raise RuntimeError("filaSim production build did not create dist/index.html")
    staging = build_root / "assets.next"
    shutil.rmtree(staging, ignore_errors=True)
    shutil.copytree(dist, staging)
    shutil.copy2(bridge, staging / "android-bridge.js")
    shutil.copy2(source_root / "LICENSE", staging / "LICENSE")
    (staging / "SOURCE.md").write_text(
        "# filaSim source\n\n"
        f"Pinned upstream commit: `{FILASIM_COMMIT}`\n\n"
        "The complete corresponding source is the CNCKitchen/smartInfillGenerator repository "
        "at the commit above. EnderSlicerCura's Android bridge and deterministic build script "
        "are stored in this repository.\n",
        encoding="utf-8",
    )
    inject_bridge(staging / "index.html")
    (staging / ".source-version").write_text(marker_text, encoding="utf-8")

    shutil.rmtree(output, ignore_errors=True)
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(staging), output)
    print(f"Prepared filaSim Android assets at {output}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"filaSim asset preparation failed: {error}", file=sys.stderr)
        raise
