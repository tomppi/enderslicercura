/*
 * Thermal Integrity lifecycle and mutation safety guard.
 *
 * The shared filaSim broker owns Worker interception. This runtime keeps the
 * Thermal-specific overlap, invalidation and cancellation policy without
 * adding another Worker proxy or another per-worker listener stack.
 */
(() => {
  "use strict";

  const INVALIDATED_EVENT = "enderslicer-thermal-integrity-invalidated";
  const RUN_STATE_EVENT = "enderslicer-thermal-integrity-run-state";
  const UI_READY_EVENT = "enderslicer-thermal-integrity-ui-ready";
  const THERMAL_GROUP_ID = "enderslicer-thermal-integrity";
  const MUTATING_OPS = new Set([
    "load",
    "loadMesh",
    "transform",
    "resegment",
    "useCadFaces",
    "setMaterial",
    "setResolution",
    "setVoxelSize",
    "setSnapWall",
    "setCompositeSkin",
    "setSmoothStress",
    "setMaterialStress",
    "setBcs",
    "optimize",
    "resmooth",
    "settingsSweep",
    "buildSim",
    "openProjectRestore",
  ]);
  const broker = window.EnderSlicerFilaSimWorkerBroker;
  if (!broker) {
    console.error("Thermal Integrity worker broker is unavailable");
    return;
  }

  let activeRequestId = null;
  let invalidationEpoch = 0;

  function recordsAddThermalGroup(records) {
    return records.some((record) =>
      Array.from(record.addedNodes || []).some((node) =>
        node instanceof Element &&
        (node.id === THERMAL_GROUP_ID || Boolean(node.querySelector?.(`#${THERMAL_GROUP_ID}`)))
      )
    );
  }

  function installMutationObserverGuard() {
    const NativeMutationObserver = window.MutationObserver;
    if (!NativeMutationObserver || NativeMutationObserver.__enderSlicerThermalObserverGuard) return;

    const WrappedMutationObserver = new Proxy(NativeMutationObserver, {
      construct(Target, args) {
        const callback = args[0];
        const guardedCallback =
          typeof callback === "function" && callback.name === "ensureUi"
            ? (records, observer) => {
                if (recordsAddThermalGroup(records)) callback(records, observer);
              }
            : callback;
        return Reflect.construct(Target, [guardedCallback]);
      },
    });
    Object.defineProperty(WrappedMutationObserver, "__enderSlicerThermalObserverGuard", {
      value: true,
    });
    window.MutationObserver = WrappedMutationObserver;
  }

  function dispatchRunState(active, error = "") {
    window.dispatchEvent(
      new CustomEvent(RUN_STATE_EVENT, {
        detail: { active: Boolean(active), requestId: activeRequestId, error: String(error || "") },
      })
    );
    syncUi();
  }

  function invalidate(reason) {
    invalidationEpoch += 1;
    const message = String(reason || "Thermal Integrity inputs changed; run the analysis again.");
    window.dispatchEvent(
      new CustomEvent(INVALIDATED_EVENT, {
        detail: { epoch: invalidationEpoch, message },
      })
    );
    const results = document.getElementById("ti-results");
    if (results) results.classList.remove("ready");
    const save = document.getElementById("ti-save");
    if (save) save.disabled = true;
    const status = document.getElementById("ti-status");
    if (status && activeRequestId === null) {
      status.className = "ti-status dim";
      status.textContent = message;
    }
  }

  function cancelForMutation() {
    if (activeRequestId !== null) broker.cancelActive();
  }

  function rejectOverlappingRequest(worker, message) {
    const error = "A Thermal Integrity solve is already running. Cancel it or wait for completion.";
    queueMicrotask(() => {
      worker.dispatchEvent(
        new MessageEvent("message", {
          data: { id: message.id, ok: false, error },
        })
      );
    });
  }

  function onWorkerPost(detail) {
    const message = detail.message;
    if (message?.op === "thermalIntegrity" && Number.isSafeInteger(message.id)) {
      if (activeRequestId !== null) {
        detail.preventDefault();
        rejectOverlappingRequest(detail.worker, message);
        return;
      }
      activeRequestId = message.id;
      dispatchRunState(true);
    } else if (message && MUTATING_OPS.has(message.op)) {
      cancelForMutation();
      invalidate(`Thermal Integrity invalidated by filaSim operation: ${message.op}.`);
    }
  }

  function onWorkerMessage({ message }) {
    if (!message || message.id !== activeRequestId || message.progress) return;
    activeRequestId = null;
    dispatchRunState(false, message.ok ? "" : message.error);
  }

  function onWorkerError({ event }) {
    if (activeRequestId === null) return;
    const error = event?.message || "filaSim worker crashed during Thermal Integrity.";
    activeRequestId = null;
    dispatchRunState(false, error);
    invalidate(error);
  }

  function onWorkerMessageError() {
    if (activeRequestId === null) return;
    const error = "filaSim returned an unreadable Thermal Integrity message.";
    activeRequestId = null;
    dispatchRunState(false, error);
    invalidate(error);
  }

  function syncUi() {
    const run = document.getElementById("ti-run");
    if (run && activeRequestId !== null) run.disabled = true;
    const cancel = document.getElementById("ti-cancel");
    if (cancel && activeRequestId === null) cancel.disabled = true;
  }

  document.addEventListener(
    "click",
    (event) => {
      const target = event.target;
      if (!(target instanceof Element) || !target.closest("#ti-run")) return;
      if (activeRequestId === null) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      const status = document.getElementById("ti-status");
      if (status) {
        status.className = "ti-status ti-warning";
        status.textContent = "A Thermal Integrity solve is already running.";
      }
    },
    true
  );

  installMutationObserverGuard();
  broker.on("post", onWorkerPost);
  broker.on("message", onWorkerMessage);
  broker.on("error", onWorkerError);
  broker.on("messageerror", onWorkerMessageError);
  syncUi();
  window.addEventListener(UI_READY_EVENT, syncUi);
})();
