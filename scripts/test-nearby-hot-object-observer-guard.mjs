#!/usr/bin/env node
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const guardPath = process.argv[2];
if (!guardPath) throw new Error("observer guard path is required");

class FakeMutationObserver {
  constructor(callback) {
    this.callback = callback;
  }
  observe() {}
  disconnect() {}
}

const window = { MutationObserver: FakeMutationObserver };
const context = vm.createContext({ window, console });
vm.runInContext(fs.readFileSync(guardPath, "utf8"), context, { filename: guardPath });

let installs = 0;
function installUi() {
  installs += 1;
}

const observer = new window.MutationObserver(installUi);
const ordinaryNode = {
  id: "ti-status",
  querySelector() {
    return null;
  },
};
observer.callback([{ addedNodes: [ordinaryNode] }], observer);
assert.equal(installs, 0, "mutations inside the thermal panel must not reinstall the UI");

const thermalMount = {
  id: "enderslicer-thermal-integrity-mount",
  querySelector() {
    return null;
  },
};
observer.callback([{ addedNodes: [thermalMount] }], observer);
assert.equal(installs, 1, "adding the React thermal mount must install the UI once");

const parentContainingMount = {
  id: "react-panel-parent",
  querySelector(selector) {
    return selector === "#enderslicer-thermal-integrity-mount" ? thermalMount : null;
  },
};
observer.callback([{ addedNodes: [parentContainingMount] }], observer);
assert.equal(installs, 2, "adding a subtree containing the mount must install the UI once");

observer.callback([{ addedNodes: [ordinaryNode] }], observer);
assert.equal(installs, 2, "later status/result mutations must remain ignored");

console.log("Nearby Hot Object observer guard regression passed");
