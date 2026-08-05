/* Shared Android filaSim Worker ownership for Thermal Integrity and Annealing. */
(() => {
  "use strict";

  if (window.EnderSlicerFilaSimWorkerBroker) return;

  const ENGINE_OPS = new Set([
    "load", "loadMesh", "transform", "resegment", "useCadFaces", "setMaterial",
    "setResolution", "setVoxelSize", "setSnapWall", "setCompositeSkin",
    "setSmoothStress", "setMaterialStress", "setBcs", "voxelInfo", "solve",
    "optimize", "resmooth", "settingsSweep", "buildSim", "thermalIntegrity",
    "thermalIntegrityPreflight", "transformMatrix", "openProjectRestore",
    "setCancelBuffer", "setProgressBuffer",
  ]);
  const listeners = new Map([
    ["worker", new Set()],
    ["post", new Set()],
    ["message", new Set()],
    ["error", new Set()],
    ["messageerror", new Set()],
  ]);
  const pending = new Map();
  let activeWorker = null;
  let cancelArray = null;
  let progressCount = null;
  let progressData = null;
  let nextRequestId = -1_950_000_000;

  function emit(type, detail) {
    for (const listener of listeners.get(type) || []) {
      try { listener(detail); } catch (error) { console.error(`filaSim broker ${type} listener failed`, error); }
    }
  }

  function rejectPending(error) {
    for (const [id, request] of pending) {
      pending.delete(id);
      request.reject(error);
    }
  }

  function registerWorker(worker) {
    if (activeWorker === worker) return;
    activeWorker = worker;
    emit("worker", { worker });
  }

  function observeMessage(worker, event) {
    const message = event.data;
    const request = message && pending.get(message.id);
    if (request) {
      if (message.progress) {
        request.onProgress?.(message.data);
      } else {
        pending.delete(message.id);
        if (message.ok) request.resolve(message.data);
        else request.reject(new Error(message.error || `${request.op} failed`));
      }
    }
    emit("message", { worker, event, message });
  }

  function observeFailure(type, worker, event) {
    const message = type === "error"
      ? event?.message || "filaSim worker failed"
      : "filaSim worker returned an unreadable message";
    rejectPending(new Error(message));
    emit(type, { worker, event });
  }

  const NativeWorker = window.Worker;
  if (NativeWorker && !NativeWorker.__enderSlicerFilaSimBroker) {
    const BrokerWorker = new Proxy(NativeWorker, {
      construct(Target, args) {
        const worker = Reflect.construct(Target, args);
        const nativePost = worker.postMessage.bind(worker);
        worker.addEventListener("message", (event) => observeMessage(worker, event));
        worker.addEventListener("error", (event) => observeFailure("error", worker, event));
        worker.addEventListener("messageerror", (event) => observeFailure("messageerror", worker, event));

        worker.postMessage = function postMessage(message, transferOrOptions) {
          if (message && ENGINE_OPS.has(message.op)) registerWorker(worker);
          if (
            message?.op === "setCancelBuffer" &&
            typeof SharedArrayBuffer !== "undefined" &&
            message.buf instanceof SharedArrayBuffer
          ) {
            cancelArray = new Int32Array(message.buf);
          }
          if (
            message?.op === "setProgressBuffer" &&
            typeof SharedArrayBuffer !== "undefined" &&
            message.buf instanceof SharedArrayBuffer
          ) {
            progressCount = new Int32Array(message.buf, 0, 1);
            progressData = new Float32Array(message.buf, 4, (message.buf.byteLength - 4) >> 2);
          }
          let prevented = false;
          const detail = {
            worker,
            message,
            preventDefault() { prevented = true; },
          };
          emit("post", detail);
          if (prevented) return;
          if (arguments.length > 1) nativePost(message, transferOrOptions);
          else nativePost(message);
        };
        return worker;
      },
    });
    Object.defineProperty(BrokerWorker, "__enderSlicerFilaSimBroker", { value: true });
    window.Worker = BrokerWorker;
  }

  function request(op, payload = {}, onProgress = null) {
    const worker = activeWorker;
    if (!worker) {
      return Promise.reject(new Error("filaSim is not ready. Wait for the model to finish loading."));
    }
    const id = nextRequestId--;
    return new Promise((resolve, reject) => {
      pending.set(id, { op, resolve, reject, onProgress });
      try {
        worker.postMessage({ id, op, ...payload });
      } catch (error) {
        pending.delete(id);
        reject(error);
      }
    });
  }

  function on(type, listener) {
    const set = listeners.get(type);
    if (!set) throw new Error(`Unsupported filaSim broker event: ${type}`);
    set.add(listener);
    return () => set.delete(listener);
  }

  function cancelActive() {
    if (!cancelArray || typeof Atomics === "undefined") return false;
    Atomics.store(cancelArray, 0, 1);
    return true;
  }

  function terminateCurrent() {
    const worker = activeWorker;
    if (!worker) return false;
    rejectPending(new Error("filaSim worker was terminated"));
    worker.terminate();
    activeWorker = null;
    cancelArray = null;
    progressCount = null;
    progressData = null;
    return true;
  }

  window.EnderSlicerFilaSimWorkerBroker = Object.freeze({
    request,
    on,
    currentWorker: () => activeWorker,
    cancelArray: () => cancelArray,
    progressBuffers: () => ({ count: progressCount, data: progressData }),
    cancelActive,
    terminateCurrent,
  });
})();
