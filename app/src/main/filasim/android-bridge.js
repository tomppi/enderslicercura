/*
 * Android host adapter for the pinned filaSim web workspace.
 * The upstream application remains responsible for analysis and optimization;
 * this adapter only injects the displayed EnderSlicerCura STL and returns
 * graded/binary modifier ZIPs or a solid-topology replacement STL.
 */
(() => {
  "use strict";

  const android = window.EnderSlicerAndroid;
  if (!android) return;

  const CHUNK_BYTES = 256 * 1024;
  const APPLY_SMART_INFILL_LABEL = "Apply Smart Infill";
  const APPLY_SMART_INFILL_NOTE =
    "Transfers the optimized infill regions to EnderSlicer and returns to the model.";
  let modelLoadStarted = false;
  let exporting = false;
  let exportUiObserver = null;

  function normalizedText(element) {
    return String(element?.textContent || "").replace(/\s+/g, " ").trim();
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

  function installAndroidExportUi() {
    simplifyModifierExportUi();
    if (exportUiObserver) return;
    exportUiObserver = new MutationObserver(() => simplifyModifierExportUi());
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
    installAndroidExportUi();
    setTimeout(loadModelFromAndroid, 250);
  };
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", startAndroidHost, { once: true });
  } else {
    startAndroidHost();
  }
})();
