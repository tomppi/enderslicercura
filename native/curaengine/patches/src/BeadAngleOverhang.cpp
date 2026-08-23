// Copyright (c) 2026 EnderSlicerCura contributors
// Masonry-bonded walls (see BeadAngleOverhang.h).
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

constexpr double PI = 3.14159265358979323846;
//! How far past the contour the interior probe travels (0.02 mm).
constexpr coord_t PROBE_UM = 20;

//! Hook-and-loop shell anchors (masonry mode): every HOOK_SPACING_UM along the
//! innermost wall the toolpath steps inward and draws a small closed ring - the
//! eye is the hook and the core material printed around and through it is the
//! loop, pinning the shell to the core instead of only melting against it.
constexpr coord_t HOOK_SPACING_UM = 4000; // 4 mm between hook eyes
constexpr coord_t HOOK_NECK_UM = 600;     // stem length from the wall to the eye
constexpr double HOOK_RADIUS_LW = 1.0;    // eye radius in line widths
constexpr int HOOK_CIRCLE_POINTS = 16;

Point2LL inwardNormal(const Point2LL& previous, const Point2LL& next, const Shape& bounded);

//! Insert hook eyes into an innermost wall contour: every HOOK_SPACING_UM the
//! path steps inward, draws a small closed ring and steps back along the stem.
//! The ring radius alternates per layer (big/small) so each small ring prints
//! inside the hole of the big ring beneath it and can never pull back through
//! the rim - a rivet through the layers. Eyes whose centre would leave the
//! outline are skipped (thin walls).
OpenPolyline withHookEyes(
    const OpenPolyline& line,
    const Shape& bounded,
    const Shape& outline,
    coord_t line_width,
    bool large_eye)
{
    const double total = line.length();
    const double radius_lw = large_eye ? 1.2 : 0.6;
    const coord_t radius = static_cast<coord_t>(std::llround(radius_lw * static_cast<double>(line_width)));
    if (line.size() < 2 || total < HOOK_SPACING_UM + radius + HOOK_NECK_UM || line_width <= 0)
    {
        return line;
    }

    std::vector<double> cumulative(line.size(), 0.0);
    for (size_t i = 1; i < line.size(); ++i)
    {
        const Point2LL delta = line[i] - line[i - 1];
        cumulative[i] = cumulative[i - 1] + std::hypot(static_cast<double>(delta.X), static_cast<double>(delta.Y));
    }

    OpenPolyline out;
    out.reserve(line.size() + (static_cast<size_t>(total / HOOK_SPACING_UM) + 1) * (HOOK_CIRCLE_POINTS + 4));
    out.push_back(line[0]);
    double next_hook = std::min(static_cast<double>(HOOK_SPACING_UM), total - 1.0);
    for (size_t i = 1; i < line.size(); ++i)
    {
        while (next_hook <= cumulative[i])
        {
            const double segment = cumulative[i] - cumulative[i - 1];
            const double t = segment > 0.0 ? (next_hook - cumulative[i - 1]) / segment : 0.0;
            const Point2LL p0 = line[i - 1] + (line[i] - line[i - 1]) * t;
            // Inward direction at the hook point itself: probing at a corner
            // vertex can land on both boundary lines and fail the inside test.
            const Point2LL tangent = line[i] - line[i - 1];
            const double t_len = std::hypot(static_cast<double>(tangent.X), static_cast<double>(tangent.Y));
            Point2LL normal(0, 0);
            if (t_len > 0.0)
            {
                const double nx = static_cast<double>(tangent.Y) / t_len;
                const double ny = -static_cast<double>(tangent.X) / t_len;
                const Point2LL cand(
                    static_cast<coord_t>(std::llround(nx * PROBE_UM)),
                    static_cast<coord_t>(std::llround(ny * PROBE_UM)));
                if (bounded.inside(p0 + cand))
                {
                    normal = cand;
                }
                else
                {
                    const Point2LL opposite(-cand.X, -cand.Y);
                    if (bounded.inside(p0 + opposite))
                    {
                        normal = opposite;
                    }
                }
            }
            const double n_len = std::hypot(static_cast<double>(normal.X), static_cast<double>(normal.Y));
            if (n_len > 0.0)
            {
                const Point2LL unit(
                    static_cast<coord_t>(std::llround(normal.X / n_len * 1000.0)),
                    static_cast<coord_t>(std::llround(normal.Y / n_len * 1000.0)));
                const Point2LL p1(p0.X + unit.X * HOOK_NECK_UM / 1000, p0.Y + unit.Y * HOOK_NECK_UM / 1000);
                const Point2LL centre(p1.X + unit.X * radius / 1000, p1.Y + unit.Y * radius / 1000);
                if (outline.inside(centre))
                {
                    out.push_back(p1);
                    const Point2LL arm(p1 - centre);
                    for (int k = 1; k <= HOOK_CIRCLE_POINTS; ++k)
                    {
                        const double angle = 2.0 * PI * static_cast<double>(k) / HOOK_CIRCLE_POINTS;
                        const double cos_a = std::cos(angle);
                        const double sin_a = std::sin(angle);
                        const Point2LL rotated(
                            centre.X + static_cast<coord_t>(std::llround(arm.X * cos_a - arm.Y * sin_a)),
                            centre.Y + static_cast<coord_t>(std::llround(arm.X * sin_a + arm.Y * cos_a)));
                        out.push_back(rotated);
                    }
                    out.push_back(p1);
                }
                out.push_back(p0);
            }
            next_hook += HOOK_SPACING_UM;
        }
        out.push_back(line[i]);
    }
    return out;
}

//! Inward direction at a contour point: the perpendicular of the local segment
//! that points INTO the shape the contour bounds. Both sides are probed and
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

//! Split a contour into the runs that stay outside the replacement region.
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
        const bool innermost = (i + 1 == parameters.base_wall_count);
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
            if (innermost)
            {
                // Hook-and-loop shell anchors on the inside of the wall stack:
                // closed eyes that the core material locks around, alternating
                // big/small per layer so each pair rivets the layers together.
                line = withHookEyes(line, inset, outline, parameters.line_width, lean >= 0);
            }
            output.push_back(std::move(line), CheckNonEmptyParam::OnlyIfValid);
        }
    }
}

} // namespace cura
