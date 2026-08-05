#!/usr/bin/env python3
"""Final physical Thermal Integrity preparer with report-schema compatibility."""
from __future__ import annotations
import importlib.util
import pathlib
import shutil
import subprocess
import sys

V11 = pathlib.Path(__file__).with_name("prepare-filasim-assets-with-thermal-integrity-v11.py")
CONTRACT_FIX = pathlib.Path(__file__).with_name(
    "filasim-thermal-integrity-physical-contract-fix.py"
)
PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[1]
WORKER_BROKER_RUNTIME = PROJECT_ROOT / "app/src/main/filasim/filasim-worker-broker.js"
for path in (V11, CONTRACT_FIX, WORKER_BROKER_RUNTIME):
    if not path.is_file():
        raise RuntimeError(f"Thermal Integrity v12 component is missing: {path}")

spec = importlib.util.spec_from_file_location("enderslicer_thermal_v11", V11)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Unable to load {V11}")
v11 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v11)
thermal = v11.thermal

marker = ".enderslicer-thermal-integrity-physical-contract-fix-v1"
if CONTRACT_FIX not in thermal.THERMAL_TRANSFORMS:
    thermal.THERMAL_TRANSFORMS = (*thermal.THERMAL_TRANSFORMS, CONTRACT_FIX)
if marker not in thermal.THERMAL_MARKERS:
    thermal.THERMAL_MARKERS = (*thermal.THERMAL_MARKERS, marker)

_base_ui = thermal.patch_thermal_ui_runtime
_base_inject = thermal.BASE.inject_bridge
WORKER_BROKER_NAME = "filasim-worker-broker.js"
WORKER_BROKER_TAG = f'<script src="./{WORKER_BROKER_NAME}"></script>'


THERMAL_RENDER_RUNTIME = r'''  const THERMAL_PALETTE_SIZE = 256;
  const thermalPalette = new Uint8ClampedArray(THERMAL_PALETTE_SIZE * 3);
  let thermalPaletteReady = false;
  let thermalScratch = null;
  let thermalScratchContext = null;
  let thermalImage = null;

  function colorFor(value, minimum, maximum) {
    const t = maximum > minimum ? Math.max(0, Math.min(1, (value - minimum) / (maximum - minimum))) : 0;
    const stops = [
      [0, 26, 45, 105],
      [0.25, 0, 154, 255],
      [0.5, 0, 220, 170],
      [0.75, 255, 220, 55],
      [1, 230, 40, 30],
    ];
    for (let index = 1; index < stops.length; index += 1) {
      if (t <= stops[index][0]) {
        const a = stops[index - 1];
        const b = stops[index];
        const f = (t - a[0]) / (b[0] - a[0]);
        return [
          Math.round(a[1] + (b[1] - a[1]) * f),
          Math.round(a[2] + (b[2] - a[2]) * f),
          Math.round(a[3] + (b[3] - a[3]) * f),
        ];
      }
    }
    return stops[stops.length - 1].slice(1);
  }

  function ensureThermalPalette() {
    if (thermalPaletteReady) return;
    for (let index = 0; index < THERMAL_PALETTE_SIZE; index += 1) {
      const rgb = colorFor(index, 0, THERMAL_PALETTE_SIZE - 1);
      const offset = index * 3;
      thermalPalette[offset] = rgb[0];
      thermalPalette[offset + 1] = rgb[1];
      thermalPalette[offset + 2] = rgb[2];
    }
    thermalPaletteReady = true;
  }

  function thermalPaletteIndex(value, minimum, maximum) {
    if (!(maximum > minimum)) return 0;
    const normalized = (Number(value) - minimum) / (maximum - minimum);
    return Math.max(0, Math.min(
      THERMAL_PALETTE_SIZE - 1,
      Math.round(normalized * (THERMAL_PALETTE_SIZE - 1))
    ));
  }

  function ensureThermalSurface(context, width, height) {
    if (!thermalScratch) thermalScratch = document.createElement("canvas");
    if (thermalScratch.width !== width || thermalScratch.height !== height || !thermalImage) {
      thermalScratch.width = width;
      thermalScratch.height = height;
      thermalScratchContext = thermalScratch.getContext("2d");
      thermalImage = context.createImageData(width, height);
    }
    return { image: thermalImage, scratch: thermalScratch, scratchContext: thermalScratchContext };
  }

  function drawHeatmap() {
    if (!latest) return;
    ensureThermalPalette();
    const canvas = input("heatmap");
    const context = canvas.getContext("2d");
    const nx = Number(latest.stats.nx);
    const ny = Number(latest.stats.ny);
    const nz = Number(latest.stats.nz);
    const minimumTemperatureC = Number(latest.stats.minimumTemperatureC);
    const maximumTemperatureC = Number(latest.stats.maximumTemperatureC);
    const axis = input("axis").value;
    const sliceIndex = Number(input("slice").value);
    const widthCells = axis === "x" ? ny : nx;
    const heightCells = axis === "z" ? ny : nz;
    const { image, scratch, scratchContext } = ensureThermalSurface(context, widthCells, heightCells);
    const pixels = image.data;

    for (let v = 0; v < heightCells; v += 1) {
      for (let u = 0; u < widthCells; u += 1) {
        let x;
        let y;
        let z;
        if (axis === "x") {
          x = sliceIndex;
          y = u;
          z = heightCells - 1 - v;
        } else if (axis === "y") {
          x = u;
          y = sliceIndex;
          z = heightCells - 1 - v;
        } else {
          x = u;
          y = heightCells - 1 - v;
          z = sliceIndex;
        }
        const cell = (z * ny + y) * nx + x;
        const pixel = (v * widthCells + u) * 4;
        if (latest.materialFraction[cell] <= 1e-7) {
          pixels[pixel] = 15;
          pixels[pixel + 1] = 15;
          pixels[pixel + 2] = 18;
          pixels[pixel + 3] = 255;
          continue;
        }
        const palette = thermalPaletteIndex(
          latest.temperatures[cell], minimumTemperatureC, maximumTemperatureC
        ) * 3;
        pixels[pixel] = thermalPalette[palette];
        pixels[pixel + 1] = thermalPalette[palette + 1];
        pixels[pixel + 2] = thermalPalette[palette + 2];
        pixels[pixel + 3] = 255;
      }
    }
    scratchContext.putImageData(image, 0, 0);
    context.imageSmoothingEnabled = false;
    context.clearRect(0, 0, canvas.width, canvas.height);
    context.drawImage(scratch, 0, 24, canvas.width, canvas.height - 48);
    context.fillStyle = "rgba(255,255,255,.9)";
    context.font = "14px sans-serif";
    context.fillText(
      `${axis.toUpperCase()} slice ${sliceIndex + 1}/${input("slice").maxAsNumber + 1} · ${format(
        minimumTemperatureC, 2
      )}–${format(maximumTemperatureC, 2)} °C`,
      10,
      17
    );
    const gradient = context.createLinearGradient(10, canvas.height - 15, canvas.width - 10, canvas.height - 15);
    for (let index = 0; index <= 20; index += 1) {
      const rgb = colorFor(index, 0, 20);
      gradient.addColorStop(index / 20, `rgb(${rgb[0]},${rgb[1]},${rgb[2]})`);
    }
    context.fillStyle = gradient;
    context.fillRect(10, canvas.height - 16, canvas.width - 20, 8);
  }

  function drawHistory() {
    const canvas = input("history");
    const context = canvas.getContext("2d");
    context.clearRect(0, 0, canvas.width, canvas.height);
    const history = latest?.history;
    if (!history || history.length < 6) {
      canvas.style.display = "none";
      return;
    }
    canvas.style.display = "block";
    let maxTime = 1;
    let minTemp = Number.POSITIVE_INFINITY;
    let maxTemp = Number.NEGATIVE_INFINITY;
    for (let index = 0; index + 2 < history.length; index += 3) {
      maxTime = Math.max(maxTime, Number(history[index]));
      minTemp = Math.min(minTemp, Number(history[index + 2]));
      maxTemp = Math.max(maxTemp, Number(history[index + 1]));
    }
    const left = 45;
    const right = canvas.width - 14;
    const top = 24;
    const bottom = canvas.height - 28;
    const x = (time) => left + (time / maxTime) * (right - left);
    const y = (temperature) =>
      bottom - ((temperature - minTemp) / Math.max(maxTemp - minTemp, 1e-9)) * (bottom - top);

    context.strokeStyle = "rgba(255,255,255,.22)";
    context.strokeRect(left, top, right - left, bottom - top);
    const draw = (valueOffset, stroke) => {
      context.beginPath();
      let point = 0;
      for (let index = 0; index + 2 < history.length; index += 3) {
        const px = x(Number(history[index]));
        const py = y(Number(history[index + valueOffset]));
        if (point === 0) context.moveTo(px, py);
        else context.lineTo(px, py);
        point += 1;
      }
      context.strokeStyle = stroke;
      context.lineWidth = 2;
      context.stroke();
    };
    draw(1, "#ff7e5f");
    draw(2, "#63d7ff");
    context.fillStyle = "rgba(255,255,255,.9)";
    context.font = "13px sans-serif";
    context.fillText("Transient temperature history", 10, 16);
    context.fillText(`${format(minTemp, 1)} °C`, 3, bottom);
    context.fillText(`${format(maxTemp, 1)} °C`, 3, top + 5);
    context.fillText(`${format(maxTime, 1)} s`, right - 45, canvas.height - 8);
    context.fillStyle = "#ff7e5f";
    context.fillText("max", left + 8, top + 16);
    context.fillStyle = "#63d7ff";
    context.fillText("mean", left + 45, top + 16);
  }

'''


def patch_ui_v12(target: pathlib.Path) -> None:
    _base_ui(target)
    text = target.read_text(encoding="utf-8")

    # Replace the feature-local Worker proxy and request listener churn with the
    # shared broker packaged immediately before the Thermal lifecycle guard.
    worker_start = text.find("  function installWorkerAccess() {")
    worker_end = text.find("  function finite(", worker_start + 1)
    if worker_start < 0 or worker_end < 0:
        raise RuntimeError("Thermal UI Worker access block could not be located")
    broker_request = '''  const workerBroker = window.EnderSlicerFilaSimWorkerBroker;
  if (!workerBroker) throw new Error("filaSim worker broker is unavailable");

  function request(op, payload = {}, onProgress = null) {
    return workerBroker.request(op, payload, onProgress);
  }

'''
    text = text[:worker_start] + broker_request + text[worker_end:]
    text = text.replace("  installWorkerAccess();\n", "", 1)

    render_start = text.find("  function colorFor(")
    render_end = text.find("  function collectReport()", render_start + 1)
    if render_start < 0 or render_end < 0:
        raise RuntimeError("Thermal result renderer block could not be located")
    text = text[:render_start] + THERMAL_RENDER_RUNTIME + text[render_end:]

    # Keep the persistent Android report schema compatible in this feature
    # round. The package transform marker records the physical-model revision.
    physical = 'solverModel: "voxel-finite-volume-contact-heater-thermomechanical-v2",'
    compatible = 'solverModel: "voxel-finite-volume-implicit-thermomechanical",'
    if physical in text:
        text = text.replace(physical, compatible, 1)
    elif compatible not in text:
        raise RuntimeError("Thermal report solver-model contract is missing")

    # This Android host intentionally does not depend on JavaScript modal-dialog
    # plumbing. Always render physical-validity warnings inline and continue the
    # thermal-only calculation; the WASM boundary blocks structural FEA when the
    # solved field leaves the material model.
    inline = '''      if (physicalWarnings.length && preflightBox) {
        const lineBreak = String.fromCharCode(10);
        preflightBox.className = "ti-status ti-warning";
        preflightBox.textContent +=
          lineBreak + physicalWarnings.join(lineBreak) + lineBreak +
          "The temperature field will still be calculated. Structural FEA will be skipped automatically if the solved field leaves the material model.";
      }
'''
    if inline not in text:
        start_marker = '      if (physicalWarnings.length && !window.confirm('
        end_marker = '      let transform = null;\n'
        start = text.find(start_marker)
        end = text.find(end_marker, start + 1) if start >= 0 else -1
        if start < 0 or end < 0:
            raise RuntimeError("Thermal modal warning block could not be located")
        text = text[:start] + inline + text[end:]

    target.write_text(text, encoding="utf-8")
    verified = target.read_text(encoding="utf-8")
    if "window.confirm(" in verified:
        raise RuntimeError("Thermal runtime still depends on a JavaScript modal confirmation")
    if "The temperature field will still be calculated" not in verified:
        raise RuntimeError("Thermal inline material warning is missing")
    if "String.fromCharCode(10)" not in verified:
        raise RuntimeError("Thermal inline warning does not use escape-safe line breaks")
    if "EnderSlicerFilaSimWorkerBroker" not in verified:
        raise RuntimeError("Thermal runtime is not using the shared worker broker")
    if "new Proxy(ExistingWorker" in verified:
        raise RuntimeError("Thermal runtime still installs a feature-local Worker proxy")
    if "const thermalPalette = new Uint8ClampedArray" not in verified:
        raise RuntimeError("Thermal heatmap palette cache is missing")
    if "const points = []" in verified or "points.map" in verified:
        raise RuntimeError("Thermal history renderer still allocates point arrays")
    subprocess.run(["node", "--check", str(target)], check=True)


def inject_worker_broker_v12(index_file: pathlib.Path) -> None:
    _base_inject(index_file)
    target = index_file.with_name(WORKER_BROKER_NAME)
    shutil.copyfile(WORKER_BROKER_RUNTIME, target)
    subprocess.run(["node", "--check", str(target)], check=True)
    broker = target.read_text(encoding="utf-8")
    for contract in (
        "EnderSlicerFilaSimWorkerBroker",
        "pending = new Map",
        "cancelActive",
        "progressBuffers",
    ):
        if contract not in broker:
            raise RuntimeError(f"Generated worker broker is missing {contract!r}")

    text = index_file.read_text(encoding="utf-8")
    text = text.replace(f"  {WORKER_BROKER_TAG}\n", "").replace(WORKER_BROKER_TAG, "")
    if thermal.THERMAL_GUARD_TAG not in text:
        raise RuntimeError("Thermal guard tag is missing before worker-broker injection")
    text = text.replace(
        thermal.THERMAL_GUARD_TAG,
        f"{WORKER_BROKER_TAG}\n  {thermal.THERMAL_GUARD_TAG}",
        1,
    )
    index_file.write_text(text, encoding="utf-8")
    verified = index_file.read_text(encoding="utf-8")
    if verified.count(WORKER_BROKER_TAG) != 1:
        raise RuntimeError("Worker broker runtime tag was not retained exactly once")
    if verified.index(WORKER_BROKER_TAG) >= verified.index(thermal.THERMAL_GUARD_TAG):
        raise RuntimeError("Worker broker must load before the Thermal lifecycle guard")


thermal.patch_thermal_ui_runtime = patch_ui_v12
thermal.BASE.inject_bridge = inject_worker_broker_v12
thermal.THERMAL_PACKAGE_MARKER_TEXT = (
    "format=1\n"
    f"filasim={thermal.BASE.FILASIM_COMMIT}\n"
    "transforms=solver,hardening,audit-fixes,progress-v2,react-tab-v1,"
    "bugfix-round1,bugfix-round2,linear-fast-path-v1,physical-model-v1,"
    "worker-broker-v1,render-cache-v1\n"
)

if __name__ == "__main__":
    try:
        raise SystemExit(thermal.BASE.main())
    except Exception as error:
        print(f"thermal filaSim v12 asset preparation failed: {error}", file=sys.stderr)
        raise
