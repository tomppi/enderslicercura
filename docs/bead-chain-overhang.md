# Bead-Chain Overhang (working design)

Status: implemented (engine + app), verified on host fixtures (45/60/80 deg).

## Goal

The originally intended bead-mode: in an overhang band the wall's outer face
becomes ONE extruded bead per layer - **the chain** - that is seated into the
valley formed between the chain bead below and the material behind it, so the
weld wraps roughly half the bead. Behind the chain a row of inner beads
**fills the wedge** the diagonal step opens, keeping the wall's inner face
straight ("in line with the other wall", the drawn 2-bead wall vs. the 3-bead
band). The chain runs on BOTH walls of a part, and only inside the bend.

## Geometry model (per band, per layer)

Real bead dimensions: the extruded bead is a rounded rectangle of width
(flow x line width) and height = layer height; flow widens the bead, never
the layer. Angle theta measured from vertical at the band (tan = layer
height / band thickness, computed from the unsupported region exactly like
bead-angle).

- step s = layer height / tan(theta) - horizontal reach per layer.
- Chain bead width = clamp(s + weld_target x lw, flow_min x lw, lw):
  the smallest bead that still reaches down to the chain bead below with the
  target weld overlap (default weld 15% of lw, floor 60%).
- Bead count total = ceil(wall_line_count / cos(theta)) (keeps the section
  thickness on the diagonal), capped by base + max-extra.
- V collapse: when the step is under a third of a layer height the chain is
  stacking nearly vertically - rows add nothing, the band is the chain alone
  (one bead, welded into the 2 beads under it). This rule is scale-free, so
  thin slices keep their geometry.
- Inner row: row_width = wall_line_count x lw + band thickness (the wedge
  accumulated through the band); the row bead count grows so no bead takes
  more than inner_flow_cap x lw (default 135%); every row bead width =
  row_width / count.
- Press: the row overlaps the chain back by press x lw (default 5%) - the
  chain seats into the row and presses into the bead below.
- Printing order: deepest row first, chain bead last (its backing is hot).

## Honest limits

- The band engages only while s <= lw: shallower slopes (s > lw, roughly
  below ~27 deg at 0.2/0.4) fall back to the ordinary walls. Finer layer
  heights in the whole print extend the chain to shallow bends (see the
  thin-layer note in the app; band-local layer heights are not supported by
  the layer pipeline - at 0.2/0.4 a 45 deg band needs no change; 20 deg
  needs ~0.07 mm layers to hold the seat).
- The chain sits between the previous layer's material only; real
  support-free reach is bounded by the bead's weld footprint per layer.

## Settings (enderslicer_bead_chain_*)

enabled, speed, fan_speed, weld_target (%), flow_min (%), inner_flow (%),
press (%), max_iterations. Mutually exclusive with arc/wave/brick-wall/
bead-angle/masonry/wall-anchors (app + engine both enforce it). Requires
bridge_settings_enabled (the app turns it on with the feature).

## Verification

- host fixtures (scripts/_chain_test.bat): cliff45 (tan=1: total 3 = chain
  240um + 2 x 450um rows, matching the drawn 3-bead band), cliff80
  (step ~17um < h/3: collapse to chain only), cliff60 (intermediate).
- type marker TYPE:BEAD-CHAIN drives the app's layer preview.
- simulated cross-sections in docs/chain-sim/ (35..80 deg, L and V
  profiles) with the derived value tables (values.csv, values-v2.csv).
