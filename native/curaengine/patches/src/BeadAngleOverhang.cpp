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
//! The slight press grows from zero at the 45-degree engagement threshold to
//! PRESS_MAX_MM at tan(theta) = 3 (roughly a 71.6 degree wall): a gentle
//! squeeze into the layer beside, never a big excursion.
constexpr double PRESS_GAIN_MM = 0.05;
constexpr double PRESS_MAX_MM = 0.10;

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

//! How far past the contour the interior probe travels. The probe must stay
//! much shorter than the thinnest engaged band (thickness in the line-width
//! range): a long probe overshoots a thin band and flips the press outward,
//! pushing the outer wall past the model outline.
constexpr coord_t PROBE_UM = 20; // 0.02 mm

//! Inward direction at a contour point: the perpendicular of the local segment
//! that points INTO the shape the contour bounds (toward the backing walls
//! and the supported region - the press squeezes the bead against its
//! neighbour instead of pushing past the outline). Both sides are probed and
//! zero is returned when neither resolves, so a degenerate segment keeps the
//! contour straight instead of guessing outward.
Point2LL inwardNormal(const Point2LL& previous, const Point2LL& next, const Shape& bounded)
{
    const Point2LL tangent = next - previous;
    const double length = std::hypot(static_cast<double>(tangent.X), static_cast<double>(tangent.Y));
    if (length <= 0.0)
    {
        return Point2LL(0, 0);
    }
    const double nx = static_cast<double>(tangent.Y) / length;
    const double ny = -static_cast<double>(tangent.X) / length;
    const Point2LL candidate(
        static_cast<coord_t>(std::llround(nx * PROBE_UM)),
        static_cast<coord_t>(std::llround(ny * PROBE_UM)));
    if (bounded.inside(previous + candidate))
    {
        return candidate;
    }
    const Point2LL opposite(-candidate.X, -candidate.Y);
    return bounded.inside(previous + opposite) ? opposite : Point2LL(0, 0);
}

//! Re-sample one contour into a slightly pressed path: every press_wavelength
//! the path makes a small triangular excursion INWARD (lay outward, press back
//! against the neighbour). amplitude == 0 emits the contour unchanged.
void emitPressedContour(const OpenPolyline& line, const Shape& bounded, coord_t amplitude, coord_t wavelength, OpenLinesSet& out)
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
        const Point2LL normal = inwardNormal(a, c, bounded);
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

//! Split a contour into the runs that stay outside the replacement region
//! (the leaning stacks already own the area inside it).
void clipOutside(const OpenPolyline& line, const Shape& region, OpenLinesSet& output)
{
    if (region.empty())
    {
        output.push_back(line, CheckNonEmptyParam::OnlyIfValid);
        return;
    }
    OpenPolyline run;
    for (const Point2LL& point : line)
    {
        if (region.inside(point))
        {
            if (run.size() >= 2)
            {
                output.push_back(std::move(run), CheckNonEmptyParam::OnlyIfValid);
            }
            run.clear();
        }
        else
        {
            run.push_back(point);
        }
    }
    if (run.size() >= 2)
    {
        output.push_back(std::move(run), CheckNonEmptyParam::OnlyIfValid);
    }
}

} // namespace

void BeadAngleGenerator::generateBaseWalls(
    const Shape& outline,
    const BeadAngleParameters& parameters,
    const Shape& replacement,
    OpenLinesSet& output)
{
    for (size_t i = 0; i < parameters.base_wall_count; ++i)
    {
        // The outer wall follows the raw outline (offset(0) can collapse
        // very thin parts); the inner walls nest inward and stop as soon as
        // the inset no longer fits.
        const Shape inset = (i == 0) ? outline : outline.offset(-static_cast<coord_t>(i) * parameters.line_width);
        if (inset.empty())
        {
            break;
        }
        // The insets lie inside the outline by construction, so they need no
        // clipping: emit each boundary directly (Clipper polygon intersection
        // would drop closed rings when extracting open paths).
        for (const Polygon& polygon : inset)
        {
            if (polygon.size() < 3)
            {
                continue;
            }
            OpenPolyline line;
            line.reserve(polygon.size() + 1);
            for (const Point2LL& point : polygon)
            {
                line.push_back(point);
            }
            line.push_back(polygon.front());
            clipOutside(line, replacement, output);
        }
    }
}

void BeadAngleGenerator::generateMasonryWalls(
    const Shape& outline,
    const BeadAngleParameters& parameters,
    const coord_t lean,
    OpenLinesSet& output)
{
    for (size_t i = 0; i < parameters.base_wall_count; ++i)
    {
        const coord_t offset = lean - static_cast<coord_t>(i) * parameters.line_width;
        const Shape inset = (offset == 0) ? outline : outline.offset(offset);
        if (inset.empty())
        {
            continue;
        }
        for (const Polygon& polygon : inset)
        {
            if (polygon.size() < 3)
            {
                continue;
            }
            OpenPolyline line;
            line.reserve(polygon.size() + 1);
            for (const Point2LL& point : polygon)
            {
                line.push_back(point);
            }
            line.push_back(polygon.front());
            output.push_back(std::move(line), CheckNonEmptyParam::OnlyIfValid);
        }
    }
}

bool BeadAngleGenerator::generate(
    const Shape& outline,
    const Shape& supported_region,
    const BeadAngleParameters& parameters,
    OpenLinesSet& output,
    Shape& replacement)
{
    output.getLines().clear();
    replacement.clear();
    if (outline.empty() || supported_region.empty() || parameters.line_width <= 0
        || parameters.layer_height <= 0 || parameters.base_wall_count < 1)
    {
        return false;
    }

    const Shape unsupported = outline.difference(supported_region);
    if (unsupported.empty())
    {
        return false;
    }

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

        // Gentle-and-thin gate, identical to the brick-wall strategy.
        const double thickness = 2.0 * area / polygonPerimeter(island_polygon);
        if (parameters.layer_height < MIN_STEEP_THICKNESS_FACTOR * thickness && thickness < parameters.line_width)
        {
            continue;
        }

        // Local overhang angle: tan(theta) = layer_height / thickness.
        const double tan_theta = parameters.layer_height / std::max(thickness, 1.0);

        // Angle-driven wall count: the leaning stack must reach the layer
        // below, so the per-layer step s = layer_height / tan(theta) must be
        // covered by (walls - 1) * line_width: walls >= 1 + ceil(s / lw).
        const double step = parameters.layer_height / std::max(tan_theta, 0.001);
        const size_t needed_walls = 1 + static_cast<size_t>(std::ceil(step / parameters.line_width - 1e-9));
        const size_t wall_cap = parameters.base_wall_count + parameters.max_extra_walls;
        if (needed_walls > wall_cap)
        {
            continue; // fail-closed: too shallow for the cap, keep normal walls
        }
        const size_t walls = std::max(parameters.base_wall_count, needed_walls);

        // Slight press: zero at the engagement threshold, PRESS_MAX_MM at
        // tan(theta) = 3.
        const double press_mm = std::clamp(PRESS_GAIN_MM * (tan_theta - 1.0), 0.0, PRESS_MAX_MM);
        const coord_t press_amplitude = static_cast<coord_t>(std::llround(press_mm * 1000.0));

        const Shape island_shape(island_polygon);
        const Shape zone = island_shape.offset(2 * parameters.line_width);

        // The leaning wall stack: outer contour on the true outline, inner
        // contours nested behind it - the stack always expands inward. Inner
        // first, so every contour has its backing already laid when it prints.
        // Each contour presses against ITS OWN inset shape (whose boundary it
        // follows exactly), so the wiggle always points inward toward the
        // model core and the outer wall never crosses the outline.
        for (size_t i = walls; i-- > 0;)
        {
            const Shape inset = outline.offset(-static_cast<coord_t>(i) * parameters.line_width);
            if (inset.empty())
            {
                continue; // deeper insets no longer fit; shallower ones still do
            }
            OpenLinesSet contours = contoursOf(inset, zone);
            for (OpenPolyline& line : contours.getLines())
            {
                emitPressedContour(line, inset, press_amplitude, parameters.press_wavelength, output);
            }
        }
        replacement = replacement.unionPolygons(zone);
        any_region = true;
    }

    return any_region && ! output.getLines().empty();
}

} // namespace cura
