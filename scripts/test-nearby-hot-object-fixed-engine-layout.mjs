#!/usr/bin/env node
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const runtimePath = process.argv[2];
if (!runtimePath) throw new Error("Fixed engine layout runtime path is required");
const source = fs.readFileSync(runtimePath, "utf8");

// The module references helpers (field, checkbox, value, finite, etc.) that
// are defined in earlier runtime parts. Provide the small subset needed to
// reach the module's test API and pure geometry helpers.
const helpers = `
  var _inputs = {};
  function field(id, label, initial, step) { return '<input id="ti-' + id + '">'; }
  function checkbox(id, label, on) { return '<input id="ti-' + id + '">'; }
  function value(id) { return (_inputs[id] !== undefined ? _inputs[id] : ""); }
  function checked(id) { return false; }
  function setValue(id, next) { _inputs[id] = String(next); }
  function finite(raw, label, min = -Infinity, max = Infinity) {
    const n = Number(raw);
    if (!Number.isFinite(n) || n < min || n > max) throw new Error(label);
    return n;
  }
  function format(raw, digits = 3) {
    const n = Number(raw);
    if (!Number.isFinite(n)) return "—";
    return n.toLocaleString(undefined, { maximumFractionDigits: digits });
  }
  function kpi(label, display) { return '<div class="ti-kpi"><b>' + display + '</b><span>' + label + '</span></div>'; }
  let createGroup = () => ({ querySelector: () => null });
  let collectOptions = () => ({ sourceTargetMm: [10, 20, 30], sourceNormal: [0, 0, 1], sourceTemperatureC: 300, source2TemperatureC: 600 });
  let combinedHeatSourceMarkers = () => null;
  let renderSelection = () => {};
  let bind = () => {};
  let restoreDraft = () => {};
  let renderResults = () => {};
  let collectReport = () => ({});
  const renderCombinedHeatSourceMarkers = () => {};
  const applySourceType = () => {};
  const syncSource2Ui = () => {};
  const syncEnvironmentUi = () => {};
  const invalidate = () => {};
  const HEAT_SOURCE_DRAG_MODE_EVENT = "enderslicer-nearby-hot-object-drag-mode";
  const HEAT_SOURCE_DRAG_EVENT = "enderslicer-nearby-hot-object-drag";
  const input = (id) => ({ className: "", textContent: "" });
  let selected = { point: [10, 20, 30], normal: [0, 0, 1] };
  const _listeners = {};
`;

const context = vm.createContext({ console });
// Provide a stub window used by module-level event registration and test API.
const window = {
  addEventListener: (type, fn) => {},
  removeEventListener: () => {},
  dispatchEvent: () => {},
};
context.window = window;
vm.runInContext(helpers, context);

// The module body uses `value("engineLayout")` etc. only at call time, so the
// module can be loaded standalone with the stub helpers above.
vm.runInContext(source, context, { filename: runtimePath });
const api = window.EnderSlicerEngineLayoutTestApi;
assert.ok(api, "Fixed engine layout test API missing");

// Layout presets are defined with rigid component offsets.
assert.ok(api.ENGINE_LAYOUT_PRESETS.engine_turbo_cluster);
assert.equal(api.ENGINE_LAYOUT_PRESETS.engine_turbo_cluster.label, "Engine + turbocharger cluster");
assert.equal(api.ENGINE_LAYOUT_PRESETS.engine_turbo_cluster.components.length, 2);
const block = api.ENGINE_LAYOUT_PRESETS.engine_turbo_cluster.components.find((c) => c.slot === "primary");
const turbo = api.ENGINE_LAYOUT_PRESETS.engine_turbo_cluster.components.find((c) => c.slot === "secondary");
assert.equal(block.label, "Engine block");
assert.deepEqual([...block.offsetMm], [0, 0, 0]);
assert.deepEqual([...turbo.offsetMm], [160, 60, 40]);

// With the anchor at origin and the selected point [10,20,30], the block target
// is the selected point and the turbo is offset from it.
const eq3 = (a, b) => { assert.ok(Array.isArray(a) && a.length === 3, "expected length-3 array"); assert.deepEqual([...a.map(Number)], b); };
context._inputs.engineLayout = "engine_turbo_cluster";
context._inputs.engineAnchorXMm = 0;
context._inputs.engineAnchorYMm = 0;
context._inputs.engineAnchorZMm = 0;
eq3(api.engineBlockTarget(), [10, 20, 30]);
eq3(api.engineComponentTarget("primary"), [10, 20, 30]);
eq3(api.engineComponentTarget("secondary"), [170, 80, 70]);

// Moving the engine anchor translates the whole assembly rigidly.
context._inputs.engineAnchorXMm = 5;
context._inputs.engineAnchorYMm = -3;
context._inputs.engineAnchorZMm = 2;
eq3(api.engineBlockTarget(), [15, 17, 32]);
eq3(api.engineComponentTarget("primary"), [15, 17, 32]);
eq3(api.engineComponentTarget("secondary"), [175, 77, 72]);

// The source marker carries the fixed gap and diameter from the component.
context._inputs.engineAnchorXMm = 0;
context._inputs.engineAnchorYMm = 0;
context._inputs.engineAnchorZMm = 0;
const blockMarker = api.engineSourceMarker("primary");
const turboMarker = api.engineSourceMarker("secondary");
eq3(blockMarker.target, [10, 20, 30]);
assert.equal(blockMarker.gapMm, 25);
assert.equal(blockMarker.diameterMm, 180);
eq3(turboMarker.target, [170, 80, 70]);
assert.equal(turboMarker.gapMm, 40);
assert.equal(turboMarker.diameterMm, 120);

// Rigid-cluster model contract.
for (const contract of [
  "ENGINE_LAYOUT_PRESETS",
  "engine_turbo_cluster",
  "Engine + turbocharger cluster",
  "Move whole engine",
  "Engine anchor X position (mm)",
  "fixed-engine-layout-rigid-cluster-v1",
  "engineAssemblyModel",
  "EnderSlicerEngineLayoutTestApi",
  "The block and turbo are positioned at fixed offsets from the engine anchor",
]) {
  assert.ok(source.includes(contract), `Missing fixed engine layout runtime contract ${contract}`);
}

console.log("Nearby Hot Object fixed engine layout tests passed");
