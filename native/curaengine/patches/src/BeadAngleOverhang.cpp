// Copyright (c) 2026 EnderSlicerCura contributors
// Bead-angle overhangs (see BeadAngleOverhang.h).
// Distributed under GNU AGPL-3.0-or-later with CuraEngine.

#include "BeadAngleOverhang.h"

#include "geometry/ClosedPolyline.h"
#include "geometry/LinesSet.h"
#include "geometry/SingleShape.h"

#include <algorithm>
#include <cmath>
#include <vector>

namespace cura
{
namespace
{

//! Below this area an unsupported island is treated as triangulation noise.
constexpr double MIN_ISLAND_AREA_UM2 = 10000.0; // 0.01 mm^2
//! Gentle-and-thin gate: a band thinner than one line width at an overhang
//! angle below 45 degrees prints fine with ordinary walls.
constexpr double MIN_STEEP_THICKNESS_FACTOR = 1.0;
//! The outward excursion keeps this much bead-to-bead anchor overlap so the
//! pressed bead can never slide off the previous ring.
constexpr coord_t ANCHOR_MARGIN_UM = 25; // 0.025 mm

OpenLinesSet contoursOf(const Shape& shape, const Shape& clip)
{
    LinesSet<ClosedPolyline> closed;
    for (const Polygon& polygon : shape)
    {
        if (polygon.size() < 3)
        {
            continue;
        }
        ClipperLib::Path points(polygon.begin(), polygon.end());
        closed.push_back(ClosedPolyline(std::move(points), false), CheckNonEmptyParam::OnlyIfValid);
    }
    return clip.intersection(closed);
}

double polygonArea(const Polygon& polygon)
{
    return std::abs(polygon.area());
}

double polygonPerimeter(const Polygon& polygon)
{
    double perimeter = 0.0;
    for (size_t i = 0; i < polygon.size(); ++i)
    {
        const Point2LL delta = polygon[(i + 1) % polygon.size()] - polygon[i];
        perimeter += std::hypot(static_cast<double>(delta.X), static_cast<double>(delta.Y));
    }
    return perimeter;
}

//! Point on the polyline at arc length s.
Point2LL pointAtPolyline(const OpenPolyline& line, const std::vector<double>& cumulative, double s)
{
    const double total = cumulative[line.size() - 1];
    if (s <= 0.0)
    {
        return line[0];
    }
    if (s >= total)
    {
        return line[line.size() - 1];
    }
    for (size_t i = 0; i + 1 < line.size(); ++i)
    {
        if (s <= cumulative[i + 1])
        {
            const double segment = cumulative[i + 1] - cumulative[i];
            const double t = segment > 0.0 ? (s - cumulative[i]) / segment : 0.0;
            return line[i] + (line[i + 1] - line[i]) * t;
        }
    }
    return line[line.size() - 1];
}

//! Outward direction at a ring point: the perpendicular of the local segment
//! that points away from the shape the ring bounds (so the press excursion
//! always goes toward the free, unsupported side).
Point2LL outwardNormal(const Point2LL& previous, const Point2LL& next, const Shape& bounded)
{
    const Point2LL tangent = next - previous;
    if (tangent.X == 0 && tangent.Y == 0)
    {
        return Point2LL(0, 0);
    }
    const Point2LL candidate_a(tangent.Y, -tangent.X);
    const Point2LL candidate_b(-tangent.Y, tangent.X);
    const Point2LL probe = previous + candidate_a;
    return bounded.inside(probe) ? candidate_b : candidate_a;
}

//! Re-sample one ring polyline into a pressed path: every press_wavelength the
//! path makes a triangular excursion outward by amplitude (lay on the way out,
//! press back toward the anchor on the way back). amplitude == 0 emits the
//! ring unchanged (the 90 degree flat-press case).
void emitPressedRing(const OpenPolyline& line, const Shape& bounded, coord_t amplitude, coord_t wavelength, OpenLinesSet& out)
{
    if (! line.isValid() || line.length() < 50)
    {
        return; // drop clipping stubs below 0.05 mm
    }
    if (amplitude <= 0 || wavelength <= 0)
    {
        out.push_back(line, CheckNonEmptyParam::OnlyIfValid);
        return;
    }

    const size_t point_count = line.size();
    if (point_count < 2)
    {
        return;
    }
    std::vector<double> cumulative(point_count, 0.0);
    for (size_t i = 1; i < point_count; ++i)
    {
        const Point2LL delta = line[i] - line[i - 1];
        cumulative[i] = cumulative[i - 1] + std::hypot(static_cast<double>(delta.X), static_cast<double>(delta.Y));
    }
    const double total = cumulative[point_count - 1];
    if (total <= 0.0)
    {
        return;
    }

    const double half_wave = wavelength / 2.0;
    OpenPolyline pressed;
    pressed.push_back(line[0]);
    double cursor = 0.0;
    while (cursor < total - 1.0)
    {
        const double mid = std::min(cursor + half_wave, total);
        const double end = std::min(cursor + wavelength, total);
        const Point2LL a = pointAtPolyline(line, cumulative, cursor);
        const Point2LL b = pointAtPolyline(line, cumulative, mid);
        const Point2LL c = pointAtPolyline(line, cumulative, end);
        const Point2LL normal = outwardNormal(a, c, bounded);
        if (normal.X == 0 && normal.Y == 0)
        {
            pressed.push_back(c);
        }
        else
        {
            const double length = std::hypot(static_cast<double>(normal.X), static_cast<double>(normal.Y));
            const Point2LL excursion(
                b.X + static_cast<coord_t>(std::llround(normal.X * amplitude / length)),
                b.Y + static_cast<coord_t>(std::llround(normal.Y * amplitude / length)));
            if (excursion != pressed.back())
            {
                pressed.push_back(excursion);
            }
            pressed.push_back(c);
        }
        cursor = end;
    }
    if (pressed.size() >= 2)
    {
        out.push_back(std::move(pressed), CheckNonEmptyParam::OnlyIfValid);
    }
}

} // namespace

bool BeadAngleGenerator::generate(
    const Shape& outline,
    const Shape& supported_region,
    const BeadAngleParameters& parameters,
    OpenLinesSet& output)
{
    output.getLines().clear();
    if (outline.empty() || supported_region.empty() || parameters.line_width <= 0 || parameters.max_iterations < 1)
    {
        return false;
    }

    const Shape unsupported = outline.difference(supported_region);
    if (unsupported.empty())
    {
        // Fully supported layer: ordinary walls are the right tool.
        return false;
    }

    const Shape supported = outline.intersection(supported_region);
    if (supported.empty())
    {
        // Nothing of the outline overlaps the layer below: nowhere to anchor.
        return false;
    }

    const double beta = std::abs(parameters.press_angle - 90.0);
    if (beta > 90.0)
    {
        // Underside presses (beyond 180 degrees) need a tilting nozzle.
        return false;
    }
    constexpr double PI = 3.14159265358979323846;
    const coord_t press_amplitude = static_cast<coord_t>(
        std::llround(std::max(0.0, parameters.line_width / 2.0 - ANCHOR_MARGIN_UM) * std::sin(beta * PI / 180.0)));
    // Half-line-width staircase: the pressed bead inner half always rests on
    // the previous ring, even at the full side press.
    const coord_t ring_step = parameters.line_width / 2;

    bool any_region = false;
    for (const Polygon& island_polygon : unsupported)
    {
        if (island_polygon.size() < 3)
        {
            continue;
        }
        const double area = polygonArea(island_polygon);
        if (area < MIN_ISLAND_AREA_UM2)
        {
            continue; // triangulation sliver
        }

        // Gentle-and-thin gate, identical to the brick-wall strategy: bands
        // below 45 degrees that are thinner than one line width print fine
        // with ordinary walls.
        const double thickness_gate = 2.0 * area / polygonPerimeter(island_polygon);
        if (parameters.layer_height < MIN_STEEP_THICKNESS_FACTOR * thickness_gate && thickness_gate < parameters.line_width)
        {
            continue;
        }

        const Shape island_shape(island_polygon);
        const Shape zone = island_shape.offset(2 * parameters.line_width);

        // Staircase rings from the supported front outward until the island is
        // covered. Ring j is the anchor edge of the band (current, next).
        Shape current = supported;
        std::vector<OpenLinesSet> rings;
        bool covered = false;
        for (size_t step = 1; step <= parameters.max_iterations; ++step)
        {
            const Shape next = outline.intersection(current.offset(ring_step));
            const Shape band = next.difference(current);
            if (band.empty())
            {
                break; // offset stalled
            }
            LinesSet<ClosedPolyline> band_closed;
            for (const Polygon& polygon : band)
            {
                if (polygon.size() < 3)
                {
                    continue;
                }
                ClipperLib::Path points(polygon.begin(), polygon.end());
                band_closed.push_back(ClosedPolyline(std::move(points), false), CheckNonEmptyParam::OnlyIfValid);
            }
            rings.push_back(current.offset(1).intersection(band_closed));
            if (island_shape.difference(next).empty())
            {
                covered = true;
                break;
            }
            current = next;
        }
        if (! covered)
        {
            continue; // fail-closed: region too wide for the cap
        }

        // Inner rings first (each anchors on the previous), pressed toward the
        // free side. bounded tracks the shape each ring bounds so the outward
        // normal pushes away from its interior.
        Shape bounded = supported;
        for (OpenLinesSet& ring : rings)
        {
            for (OpenPolyline& line : ring.getLines())
            {
                emitPressedRing(line, bounded, press_amplitude, parameters.press_wavelength, output);
            }
            bounded = outline.intersection(bounded.offset(ring_step));
        }

        // Outer ring: the true island boundary, continuous for surface
        // fidelity, pressed outward past the boundary.
        OpenLinesSet outline_course = contoursOf(outline, zone);
        for (OpenPolyline& line : outline_course.getLines())
        {
            emitPressedRing(line, island_shape, press_amplitude, parameters.press_wavelength, output);
        }
        any_region = true;
    }

    return any_region && ! output.getLines().empty();
}

} // namespace cura
