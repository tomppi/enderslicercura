# native/blender — Blender 3.6 MCP engine wrapper (libblender_exec.so)

Drop-in engine wrapper for enderslicercura. Pattern: identical to the
CuraEngine/Prusa exec wrappers (one shared lib + JNI + assets).

## What's here
- `blender_exec.cpp` — JNI wrapper: starts Blender **embedded** in background
  mode (`-b --python start_blender_mcp.py`), which boots the MCP addon (socket
  server, default port 9876) inside the engine. No GL/viewport needed for
  generation — the AI MCP server (external) drives bpy via the socket.
- `creator/` — epai-patched creator sources (from APP-android_arm64) that
  provide `mainBlenderInitial(argv)` (embedded entry).
- `blender-gensrc/` — generated DNA/RNA headers from the harness build
  (dna.c, dna_type_offsets.h, rna_*_gen.c, RNA_*.h).
- `blender-jniLibs/` — the engine's runtime shared libs (120 `.so`,
  Alembic/OIIO/cpython/boost/ffmpeg…) — copied into the app's jniLibs at
  install time.
- `blender-libs/` *(optional vendored copy of the 148 static libs)*.

## Building (verified: 1.34 GB arm64 .so, exports OK)
Env: BLENDER_ENGINE_LIBS (engine package libs dir), BLENDER_SO_DIR,
BLENDER_SRC_DIR (Blender source root), BLENDER_LIBDIR (lib-android_arm64),
BLENDER_PYTHON_INCLUDE (cpython/include).

CMake: `-G Ninja -DCMAKE_TOOLCHAIN_FILE=<NDK 28.2>/build/cmake/android.toolchain.cmake
 -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared`
(NDK 28.2 — the app's pinned NDK; NDK 21 fails on the patched creator's C++17
math usage).

Result: `libblender_exec.so` — copy to `app/src/main/jniLibs/arm64-v8a/`
(done). Kotlin: `app/.../nativebridge/BlenderBridge.kt`
(System.loadLibrary("blender_exec"); start(home, config, port)).

## Runtime assets (app must package)
- `assets/blender/python/` — CPython 3.11 stdlib (engine package python/) —
  MUST match the engine's cpython 3.11.4.
- `assets/blender/scripts/` — Blender scripts + `startup/{start_blender_mcp.py,
  blender_mcp_slim.py}` — auto-boots the MCP addon.
- `assets/blender/3.6/config/datafiles/` — OCIO + locale datafiles.

## MCP server (external, per user decision 2026-09-09)
`blender-mcp-slim/server.py` (FastMCP, 5 tools) runs wherever the AI session
runs; connects via `BLENDER_HOST`/`BLENDER_PORT` (default localhost:9876).
`device_bridge.py` does `adb forward tcp:9876 tcp:9876` for dev.

## Why NDK 28.2 and not the harness's 21.4
The epai harness pins NDK 21.4 (their 3.6 line), but its libc++ breaks on the
patched C++ creator sources (`std::is_trivial_v` etc.). The wrapper compiles
only the creator glue; everything else came prebuilt from the harness. The
app's pinned NDK 28.2 works (verified: full link + exports).
