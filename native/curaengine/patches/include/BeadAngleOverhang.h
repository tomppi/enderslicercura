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

namespace cura
{

struct BeadAngleParameters
{
    coord_t line_width{ 400 };        // extrusion width (microns)
    coord_t layer_height{ 200 };      // layer height (microns)
    coord_t press_wavelength{ 3000 }; // press wiggle period along the wall (microns)
    size_t base_wall_count{ 2 };      // the sliced wall_line_count
    size_t max_extra_walls{ 4 };      // fail-closed cap on angle-added walls
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
};

} // namespace cura
