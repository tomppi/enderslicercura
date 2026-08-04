/*
 * EnderSlicerCura Thermal Integrity workspace shell.
 *
 * Keeps the service-temperature analysis on its own workflow tab and exposes
 * phase progress, elapsed time, voxel preflight details and safe cancellation.
 */
(() => {
  "use strict";

  const GROUP_ID = "enderslicer-thermal-integrity";
  const TAB_ID = "enderslicer-thermal-integrity-tab";
  const STYLE_ID = "enderslicer-thermal-integrity-workspace-style";
  const PREFLIGHT_ID_START = -1_500_000_000;
  const ENGINE_OPS = new Set([
    "load",
    "loadMesh",
    "setResolution",
    "setVoxelSize",
    "setBcs",
    "voxelInfo",
    "thermalIntegrity",
    "transformMatrix",
  ]);

  let engineWorker = null;
  let cancelFlag = null;
  let activeRequestId = null;
  let nextPreflightId = PREFLIGHT_ID_START;
  let thermalActive = false;
  let runActive = false;
  let runStartedAt = 0;
  let elapsedTimer = null;
  let observer = null;
  let progressState = { phase: "Idle", progress: 0, detail: "" };

  function installWorkerAccess() {
    const ExistingWorker = window.Worker;
    if (!ExistingWorker || ExistingWorker.__enderSlicerThermalWorkspace) return;

    const WrappedWorker = new Proxy(ExistingWorker, {
      construct(Target, args) {
        const worker = Reflect.construct(Target, args);
        const nativePost = worker.postMessage.bind(worker);

        worker.addEventListener("message", (event) => {
          const message = event.data;
          if (!message || message.id !== activeRequestId) return;
          if (message.progress) {
            handleProgressMessage(message.data);
            return;
          }
          finishRun(message.ok, message.error);
        });

        worker.postMessage = function postMessage(message, transferOrOptions) {
          if (
            message?.op === "setCancelBuffer" &&
            typeof SharedArrayBuffer !== "undefined" &&
            message.buf instanceof SharedArrayBuffer
          ) {
            cancelFlag = new Int32Array(message.buf);
          }
          if (message && Number.isSafeInteger(message.id) && ENGINE_OPS.has(message.op)) {
            engineWorker = worker;
          }
          if (message?.op === "thermalIntegrity" && Number.isSafeInteger(message.id)) {
            activeRequestId = message.id;
            beginRun(false);
            setProgress("Thermal solver started", 0.08, "The worker accepted the analysis request.");
          }
          if (arguments.length > 1) nativePost(message, transferOrOptions);
          else nativePost(message);
        };
        return worker;
      },
    });

    Object.defineProperty(WrappedWorker, "__enderSlicerThermalWorkspace", { value: true });
    window.Worker = WrappedWorker;
  }

  function installStyle() {
    if (document.getElementById(STYLE_ID)) return;
    const style = document.createElement("style");
    style.id = STYLE_ID;
    style.textContent = `
      #${GROUP_ID}[hidden] { display:none !important; }
      #${TAB_ID}.station { cursor:pointer; }
      #${GROUP_ID} .ti-actions { grid-template-columns:1.25fr .75fr 1fr !important; }
      #${GROUP_ID} .ti-progress-shell {
        margin-top:8px; padding:8px; border-radius:6px;
        background:rgba(255,255,255,.055);
      }
      #${GROUP_ID} .ti-progress-shell[hidden] { display:none !important; }
      #${GROUP_ID} .ti-progress-head {
        display:flex; justify-content:space-between; gap:8px; align-items:baseline;
      }
      #${GROUP_ID} .ti-progress-head b { font-size:12px; }
      #${GROUP_ID} .ti-progress-head span { font:11px/1.2 monospace; opacity:.8; }
      #${GROUP_ID} .ti-progress-bar { width:100%; height:10px; margin-top:6px; }
      #${GROUP_ID} .ti-progress-detail { min-height:15px; margin-top:4px; font-size:10px; opacity:.76; }
      #${GROUP_ID} .ti-progress-grid {
        display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:4px; margin-top:6px;
      }
      #${GROUP_ID} .ti-progress-chip {
        padding:4px 5px; border-radius:4px; background:rgba(255,255,255,.045);
        font-size:10px; overflow-wrap:anywhere;
      }
      @media (max-width:520px) {
        #${GROUP_ID} .ti-actions { grid-template-columns:1fr 1fr !important; }
        #${GROUP_ID} #ti-save { grid-column:1 / -1; }
      }
    `;
    document.head.appendChild(style);
  }

  function ensureProgressUi(group) {
    const actions = group.querySelector(".ti-actions");
    if (!actions) return;

    let cancel = document.getElementById("ti-cancel");
    if (!cancel) {
      cancel = document.createElement("button");
      cancel.id = "ti-cancel";
      cancel.type = "button";
      cancel.textContent = "Cancel";
      cancel.disabled = true;
      const save = document.getElementById("ti-save");
      actions.insertBefore(cancel, save || null);
      cancel.addEventListener("click", cancelRun);
    }

    if (!document.getElementById("ti-progress-shell")) {
      const shell = document.createElement("div");
      shell.id = "ti-progress-shell";
      shell.className = "ti-progress-shell";
      shell.hidden = true;
      shell.innerHTML = `
        <div class="ti-progress-head">
          <b id="ti-progress-phase">Preparing</b>
          <span id="ti-progress-time">0% · 0:00</span>
        </div>
        <progress id="ti-progress-bar" class="ti-progress-bar" max="100" value="0"></progress>
        <div id="ti-progress-detail" class="ti-progress-detail"></div>
        <div class="ti-progress-grid">
          <div id="ti-progress-grid" class="ti-progress-chip">Grid: waiting for preflight</div>
          <div id="ti-progress-cancel" class="ti-progress-chip">Cancel: checking availability</div>
        </div>
      `;
      const status = document.getElementById("ti-status");
      actions.parentElement?.insertBefore(shell, status || actions.nextSibling);
    }

    const run = document.getElementById("ti-run");
    if (run && run.dataset.tiProgressBound !== "1") {
      run.dataset.tiProgressBound = "1";
      run.addEventListener(
        "click",
        () => {
          beginRun(true);
          requestVoxelInfo();
        },
        true
      );
    }
    updateCancelAvailability();
  }

  function formatElapsed(milliseconds) {
    const total = Math.max(0, Math.floor(milliseconds / 1000));
    const minutes = Math.floor(total / 60);
    const seconds = String(total % 60).padStart(2, "0");
    return `${minutes}:${seconds}`;
  }

  function renderProgress() {
    const shell = document.getElementById("ti-progress-shell");
    const bar = document.getElementById("ti-progress-bar");
    const phase = document.getElementById("ti-progress-phase");
    const time = document.getElementById("ti-progress-time");
    const detail = document.getElementById("ti-progress-detail");
    if (!shell || !bar || !phase || !time || !detail) return;

    const progress = Math.max(0, Math.min(1, Number(progressState.progress) || 0));
    const elapsed = runStartedAt ? formatElapsed(performance.now() - runStartedAt) : "0:00";
    shell.hidden = !runActive && progress === 0;
    phase.textContent = progressState.phase || "Working";
    time.textContent = `${Math.round(progress * 100)}% · ${elapsed}`;
    bar.value = Math.round(progress * 100);
    detail.textContent = progressState.detail || "";
  }

  function setProgress(phase, progress, detail = "") {
    progressState = { phase, progress, detail };
    renderProgress();
  }

  function beginRun(fromButton) {
    ensureUi();
    if (!runActive) {
      runActive = true;
      runStartedAt = performance.now();
      if (elapsedTimer !== null) window.clearInterval(elapsedTimer);
      elapsedTimer = window.setInterval(renderProgress, 500);
    }
    const cancel = document.getElementById("ti-cancel");
    if (cancel) cancel.disabled = !cancelFlag;
    if (fromButton) {
      setProgress("Preflight", 0.01, "Reading the current voxel grid and capturing the model pose…");
    }
    updateCancelAvailability();
  }

  function finishRun(ok, error) {
    if (!runActive) return;
    const message = String(error || "");
    const cancelled = /cancelled/i.test(message);
    if (ok) {
      setProgress("Complete", 1, `Thermal Integrity finished in ${formatElapsed(performance.now() - runStartedAt)}.`);
    } else if (cancelled) {
      setProgress("Cancelled", progressState.progress, "The solver stopped at a cancellation checkpoint.");
    } else {
      setProgress("Failed", progressState.progress, message || "The worker returned an error.");
    }
    runActive = false;
    activeRequestId = null;
    if (elapsedTimer !== null) window.clearInterval(elapsedTimer);
    elapsedTimer = null;
    const cancel = document.getElementById("ti-cancel");
    if (cancel) cancel.disabled = true;
    renderProgress();
  }

  function handleProgressMessage(data) {
    let progress = data;
    if (typeof progress === "string") {
      try {
        progress = JSON.parse(progress);
      } catch (_) {
        progress = { phase: "Solving", detail: data };
      }
    }
    if (!progress || typeof progress !== "object") return;
    const value = Number(progress.progress);
    setProgress(
      String(progress.phase || "Solving"),
      Number.isFinite(value) ? value : progressState.progress,
      String(progress.detail || "")
    );
  }

  function updateCancelAvailability() {
    const chip = document.getElementById("ti-progress-cancel");
    if (!chip) return;
    chip.textContent = cancelFlag
      ? "Cancel: available at solver checkpoints"
      : "Cancel: unavailable until threaded WASM initializes";
  }

  function cancelRun() {
    if (!cancelFlag || typeof Atomics === "undefined") {
      setProgress("Cancellation unavailable", progressState.progress, "Threaded WASM has not exposed its shared cancel flag.");
      return;
    }
    Atomics.store(cancelFlag, 0, 1);
    const cancel = document.getElementById("ti-cancel");
    if (cancel) cancel.disabled = true;
    setProgress("Cancelling", progressState.progress, "Waiting for the active solver iteration to stop safely…");
  }

  function requestVoxelInfo() {
    const worker = engineWorker;
    if (!worker) return;
    const id = nextPreflightId--;
    const listener = (event) => {
      const message = event.data;
      if (!message || message.id !== id || message.progress) return;
      worker.removeEventListener("message", listener);
      if (!message.ok || !message.data) return;
      const voxel = message.data;
      const solid = Number(voxel.solid || 0);
      const text = `${solid.toLocaleString()} solid voxels · ${voxel.nx}×${voxel.ny}×${voxel.nz} · h=${Number(voxel.h).toFixed(3)} mm`;
      const chip = document.getElementById("ti-progress-grid");
      if (chip) chip.textContent = `Grid: ${text}`;
      if (progressState.phase === "Preflight") {
        setProgress("Preflight", progressState.progress, text);
      }
    };
    worker.addEventListener("message", listener);
    worker.postMessage({ id, op: "voxelInfo" });
  }

  function restorePanelChildren(panel, group) {
    for (const child of Array.from(panel.children)) {
      if (child === group) continue;
      if (Object.prototype.hasOwnProperty.call(child.dataset, "tiPreviousDisplay")) {
        child.style.display = child.dataset.tiPreviousDisplay;
        delete child.dataset.tiPreviousDisplay;
      }
    }
  }

  function syncWorkspace() {
    const panel = document.querySelector(".panel");
    const group = document.getElementById(GROUP_ID);
    const tab = document.getElementById(TAB_ID);
    if (!panel || !group || !tab) return;

    group.hidden = !thermalActive;
    tab.classList.toggle("active", thermalActive);
    if (thermalActive) {
      for (const station of document.querySelectorAll(".rail .station")) {
        if (station !== tab) station.classList.remove("active");
      }
      for (const child of Array.from(panel.children)) {
        if (child === group) continue;
        if (!Object.prototype.hasOwnProperty.call(child.dataset, "tiPreviousDisplay")) {
          child.dataset.tiPreviousDisplay = child.style.display || "";
        }
        child.style.display = "none";
      }
    } else {
      restorePanelChildren(panel, group);
    }
  }

  function leaveWorkspace() {
    if (!thermalActive) return;
    thermalActive = false;
    syncWorkspace();
  }

  function ensureTab() {
    const rail = document.querySelector(".rail");
    if (!rail) return false;
    let tab = document.getElementById(TAB_ID);
    if (!tab) {
      tab = document.createElement("button");
      tab.id = TAB_ID;
      tab.type = "button";
      tab.className = "station";
      tab.title = "Thermal Integrity — service-temperature heat flow and structural FEA";
      tab.innerHTML = '<span class="st-no">T</span><span class="st-name">Thermal</span>';
      tab.addEventListener("click", () => {
        thermalActive = true;
        syncWorkspace();
      });
      rail.appendChild(tab);
    }

    for (const station of rail.querySelectorAll(".station")) {
      if (station === tab || station.dataset.tiLeaveBound === "1") continue;
      station.dataset.tiLeaveBound = "1";
      station.addEventListener("click", leaveWorkspace);
    }
    return true;
  }

  function ensureUi() {
    installStyle();
    const group = document.getElementById(GROUP_ID);
    if (!group) return false;
    ensureProgressUi(group);
    ensureTab();
    syncWorkspace();
    return true;
  }

  installWorkerAccess();
  ensureUi();
  observer = new MutationObserver(() => ensureUi());
  observer.observe(document.documentElement, { childList: true, subtree: true });
})();
