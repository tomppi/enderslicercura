import fs from "node:fs";
import vm from "node:vm";
import assert from "node:assert/strict";

class FakeMutationObserver { observe() {} disconnect() {} }
class FakeWorker {}
const listeners = new Map();
const windowObject = {
  EnderSlicerAndroid: {}, Worker: FakeWorker,
  addEventListener(name, callback) { listeners.set(name, callback); },
  dispatchEvent() {}, location: { reload() {} },
  setTimeout, clearTimeout, setInterval, clearInterval,
};
const documentObject = {
  documentElement: {}, head: { appendChild() {} },
  getElementById() { return null; },
  createElement() { return { id: "", textContent: "", style: {}, appendChild() {} }; },
  querySelectorAll() { return []; },
};

globalThis.window = windowObject;
globalThis.document = documentObject;
globalThis.MutationObserver = FakeMutationObserver;
globalThis.CustomEvent = class CustomEvent { constructor(type, options = {}) { this.type = type; this.detail = options.detail; } };
globalThis.performance = { now: () => 0 };

const parts = [
  "annealing-calculator-01-core.js",
  "annealing-calculator-02-ui.js",
  "annealing-calculator-03-cycle.js",
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
console.log("Annealing calculator pure-function contracts passed");
