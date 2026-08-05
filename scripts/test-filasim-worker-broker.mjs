import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

class FakeWorker {
  constructor() {
    this.listeners = new Map();
    this.sent = [];
    this.terminated = false;
  }

  addEventListener(type, listener) {
    const values = this.listeners.get(type) || [];
    values.push(listener);
    this.listeners.set(type, values);
  }

  postMessage(message) {
    this.sent.push(message);
  }

  dispatchEvent(event) {
    for (const listener of this.listeners.get(event.type) || []) listener(event);
  }

  terminate() {
    this.terminated = true;
  }
}

const context = {
  Atomics,
  console,
  Error,
  Float32Array,
  Int32Array,
  Map,
  Number,
  Object,
  Promise,
  Proxy,
  Reflect,
  Set,
  SharedArrayBuffer,
  String,
  window: { Worker: FakeWorker },
};
context.globalThis = context;
vm.createContext(context);
const source = fs.readFileSync(
  new URL("../app/src/main/filasim/filasim-worker-broker.js", import.meta.url),
  "utf8",
);
new vm.Script(source, { filename: "filasim-worker-broker.js" }).runInContext(context);

const broker = context.window.EnderSlicerFilaSimWorkerBroker;
assert.ok(broker, "broker was not installed");
const worker = new context.window.Worker("engine.js");
worker.postMessage({ id: 1, op: "load" });
assert.equal(broker.currentWorker(), worker);

const cancelBuffer = new SharedArrayBuffer(4);
worker.postMessage({ op: "setCancelBuffer", buf: cancelBuffer });
assert.ok(broker.cancelArray() instanceof Int32Array);
assert.equal(broker.cancelActive(), true);
assert.equal(Atomics.load(new Int32Array(cancelBuffer), 0), 1);

const progressBuffer = new SharedArrayBuffer(4 + 8 * 4);
worker.postMessage({ op: "setProgressBuffer", buf: progressBuffer });
assert.equal(broker.progressBuffers().data.length, 8);

let postObserved = 0;
broker.on("post", ({ message, preventDefault }) => {
  if (message.op === "blocked") {
    postObserved += 1;
    preventDefault();
  }
});
const sentBefore = worker.sent.length;
worker.postMessage({ id: 7, op: "blocked" });
assert.equal(postObserved, 1);
assert.equal(worker.sent.length, sentBefore, "prevented posts must not reach the native worker");

const request = broker.request("voxelInfo");
const requestMessage = worker.sent.at(-1);
assert.equal(requestMessage.op, "voxelInfo");
worker.dispatchEvent({
  type: "message",
  data: { id: requestMessage.id, progress: true, data: { progress: 0.5 } },
});
worker.dispatchEvent({
  type: "message",
  data: { id: requestMessage.id, ok: true, data: { solid: 42 } },
});
assert.deepEqual(await request, { solid: 42 });

const failed = broker.request("transformMatrix");
const failedMessage = worker.sent.at(-1);
worker.dispatchEvent({
  type: "message",
  data: { id: failedMessage.id, ok: false, error: "pose unavailable" },
});
await assert.rejects(failed, /pose unavailable/);

assert.equal(broker.terminateCurrent(), true);
assert.equal(worker.terminated, true);
assert.equal(broker.currentWorker(), null);

console.log("Shared filaSim worker broker routing, cancellation, buffers and request ownership passed");
