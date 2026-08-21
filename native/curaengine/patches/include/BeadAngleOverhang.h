// Copyright (c) 2026 EnderSlicerCura contributors
// Bead-angle overhangs: in every unsupported overhang band the WHOLE wall
// stack is rebuilt as a leaning wall - the outer contour rides the true model
// outline, the inner contours nest behind it, and the wall count grows with the
// local overhang angle so every bead has backing material to press into.
// All contours carry a slight, slow, inward press (the wave-overhang squeeze)
// that welds each bead sideways into the layer beside it. No rings, no extra
// infill: the adjusted wall stack is the support.
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
    // collects the engaged regions (so the caller can clip the ordinary wall
    // toolpaths there and avoid double-printing). Returns false when nothing
    // engages (gentle/fully supported bands keep their normal walls).
    static bool generate(
        const Shape& outline,
        const Shape& supported_region,
        const BeadAngleParameters& parameters,
        OpenLinesSet& output,
        Shape& engaged);
};

} // namespace cura
