// Copyright (c) 2026 Tomas Kald and EnderSlicerCura contributors
// Algorithm adapted from the wavefront method documented by
// dennisklappe/OrcaSlicer-WaveOverhangs and stmcculloch/PrusaSlicer-WaveOverhangs.
// Distributed under GNU AGPL-3.0-or-later with CuraEngine.

#include "WaveOverhang.h"

#include <algorithm>
#include <vector>

#include "geometry/ClosedPolyline.h"
#include "geometry/LinesSet.h"
#include "geometry/OpenPolyline.h"
#include "geometry/SingleShape.h"

namespace cura
{
namespace
{

constexpr double MIN_AREA_GROWTH = 100.0;

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

bool appendIsland(
    const SingleShape& island,
    const Shape& supported_region,
    const WaveOverhangParameters& parameters,
    OpenLinesSet& output)
{
    const Shape unsupported = island.difference(supported_region);
    if (unsupported.empty())
    {
        return false;
    }
    if (parameters.minimum_width > 0 && unsupported.offset(-parameters.minimum_width / 2).empty())
    {
        // A region narrower than the requested printable width stays on Cura's
        // normal bridge path instead of producing fragile isolated wavefronts.
        return false;
    }

    const coord_t seed_expansion = std::max<coord_t>(parameters.line_spacing, 10);
    Shape current = island.intersection(supported_region.offset(seed_expansion));
    if (current.empty())
    {
        return false;
    }

    Shape trim_boundary = island.offset(-std::max<coord_t>(parameters.line_spacing / 2 - parameters.perimeter_overlap, 0));
    if (trim_boundary.empty())
    {
        trim_boundary = island;
    }

    std::vector<OpenLinesSet> levels;
    levels.reserve(std::min<size_t>(parameters.max_iterations, 256));
    for (size_t iteration = 0; iteration < parameters.max_iterations; ++iteration)
    {
        const Shape next = current.offset(parameters.line_spacing).intersection(island);
        if (next.empty())
        {
            break;
        }
        const double growth = next.area() - current.area();
        if (growth <= MIN_AREA_GROWTH)
        {
            current = next;
            break;
        }

        OpenLinesSet front = contoursOf(next, trim_boundary);
        if (! front.empty())
        {
            levels.emplace_back(std::move(front));
        }
        current = next;
        if (unsupported.difference(current.offset(parameters.line_spacing)).empty())
        {
            break;
        }
    }

    if (levels.size() < 2 || ! unsupported.difference(current.offset(parameters.line_spacing)).empty())
    {
        return false;
    }

    for (size_t level_index = 0; level_index < levels.size(); ++level_index)
    {
        auto& lines = levels[level_index].getLines();
        const bool reverse_level
            = (parameters.pattern == "zigzag" && level_index % 2 == 1) != parameters.reverse_direction;
        if (reverse_level)
        {
            std::reverse(lines.begin(), lines.end());
            for (OpenPolyline& line : lines)
            {
                line.reverse();
            }
        }
        for (OpenPolyline& line : lines)
        {
            output.push_back(std::move(line), CheckNonEmptyParam::OnlyIfValid);
        }
    }
    return ! output.empty();
}

} // namespace

bool WaveOverhangGenerator::generate(
    const Shape& area,
    const Shape& supported_region,
    const WaveOverhangParameters& parameters,
    OpenLinesSet& output)
{
    output.getLines().clear();
    if (area.empty() || supported_region.empty() || parameters.line_spacing <= 0
        || parameters.max_iterations == 0
        || (parameters.pattern != "smart" && parameters.pattern != "monotonic" && parameters.pattern != "zigzag"))
    {
        return false;
    }

    OpenLinesSet generated;
    for (const SingleShape& island : area.splitIntoParts())
    {
        if (! appendIsland(island, supported_region, parameters, generated))
        {
            return false;
        }
    }
    output.getLines() = std::move(generated.getLines());
    return ! output.empty();
}

} // namespace cura
