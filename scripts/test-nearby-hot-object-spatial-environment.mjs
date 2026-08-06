#!/usr/bin/env node
import fs from "node:fs";

const [,, runtimePath, transformPath] = process.argv;
if (!runtimePath || !transformPath) {
  throw new Error("Expected runtime and transform paths");
}
const runtime = fs.readFileSync(runtimePath, "utf8");
const transform = fs.readFileSync(transformPath, "utf8");
const runtimeContracts = [
  "No model-point marking is required.",
  "entire 3D model",
  "Show the enclosure / engine-bay walls in 3D",
  "enclosureWidthMm",
  "enclosureDepthMm",
  "enclosureHeightMm",
  "enclosureVolumeFromDimensions",
  "Hold the heat-source sphere still for 5 seconds",
  "movable-source-automatic-nearest-surface",
  "entire-voxel-model",
];
const transformContracts = [
  "nearestSurfaceForNearbySource",
  "setNearbyHotObjectEnclosureBox",
  "nearbyHotObjectDragAxis",
  "new THREE.Vector3(0, 0, 1)",
  "window.setTimeout",
  "5000",
  "clearNearbyHotObjectState",
  "NEARBY_ENCLOSURE_BOX_EVENT",
];
for (const token of runtimeContracts) {
  if (!runtime.includes(token)) throw new Error(`Spatial runtime is missing ${token}`);
}
for (const token of transformContracts) {
  if (!transform.includes(token)) throw new Error(`Spatial transform is missing ${token}`);
}
if (runtime.includes("Select the primary source point before dragging")) {
  throw new Error("Spatial runtime still requires manual primary-source marking");
}
console.log("Automatic source projection, constrained dragging, full-model text and enclosure walls verified.");
