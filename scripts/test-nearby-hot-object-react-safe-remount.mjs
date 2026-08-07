#!/usr/bin/env node
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const [runtimePath] = process.argv.slice(2);
if (!runtimePath) throw new Error("Expected React-safe Nearby Hot Object runtime path");
const source = fs.readFileSync(runtimePath, "utf8");
for (const token of [
  "installUiWithoutCrossRootReparent",
  "scheduleReactSafeThermalInstall",
  "staleGroup.remove()",
  "requestAnimationFrame",
  "EnderSlicerNearbyReactSafeMountTestApi",
]) {
  assert.ok(source.includes(token), `React-safe remount runtime is missing ${token}`);
}

const mount = { id: "enderslicer-thermal-integrity-mount" };
const oldMount = { id: "old-thermal-mount" };
let staleConnected = true;
const staleGroup = {
  id: "enderslicer-thermal-integrity",
  parentElement: oldMount,
  remove() {
    staleConnected = false;
    this.parentElement = null;
  },
};
const frames = [];
let baseCalls = 0;
const context = {
  console,
  GROUP_ID: "enderslicer-thermal-integrity",
  document: {
    getElementById(id) {
      if (id === "enderslicer-thermal-integrity-mount") return mount;
      if (id === "enderslicer-thermal-integrity") return staleConnected ? staleGroup : null;
      return null;
    },
  },
  window: {
    requestAnimationFrame(callback) {
      frames.push(callback);
      return frames.length;
    },
    setTimeout(callback) {
      frames.push(callback);
      return frames.length;
    },
  },
  installUi() {
    baseCalls += 1;
    return true;
  },
  Object,
};
vm.createContext(context);
vm.runInContext(source, context, { filename: runtimePath });

const installedImmediately = context.installUi();
assert.equal(installedImmediately, false, "A stale cross-root panel must not be reparented synchronously");
assert.equal(baseCalls, 0, "The legacy installer must not run while its panel belongs to another React root");
assert.equal(frames.length, 1, "Cross-root recovery must be deferred by one frame");
assert.equal(staleConnected, true, "The stale panel must remain untouched until React has completed its current commit");

frames.shift()();
assert.equal(staleConnected, false, "The stale custom panel must be discarded after the deferred boundary");
assert.equal(baseCalls, 1, "A fresh panel must be installed once the current mount is stable");
assert.equal(frames.length, 0, "Recovery must not recurse into another remount frame");

console.log("Nearby Hot Object avoids React cross-root reparenting and deferred remount loops.");
