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
  let modelLoadStarted = false;
  let exporting = false;

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

  async function captureModifierZip(bytes, metadata) {
    const info = {
      ...metadata,
      sourceName: String(android.sourceFileName() || metadata?.sourceName || "model.stl"),
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

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => setTimeout(loadModelFromAndroid, 250), { once: true });
  } else {
    setTimeout(loadModelFromAndroid, 250);
  }
})();
