// Copyright (c) 2026 EnderSlicerCura contributors
// Bead-angle overhangs: the generator OWNS the walls of the layer. The
// ordinary wall insets print everywhere except in unsupported overhang bands,
// where the WHOLE wall stack is rebuilt as a leaning wall - the outer contour
// rides the true model outline, the inner contours nest behind it, and the
// wall count grows with the local overhang angle so every bead has backing
// material to press into. All contours carry a slight, slow, inward press (the
// wave-overhang squeeze) that welds each bead sideways into the layer beside
// it. No rings, no extra infill: the adjusted wall stack is the support.
// Distributed under GNU AGPL-3.0-or-later with CuraEngine.

#pragma once

#include "geometry/OpenLinesSet.h"
#include "geometry/Shape.h"
#include "utils/Coord_t.h"

#include <cstddef>
#include <vector>

namespace cura
{

struct ChainPaint
{
    coord_t width_um{ 400 };  // extrusion width for this paint (microns)
    bool is_chain{ false };   // true = the outer seated chain bead
    OpenLinesSet lines;       // contour runs for this width, in print order
};

struct BeadAngleParameters
{
    coord_t line_width{ 400 };        // extrusion width (microns)
    coord_t layer_height{ 200 };      // layer height (microns)
    coord_t press_wavelength{ 3000 }; // press wiggle period along the wall (microns)
    size_t base_wall_count{ 2 };      // the sliced wall_line_count
    size_t max_extra_walls{ 4 };      // fail-closed cap on angle-added walls

    // Chain-overhang mode (fractions of the line width, from settings):
    // the outer chain bead rides the outline with width
    // clamp(step + weld_target, flow_min, 1.0) * line_width, the inner rows
    // swell up to inner_flow_cap * line_width to fill the wedge the diagonal
    // chain step opens, and the chain welds into the rows by chain_press.
    double chain_weld_target{ 0.15 };  // min chain-chain overlap, fraction of lw
    double chain_flow_min{ 0.60 };     // floor for the chain bead width factor
    double chain_flow_cap{ 1.35 };     // cap for the inner row bead width factor
    double chain_press{ 0.05 };        // chain-row weld overlap, fraction of lw
    bool all_walls{ false };           // true = the chain is the whole outer wall
                                       // (every layer), not just overhang bands
};

class BeadAngleGenerator
{
public:
    // Builds the leaning wall stack for every engaged unsupported band and
    // collects the replacement region (the bands expanded past the stack
    // depth) so the caller can clip the base walls there and avoid
    // double-printing. Returns false when nothing engages.
    static bool generate(
        const Shape& outline,
        const Shape& supported_region,
        const BeadAngleParameters& parameters,
        OpenLinesSet& output,
        Shape& replacement);

    // Emits the ordinary wall insets (0 .. base_wall_count - 1) for the whole
    // layer outline, clipped out of the replacement region. Together with
    // generate() this replaces Cura's wall toolpaths entirely in bead-angle
    // mode.
    static void generateBaseWalls(
        const Shape& outline,
        const BeadAngleParameters& parameters,
        const Shape& replacement,
        OpenLinesSet& output);

    // Emits the wall insets (0 .. base_wall_count - 1) shifted sideways by
    // [lean] (signed microns). Masonry-bonded walls lean alternately +/- half
    // a bead per layer so every bead rests on the shoulder of the bead
    // beneath instead of stacking flat like Lego bricks.
    static void generateMasonryWalls(
        const Shape& outline,
        const BeadAngleParameters& parameters,
        coord_t lean,
        OpenLinesSet& output);

    // Emits the plain wall insets (0 .. base_wall_count - 1) for the whole
    // layer; the innermost wall sprouts straight anchor teeth into the core as
    // continuous detours of the wall bead, so the wall and the infill-facing
    // material are one hot extrusion instead of a cooled butt joint.
    static void generateWallAnchors(
        const Shape& outline,
        const BeadAngleParameters& parameters,
        OpenLinesSet& output);

    // Chain-overhang bands: the band's outer face is ONE bead (the chain)
    // riding the outline, seated into the valley of the chain bead below,
    // with a row of inner insets behind it that fill the wedge the diagonal
    // step opens (the inner face stays in line with the straight wall). The
    // per-bead widths ride the associated ChainPaint so the writer can use
    // per-path extrusion widths. Fills nothing (and reports false) when the
    // band slope is beyond the bead reach or nothing engages.
    static bool generateChain(
        const Shape& outline,
        const Shape& supported_region,
        const BeadAngleParameters& parameters,
        std::vector<ChainPaint>& paints,
        Shape& replacement);
};

} // namespace cura