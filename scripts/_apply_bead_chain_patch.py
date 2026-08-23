# Bead-chain overhang engine patch (applied after the bead-angle module).

def apply(root, arc_patch_root, replace):
    gcode_path_config_h = root / "include" / "GCodePathConfig.h"
    layer_plan_cpp = root / "src" / "LayerPlan.cpp"
    fff_gcode_writer_cpp = root / "src" / "FffGcodeWriter.cpp"

    replace(
        gcode_path_config_h,
        """    bool is_bead_angle{ false }; //!< EnderSlicer bead-angle overhang path""",
        """    bool is_bead_angle{ false }; //!< EnderSlicer bead-angle overhang path
    bool is_bead_chain{ false }; //!< EnderSlicer bead-chain overhang path""",
    )

    replace(
        layer_plan_cpp,
        """                                         || last_extrusion_config.value().is_bead_angle != path.config.is_bead_angle;""",
        """                                         || last_extrusion_config.value().is_bead_angle != path.config.is_bead_angle
                                         || last_extrusion_config.value().is_bead_chain != path.config.is_bead_chain;""",
    )

    replace(
        layer_plan_cpp,
        """                else if (path.config.is_bead_angle)
                {
                    // App-owned semantic marker for the bead-angle pressed rings.
                    gcode.writeComment("TYPE:BEAD-ANGLE-OVERHANG");
                }""",
        """                else if (path.config.is_bead_angle)
                {
                    // App-owned semantic marker for the bead-angle pressed rings.
                    gcode.writeComment("TYPE:BEAD-ANGLE-OVERHANG");
                }
                else if (path.config.is_bead_chain)
                {
                    // App-owned semantic marker for the seated bead chain.
                    gcode.writeComment("TYPE:BEAD-CHAIN");
                }""",
    )

    replace(
        fff_gcode_writer_cpp,
        """    const bool bead_angle_enabled = mesh.settings.get<bool>("enderslicer_bead_angle_enabled") && ! masonry_walls_enabled && ! wall_anchor_infill_enabled;""",
        """    const bool bead_chain_enabled = mesh.settings.get<bool>("enderslicer_bead_chain_enabled") && ! masonry_walls_enabled && ! wall_anchor_infill_enabled;
    const bool bead_angle_enabled = mesh.settings.get<bool>("enderslicer_bead_angle_enabled") && ! bead_chain_enabled && ! masonry_walls_enabled && ! wall_anchor_infill_enabled;""",
    )
    replace(
        fff_gcode_writer_cpp,
        """    const bool brick_walls_enabled = mesh.settings.get<bool>("enderslicer_brick_wall_enabled") && ! bead_angle_enabled && ! masonry_walls_enabled && ! wall_anchor_infill_enabled;""",
        """    const bool brick_walls_enabled = mesh.settings.get<bool>("enderslicer_brick_wall_enabled") && ! bead_angle_enabled && ! bead_chain_enabled && ! masonry_walls_enabled && ! wall_anchor_infill_enabled;""",
    )

    # The chain branch, right before the brick branch (guarded on a marker so
    # edits to the branch text stay idempotent).
    if "    if (bead_chain_enabled)" not in fff_gcode_writer_cpp.read_text():
        replace(
            fff_gcode_writer_cpp,
            """    if (layer_nr > 0 && brick_walls_enabled)""",
            BRANCH + """    if (layer_nr > 0 && brick_walls_enabled)""",
        )

    # Hybrid stack: the chain owns the outer bead; the remaining Cura walls
    # (1 .. base-1) stay behind it, printing before the chain paints.
    replace(
        fff_gcode_writer_cpp,
        OLD_BASE_GEN,
        NEW_BASE_GEN,
    )
    replace(
        fff_gcode_writer_cpp,
        OLD_ORDER,
        NEW_ORDER,
    )
    replace(
        fff_gcode_writer_cpp,
        OLD_TRAIL,
        "",
    )


BRANCH = """    if (bead_chain_enabled)
    {
        // Bead-chain mode owns the walls: drop Cura's wall emission and
        // replace it with the chain bands - the outer seated chain bead plus
        // the inner row that fills the wedge (per-bead extrusion widths ride
        // the ChainPaint buckets) - and the base walls for the rest.
        insets_preprocess_result.walls_optimizer.reset();
        part.wall_toolpaths.clear();

        BeadAngleParameters chain_parameters;
        chain_parameters.line_width = mesh_config.inset0_config.getLineWidth();
        chain_parameters.layer_height = mesh.settings.get<coord_t>("layer_height");
        chain_parameters.base_wall_count = std::max<size_t>(mesh.settings.get<size_t>("wall_line_count"), 1);
        chain_parameters.max_extra_walls = mesh.settings.get<size_t>("enderslicer_bead_chain_max_iterations");
        chain_parameters.chain_weld_target = mesh.settings.get<double>("enderslicer_bead_chain_weld_target") / 100.0;
        chain_parameters.chain_flow_min = mesh.settings.get<double>("enderslicer_bead_chain_flow_min") / 100.0;
        chain_parameters.chain_flow_cap = mesh.settings.get<double>("enderslicer_bead_chain_inner_flow") / 100.0;
        chain_parameters.chain_press = mesh.settings.get<double>("enderslicer_bead_chain_press") / 100.0;
        chain_parameters.all_walls = mesh.settings.get<bool>("enderslicer_bead_chain_all_walls");

        std::vector<ChainPaint> chain_paint_buckets;
        Shape replacement;
        // All-walls mode chains layer 0 as well (the whole outer wall is the
        // chain); band-only mode needs the supported region, so layer 0 stays
        // with the normal walls. bridgeAngle reads the layer below, so it
        // must not run on layer 0 - generateChain treats the empty supported
        // region as "everything unsupported" and chains the whole outline.
        if (layer_nr > 0 || chain_parameters.all_walls)
        {
            Shape supported;
            if (layer_nr > 0)
            {
                bridgeAngle(mesh, part.outline, storage, layer_nr, 1, nullptr, supported);
            }
            BeadAngleGenerator::generateChain(part.outline, supported, chain_parameters, chain_paint_buckets, replacement);
        }
        OpenLinesSet base_walls;
        // The chain owns the outer wall: the remaining Cura wall insets
        // (1 .. base-1, with real transitions) stay as the stack behind it.
        BeadAngleGenerator::generateBaseWalls(part.outline, chain_parameters, replacement, base_walls,
                                                chain_parameters.all_walls ? 1 : 0);

        GCodePathConfig chain_config = mesh_config.inset0_config;
        chain_config.is_bead_chain = true;
        chain_config.speed_derivatives.speed = mesh.settings.get<Velocity>("enderslicer_bead_chain_speed");
        chain_config.fan_speed = mesh.settings.get<double>("enderslicer_bead_chain_fan_speed");

        gcode_layer.setIsInside(true);
        // The inner wall insets print first (the chain's backing), then the
        // chain paints rows-first with the chain bead last: it seats and
        // presses into the row.
        for (OpenPolyline& wall : base_walls.getLines())
        {
            if (! wall.isValid())
            {
                continue;
            }
            OpenLinesSet ordered_wall;
            ordered_wall.push_back(std::move(wall), CheckNonEmptyParam::OnlyIfValid);
            gcode_layer.addLinesByOptimizer(
                ordered_wall,
                mesh_config.inset0_config,
                SpaceFillType::PolyLines,
                false,
                0,
                1.0,
                std::nullopt,
                mesh_config.inset0_config.fan_speed);
        }
        std::vector<ChainPaint> paints = std::move(chain_paint_buckets);
        for (ChainPaint& paint : paints)
        {
            GCodePathConfig paint_config = chain_config;
            paint_config.line_width = paint.width_um;
            for (OpenPolyline& wall : paint.lines.getLines())
            {
                if (! wall.isValid())
                {
                    continue;
                }
                OpenLinesSet ordered_wall;
                ordered_wall.push_back(std::move(wall), CheckNonEmptyParam::OnlyIfValid);
                gcode_layer.addLinesByOptimizer(
                    ordered_wall,
                    paint_config,
                    SpaceFillType::PolyLines,
                    false,
                    0,
                    1.0,
                    std::nullopt,
                    paint_config.fan_speed);
            }
        }
        added_something = true;
    }

"""

OLD_BASE_GEN = """        OpenLinesSet base_walls;
        if (! chain_parameters.all_walls)
        {
            BeadAngleGenerator::generateBaseWalls(part.outline, chain_parameters, replacement, base_walls);
        }"""

NEW_BASE_GEN = """        OpenLinesSet base_walls;
        // The chain owns the outer wall: the remaining Cura wall insets
        // (1 .. base-1, with real transitions) stay as the stack behind it.
        BeadAngleGenerator::generateBaseWalls(part.outline, chain_parameters, replacement, base_walls,
                                                chain_parameters.all_walls ? 1 : 0);"""

OLD_ORDER = """        gcode_layer.setIsInside(true);
        // Rows first, chain bead last: it seats and presses into the row.
        std::vector<ChainPaint> paints = std::move(chain_paint_buckets);"""

NEW_ORDER = """        gcode_layer.setIsInside(true);
        // The inner wall insets print first (the chain's backing), then the
        // chain paints rows-first with the chain bead last: it seats and
        // presses into the row.
        for (OpenPolyline& wall : base_walls.getLines())
        {
            if (! wall.isValid())
            {
                continue;
            }
            OpenLinesSet ordered_wall;
            ordered_wall.push_back(std::move(wall), CheckNonEmptyParam::OnlyIfValid);
            gcode_layer.addLinesByOptimizer(
                ordered_wall,
                mesh_config.inset0_config,
                SpaceFillType::PolyLines,
                false,
                0,
                1.0,
                std::nullopt,
                mesh_config.inset0_config.fan_speed);
        }
        std::vector<ChainPaint> paints = std::move(chain_paint_buckets);"""

OLD_TRAIL = """        if (! chain_parameters.all_walls)
        {
            for (OpenPolyline& wall : base_walls.getLines())
            {
                if (! wall.isValid())
                {
                    continue;
                }
                OpenLinesSet ordered_wall;
                ordered_wall.push_back(std::move(wall), CheckNonEmptyParam::OnlyIfValid);
                gcode_layer.addLinesByOptimizer(
                    ordered_wall,
                    mesh_config.inset0_config,
                    SpaceFillType::PolyLines,
                    false,
                    0,
                    1.0,
                    std::nullopt,
                    mesh_config.inset0_config.fan_speed);
            }
        }
"""
