# Bead-Angle Overhang (working design)

Status: implemented (adaptive angle, inward press, full wall takeover), iterating on print quality.

## Goal

A support-free overhang strategy where the user chooses **where on the extruded
bead's circular cross-section the nozzle presses**, measured around the bead:

- 0°  = left side of the bead
- 90° = top of the bead (normal flat printing)
- 180° = right side of the bead (what wave overhangs do today, without the oscillation)
- >180° = underside — **impossible on a fixed vertical nozzle** (needs a tilting/rotating head); out of scope.

The printable band is therefore 0–180° continuous. Wave overhangs become the
special case at 0°/180° (full side press, plus their oscillation for re-welding);
flat printing is the 90° case; everything in between is a leaned bead pressed at
a chosen shoulder angle. Target use cases: support-free pyramids (steep cap
undersides) and snowman-style domed prints whose overhang rings anchor inward.

## Physical mechanism

The nozzle is fixed and vertical, so the press angle is realised by **leaning the
bead**, not by tilting the nozzle. The bead always hangs below the tip; moving the
bead's centerline sideways relative to the tip axis rotates the tip's contact
point around the bead's top half:

- bead directly under tip          -> contact at ~90° (top)
- bead offset ~half a line width   -> contact at ~45° / ~135° (shoulder)
- bead offset ~a full line width   -> contact at ~0° / ~180° (side press)

Each overhang ring therefore steps outward by an offset `s` per layer along the
wall's outward normal, where `s = f(pressAngle, lineWidth, layerHeight)`. The
anchor half of the bead stays welded to the ring below (inside the part), while
the free half overhangs by `s`. The maximum outward step per layer is bounded by
the bead's adhesion footprint — the honest limit of every side-press technique,
wave overhangs included.

## Path rules per band

| Press angle | Lean | Path behaviour |
| --- | --- | --- |
| 90° | 0 | unchanged planar wall |
| 60–90 / 90–120 | slight outward offset | shoulder-stepped rings, no oscillation |
| 0–45 / 135–180 | near-full side press | side press + optional small re-weld oscillation (wave-style, amplitude scaled by angle) |

Mirroring: the press side must follow the overhang's outward direction. A wall
hanging toward +X presses the bead's −X side (0°), one hanging toward −X presses
+180°; the angle setting is interpreted **relative to the outward normal**
(0–180° symmetric around the top), so one knob works for all wall orientations.

## Compensation knobs

Angled contact deforms the bead cross-section, so the strategy ships its own
defaults, adjustable per feature (same pattern as wave/brick):

- flow multiplier (expect ~100–110%; angled press flattens the bead)
- speed (slower at steeper angles; the Z-component is not involved here)
- fan (full)
- optional oscillation amplitude for re-welding at extreme angles

## Integration

Follows the existing engine strategy pattern (ArcOverhang.cpp, WaveOverhang.cpp,
BrickWalls.cpp, ConicalOverhang.cpp):

1. New CuraEngine generator `BeadAngleOverhang.cpp` hooked in
   `FffGcodeWriter.cpp` the same way as the brick-wall hook. In bead-angle
   mode the generator OWNS the walls: Cura's wall toolpaths are dropped for
   every layer (`part.wall_toolpaths.clear()`) and replaced by our base
   insets (`generateBaseWalls`, normal wall speed/flow, clipped out of the
   band zones) plus the pressed leaning stacks of the engaged bands, so
   nothing doubles up with them.
2. Settings enderslicer_bead_angle_enabled, ..._wavelength,
   ..._flow, ..._speed, ..._fan_speed, ..._max_iterations
   (no press-angle setting: it is derived from the model), plumbed through
   CuraSettingDelta + the resolved settings writer.
3. Smart-overhang planner: new recommendation so the planner can pick
   bead-angle instead of ARC/WAVE/BRICK when the overhang band suits it.
4. G-code transport: new `;TYPE:BEAD-ANGLE-OVERHANG` marker for the path view.

## Honest limits

- Per-layer outward reach is bounded by the anchor footprint; beyond-horizontal
  (concave, >90° wall) undersides still need wave/brick outward pushes or the
  non-planar z-dive.
- Support-free snowman/pyramid targets are realistic for cap slopes up to
  roughly the side-press reach per layer; each model needs a test print to
  confirm the chosen angle.

## Validation plan

- Engine harness fixtures (same .build/pattern as brick/wave): cliff at 45/60/70/80°,
  dome, ring, pyramid cap, mushroom (cap underside) and a two-ball snowman
  (sphere-on-sphere bands) — assert marker counts and path geometry. Note: a
  solid dome sitting on its widest layer nests every layer inside the previous
  one, so it has no unsupported band and correctly stays unengaged; sphere
  undersides (snowman/mushroom) DO engage.
- Printed test protocol on the Ender 3 V2: pyramid cap at 45°, dome at two angles,
  then the snowman head.
