# start_blender_mcp.py -- Blender STARTUP script for embedded/on-device builds.
# Drop into <blender-assets>/scripts/startup/ (e.g. OBLFiles assets on Android)
# or any folder Blender loads at startup. Auto-starts the slim MCP socket server
# so the app/PC client can drive generation without CLI args.
#
# Port: env BLENDER_MCP_PORT, or DEFAULT_PORT (9876). Config file variant:
#   <blender-home>/blender_mcp_port.txt containing the port number.

import os
import sys

# Locate the addon source next to this startup script (assets/scripts/startup)
_startup_dir = os.path.dirname(os.path.abspath(__file__))
_addon_dir = os.path.dirname(_startup_dir)
if _addon_dir not in sys.path:
    sys.path.insert(0, _addon_dir)
if _startup_dir not in sys.path:
    sys.path.insert(0, _startup_dir)

# Bundled addons that are worth having and are off by default.
#
# Blender ships 104 addons and enables 11 of them. The rest are off because they
# add UI panels, and this engine has no UI - but the operators are what matter,
# and they register perfectly well without a window. Between them these are the
# tools for repairing and building geometry: Looptools bridges and relaxes loops,
# F2 fills a hole from a ring of vertices, Bool Tool and Carver cut solids, Extra
# Objects and BoltFactory make parametric parts, tinyCAD intersects precisely.
_MODELLING_ADDONS = (
    "mesh_looptools",
    "mesh_f2",
    "mesh_inset",
    "mesh_tools",
    "mesh_tiny_cad",
    "mesh_snap_utilities_line",
    "mesh_auto_mirror",
    "mesh_tissue",
    "object_boolean_tools",
    "object_carver",
    "add_mesh_extra_objects",
    "add_mesh_BoltFactory",
    "add_curve_extra_objects",
    "curve_tools",
    "lighting_tri_lights",
    "io_import_images_as_planes",
    "io_import_dxf",
)


def _enable_modelling_addons() -> None:
    """Register the modelling addons. Failures are reported, never fatal."""
    import addon_utils

    for name in _MODELLING_ADDONS:
        try:
            addon_utils.enable(name, default_set=False, persistent=False)
        except Exception as error:
            print(f"start_blender_mcp: could not enable {name}: {error}")


def _port_setting() -> int:
    """Port resolution: env var > port file > 9876."""
    try:
        return int(os.environ.get("BLENDER_MCP_PORT", "0") or "0")
    except ValueError:
        return 0

def start() -> bool:
    """Start the MCP server; returns True if started fresh."""
    import bpy  # noqa: F401 -- startup scripts always run with bpy

    _enable_modelling_addons()

    port = _port_setting()
    # Allow override via a tiny file next to the addon (no env on Android).
    for p in (os.path.join(_addon_dir, "blender_mcp_port.txt"),
              os.path.join(_startup_dir, "blender_mcp_port.txt")):
        try:
            with open(p, encoding="utf-8") as f:
                port = int(f.read().strip())
            break
        except (OSError, ValueError):
            continue

    if port <= 0 or port > 65535:
        port = 9876

    host = os.environ.get("BLENDER_MCP_HOST", "") or "localhost"

    try:
        import blender_mcp_slim as bm
    except ImportError:
        # The addon file lives right next to this script?
        import importlib.util
        spec = importlib.util.spec_from_file_location(
            "blender_mcp_slim", os.path.join(_addon_dir, "blender_mcp_slim.py"))
        if spec is None or spec.loader is None:
            print(f"start_blender_mcp: addon not found in {_addon_dir}")
            return False
        bm = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(bm)

    global _server, _stopped
    if _stopped:
        return False
    if _server is not None and _server.running:
        return False
    _server = bm.BlenderMCPServer(host=host, port=port)
    try:
        _server.start()
    except Exception as e:
        print(f"start_blender_mcp: start failed: {e}")
        return False
    print(f"start_blender_mcp: MCP server on {host}:{port} (background={bpy.app.background})")
    if _server.headless_driver:
        # Background mode (blender -b --python ...): the script runs
        # synchronously on the bpy main thread, so keep Blender alive by
        # draining MCP commands here. Exits when the client sends "shutdown".
        print("start_blender_mcp: headless driver loop running")
        _server.run_headless()
        _stopped = True
        print("start_blender_mcp: headless driver exiting")
    return True

_server = None
_stopped = False
if __name__ == "__main__":
    start()

# Auto-start when loaded as a startup script (module import context).
try:
    if __name__ != "__main__":
        import bpy as _bpy
        if not getattr(_bpy.context.scene, "blendermcp_started", False):
            _bpy.types.Scene.blendermcp_started = _bpy.props.BoolProperty(default=False)
            start()
            _bpy.context.scene.blendermcp_started = True
except Exception as e:
    print(f"start_blender_mcp: auto-start skipped: {e}")
