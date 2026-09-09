# BLENDER MCP ENGINE — Integration guide for the app developer (other AI)

You (the app AI) only need to do the **app-side wiring** listed below. The
engine, the MCP addon, and the MCP server are already built, tested, and
staged. Read this whole file once, then follow section 3.

---

## 1. The loop — how it works, step by step

This is the core UX. Everyone (you, the AI session, the engine) has one role:

| Role | Session-scoped? | Job |
|---|---|---|
| User | - | states what they want, looks at the result, gives feedback |
| AI session | one open conversation | owns the design intent; issues MCP calls; keeps the spec |
| MCP server | external process, launched by the AI session | converts AI calls into socket commands |
| Blender engine | in-app, one long-lived process | holds the live bpy scene; executes code; exports STL |
| EnderSlicerCura app | one process | boots engine, watches exports/, shows STLs |

### Sequence of one iteration (e.g. "make a cable-clip grid")

1. **Session start (once):** AI session starts `server.py` with
   `BLENDER_HOST/BLENDER_PORT` (dev: `adb forward tcp:9876 tcp:9876`,
   done automatically by `device_bridge.py`). The app (already running) has
   booted the engine via `BlenderBridge.start()` — the addon is listening on
   9876. AI pings (`get_scene_info`) to confirm.

2. **User request:** "make a cable clip grid, 4x2, 6mm cables" (in the app's
   chat, or wherever the user talks to the AI).

3. **AI builds the model** with MCP tools:
   - `execute_blender_code` — the big one: sends a bpy script. The script
     either builds from the parametric library (`generate_models.py`,
     e.g. `cable_clip_grid(cols=4, rows=2, cable_d=6)`) or hand-written bpy
     (modifiers, booleans, precise geometry). End of script: export STL to the
     shared dir, e.g. `<files>/blender/exports/model.stl`. (The lib helper
     `_export_stl` exists; scripts print `GEN_RESULT <json>` for the AI.)
   - `get_scene_info` / `get_object_info` — read the scene before/after
     (what exists, dimensions) so the AI stays honest about state.
   - (optional) `slice_model` — PC-side PrusaSlicer bridge: verifies the STL
     actually slices and reports layers/print time, so the AI can warn
     "this is 13 minutes" before the user sees it.

4. **The app shows the result — automatically.** The app watches
   `exports/`; a new/updated `.stl` → import + display it (replace the
   previous view). The user does nothing manually.

5. **User feedback:** "make the holes 1 mm bigger" → **new iteration.** The
   Blender scene is STILL ALIVE from step 3 — the AI does not regenerate from
   scratch; it sends a delta script (`execute_blender_code`) that edits the
   existing objects (e.g. `obj.dimensions.z *= 1.2`), re-exports the STL
   (overwrite the same file), and the app refreshes. Each iteration = one
   execute + one export + one app refresh.

6. **Done:** user accepts → AI can leave the scene as-is (session continues,
   next request reuses it) or `reset` it (execute a factory-reset script) on
   request. Closing the app stops the engine; the AI conversation keeps the
   intent, so a later session can rebuild deterministically.

### State model — important

- **Design intent lives in the AI conversation** (the user's words + the AI's
  plan). The Blender scene is derived state.
- **Scene state lives in the engine** (kept warm between iterations; that is
  why edits are cheap deltas, not rebuilds).
- **The app holds nothing but the latest STL on screen.** If the app/engine
  restarts, the scene is gone; the AI regenerates from its conversation
  (deterministic generators make that easy).
- **Every iteration must end in an STL in `exports/`** — that file is the
  only contract between engine and app.

### Failure modes the AI should handle

- Engine not up yet (cold boot ~10-20 s): retry `get_scene_info` until
  success.
- App killed the engine mid-edit: next request re-runs `BlenderBridge.start()`
  and the AI rebuilds (from the conversation).
- Generate produced nonsense geometry: `slice_model` or
  `get_scene_info` (dimensions/vertex counts) catches it in-step; the AI
  can also render a preview via a screenshot-render step later if wanted.

### Boundary: what the AI NEVER does

- Never slices in Blender (the app's engines do that when the user slices).
- Never touches the app UI: the app updates purely by watching `exports/`.
- Never asks the app to "start the engine" — the app owns engine lifetime.


## 2. Decisions already locked (do not re-negotiate)

- **MCP server = external.** `server.py` runs wherever the AI session runs.
  The app never starts it.
- **Engine runs embedded** via `libblender_exec.so` in **background mode**
  (`-b --python start_blender_mcp.py`). No GL/viewport needed for generation.
- **MCP socket = TCP 9876 on localhost** (override: `blender_mcp_port.txt`
  next to the addon, or `BLENDER_MCP_PORT` env).
- **STL handoff = a directory** the engine writes to and the app watches
  (suggested: `<app files>/blender/exports/`).
- **arm64-v8a only** for the engine (the app's other engines are arm64 too;
  the x86_64 emulator will not run the Blender engine — use a real device).

## 3. What YOU must do (the entire work list)

### 3.1 Copy the runtime shared libs into jniLibs (CRITICAL — not done yet)

`libblender_exec.so` is already in `app/src/main/jniLibs/arm64-v8a/`, but the
**120 runtime shared libs it depends on** are still staged at
`native/blender/blender-jniLibs/*.so`. They must land in
`app/src/main/jniLibs/arm64-v8a/` too, or dlopen will fail:

```
copy native/blender/blender-jniLibs/*.so  ->  app/src/main/jniLibs/arm64-v8a/
```

(These are the engine's prebuilt deps: Alembic, OpenImageIO, OpenColorIO,
libcpython.so, boost, ffmpeg, TBB, USD, draco, ... — 120 files, identical to
what the OBlender app ships.)

### 3.2 First-run asset extraction

`app/src/main/assets/blender/` (~480 MB) contains `python/` + `scripts/`
(CPython 3.11 stdlib + Blender scripts + our addon in
`scripts/startup/`). Like your other engine assets, materialize them to disk
on first run (they cannot be imported in place):

```
<getFilesDir()>/blender/python/      <- from assets/blender/python
<getFilesDir()>/blender/scripts/     <- from assets/blender/scripts
<getFilesDir()>/blender/exports/     <- create; engine writes STLs here
```

(If you already have a generic "copy assets to files dir" helper (you do for
gcode/printers), reuse it for `blender/`.)

### 3.3 Wire BlenderBridge

Already written: `app/src/main/java/com/tomppi/enderslicer/nativebridge/BlenderBridge.kt`
(`System.loadLibrary("blender_exec")`, `start(home, config, port)`,
`stop()`, `nativeBlenderIsRunning()`).

Suggested call sites:
- Start when the app's model-generation UI/AI chat is first used (or at app
  startup — the engine takes ~10-20 s to boot the first time; prefer lazy).
- On service/process death, the engine dies with the process — start() again
  next time (it's idempotent: guards on `nativeBlenderIsRunning()`).
- Stop on app exit: `BlenderBridge.stop()` (graceful addon shutdown).

Example:

```kotlin
val config = File(filesDir, "blender").absolutePath
BlenderBridge.start(
    home = filesDir.absolutePath + "/blender-home",
    config = config,
    port = 9876,
)
```

Path contract inside the engine (set by `blender_exec.cpp`):
`BLENDER_SYSTEM_SCRIPTS = <config>/scripts`, `PYTHONHOME = <config>/python`,
`BLENDER_SYSTEM_DATAFILES = <config>/3.6/config/datafiles` (create it),
`HOME/XDG_CACHE_HOME = <config>/../home`.

### 3.4 Watch the STL handoff dir

Poll `config/exports/` (or use FileObserver). When a new `.stl` appears (the
AI finished an edit), import it with your existing STL import path and show
it; keep the old model until replaced. That is the entire UI contract.

### 3.5 Build considerations

- The APK grows by ~1.8 GB (1.34 GB lib + 480 MB assets). If the build chokes
  on the big native lib, enable legacy packaging in `build.gradle.kts`:
  `packaging { jniLibs { useLegacyPackaging = true } }`.
- Do NOT strip/optimize `libblender_exec.so` unnecessarily; it is a 1.34 GB
  blob (prebuilt linker kept all symbols). If size matters later, ask for a
  release build of the engine wrapper.
- `libblender_exec.so` was linked with **NDK 28.2** (your pinned NDK). Do not
  recompile it; it is prebuilt and verified.

### 3.6 What NOT to touch

- `native/blender/*` source + `blender-gensrc/`: used to rebuild the wrapper
  only. You never need to recompile it.
- The addon files in `assets/blender/scripts/startup/`: the engine boots them;
  modify only if YOU are changing the MCP transport contract.
- The engine static libs (`PrintShare/blender-engine-arm64/libs`): prebuilt.

## 4. Verifying it works (runbook, then hand to user/AI)

1. Build + install on a real arm64 device (or arm64-capable image).
2. Start the app → trigger `BlenderBridge.start()` → logcat: `BlenderBridge: started=true port=9876`.
3. On the dev machine:
   ```
   adb forward tcp:9876 tcp:9876
   python blender-mcp-slim/server.py        # FastMCP on stdio
   ```
   (or `python blender-mcp-slim/device_bridge.py` — it does both + ping.)
4. From the AI session, call `execute_blender_code` ("make a 20 mm cube") →
   engine writes `.../blender/exports/cube.stl` → app loads it (3.4).
5. Full-loop check: 3 builds, user feedback, 3 revised STLs.

## 5. What's already verified (evidence)

- Desktop MCP full loop on Blender 3.6.23 (same code line): cube -> STL ->
  PrusaSlicer 66-layer G-code; `generate_part` wafer; timer mode (embedded)
  PASS. Tests in `blender-mcp-slim/{smoke,client,mcp_client,final}_test.py`.
- Engine: epai harness 500/500 targets; DNA+RNA passes generated on an arm64
  device; `bin/blender` 1.34 GB arm64 ELF; 148 static libs.
- Wrapper: full link + symbol check (`nm -D` shows all 3 JNI exports +
  `mainBlenderInitial`), staged at the paths in 3.1/3.2.

## 6. Open questions for you (non-blocking)

- Asset unpack: do you already have a first-run extraction helper that we
  should reuse (instead of a new one)?
- Should the engine start on app launch or lazily on first generation?
  (Recommendation: lazy — it costs ~1 GB RAM when running.)
