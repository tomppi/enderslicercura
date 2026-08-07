#!/usr/bin/env python3
"""Add automatic heat-source projection, constrained dragging and enclosure walls."""
from __future__ import annotations

import pathlib

MARKER = "EnderSlicer spatial heat source and enclosure viewer v2"
SOURCE_MARKER = ".enderslicer-nearby-hot-object-spatial-environment-viewer-v2"


def replace_once(path: pathlib.Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one {label} in {path}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_between(
    path: pathlib.Path,
    start: str,
    end: str,
    replacement: str,
    label: str,
) -> None:
    text = path.read_text(encoding="utf-8")
    if replacement in text:
        return
    start_index = text.find(start)
    end_index = text.find(end, start_index + len(start)) if start_index >= 0 else -1
    if start_index < 0 or end_index < 0:
        raise RuntimeError(f"Unable to locate {label} in {path}")
    path.write_text(text[:start_index] + replacement + text[end_index:], encoding="utf-8")


def apply(source_root: pathlib.Path) -> None:
    source_root = source_root.resolve()
    scene = source_root / "web/src/viewer/SceneManager.ts"
    viewer = source_root / "web/src/viewer/Viewer.tsx"
    for path in (scene, viewer):
        if not path.is_file():
            raise RuntimeError(f"Spatial environment viewer target is missing: {path}")

    replace_once(
        scene,
        """  private nearbyHotObjectDragRadiusMm = 0;
  private nearbyHotObjectMarker = new THREE.Group();
  private nearbyHotObjectMarkerDisposables: (THREE.BufferGeometry | THREE.Material)[] = [];

""",
        """  private nearbyHotObjectDragRadiusMm = 0;
  private nearbyHotObjectDragAxis: \"xy\" | \"z\" = \"xy\";
  private nearbyHotObjectLongPressTimer: number | null = null;
  private nearbyHotObjectDragStartCenter = new THREE.Vector3();
  private nearbyHotObjectDragStartPointer = { x: 0, y: 0 };
  private nearbyHotObjectLastPointer = { x: 0, y: 0 };
  private nearbyHotObjectCenters = new Map<number, THREE.Vector3>();
  private nearbyHotObjectAutoNotified = new Set<number>();
  private nearbyHotObjectMarker = new THREE.Group();
  private nearbyHotObjectMarkerDisposables: (THREE.BufferGeometry | THREE.Material)[] = [];
  private nearbyHotObjectEnclosure = new THREE.Group();
  private nearbyHotObjectEnclosureDisposables: (THREE.BufferGeometry | THREE.Material)[] = [];

""",
        "spatial heat-source state",
    )

    marker_method = '''  private nearestSurfaceForNearbySource(center: THREE.Vector3): {
    point: THREE.Vector3;
    normal: THREE.Vector3;
  } | null {
    if (!this.mesh) return null;
    const geometry = this.mesh.geometry;
    const position = geometry.getAttribute("position") as THREE.BufferAttribute;
    if (!position || position.count < 3) return null;
    const index = geometry.getIndex();
    const triangleCount = index ? Math.floor(index.count / 3) : Math.floor(position.count / 3);
    if (triangleCount <= 0) return null;
    this.mesh.updateMatrixWorld(true);
    const a = new THREE.Vector3();
    const b = new THREE.Vector3();
    const c = new THREE.Vector3();
    const candidate = new THREE.Vector3();
    const bestPoint = new THREE.Vector3();
    const triangle = new THREE.Triangle();
    let bestDistance2 = Number.POSITIVE_INFINITY;
    for (let triangleIndex = 0; triangleIndex < triangleCount; triangleIndex += 1) {
      const offset = triangleIndex * 3;
      const ia = index ? index.getX(offset) : offset;
      const ib = index ? index.getX(offset + 1) : offset + 1;
      const ic = index ? index.getX(offset + 2) : offset + 2;
      a.fromBufferAttribute(position, ia).applyMatrix4(this.mesh.matrixWorld);
      b.fromBufferAttribute(position, ib).applyMatrix4(this.mesh.matrixWorld);
      c.fromBufferAttribute(position, ic).applyMatrix4(this.mesh.matrixWorld);
      triangle.set(a, b, c).closestPointToPoint(center, candidate);
      const distance2 = candidate.distanceToSquared(center);
      if (distance2 < bestDistance2) {
        bestDistance2 = distance2;
        bestPoint.copy(candidate);
      }
    }
    if (!Number.isFinite(bestDistance2)) return null;
    const normal = center.clone().sub(bestPoint);
    if (normal.lengthSq() <= 1e-12) normal.set(1, 0, 0);
    else normal.normalize();
    return { point: bestPoint, normal };
  }

  private defaultNearbySourceCenter(sourceId: number, radius: number, gap: number): THREE.Vector3 | null {
    if (!this.partBbox) return null;
    const [lx, ly, lz, hx, hy, hz] = this.partBbox;
    const center = new THREE.Vector3((lx + hx) * 0.5, (ly + hy) * 0.5, (lz + hz) * 0.5);
    if (sourceId === 2) center.y = ly - gap - radius;
    else center.x = hx + gap + radius;
    return center;
  }

  setNearbyHotObjectMarker(detail:
    | {
        target?: [number, number, number];
        normal?: [number, number, number];
        gapMm: number;
        diameterMm: number;
        sourceId?: number;
        label?: string;
        autoPlace?: boolean;
      }
    | {
        markers: Array<{
          target?: [number, number, number];
          normal?: [number, number, number];
          gapMm: number;
          diameterMm: number;
          sourceId?: number;
          label?: string;
          autoPlace?: boolean;
        }>;
      }
    | null
  ) {
    for (const child of [...this.nearbyHotObjectMarker.children]) {
      this.nearbyHotObjectMarker.remove(child);
    }
    for (const disposable of this.nearbyHotObjectMarkerDisposables) disposable.dispose();
    this.nearbyHotObjectMarkerDisposables = [];
    if (!detail) return;
    if (!this.nearbyHotObjectMarker.parent) this.scene.add(this.nearbyHotObjectMarker);
    const markers = Array.isArray((detail as any).markers)
      ? (detail as any).markers
      : [detail as any];
    markers.forEach((entry: any, index: number) => {
      const sourceId = Number(entry.sourceId ?? index + 1);
      const radius = Math.max(0.05, Number(entry.diameterMm) * 0.5);
      const requestedGap = Math.max(0, Number(entry.gapMm));
      let center: THREE.Vector3 | null = null;
      if (Array.isArray(entry.target) && Array.isArray(entry.normal)) {
        const target = new THREE.Vector3(...entry.target);
        const normal = new THREE.Vector3(...entry.normal);
        if (normal.lengthSq() > 1e-12) {
          normal.normalize();
          center = target.addScaledVector(normal, requestedGap + radius);
        }
      }
      if (!center) center = this.nearbyHotObjectCenters.get(sourceId)?.clone() ?? null;
      if (!center) center = this.defaultNearbySourceCenter(sourceId, radius, requestedGap);
      if (!center) return;
      this.nearbyHotObjectCenters.set(sourceId, center.clone());
      const projected = this.nearestSurfaceForNearbySource(center);
      if (!projected) return;
      const target = projected.point;
      const normal = projected.normal;
      const distance = center.distanceTo(target);
      const gap = Math.max(0, distance - radius);
      const sourceSurface = center.clone().addScaledVector(normal, -radius);
      const primary = sourceId !== 2;
      const pointColor = primary ? 0xffd166 : 0xb5d8ff;
      const guideColor = primary ? 0xff8c5c : 0x64a8ff;
      const sourceColor = primary ? 0xff5a36 : 0x3978ef;
      const emissiveColor = primary ? 0xff2d16 : 0x173e9b;
      const pointRadius = Math.max(this.bboxDiag * 0.009, 0.35);

      const pointGeo = new THREE.SphereGeometry(pointRadius, 18, 12);
      const pointMat = new THREE.MeshBasicMaterial({ color: pointColor, depthTest: false });
      const point = new THREE.Mesh(pointGeo, pointMat);
      point.position.copy(target);
      point.renderOrder = 1002;
      point.userData = { nearbyMarkerType: "target", nearbySourceId: sourceId };

      const lineGeo = new THREE.BufferGeometry().setFromPoints([target, center]);
      const lineMat = new THREE.LineBasicMaterial({ color: guideColor, depthTest: false });
      const line = new THREE.Line(lineGeo, lineMat);
      line.renderOrder = 1001;
      line.userData = { nearbyMarkerType: "gap-line", nearbySourceId: sourceId };

      const gapLength = Math.max(0.05, target.distanceTo(sourceSurface));
      const gapRadius = Math.max(pointRadius * 0.65, Math.min(radius * 0.18, this.bboxDiag * 0.025));
      const gapGeo = new THREE.CylinderGeometry(gapRadius, gapRadius, 1, 18, 1, true);
      const gapMat = new THREE.MeshBasicMaterial({
        color: guideColor,
        transparent: true,
        opacity: 0.2,
        depthWrite: false,
        side: THREE.DoubleSide,
      });
      const gapGuide = new THREE.Mesh(gapGeo, gapMat);
      gapGuide.position.copy(target).add(sourceSurface).multiplyScalar(0.5);
      gapGuide.quaternion.setFromUnitVectors(
        new THREE.Vector3(0, 1, 0),
        sourceSurface.clone().sub(target).normalize(),
      );
      gapGuide.scale.y = gapLength;
      gapGuide.renderOrder = 1000;
      gapGuide.userData = { nearbyMarkerType: "gap-volume", nearbySourceId: sourceId };

      const sourceGeo = new THREE.SphereGeometry(radius, 28, 18);
      const sourceMat = new THREE.MeshStandardMaterial({
        color: sourceColor,
        emissive: emissiveColor,
        emissiveIntensity: 0.5,
        transparent: true,
        opacity: 0.42,
        wireframe: true,
        depthWrite: false,
      });
      const sourceMesh = new THREE.Mesh(sourceGeo, sourceMat);
      sourceMesh.position.copy(center);
      sourceMesh.renderOrder = 1003;
      sourceMesh.userData = {
        nearbyMarkerType: "source",
        nearbySourceId: sourceId,
        nearbyTarget: [target.x, target.y, target.z],
        nearbyNormal: [normal.x, normal.y, normal.z],
        nearbyGapMm: gap,
        nearbyDiameterMm: Number(entry.diameterMm),
      };

      const group = new THREE.Group();
      group.userData = { nearbySourceId: sourceId };
      group.add(point, line, gapGuide, sourceMesh);
      this.nearbyHotObjectMarker.add(group);
      this.nearbyHotObjectMarkerDisposables.push(
        pointGeo, pointMat, lineGeo, lineMat, gapGeo, gapMat, sourceGeo, sourceMat,
      );

      const hadExplicitProjection = Array.isArray(entry.target) && Array.isArray(entry.normal);
      if (!hadExplicitProjection && !this.nearbyHotObjectAutoNotified.has(sourceId)) {
        this.nearbyHotObjectAutoNotified.add(sourceId);
        queueMicrotask(() => this.callbacks.onNearbyHotObjectDrag?.(
          sourceId,
          [target.x, target.y, target.z],
          [normal.x, normal.y, normal.z],
          gap,
        ));
      }
    });
  }

  setNearbyHotObjectEnclosureBox(detail: {
    visible?: boolean;
    widthMm: number;
    depthMm: number;
    heightMm: number;
    offsetXmm?: number;
    offsetYmm?: number;
    offsetZmm?: number;
  } | null) {
    for (const child of [...this.nearbyHotObjectEnclosure.children]) {
      this.nearbyHotObjectEnclosure.remove(child);
    }
    for (const disposable of this.nearbyHotObjectEnclosureDisposables) disposable.dispose();
    this.nearbyHotObjectEnclosureDisposables = [];
    if (!detail || detail.visible === false || !this.partBbox) return;
    const width = Math.max(0.1, Number(detail.widthMm));
    const depth = Math.max(0.1, Number(detail.depthMm));
    const height = Math.max(0.1, Number(detail.heightMm));
    if (![width, depth, height].every(Number.isFinite)) return;
    const [lx, ly, lz, hx, hy, hz] = this.partBbox;
    const center = new THREE.Vector3(
      (lx + hx) * 0.5 + Number(detail.offsetXmm || 0),
      (ly + hy) * 0.5 + Number(detail.offsetYmm || 0),
      (lz + hz) * 0.5 + Number(detail.offsetZmm || 0),
    );
    const boxGeo = new THREE.BoxGeometry(width, depth, height);
    const wallMat = new THREE.MeshBasicMaterial({
      color: 0x74a7d8,
      transparent: true,
      opacity: 0.055,
      depthWrite: false,
      side: THREE.DoubleSide,
    });
    const walls = new THREE.Mesh(boxGeo, wallMat);
    walls.position.copy(center);
    walls.renderOrder = 50;
    const edgeGeo = new THREE.EdgesGeometry(boxGeo);
    const edgeMat = new THREE.LineBasicMaterial({
      color: 0x9ac7ef,
      transparent: true,
      opacity: 0.78,
      depthTest: false,
    });
    const edges = new THREE.LineSegments(edgeGeo, edgeMat);
    edges.position.copy(center);
    edges.renderOrder = 51;
    this.nearbyHotObjectEnclosure.add(walls, edges);
    if (!this.nearbyHotObjectEnclosure.parent) this.scene.add(this.nearbyHotObjectEnclosure);
    this.nearbyHotObjectEnclosureDisposables.push(boxGeo, wallMat, edgeGeo, edgeMat);
  }

  clearNearbyHotObjectState() {
    this.finishNearbyHotObjectDrag();
    this.nearbyHotObjectCenters.clear();
    this.nearbyHotObjectAutoNotified.clear();
    this.setNearbyHotObjectMarker(null);
    this.setNearbyHotObjectEnclosureBox(null);
  }

'''
    replace_between(
        scene,
        "  setNearbyHotObjectMarker(detail:",
        "  setNearbyHotObjectDragMode(sourceId: number) {",
        marker_method,
        "automatic marker projection and enclosure renderer",
    )

    replace_once(
        scene,
        """  private finishNearbyHotObjectDrag() {
    this.nearbyHotObjectDragSourceId = null;
    this.nearbyHotObjectDragPointerId = null;
    if (this.controls) this.controls.enabled = true;
    if (this.canvas) this.canvas.style.cursor = this.nearbyHotObjectDragMode > 0 ? "grab" : "";
  }
""",
        """  private finishNearbyHotObjectDrag() {
    if (this.nearbyHotObjectLongPressTimer !== null) {
      window.clearTimeout(this.nearbyHotObjectLongPressTimer);
      this.nearbyHotObjectLongPressTimer = null;
    }
    this.nearbyHotObjectDragSourceId = null;
    this.nearbyHotObjectDragPointerId = null;
    this.nearbyHotObjectDragAxis = "xy";
    if (this.controls) this.controls.enabled = true;
    if (this.canvas) this.canvas.style.cursor = this.nearbyHotObjectDragMode > 0 ? "grab" : "";
  }

  private updateNearbyHotObjectDragVisual(sourceId: number, center: THREE.Vector3) {
    let source: THREE.Object3D | null = null;
    this.nearbyHotObjectMarker.traverse((object) => {
      if (!source && object.userData?.nearbyMarkerType === "source"
          && Number(object.userData.nearbySourceId) === sourceId) source = object;
    });
    if (!source) return;
    const data = source.userData;
    if (!Array.isArray(data.nearbyTarget)) return;
    const target = new THREE.Vector3(...data.nearbyTarget);
    const radius = Math.max(0.05, Number(data.nearbyDiameterMm) * 0.5);
    const direction = center.clone().sub(target);
    if (direction.lengthSq() <= 1e-12) return;
    direction.normalize();
    const sourceSurface = center.clone().addScaledVector(direction, -radius);
    source.position.copy(center);
    const group = source.parent;
    group?.traverse((object) => {
      if (Number(object.userData?.nearbySourceId) !== sourceId) return;
      if (object.userData?.nearbyMarkerType === "gap-line" && object instanceof THREE.Line) {
        object.geometry.setFromPoints([target, center]);
      } else if (object.userData?.nearbyMarkerType === "gap-volume" && object instanceof THREE.Mesh) {
        const gapLength = Math.max(0.05, target.distanceTo(sourceSurface));
        object.position.copy(target).add(sourceSurface).multiplyScalar(0.5);
        object.quaternion.setFromUnitVectors(
          new THREE.Vector3(0, 1, 0),
          sourceSurface.clone().sub(target).normalize(),
        );
        object.scale.y = gapLength;
      }
    });
  }
""",
        "constrained drag cleanup and preview",
    )

    drag_handlers = '''  private onNearbyHotObjectPointerDown = (ev: PointerEvent) => {
    if (!this.mesh || ev.button !== 0) return;
    if (this.nearbyHotObjectDragMode > 0) {
      const markerHit = this.nearbyHotObjectMarkerHit(ev);
      if (markerHit) {
        const data = markerHit.object.userData;
        const sourceId = Number(data.nearbySourceId);
        const diameterMm = Number(data.nearbyDiameterMm);
        if (sourceId === this.nearbyHotObjectDragMode
            && Number.isFinite(diameterMm) && diameterMm > 0) {
          const center = new THREE.Vector3();
          markerHit.object.getWorldPosition(center);
          this.nearbyHotObjectDragSourceId = sourceId;
          this.nearbyHotObjectDragPointerId = ev.pointerId;
          this.nearbyHotObjectDragRadiusMm = diameterMm * 0.5;
          this.nearbyHotObjectDragAxis = "xy";
          this.nearbyHotObjectDragStartCenter.copy(center);
          this.nearbyHotObjectDragStartPointer = { x: ev.clientX, y: ev.clientY };
          this.nearbyHotObjectLastPointer = { x: ev.clientX, y: ev.clientY };
          this.nearbyHotObjectDragPlane.setFromNormalAndCoplanarPoint(
            new THREE.Vector3(0, 0, 1),
            center,
          );
          this.controls.enabled = false;
          this.canvas.style.cursor = "grabbing";
          this.nearbyHotObjectLongPressTimer = window.setTimeout(() => {
            if (this.nearbyHotObjectDragSourceId !== sourceId
                || this.nearbyHotObjectDragPointerId !== ev.pointerId) return;
            this.nearbyHotObjectDragAxis = "z";
            const current = this.nearbyHotObjectCenters.get(sourceId) ?? center;
            this.nearbyHotObjectDragStartCenter.copy(current);
            this.nearbyHotObjectDragStartPointer = { ...this.nearbyHotObjectLastPointer };
            this.canvas.style.cursor = "ns-resize";
          }, 5000);
          ev.preventDefault();
          ev.stopImmediatePropagation();
          return;
        }
      }
    }
    if (!this.nearbyHotObjectPickMode) return;
    const hit = this.rayTri(ev);
    const normal = hit && hit.faceIndex != null ? this.triNormalOf(hit.faceIndex) : null;
    if (!hit || !normal) return;
    this.nearbyHotObjectPickMode = false;
    this.canvas.style.cursor = "";
    ev.preventDefault();
    ev.stopImmediatePropagation();
    this.callbacks.onNearbyHotObjectPick?.(
      [hit.point.x, hit.point.y, hit.point.z],
      [normal.x, normal.y, normal.z]
    );
  };

  private onNearbyHotObjectDragMove = (ev: PointerEvent) => {
    const sourceId = this.nearbyHotObjectDragSourceId;
    if (sourceId === null || this.nearbyHotObjectDragPointerId !== ev.pointerId) return;
    this.nearbyHotObjectLastPointer = { x: ev.clientX, y: ev.clientY };
    const moved = Math.hypot(
      ev.clientX - this.nearbyHotObjectDragStartPointer.x,
      ev.clientY - this.nearbyHotObjectDragStartPointer.y,
    );
    if (this.nearbyHotObjectDragAxis === "xy" && moved > 6
        && this.nearbyHotObjectLongPressTimer !== null) {
      window.clearTimeout(this.nearbyHotObjectLongPressTimer);
      this.nearbyHotObjectLongPressTimer = null;
    }
    const center = this.nearbyHotObjectDragStartCenter.clone();
    if (this.nearbyHotObjectDragAxis === "z") {
      const rect = this.renderer.domElement.getBoundingClientRect();
      const worldPerPixel = (this.camera.top - this.camera.bottom)
        / Math.max(1e-9, this.camera.zoom * rect.height);
      center.z -= (ev.clientY - this.nearbyHotObjectDragStartPointer.y) * worldPerPixel;
    } else {
      const rect = this.renderer.domElement.getBoundingClientRect();
      this.pointer.x = ((ev.clientX - rect.left) / rect.width) * 2 - 1;
      this.pointer.y = -((ev.clientY - rect.top) / rect.height) * 2 + 1;
      this.raycaster.setFromCamera(this.pointer, this.camera);
      if (!this.raycaster.ray.intersectPlane(this.nearbyHotObjectDragPlane, center)) {
        const worldPerPixel = (this.camera.top - this.camera.bottom)
          / Math.max(1e-9, this.camera.zoom * rect.height);
        const right = new THREE.Vector3().setFromMatrixColumn(this.camera.matrixWorld, 0);
        const up = new THREE.Vector3().setFromMatrixColumn(this.camera.matrixWorld, 1);
        right.z = 0;
        up.z = 0;
        if (right.lengthSq() > 1e-12) right.normalize();
        if (up.lengthSq() > 1e-12) up.normalize();
        center.copy(this.nearbyHotObjectDragStartCenter)
          .addScaledVector(right, (ev.clientX - this.nearbyHotObjectDragStartPointer.x) * worldPerPixel)
          .addScaledVector(up, -(ev.clientY - this.nearbyHotObjectDragStartPointer.y) * worldPerPixel);
        center.z = this.nearbyHotObjectDragStartCenter.z;
      }
    }
    this.nearbyHotObjectCenters.set(sourceId, center.clone());
    this.updateNearbyHotObjectDragVisual(sourceId, center);
    ev.preventDefault();
    ev.stopImmediatePropagation();
  };

  private onNearbyHotObjectDragEnd = (ev: PointerEvent) => {
    const sourceId = this.nearbyHotObjectDragSourceId;
    if (sourceId === null || this.nearbyHotObjectDragPointerId !== ev.pointerId) return;
    const center = this.nearbyHotObjectCenters.get(sourceId)?.clone();
    const radius = this.nearbyHotObjectDragRadiusMm;
    if (center) {
      const projected = this.nearestSurfaceForNearbySource(center);
      if (projected) {
        const gapMm = Math.max(0, center.distanceTo(projected.point) - radius);
        this.callbacks.onNearbyHotObjectDrag?.(
          sourceId,
          [projected.point.x, projected.point.y, projected.point.z],
          [projected.normal.x, projected.normal.y, projected.normal.z],
          gapMm,
        );
      }
    }
    this.finishNearbyHotObjectDrag();
    ev.preventDefault();
    ev.stopImmediatePropagation();
  };

'''
    replace_between(
        scene,
        "  private onNearbyHotObjectPointerDown = (ev: PointerEvent) => {",
        "    canvas.addEventListener(\"pointerdown\", this.onNearbyHotObjectPointerDown, true);",
        drag_handlers,
        "XY/Z constrained heat-source drag handlers",
    )

    replace_once(
        scene,
        """    this.callouts.dispose();
    this.canvas?.removeEventListener("wheel", this.onWheel);
""",
        """    this.setNearbyHotObjectMarker(null);
    this.setNearbyHotObjectEnclosureBox(null);
    this.callouts.dispose();
    this.canvas?.removeEventListener("wheel", this.onWheel);
""",
        "spatial viewer disposal",
    )

    replace_once(
        viewer,
        """const NEARBY_DRAG_EVENT = "enderslicer-nearby-hot-object-drag";
const NEARBY_CLEAR_EVENT = "enderslicer-nearby-hot-object-clear";
""",
        """const NEARBY_DRAG_EVENT = "enderslicer-nearby-hot-object-drag";
const NEARBY_ENCLOSURE_BOX_EVENT = "enderslicer-nearby-hot-object-enclosure-box";
const NEARBY_CLEAR_EVENT = "enderslicer-nearby-hot-object-clear";
""",
        "enclosure box event constant",
    )
    replace_once(
        viewer,
        """    const onNearbyDragMode = (event: Event) => {
      scene.setNearbyHotObjectDragMode(Number((event as CustomEvent).detail ?? 0));
    };
    const onNearbyClear = () => {
      scene.setNearbyHotObjectPickMode(false);
      scene.setNearbyHotObjectDragMode(0);
      scene.setNearbyHotObjectMarker(null);
    };
""",
        """    const onNearbyDragMode = (event: Event) => {
      scene.setNearbyHotObjectDragMode(Number((event as CustomEvent).detail ?? 0));
    };
    const onNearbyEnclosureBox = (event: Event) => {
      scene.setNearbyHotObjectEnclosureBox((event as CustomEvent).detail ?? null);
    };
    const onNearbyClear = () => {
      scene.setNearbyHotObjectPickMode(false);
      scene.setNearbyHotObjectDragMode(0);
      scene.clearNearbyHotObjectState();
    };
""",
        "enclosure box event handler",
    )
    replace_once(
        viewer,
        """    window.addEventListener(NEARBY_DRAG_MODE_EVENT, onNearbyDragMode);
    window.addEventListener(NEARBY_CLEAR_EVENT, onNearbyClear);
""",
        """    window.addEventListener(NEARBY_DRAG_MODE_EVENT, onNearbyDragMode);
    window.addEventListener(NEARBY_ENCLOSURE_BOX_EVENT, onNearbyEnclosureBox);
    window.addEventListener(NEARBY_CLEAR_EVENT, onNearbyClear);
""",
        "enclosure box event registration",
    )
    replace_once(
        viewer,
        """      window.removeEventListener(NEARBY_DRAG_MODE_EVENT, onNearbyDragMode);
      window.removeEventListener(NEARBY_CLEAR_EVENT, onNearbyClear);
""",
        """      window.removeEventListener(NEARBY_DRAG_MODE_EVENT, onNearbyDragMode);
      window.removeEventListener(NEARBY_ENCLOSURE_BOX_EVENT, onNearbyEnclosureBox);
      window.removeEventListener(NEARBY_CLEAR_EVENT, onNearbyClear);
""",
        "enclosure box event cleanup",
    )

    for path, contract in (
        (scene, "nearestSurfaceForNearbySource"),
        (scene, "setNearbyHotObjectEnclosureBox"),
        (scene, "5000"),
        (scene, 'nearbyHotObjectDragAxis: "xy" | "z"'),
        (scene, "new THREE.Vector3(0, 0, 1)"),
        (viewer, "NEARBY_ENCLOSURE_BOX_EVENT"),
        (viewer, "clearNearbyHotObjectState"),
    ):
        if contract not in path.read_text(encoding="utf-8"):
            raise RuntimeError(f"Spatial environment contract {contract!r} missing from {path}")
    (source_root / SOURCE_MARKER).write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
