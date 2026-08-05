#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const guardPath = new URL("../app/src/main/filasim/thermal-integrity-guard.js", import.meta.url);
const workspacePath = new URL("../app/src/main/filasim/thermal-integrity-workspace.js", import.meta.url);
const livePath = new URL("../app/src/main/filasim/thermal-integrity-live-progress.js", import.meta.url);
const source = fs.readFileSync(guardPath, "utf8");
const workspaceSource = fs.readFileSync(workspacePath, "utf8");
const liveSource = fs.readFileSync(livePath, "utf8");

class FakeElement {
  constructor(id = "", descendants = []) {
    this.id = id;
    this.descendants = descendants;
  }

  querySelector(selector) {
    if (!selector.startsWith("#")) return null;
    const id = selector.slice(1);
    return this.descendants.find((entry) => entry.id === id) || null;
  }

  closest() {
    return null;
  }
}

class FakeMutationObserver {
  static instances = [];

  constructor(callback) {
    this.callback = callback;
    FakeMutationObserver.instances.push(this);
  }

  observe() {}
  disconnect() {}
}

const brokerListeners = new Map();
const fakeBroker = {
  on(type, callback) {
    const listeners = brokerListeners.get(type) || [];
    listeners.push(callback);
    brokerListeners.set(type, listeners);
    return () => {};
  },
  cancelActive() { return true; },
  cancelArray() { return null; },
  progressBuffers() { return { count: null, data: null }; },
  request() { return Promise.reject(new Error("no worker in observer test")); },
  currentWorker() { return null; },
  terminateCurrent() { return false; },
};

const document = {
  documentElement: new FakeElement("root"),
  addEventListener() {},
  getElementById() {
    return null;
  },
};

const context = {
  console,
  queueMicrotask,
  Array,
  Atomics,
  Boolean,
  CustomEvent: class CustomEvent {
    constructor(type, init = {}) {
      this.type = type;
      this.detail = init.detail;
    }
  },
  Element: FakeElement,
  Error,
  Int32Array,
  MessageEvent: class MessageEvent {
    constructor(type, init = {}) {
      this.type = type;
      this.data = init.data;
    }
  },
  MutationObserver: FakeMutationObserver,
  Number,
  Promise,
  Proxy,
  Reflect,
  Set,
  SharedArrayBuffer,
  String,
  document,
};
context.window = {
  EnderSlicerFilaSimWorkerBroker: fakeBroker,
  MutationObserver: FakeMutationObserver,
  Worker: undefined,
  addEventListener() {},
  dispatchEvent() {},
};
context.window.window = context.window;
context.globalThis = context;

vm.runInNewContext(source, context, { filename: guardPath.pathname });

let calls = 0;
function ensureUi() {
  calls += 1;
}

const GuardedObserver = context.window.MutationObserver;
const observer = new GuardedObserver(ensureUi);
const native = FakeMutationObserver.instances.at(-1);
assert.ok(native, "guarded observer was not constructed");

native.callback([{ addedNodes: [{ nodeType: 3 }] }], observer);
assert.equal(calls, 0, "text-node progress updates must not retrigger Thermal UI installation");

native.callback([{ addedNodes: [new FakeElement("ti-progress-phase")] }], observer);
assert.equal(calls, 0, "ordinary Thermal progress elements must not retrigger installation");

native.callback([{ addedNodes: [new FakeElement("enderslicer-thermal-integrity")] }], observer);
assert.equal(calls, 1, "the Thermal group itself must trigger installation");

native.callback([
  {
    addedNodes: [
      new FakeElement("wrapper", [new FakeElement("enderslicer-thermal-integrity")]),
    ],
  },
], observer);
assert.equal(calls, 2, "a remounted subtree containing the Thermal group must trigger installation");

assert.match(workspaceSource, /UI_READY_EVENT = "enderslicer-thermal-integrity-ui-ready"/);
assert.match(workspaceSource, /function recordsAddThermalGroup/);
assert.match(workspaceSource, /function handleMountMutations/);
assert.match(
  workspaceSource,
  /new MutationObserver\(handleMountMutations\)/,
  "the one remaining document observer must use the mount-only callback",
);
assert.doesNotMatch(
  workspaceSource,
  /new MutationObserver\(ensureUi\)/,
  "workspace progress mutations must not directly call ensureUi",
);
assert.match(workspaceSource, /broker\.request\("voxelInfo"\)/);
assert.doesNotMatch(workspaceSource, /new Proxy\(ExistingWorker/);
assert.doesNotMatch(
  source,
  /new MutationObserver\(syncUi\)\.observe/,
  "the lifecycle guard must consume the shared mount event instead of observing the document",
);
assert.match(source, /window\.addEventListener\(UI_READY_EVENT, syncUi\)/);
assert.match(source, /broker\.on\("post", onWorkerPost\)/);
assert.doesNotMatch(source, /new Proxy\(ExistingWorker/);
assert.doesNotMatch(
  liveSource,
  /new MutationObserver\(ensureChip\)\.observe/,
  "live progress must consume the shared mount event instead of observing the document",
);
assert.match(liveSource, /window\.addEventListener\(UI_READY_EVENT, ensureChip\)/);
assert.match(liveSource, /broker\.progressBuffers\(\)/);
assert.doesNotMatch(liveSource, /new Proxy\(ExistingWorker/);

console.log("Thermal MutationObserver feedback-loop, shared broker and mount-event contracts passed");
