---
name: blender-mcp-engine
description: Drive the Blender MCP socket engine embedded in the enderslicercura Android app (JSON protocol on 127.0.0.1:9876 via adb forward), including the STL export + hot-load handoff into the slicer UI.
whenToUse: When asked to modify, reshape, deepen or otherwise edit an existing model on the device - this is the only modelling environment there is, and the GPU box is not a substitute. Also when generating 3D models through the phone's embedded Blender engine, writing STL handoff files, debugging MCP command responses, or checking that an exported model reached the app.
---

# Blender MCP Engine (enderslicercura embedded)

**This is the device's modelling environment, and the only one.** Anything that *changes* an existing model - reshaping a profile, deepening a dish, adding or subtracting material, fixing a wall - belongs here, in bpy, over the MCP socket.

The pipeline in [image-to-3d-model](../image-to-3d-model/SKILL.md) does exactly one thing: turn a photograph into a mesh on the GPU box. It is not a modelling tool. Reaching for it to edit a model means reimplementing, badly and with whatever Python happens to be installed there, mesh surgery the embedded Blender already does properly - and the box's `/usr/bin/blender` is broken besides.

The app bundle (package `com.tomppi.enderslicercura`) runs Blender 3.6 **inside the app process** via `libblender_exec.so` (in-process wrapper, `mainBlenderInitial` on a detached thread), with a slim MCP addon serving on **localhost:9876**. Blender stdout/stderr appear in logcat as `I app_process64` when the `wrap.com.tomppi.enderslicercura` property is set to `logwrapper` (capture with `adb logcat -d -s app_process64`).

## 1. Connect

- Device: `192.0.2.20:5555` (`adb -s 192.0.2.20:5555 ...`); root shell via `su -c`.
- **Do not use the device as a camera.** `adb shell screencap` and `exec-out screencap -p` photograph the user's own screen, while they are using the phone, and tell you nothing about the mesh. To look at a model, render it in the engine and read the PNG back - that is the camera for this job. Verify geometry from renders plus measurements computed in bpy (ring-band vertex counts, z extents, non-manifold edge counts). Leave the app's UI alone generally: it is the user's device, not an observation port.
- **Do not invent directories inside the app's private storage.** Two directories exist for handoff - `files/blender/imports/` (model into the engine) and `files/blender/exports/` (engine out to the app) - and nothing else belongs there. An agent once made up `files/blender/incoming/` with `su`, copied a 67 MB mesh into it and left it behind: a root-owned directory, in a place the app cannot write, that no code had ever heard of. If you need somewhere to put a file first, that is what the drop box is for.
- **Drop box: anything you push to the device goes in `/sdcard/Download/dsh-agent/`, never the Download root.** Models stay there - it is the copy the user can find; probe scripts and screenshots are scaffolding and come back out.
- Forward (required): `adb -s 192.0.2.20:5555 forward tcp:9876 tcp:9876`. The MCP socket MUST bind `localhost` (the addon's default; app passes `BLENDER_MCP_HOST` via the C++ wrapper). **Do NOT bind the Tailscale/CGNAT IP** (100.64.0.0/10): Tailscale Android does not deliver inbound TCP to app sockets (SYN times out / ports RST from tailscaled's userspace stack; verified 2026-09-09), so a tailnet-bound socket breaks the adb-forward loopback path and is unreachable anyway.
- The engine lives only while the app process runs. Relaunch: `adb shell am start -n com.tomppi.enderslicercura/com.tomppi.enderslicer.MainActivity`. First launch after an install can hit a transient wrapper/zygote race ("start timeout", signal 9) — just launch again.
- Quick liveness: `adb shell su -c 'ss -tlnp | grep 9876'` (owner pid should be the app).

## 2. Protocol

Plain TCP, one JSON object per message (no framing beyond a single write per request; newline-free):

```text
→ {"type": "ping", "params": {}}
← {"status": "success", "result": {"pong": true}}

→ {"type": "execute_code", "params": {"code": "..."}}
← {"status": "success", "result": {"executed": true, "result": "<captured stdout>"}}
```

Commands (`params` are keyword args, so `{"type":"execute_code","code":...}` without `params` FAILS with "missing required positional argument"):

| type | params | notes |
|---|---|---|
| `ping` | – | liveness; touches no bpy data |
| `execute_code` | `code` | namespace: `bpy`, `mathutils`, `json`, `os`; stdout captured via `redirect_stdout` |
| `export_stl` | `filepath` | writes ALL scene meshes as binary STL via native writer (no `bpy.ops`); returns `{filepath, bytes, triangles}` |
| `get_scene_info` | – | name, object_count, objects[{name,type,location,dimensions}] |
| `get_object_info` | `name` | + vertices/polygons for MESH |
| `get_world_state_snapshot` | – | object names |
| `get_addon_info` | – | name, version, headless_ready |
| `shutdown` | – | drains queue, exits headless driver, Blender exits (app process keeps running) |

Errors: `{"status": "error", "message": "<exc>"}`. A command run in `blender -b` (headless) is executed on the MCP addon's **main-thread driver loop** (`start_blender_mcp.py` calls `_server.run_headless()`); `bpy.data` access is safe there. New objects persist in the scene between commands; use `bpy.data.objects` to find them within `execute_code`.

## 3. STL handoff (hot-load into the slicer UI)

1. Export to the handoff dir: `/data/user/0/com.tomppi.enderslicercura/files/blender/exports/<name>.stl` — either via `export_stl` or inside `execute_code`:
   ```python
   import os, blender_mcp_slim as bm
   base  = "/data/user/0/com.tomppi.enderslicercura/files/blender/exports"
   final = base + "/model.stl"
   n = bm._mesh_to_binary_stl(bpy.context.active_object.data, final + ".part")
   os.replace(final + ".part", final)   # the rename is what publishes it
   print(n, "tris")
   ```

   **Export through a temporary name and rename into place.** A `.part` suffix is invisible to the poller, so nothing is published until the rename - which is atomic. Writing straight to `.stl` instead exposes the file while it is still being written, and a 67 MB mesh took seconds to write.
   (helper `_export_scene_stl(path)` exports every scene mesh; header is `enderslicercura MCP STL`, little-endian binary STL.)
2. The app **polls** the exports dir every 500 ms (authoritative; FileObserver kept as accelerator) and imports any new revision, deduped by `path|size|mtime` signature.

   It refuses anything that is not a **whole** STL, because a binary STL declares its own length: it reads the triangle count from the header and requires `84 + 50 x triangles == filesize`, with an ASCII export required to end in `endsolid`. So a half-written file can no longer be imported.

   That guard is a safety net, not a licence to write carelessly. Growing a file in place still costs a probe on every poll and, before the guard existed, dispatched **eleven revisions of one export - seven of them truncated mid-triangle - and left 486 MB of staged copies** in `files/models/`. Write atomically (step 1).
3. On dispatch the app stages a private copy `files/models/blender-<nanoTime>.stl` and swaps it into the UI ("Imported … from the Blender engine"). **Always export a fresh unique/canonical filename per generation** — size+mtime signature means a rewrite of the same path only re-fires if mtime changes.
4. Verify: `adb shell su -c 'ls -la /data/user/0/com.tomppi.enderslicercura/files/models/'` — a new `blender-*.stl` proves the full chain.

## 4. Debugging

- App-side tags: `BlenderEngine` (resources materialization, watch/poll lines, export dispatch), `BlenderBridge` (`started=true port=9876`).
- Engine-side: `adb logcat -d -v threadtime | grep app_process64` (works only when the wrap property is set; note the wrap wrapper occasionally causes a one-shot start race — relaunch to clear).
- Structure checks (root shell): `ls -la /data/user/0/com.tomppi.enderslicercura/files/blender/` — `python/lib/python3.11/` must exist (stdlib), plus `scripts/`, `exports/`, and `.resources-version` marker. Bumping `RESOURCES_VERSION` in `BlenderEngine.kt` forces re-extraction on next launch.
- `files/blender/exports/` has the handoff files; `files/models/` has staged imported copies.
- Nightly/off-WiFi note: the phone (phone-host, `100.64.0.20`) is reachable via tailscale ICMP, but no inbound TCP to apps; all device IO goes through `adb -s 192.0.2.20:5555` while on the same WiFi.
