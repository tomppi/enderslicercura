// Copyright (c) 2026 EnderSlicerCura contributors
// Bead-angle overhangs: anchored staircase rings whose extrusion is pressed at
// a chosen angle around the bead's circular cross-section. The press angle is
// measured around the bead with the top at 90 degrees, the left side at 0 and
// the right side at 180 (mirrored per overhang direction): 90 prints flat,
// 0/180 press from the side exactly like wave overhangs, and every value in
// between leans the bead. Angles beyond 180 (the underside) are impossible on
// a fixed vertical nozzle and are not generated.
// Distributed under GNU AGPL-3.0-or-later with CuraEngine.

#pragma once

#include "geometry/OpenLinesSet.h"
#include "utils/Coord_t.h"

#include <cstddef>

namespace cura
{

struct BeadAngleParameters
{
    coord_t line_width{ 400 };        // extrusion width (microns)
    coord_t layer_height{ 200 };      // layer height (microns)
    double press_angle{ 90.0 };       // degrees: 0..180, 90 = top press
    coord_t press_wavelength{ 3000 }; // press wiggle period along the ring (microns)
    size_t max_iterations{ 60 };      // fail-closed cap on rings per island
};

class BeadAngleGenerator
{
public:
    // Generates bead-angle rings for every unsupported region of the outline:
    // a half-line-width staircase anchored on the supported region, each ring
    // pressed outward by an excursion whose amplitude follows the chosen press
    // angle, plus the true island boundary as the outer ring so the visible
    // surface stays exact. Returns false when the layer is fully supported
    // (ordinary walls suffice) or when no region can be covered within
    // max_iterations.
    static bool generate(
        const Shape& outline,
        const Shape& supported_region,
        const BeadAngleParameters& parameters,
        OpenLinesSet& output);
};

} // namespace cura
