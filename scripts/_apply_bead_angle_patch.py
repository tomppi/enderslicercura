# Bead-angle overhang engine patch (applied after the main patch script).
# The G-code writer hook lives in the main script's brick-wall block; this
# module only installs the generator sources and the marker plumbing.

def apply(root, arc_patch_root, replace):
    cmake = root / "CMakeLists.txt"
    gcode_path_config_h = root / "include" / "GCodePathConfig.h"
    layer_plan_cpp = root / "src" / "LayerPlan.cpp"
    fff_gcode_writer_cpp = root / "src" / "FffGcodeWriter.cpp"

    (root / "include" / "BeadAngleOverhang.h").write_text((arc_patch_root / "include" / "BeadAngleOverhang.h").read_text())
    (root / "src" / "BeadAngleOverhang.cpp").write_text((arc_patch_root / "src" / "BeadAngleOverhang.cpp").read_text())

    replace(
        cmake,
        "        src/BrickWalls.cpp\n",
        "        src/BrickWalls.cpp\n        src/BeadAngleOverhang.cpp\n",
    )

    # Bead-angle paths carry their own preview marker.
    replace(
        gcode_path_config_h,
        """    bool is_brick_wall{ false }; //!< EnderSlicer brick-wall staircase path""",
        """    bool is_brick_wall{ false }; //!< EnderSlicer brick-wall staircase path
    bool is_bead_angle{ false }; //!< EnderSlicer bead-angle overhang path""",
    )

    replace(
        layer_plan_cpp,
        """                                         || last_extrusion_config.value().is_brick_wall != path.config.is_brick_wall;""",
        """                                         || last_extrusion_config.value().is_brick_wall != path.config.is_brick_wall
                                         || last_extrusion_config.value().is_bead_angle != path.config.is_bead_angle;""",
    )

    replace(
        layer_plan_cpp,
        """                else if (path.config.is_brick_wall)
                {
                    // App-owned semantic marker for the brick-wall staircase
                    // courses, classified exactly in EnderSlicer's layer preview.
                    gcode.writeComment("TYPE:BRICK-WALL");
                }
                else if (path.config.is_arc_overhang)""",
        """                else if (path.config.is_bead_angle)
                {
                    // App-owned semantic marker for the bead-angle pressed rings.
                    gcode.writeComment("TYPE:BEAD-ANGLE-OVERHANG");
                }
                else if (path.config.is_brick_wall)
                {
                    // App-owned semantic marker for the brick-wall staircase
                    // courses, classified exactly in EnderSlicer's layer preview.
                    gcode.writeComment("TYPE:BRICK-WALL");
                }
                else if (path.config.is_arc_overhang)""",
    )

    replace(
        fff_gcode_writer_cpp,
        '#include "BrickWalls.h"\n',
        '#include "BeadAngleOverhang.h"\n#include "BrickWalls.h"\n',
    )
