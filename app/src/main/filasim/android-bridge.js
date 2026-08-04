/*
 * Android host adapter for the pinned filaSim web workspace.
 * The upstream application remains responsible for analysis and optimization;
 * this adapter injects the displayed EnderSlicerCura STL, returns validated
 * optimizer outputs, and captures an auditable build-process thermal FEA report.
 */
(() => {
  "use strict";

  const android = window.EnderSlicerAndroid;
  if (!android) return;

  const CHUNK_BYTES = 256 * 1024;
  const APPLY_SMART_INFILL_LABEL = "Apply Smart Infill";
  const APPLY_SMART_INFILL_NOTE =
    "Transfers the optimized infill regions to EnderSlicer and returns to the model.";
  const THERMAL_REPORT_GROUP_ID = "enderslicer-thermal-fea-report";
  const THERMAL_REPORT_LABEL = "Save Thermal FEA Report";
  const THERMAL_REPORT_NOTE =
    "Saves a source-fingerprinted report for warp and bed-reaction results. No absolute pass/fail threshold is claimed.";
  let modelLoadStarted = false;
  let exporting = false;
  let exportUiObserver = null;

  function normalizedText(element) {
    return String(element?.textContent || "").replace(/\s+/g, " ").trim();
  }

  function sameText(element, value) {
    if (normalizedText(element) !== value) element.textContent = value;
  }

  function currentWorkspace() {
    return document.querySelector("label.workspace select")?.value || "optimize";
  }

  function findGroup(label) {
    const wanted = label.toLowerCase();
    return Array.from(document.querySelectorAll(".panel .group")).find((group) => {
      const heading = normalizedText(group.querySelector(".g-label span")).toLowerCase();
      return heading === wanted;
    }) || null;
  }

  function findKvValue(labelPrefix) {
    const wanted = labelPrefix.toLowerCase();
    for (const row of document.querySelectorAll(".panel .kv")) {
      const label = normalizedText(row.firstElementChild).toLowerCase();
      if (label.startsWith(wanted)) {
        return normalizedText(row.children[1] || row.lastElementChild);
      }
    }
    return "";
  }

  function parseFirstNumber(text) {
    const match = String(text).replace(/,/g, ".").match(/[-+]?\d+(?:\.\d+)?(?:e[-+]?\d+)?/i);
    if (!match) return null;
    const value = Number(match[0]);
    return Number.isFinite(value) ? value : null;
  }

  function parseLengthMm(text) {
    const match = String(text)
      .replace(/,/g, ".")
      .match(/([-+]?\d+(?:\.\d+)?(?:e[-+]?\d+)?)\s*(nm|µm|μm|um|mm|cm|m|mil|in|inch|ft)\b/i);
    if (!match) return null;
    const value = Number(match[1]);
    if (!Number.isFinite(value)) return null;
    const unit = match[2].toLowerCase();
    const scale = {
      nm: 1e-6,
      "µm": 1e-3,
      "μm": 1e-3,
      um: 1e-3,
      mm: 1,
      cm: 10,
      m: 1000,
      mil: 0.0254,
      in: 25.4,
      inch: 25.4,
      ft: 304.8,
    }[unit];
    return scale == null ? null : value * scale;
  }

  function parseStressMpa(text) {
    const match = String(text)
      .replace(/,/g, ".")
      .match(/([-+]?\d+(?:\.\d+)?(?:e[-+]?\d+)?)\s*(pa|kpa|mpa|gpa|psi|ksi|n\/mm(?:²|2))\b/i);
    if (!match) return null;
    const value = Number(match[1]);
    if (!Number.isFinite(value)) return null;
    const unit = match[2].toLowerCase();
    const scale = {
      pa: 1e-6,
      kpa: 1e-3,
      mpa: 1,
      gpa: 1000,
      psi: 0.006894757293168,
      ksi: 6.894757293168,
      "n/mm²": 1,
      "n/mm2": 1,
    }[unit];
    return scale == null ? null : value * scale;
  }

  function parsePair(text, parser) {
    const parts = String(text).split("·");
    if (parts.length < 2) return null;
    const first = parser(parts[0]);
    const second = parser(parts[1]);
    return first == null || second == null ? null : [first, second];
  }

  function readInputNumber(label) {
    const input = findGroup(label)?.querySelector('input[type="number"]');
    if (!input) return null;
    const value = Number(input.value);
    return Number.isFinite(value) ? value : null;
  }

  function readMaterial() {
    const group = findGroup("Material");
    const name = normalizedText(group?.querySelector(".g-label b"));
    const detail = normalizedText(group?.querySelector(".dim"));
    const shrink = detail.match(
      /Shrink XY\s*([-+]?\d+(?:[.,]\d+)?)\s*%\s*·\s*Z\s*([-+]?\d+(?:[.,]\d+)?)\s*%(?:\s*·\s*locks at\s*([-+]?\d+(?:[.,]\d+)?)\s*°C)?/i,
    );
    if (!name || !shrink) return null;
    const xy = Number(shrink[1].replace(",", "."));
    const z = Number(shrink[2].replace(",", "."));
    const lock = shrink[3] == null ? null : Number(shrink[3].replace(",", "."));
    if (![xy, z].every(Number.isFinite) || (lock != null && !Number.isFinite(lock))) return null;
    return { name, shrinkXyPercent: Math.abs(xy), shrinkZPercent: Math.abs(z), lockingTemperatureC: lock };
  }

  function readVoxelSizeMm() {
    const panelText = normalizedText(document.querySelector(".panel"));
    const match = panelText.match(
      /\bh\s*=\s*([-+]?\d+(?:[.,]\d+)?(?:e[-+]?\d+)?)\s*(nm|µm|μm|um|mm|cm|m|mil|in|inch|ft)\b/i,
    );
    return match ? parseLengthMm(`${match[1]} ${match[2]}`) : null;
  }

  function readSolverSeconds() {
    const statuses = Array.from(document.querySelectorAll(".panel .status.ok"));
    const status = statuses.map(normalizedText).find((text) => /Max warp/i.test(text)) || "";
    const match = status.replace(/,/g, ".").match(/([-+]?\d+(?:\.\d+)?)\s*s(?:\b|$)/i);
    if (!match) return null;
    const value = Number(match[1]);
    return Number.isFinite(value) ? value : null;
  }

  function staleThermalResult() {
    return Array.from(document.querySelectorAll(".panel .warnbanner")).some((element) => {
      const text = normalizedText(element).toLowerCase();
      return text.includes("settings changed") || text.includes("out of date") || text.includes("stale");
    });
  }

  function collectThermalReport() {
    if (currentWorkspace() !== "buildsim") {
      throw new Error("Switch filaSim to Build Simulation first");
    }
    if (staleThermalResult()) {
      throw new Error("The build-simulation result is stale; run the simulation again");
    }

    const material = readMaterial();
    const warp = parsePair(findKvValue("On bed / released"), parseLengthMm);
    const peel = parsePair(findKvValue("Bed peel"), parseStressMpa);
    const stiffness = findKvValue("Stiffness field");
    if (!material || !warp || !peel || !stiffness) {
      throw new Error("Run the complete build simulation before saving a report");
    }

    const report = {
      schemaVersion: 1,
      analysisKind: "fdm-build-thermomechanical",
      solverModel: "sequential-voxel-inherent-strain",
      sourceName: String(android.sourceFileName()),
      sourceSha256: String(android.sourceSha256()),
      upstreamCommit: String(android.upstreamCommit()),
      generatedAtEpochMillis: Date.now(),
      material,
      process: {
        bedTemperatureC: readInputNumber("Bed temp"),
        chamberTemperatureC: readInputNumber("Chamber temp"),
        densityAware: /as-printed/i.test(stiffness),
      },
      mesh: {
        voxelSizeMm: readVoxelSizeMm(),
      },
      results: {
        bondedWarpMm: Math.abs(warp[0]),
        releasedWarpMm: Math.abs(warp[1]),
        peakLiftMpa: Math.abs(peel[0]),
        peakShearMpa: Math.abs(peel[1]),
        solverSeconds: readSolverSeconds(),
      },
      confidence: {
        level: "experimental-literature-seeded",
        calibratedToPrinter: false,
      },
    };

    const required = [
      report.material.shrinkXyPercent,
      report.material.shrinkZPercent,
      report.results.bondedWarpMm,
      report.results.releasedWarpMm,
      report.results.peakLiftMpa,
      report.results.peakShearMpa,
    ];
    if (!required.every(Number.isFinite)) {
      throw new Error("filaSim displayed a non-finite thermal result");
    }
    return report;
  }

  function thermalResultReady() {
    if (currentWorkspace() !== "buildsim") return false;
    return Boolean(findKvValue("On bed / released") && findKvValue("Bed peel"));
  }

  function syncThermalReportUi() {
    const existing = document.getElementById(THERMAL_REPORT_GROUP_ID);
    if (!thermalResultReady()) {
      existing?.remove();
      return false;
    }

    const panel = document.querySelector(".panel");
    if (!panel) return false;
    const group = existing || document.createElement("div");
    if (!existing) {
      group.id = THERMAL_REPORT_GROUP_ID;
      group.className = "group";

      const heading = document.createElement("div");
      heading.className = "g-label";
      const title = document.createElement("span");
      title.textContent = "EnderSlicer report";
      heading.appendChild(title);

      const button = document.createElement("button");
      button.className = "primary";
      button.textContent = THERMAL_REPORT_LABEL;
      button.addEventListener("click", () => {
        try {
          const payload = JSON.stringify(collectThermalReport());
          if (!android.captureThermalReport(payload)) {
            throw new Error("Android rejected the thermal FEA report");
          }
        } catch (error) {
          console.error("EnderSlicerCura thermal FEA report capture failed", error);
          alert(`Unable to save the thermal FEA report: ${error?.message || error}`);
        }
      });

      const note = document.createElement("div");
      note.className = "dim small";
      note.textContent = THERMAL_REPORT_NOTE;

      group.appendChild(heading);
      group.appendChild(button);
      group.appendChild(note);
      panel.appendChild(group);
    }

    const button = group.querySelector("button");
    if (button) {
      const stale = staleThermalResult();
      button.disabled = stale;
      button.title = stale
        ? "Settings changed after the solve; run Build Simulation again"
        : "Save a validated native report tied to this model fingerprint";
    }
    const note = group.querySelector(".dim");
    if (note) sameText(note, THERMAL_REPORT_NOTE);
    return true;
  }

  /**
   * filaSim's browser build offers Orca/Bambu/Prusa 3MF projects plus a raw
   * modifier ZIP. EnderSlicer only consumes the validated modifier package, so
   * the Android host exposes that one working action with app-native wording.
   * Solid-topology export is a separate workflow and is intentionally untouched.
   */
  function simplifyModifierExportUi() {
    const buttons = Array.from(document.querySelectorAll("button"));
    const modifierButton = buttons.find((button) => {
      const label = normalizedText(button);
      return label === "Download modifier STLs (.zip)" || label === APPLY_SMART_INFILL_LABEL;
    });
    if (!modifierButton) return false;

    const group = modifierButton.closest(".group");
    if (!group) return false;

    for (const child of Array.from(group.children)) {
      if (child === modifierButton) continue;
      const label = normalizedText(child);
      const isSlicerChoice = child.classList.contains("seg");
      const isHandoffHeading = child.classList.contains("g-label");
      const isThreeMfProject =
        child.tagName === "BUTTON" && /^Download .+ project \(\.3mf\)$/i.test(label);
      if (isSlicerChoice || isHandoffHeading || isThreeMfProject) {
        child.hidden = true;
        child.setAttribute("aria-hidden", "true");
      }
    }

    if (normalizedText(modifierButton) !== APPLY_SMART_INFILL_LABEL) {
      modifierButton.textContent = APPLY_SMART_INFILL_LABEL;
    }
    modifierButton.classList.add("primary");
    modifierButton.title = "Use the generated infill regions in EnderSlicer";
    modifierButton.setAttribute("aria-label", APPLY_SMART_INFILL_LABEL);

    const note = modifierButton.nextElementSibling;
    if (note?.classList.contains("dim") && normalizedText(note) !== APPLY_SMART_INFILL_NOTE) {
      note.textContent = APPLY_SMART_INFILL_NOTE;
    }
    return true;
  }

  function installAndroidHostUi() {
    simplifyModifierExportUi();
    syncThermalReportUi();
    if (exportUiObserver) return;
    exportUiObserver = new MutationObserver(() => {
      simplifyModifierExportUi();
      syncThermalReportUi();
    });
    exportUiObserver.observe(document.documentElement, {
      childList: true,
      characterData: true,
      subtree: true,
    });
  }

  function bytesToBase64(bytes) {
    let binary = "";
    const step = 0x8000;
    for (let offset = 0; offset < bytes.length; offset += step) {
      const slice = bytes.subarray(offset, Math.min(bytes.length, offset + step));
      binary += String.fromCharCode.apply(null, slice);
    }
    return btoa(binary);
  }

  async function waitForModelInput() {
    for (let attempt = 0; attempt < 240; attempt += 1) {
      const input = Array.from(document.querySelectorAll('input[type="file"]'))
        .find((element) => String(element.accept || "").toLowerCase().includes(".stl"));
      if (input) return input;
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
    throw new Error("filaSim did not expose its model input");
  }

  async function loadModelFromAndroid() {
    if (modelLoadStarted) return;
    modelLoadStarted = true;
    try {
      const response = await fetch("/model/current.stl", { cache: "no-store" });
      if (!response.ok) throw new Error(`Unable to read Android STL (${response.status})`);
      const bytes = await response.arrayBuffer();
      const name = String(android.sourceFileName() || "model.stl");
      const file = new File([bytes], name, { type: "model/stl", lastModified: Date.now() });
      const input = await waitForModelInput();
      const transfer = new DataTransfer();
      transfer.items.add(file);
      input.files = transfer.files;
      input.dispatchEvent(new Event("change", { bubbles: true }));
    } catch (error) {
      modelLoadStarted = false;
      console.error("EnderSlicerCura filaSim model handoff failed", error);
      alert(`Unable to load the EnderSlicerCura model into filaSim: ${error?.message || error}`);
    }
  }

  async function streamBytes(bytes, begin, append, finish, cancel, beginArgs) {
    if (exporting) throw new Error("A filaSim export is already running");
    if (!(bytes instanceof Uint8Array)) bytes = new Uint8Array(bytes);
    if (!bytes.length) throw new Error("filaSim returned an empty export");
    exporting = true;
    try {
      if (!begin(...beginArgs, bytes.byteLength)) {
        throw new Error("Android rejected the filaSim export metadata or size");
      }
      for (let offset = 0; offset < bytes.length; offset += CHUNK_BYTES) {
        const chunk = bytes.subarray(offset, Math.min(bytes.length, offset + CHUNK_BYTES));
        if (!append(bytesToBase64(chunk))) {
          throw new Error("Android rejected a filaSim export chunk");
        }
        await new Promise((resolve) => setTimeout(resolve, 0));
      }
      if (!finish()) throw new Error("Android could not validate the filaSim export");
    } catch (error) {
      cancel();
      throw error;
    } finally {
      exporting = false;
    }
  }

  function normalizeModifierMetadata(metadata) {
    const mode = metadata?.mode === "binary" ? "binary" : "graded";
    const normalized = {
      ...metadata,
      metadataVersion: 2,
      // This pinned filaSim solver has one calibrated sparse pattern. Old or
      // restored WebView state can still expose null/retired values, so the
      // Android boundary writes the actual solver contract deterministically.
      basePattern: "cubic",
      gradedFullDensityPattern: "rectilinear",
      mode,
    };

    if (mode === "binary") {
      normalized.binarySolidPattern =
        metadata?.binarySolidPattern === "concentric" || metadata?.solidPattern === "concentric"
          ? "concentric"
          : "rectilinear";
    } else {
      // JSONObject.optString() turns an explicit JSON null into the literal
      // string "null". Omit this optional field for graded mode instead.
      delete normalized.binarySolidPattern;
      delete normalized.solidPattern;
    }
    return normalized;
  }

  async function captureModifierZip(bytes, metadata) {
    const normalized = normalizeModifierMetadata(metadata);
    const info = {
      ...normalized,
      sourceName: String(android.sourceFileName() || normalized?.sourceName || "model.stl"),
      sourceSha256: String(android.sourceSha256()),
      upstreamCommit: String(android.upstreamCommit()),
    };
    const filename = `${info.sourceName.replace(/\.(stl|3mf)$/i, "")}_smart_infill_modifiers.zip`;
    await streamBytes(
      bytes,
      (name, json, size) => android.beginModifierExport(name, size, json),
      (chunk) => android.appendModifierChunk(chunk),
      () => android.finishModifierExport(),
      () => android.cancelModifierExport(),
      [filename, JSON.stringify(info)],
    );
  }

  async function captureOptimizedShape(bytes) {
    const sourceName = String(android.sourceFileName() || "part.stl");
    const filename = `${sourceName.replace(/\.(stl|3mf)$/i, "")}_optimized.stl`;
    await streamBytes(
      bytes,
      (name, size) => android.beginShapeExport(name, size),
      (chunk) => android.appendShapeChunk(chunk),
      () => android.finishShapeExport(),
      () => android.cancelShapeExport(),
      [filename],
    );
  }

  window.EnderSlicerBridge = {
    loadModelFromAndroid,
    captureModifierZip,
    captureOptimizedShape,
  };

  const startAndroidHost = () => {
    installAndroidHostUi();
    setTimeout(loadModelFromAndroid, 250);
  };
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", startAndroidHost, { once: true });
  } else {
    startAndroidHost();
  }
})();
