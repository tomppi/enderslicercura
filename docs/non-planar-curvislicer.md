# Non-planar CurviSlicer

EnderSlicerCura includes an Android-native non-planar pipeline inspired by the CurviSlicer research method. It is available under **Menu → Non Planar → CurviSlicer options** and is disabled by default.

## Pipeline

1. EnderSlicerCura samples the upper surface of the displayed and positioned STL.
2. It creates a smoothed height field and derives a bounded relief field.
3. Requested curvature strength is reduced automatically when needed to preserve layer ordering or the configured nozzle-clearance slope.
4. The displayed STL is flattened with the field. Imported Cura affine transforms are resolved before this step.
5. Smart Infill modifier volumes are flattened with the same field so their density regions remain aligned.
6. CuraEngine slices the flattened solid using the active Cura profile.
7. Every printable `G0`/`G1` path is subdivided and mapped through the inverse field, creating continuously varying Z coordinates.
8. Positive extrusion is compensated for the actual three-dimensional path length and feed rate is reduced where necessary to respect the configured maximum Z speed.
9. The normal EnderSlicerCura G-code sanitizer, build-envelope checks, layer-event processing, preview parser and immutable artifact publisher run on the curved result.

The final file contains these markers:

```gcode
;ENDERSLICER_NON_PLANAR:CurviSlicer-Android-v1
;ENDERSLICER_CURVI_STRENGTH:...
;ENDERSLICER_CURVI_MAX_DISPLACEMENT:...
;ENDERSLICER_CURVI_GRID:...
```

## Options

- **Curvature strength:** requested fraction of the safe field deformation.
- **Surface smoothing radius:** spatial scale that separates broad curved layers from small mesh features.
- **Maximum path slope:** upper bound for the generated path gradient.
- **Nozzle clearance angle and height:** conservative physical clearance model around the nozzle.
- **Flat base layers:** keeps the first layers planar for bed adhesion.
- **Field resolution:** sampling resolution of the deformation field.
- **Maximum generated move length:** subdivision limit used while restoring curves.
- **Maximum Z speed:** feed-rate limiter for simultaneous XYZ moves.
- **Extrusion length compensation:** scales extrusion for the true curved path length.
- **Warp Smart Infill modifiers:** required when Smart Infill regions are active.

## Path viewer

After slicing, the preview selector contains three independent modes:

- **Model** — the imported STL and build plate.
- **Layers** — the existing cumulative Cura layer preview.
- **Path** — the ordered nozzle journey from the first spatial move to the last.

The Path view includes both travel and extrusion. Travel is gray. Extrusion is colored from blue at low Z to red at high Z, which makes continuously changing non-planar height visible. The slider and playback controls follow print order rather than layer order.

## Rejected output

The slice fails instead of publishing G-code when:

- the model is too short for the selected flat base region;
- the field cannot be represented safely;
- the result goes below the bed or above the configured build height;
- the move budget would be exceeded;
- fitted `G2`/`G3` arcs appear inside printable layers;
- a Smart Infill package is active while modifier warping is disabled;
- CuraEngine or the normal EnderSlicerCura validation rejects the output.

## Physical safety

Software clearance checks cannot know the exact shape of a heater block, silicone sock, fan duct, probe or carriage. Start with a small model, conservative strength and slope, and inspect the complete Path view before printing. Keep a hand near the printer stop control during initial tests.

## Attribution and implementation scope

The original **CurviSlicer: Slightly Curved Slicing for 3-Axis Printers** research prototype is maintained by the MFX/Inria team at `mfx-inria/curvislicer` and is licensed under AGPL-3.0. EnderSlicerCura is also AGPL-3.0-or-later.

The Android backend is a clean Android-oriented implementation of the flatten/slice/inverse-map concept. It does not package the desktop Wine/TetWild automation or claim numerical identity with the original tetrahedral OSQP/Gurobi optimizer. This design avoids a desktop runtime dependency and keeps the complete process offline on ARM64 Android devices.
