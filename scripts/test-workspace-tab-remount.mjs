#!/usr/bin/env node
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const [nearbyGuardPath, annealingGuardPath] = process.argv.slice(2);
if (!nearbyGuardPath || !annealingGuardPath) {
  throw new Error("Nearby and Anneal observer guard paths are required");
}

class FakeMutationObserver {
  constructor(callback) {
    this.callback = callback;
  }
  observe() {}
  disconnect() {}
}

function exerciseGuard(guardPath, mountId, label) {
  let currentMount = null;
  const document = {
    getElementById(id) {
      return id === mountId ? currentMount : null;
    },
  };
  const window = { MutationObserver: FakeMutationObserver };
  const context = vm.createContext({ window, document, console });
  vm.runInContext(fs.readFileSync(guardPath, "utf8"), context, { filename: guardPath });

  let installs = 0;
  function installUi() {
    installs += 1;
  }

  const observer = new window.MutationObserver(installUi);
  const internalMutation = [{ addedNodes: [{ id: `${label}-status`, querySelector() { return null; } }] }];
  const reusedPanel = { id: mountId, querySelector() { return null; } };

  observer.callback(internalMutation, observer);
  assert.equal(installs, 0, `${label}: an inactive workspace must not install`);

  currentMount = reusedPanel;
  observer.callback(internalMutation, observer);
  assert.equal(installs, 1, `${label}: opening the workspace must install once`);

  observer.callback(internalMutation, observer);
  assert.equal(installs, 1, `${label}: internal mutations must not recursively reinstall`);

  // React reconciles T and A onto the same div. Switching away changes its id,
  // and switching back restores the id without adding a new DOM node.
  currentMount = null;
  observer.callback(internalMutation, observer);
  assert.equal(installs, 1, `${label}: switching away must only reset mount tracking`);

  currentMount = reusedPanel;
  observer.callback(internalMutation, observer);
  assert.equal(installs, 2, `${label}: returning on the reused panel must reinstall once`);

  observer.callback(internalMutation, observer);
  assert.equal(installs, 2, `${label}: the remounted workspace must remain loop-free`);
}

exerciseGuard(
  nearbyGuardPath,
  "enderslicer-thermal-integrity-mount",
  "Nearby Hot Object T tab",
);
exerciseGuard(
  annealingGuardPath,
  "enderslicer-annealing-calculator-mount",
  "Annealing A tab",
);

console.log("T/A reused-panel remount regression passed");
