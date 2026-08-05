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
    this.observeTarget = null;
    this.observeOptions = null;
  }
  observe(target, options) {
    this.observeTarget = target;
    this.observeOptions = options;
  }
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
  const observedRoot = {};
  observer.observe(observedRoot, { childList: true, subtree: true });
  assert.equal(observer.observeTarget, observedRoot, `${label}: observer target must be preserved`);
  assert.equal(observer.observeOptions.childList, true, `${label}: child-list observation must be preserved`);
  assert.equal(observer.observeOptions.subtree, true, `${label}: subtree observation must be preserved`);
  assert.equal(observer.observeOptions.attributes, true, `${label}: reused panel id changes must be observed`);
  assert.ok(observer.observeOptions.attributeFilter.includes("id"), `${label}: id must be in attributeFilter`);

  const internalMutation = [{ addedNodes: [{ id: `${label}-status`, querySelector() { return null; } }] }];
  const idMutation = [{ type: "attributes", attributeName: "id", addedNodes: [] }];
  const reusedPanel = { id: mountId, querySelector() { return null; } };

  observer.callback(internalMutation, observer);
  assert.equal(installs, 0, `${label}: an inactive workspace must not install`);

  currentMount = reusedPanel;
  observer.callback(idMutation, observer);
  assert.equal(installs, 1, `${label}: opening through a reused panel id change must install once`);

  observer.callback(internalMutation, observer);
  assert.equal(installs, 1, `${label}: internal mutations must not recursively reinstall`);

  // React reconciles T and A onto the same div. Switching away changes its id,
  // and switching back restores the id without adding a new DOM node.
  currentMount = null;
  observer.callback(idMutation, observer);
  assert.equal(installs, 1, `${label}: switching away must only reset mount tracking`);

  currentMount = reusedPanel;
  observer.callback(idMutation, observer);
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

console.log("T/A reused-panel id-change remount regression passed");
