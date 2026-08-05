import fs from "node:fs";
import vm from "node:vm";
import assert from "node:assert/strict";

class FakeMutationObserver {
  observe() {}
  disconnect() {}
}
class FakeWorker {}
const listeners = new Map();
const windowObject = {
  EnderSlicerAndroid: {},
  Worker: FakeWorker,
  addEventListener(name, callback) { listeners.set(name, callback); },
  dispatchEvent() {},
  location: { reload() {} },
  setTimeout,
  clearTimeout,
  setInterval,
  clearInterval,
};
const documentObject = {
  documentElement: {},
  head: { appendChild() {} },
  getElementById() { return null; },
  createElement() { return { id: "", textContent: "", style: {}, appendChild() {} }; },
  querySelectorAll() { return []; },
};

globalThis.window = windowObject;
globalThis.document = documentObject;
globalThis.MutationObserver = FakeMutationObserver;
globalThis.CustomEvent = class CustomEvent { constructor(type, options = {}) { this.type = type; this.detail = options.detail; } };
globalThis.performance = { now: () => 0 };

const source = fs.readFileSync(new URL("../app/src/main/filasim/annealing-calculator.js", import.meta.url), "utf8");
vm.runInThisContext(source, { filename: "annealing-calculator.js" });
const api = windowObject.EnderSlicerAnnealingTestApi;
assert.ok(api, "test API was not exposed");
assert.equal(api.compensationScale(100, 98), 100 / 98);
assert.equal(api.formatDuration(3661), "1 h 1 min");
assert.equal(api.validateCycleInputs({
  ovenTemperatureC: 80,
  initialTemperatureC: 23,
  targetToleranceC: 2,
  handlingTemperatureC: 45,
  roomTemperatureC: 23,
}), true);
assert.throws(() => api.validateCycleInputs({
  ovenTemperatureC: 80,
  initialTemperatureC: 79,
  targetToleranceC: 2,
  handlingTemperatureC: 45,
  roomTemperatureC: 23,
}), /core target/);
assert.throws(() => api.validateCycleInputs({
  ovenTemperatureC: 80,
  initialTemperatureC: 23,
  targetToleranceC: 2,
  handlingTemperatureC: 20,
  roomTemperatureC: 23,
}), /handling target/);
console.log("Annealing calculator pure-function contracts passed");
