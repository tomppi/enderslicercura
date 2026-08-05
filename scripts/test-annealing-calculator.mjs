import fs from "node:fs";
import vm from "node:vm";
import assert from "node:assert/strict";

class FakeMutationObserver {
  constructor(callback) { this.callback = callback; }
  observe() {}
  disconnect() {}
}
class FakeWorker {}
const listeners = new Map();
const windowObject = {
  EnderSlicerAndroid: {}, Worker: FakeWorker, MutationObserver: FakeMutationObserver,
  addEventListener(name, callback) { listeners.set(name, callback); },
  dispatchEvent() {}, location: { reload() {} },
  setTimeout, clearTimeout, setInterval, clearInterval,
};
const documentObject = {
  documentElement: {}, head: { appendChild() {} },
  getElementById() { return null; },
  createElement() { return { id: "", textContent: "", style: {}, dataset: {}, appendChild() {} }; },
  querySelectorAll() { return []; },
};

globalThis.window = windowObject;
globalThis.document = documentObject;
globalThis.MutationObserver = FakeMutationObserver;
globalThis.CustomEvent = class CustomEvent { constructor(type, options = {}) { this.type = type; this.detail = options.detail; } };
globalThis.performance = { now: () => 0 };
globalThis.localStorage = { getItem() { return null; }, setItem() {} };

const materialSource = fs.readFileSync(
  new URL("../app/src/main/filasim/material-profile-source.js", import.meta.url),
  "utf8",
);
new vm.Script(materialSource, { filename: "material-profile-source.js" }).runInThisContext();

const materialApi = windowObject.EnderSlicerMaterialProfileTestApi;
assert.ok(materialApi, "material profile test API was not exposed");
assert.equal(materialApi.inferFamily("eSUN PLA+ Black"), "PLA+");
assert.equal(materialApi.inferFamily("Generic ASA"), "ASA");
const customSnapshot = {
  activeMaterialName: "Calibrated PLA",
  source: "filaSim live material library",
  print: null,
  materials: [{
    name: "Calibrated PLA", e0: 3725, nu: 0.34, density: 1.255,
    strength: 54, strengthZ: 31, shrink: 0.0037, shrinkZ: 0.0019,
    yieldStrength: 47, tLock: 62, cte: 101e-6,
  }],
};
const resolved = materialApi.resolveMaterialFromSnapshot(customSnapshot, "Calibrated PLA");
assert.equal(resolved.densityKgM3, 1255);
assert.equal(resolved.youngsModulusMpa, 3725);
assert.equal(resolved.poissonRatio, 0.34);
assert.equal(resolved.referenceStrengthMpa, 31);
assert.equal(resolved.alphaXyPerK, 101e-6);
assert.equal(resolved.alphaZPerK, 110e-6, "missing Z CTE must be complemented, not overwrite XY CTE");
assert.deepEqual(
  [resolved.conductivityXWmK, resolved.conductivityYWmK, resolved.conductivityZWmK],
  [0.18, 0.18, 0.13],
);

const observerGuard = fs.readFileSync(
  new URL("../app/src/main/filasim/annealing-calculator-observer-guard.js", import.meta.url),
  "utf8",
);
new vm.Script(observerGuard, { filename: "annealing-calculator-observer-guard.js" }).runInThisContext();
globalThis.MutationObserver = windowObject.MutationObserver;

const annealingObserverApi = windowObject.EnderSlicerAnnealingObserverTestApi;
assert.ok(annealingObserverApi, "Anneal observer test API was not exposed");
const annealingMount = { id: "enderslicer-annealing-calculator-mount" };
const annealingInternal = { id: "ac-material-source", querySelector() { return null; } };
assert.equal(
  annealingObserverApi.recordsAddAnnealingMount([{ addedNodes: [annealingMount] }]),
  true,
  "React adding the Anneal mount must trigger installation",
);
assert.equal(
  annealingObserverApi.recordsAddAnnealingMount([{ addedNodes: [annealingInternal] }]),
  false,
  "Anneal's own status/source mutations must not retrigger installUi",
);

const thermalAdapter = fs.readFileSync(
  new URL("../app/src/main/filasim/thermal-material-profile-adapter.js", import.meta.url),
  "utf8",
);
new vm.Script(thermalAdapter, { filename: "thermal-material-profile-adapter.js" }).runInThisContext();
const thermalObserverApi = windowObject.EnderSlicerThermalMaterialObserverTestApi;
assert.ok(thermalObserverApi, "Thermal material observer test API was not exposed");
assert.equal(
  thermalObserverApi.recordsAddThermalGroup([{ addedNodes: [{ id: "enderslicer-thermal-integrity" }] }]),
  true,
);
assert.equal(
  thermalObserverApi.recordsAddThermalGroup([{ addedNodes: [{ id: "ti-material-source" }] }]),
  false,
  "Thermal source-note mutations must not retrigger material synchronization",
);

const parts = [
  "annealing-calculator-01-core.js",
  "annealing-calculator-02-ui.js",
  "annealing-calculator-03-cycle.js",
  "annealing-calculator-03b-materials.js",
  "annealing-calculator-04-report.js",
];
const source = parts.map((name) => fs.readFileSync(new URL(`../app/src/main/filasim/${name}`, import.meta.url), "utf8").trimEnd()).join("\n") + "\n";
new vm.Script(source, { filename: "annealing-calculator.js" }).runInThisContext();
const api = windowObject.EnderSlicerAnnealingTestApi;
assert.ok(api, "test API was not exposed");
assert.equal(api.compensationScale(100, 98), 100 / 98);
assert.equal(api.formatDuration(3661), "1 h 1 min");
assert.equal(api.validateCycleInputs({ ovenTemperatureC: 80, initialTemperatureC: 23, targetToleranceC: 2, handlingTemperatureC: 45, roomTemperatureC: 23 }), true);
assert.throws(() => api.validateCycleInputs({ ovenTemperatureC: 80, initialTemperatureC: 79, targetToleranceC: 2, handlingTemperatureC: 45, roomTemperatureC: 23 }), /core target/);
assert.throws(() => api.validateCycleInputs({ ovenTemperatureC: 80, initialTemperatureC: 23, targetToleranceC: 2, handlingTemperatureC: 20, roomTemperatureC: 23 }), /handling target/);
console.log("Annealing calculator, observer guards and filaSim material-source contracts passed");
