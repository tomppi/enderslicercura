# Changelog

All notable changes to EnderSlicerCura are documented here. The format is based
on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.0] - 2026-09-13

### Added

- **Modelling from scratch**: a full-screen destination reached from Plate ▸ Blender, holding the model, a chat and an exit button. It runs its own harness conversation with its own session, split away from the photo-to-3D chat. The engine starts on its default scene, so a first visit loads the cube it already has.
- **One camera, shared with the agent.** The view is the engine's own render rather than a second viewport, so what the user sees is what the agent sees - same scene, same camera, same shading - and there is no coordinate frame to translate between. Orbit with one finger, pinch to close in on a detail, two fingers to move the point being orbited. The camera belongs to the agent; **Take camera** is locked while it works, and sending a message hands it back.
- The frame size is published alongside the camera, so the agent renders the user's exact picture rather than merely pointing at the same place.
- **Workbench and EEVEE render in the embedded engine.** They never could before; see Fixed.

### Changed

- The chat takes a share of the screen rather than a fixed 260 dp strip, and collapses entirely when the model wants the room.
- The user's own messages are read from the session log, so long prompts are shown in full rather than clipped to a hundred characters.
- `adb` reaches the phone over the tailnet, which works anywhere, rather than only on the same WiFi.

### Fixed

- **GPU rendering in the embedded engine.** Any render with a GPU engine killed the app outright - no reply, no log, no tombstone, and a crash report with an empty backtrace. Five faults sat in a row, each hiding the next: `GHOST_SystemAndroid` dereferenced a null `android_app` in its constructor, before EGL was reached at all; `GHOST_ContextEGL` never pushed the `EGL_RENDERABLE_TYPE` its own comment required and pushed `EGL_SURFACE_TYPE` twice; all three Android context factories asked for desktop GL 4.x, which no Android EGL can bind; `WM_init_opengl()` reported no GPU backend because nothing on that path runs backend detection; and the first GPU render then aborted in Boost.Locale, which falls back to ICU data this engine does not ship. Workbench now renders at about 10 ms a frame. The patches and the reasoning are in `native/blender/patches/`.
- Blender's crash handler writes a crash file with an empty backtrace and then exits, so Android never wrote a tombstone either; the wrapper now passes `--disable-crash-handler`, which is what made the above findable.
- Uploading a model to the engine only copied the file into the import directory, so the engine kept whatever it already held; it now loads it.
- A restarted engine put back the file the user sent rather than the newest thing the engine produced, discarding everything done to it since.

## [1.2.1] - 2026-09-14

### Fixed

- The Blender engine fetch staged the binary, the Python stdlib, the Blender
  scripts and the datafiles but not the license texts, so a build from the
  released `blender-engine-arm64-v1.2.0.zip` produced an APK without the GPL
  texts and CI's own asset check failed on them. The script now stages them
  from the engine package, falling back to the tracked copy in
  `native/blender/assets/licenses`, and fails loudly when neither exists;
  `verifyDebugApkContents` checks they reach the APK.
- **AGP's default asset filter drops every directory whose name starts with
  `_`.** In the embedded CPython tree that quietly removed `numpy/_typing`,
  `numpy/testing/_private`, `setuptools/_distutils` and `pkg_resources/_vendor`,
  so `import numpy.typing` failed inside the engine, and it took one license
  text with it. The packaged assets now keep `_`-prefixed directories; dotfiles
  and VCS metadata are still ignored, `__pycache__` is pruned rather than
  shipped, and the APK check covers both the recovered package and the license.
- **The engine package shipped the engine without the libraries it loads.**
  `blender-engine-arm64-v1.2.0.zip` carried `libblender_exec.so` and the assets
  but none of the 120 runtime libraries beside it (bundled CPython, ffmpeg,
  OpenVDB, USD, OpenImageDenoise), and `app/src/main/jniLibs` is delivery rather
  than source - so a build from the release, the CI one included, produced an APK
  whose Blender engine could not be loaded at all. The package now carries
  `jniLibs/`, the fetch script stages it (falling back to
  `native/blender/blender-jniLibs`), and both the fetch step and
  `verifyDebugApkContents` fail when the runtime libraries are missing.
- **Builds were signed with a throwaway key.** AGP generates a debug key when
  none is configured, so every CI run produced an APK that refused to install
  over the last one (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) and left uninstalling -
  and losing the app's data with it - as the only way forward. `keystore/debug.keystore`
  is committed and wired into `signingConfigs`, so CI and local builds share the
  key the 1.2.0 APK was published with and upgrades work in place.
- **A `shutdown` command - or a port already in use - killed the whole app.** The
  MCP addon runs as `blender -b --python`, so *returning* from that script ends
  Blender's background main, and Blender's teardown calls `exit()`. Sending the
  documented `shutdown` (reproduced on the device) or starting the engine while
  9876 was held took the app process with it, losing whatever was unsaved; Android
  restarted the keeper service, which made it look like a restart rather than a
  death. The addon now parks instead of returning, records a failed bind in a
  status file, and serves again when the app asks through
  `blender_mcp_restart.txt`.
- **`native/blender/assets/startup/blender_mcp_slim.py` was never in the
  repository.** The MCP server that ships inside the app existed only in the
  delivery tree and the release archive, so nothing in git described what the
  engine actually runs - and a fix could not have reached a clone or CI. Both
  startup scripts are tracked now, and the fetch script lays them over the
  package's copies.
- **"Stop Blender engine" did nothing.** It set a native flag nothing read, so the
  engine thread, its socket and the loaded scene kept running while the UI said
  otherwise. Stop now sends the socket `shutdown`, cancels the exports watcher and
  logs what actually happened; the engine's memory is released when the app exits,
  which is the only thing that can release an in-process engine.
- **The engine's socket was open to every app on the device.** Any co-installed app
  could reach 127.0.0.1:9876 and run Python as this app's uid - reading the app's
  private files out of that socket was one request away. The app
  now generates a token, writes it next to the addon and sends it with every
  request; the engine refuses everything else. An engine started by hand with no
  token file stays open, which is the development path.
- The MCP server only understood one request per read: two requests arriving
  together were never parsed, never answered and never dropped, and the receive
  buffer had no ceiling. Requests are now newline-delimited as well as
  unterminated, the buffer is bounded, and a request that arrives while a command
  has been running for more than ten seconds is told the engine is busy instead of
  waiting out its own timeout.
- **The preview never returned to full size.** After a gesture - or a rotation, or
  collapsing the chat - the picture stayed at the 512-pixel interactive frame: the
  re-render wrote an equal camera back into snapshot state, which is not a change,
  and the render loop waits for a change. The render trigger is now its own state,
  so `Copy`-equal writes cannot swallow it.
- The published handoff was never imported if the engine was still booting when
  Modelling opened: a failed `isOnDefaultScene` read as "the scene has content",
  and the import was single-shot. The question now has three answers - yes, no,
  could not ask - and the import waits for the engine in the third case.
- An engine restart re-imported the export it had already handed over (replacing
  the model the user had just sent) and left another poller and FileObserver
  running: the newest export is claimed like any other, the watcher is cancelled
  and replaced, and the poller is owned by a job that can be cancelled.
- An export that arrived while the app was busy was claimed and then dropped, so
  the model was lost with no message. The newest one is now taken as soon as the
  running operation finishes.
- Camera writes ran on the UI thread, one file write and rename per pointer event,
  and an agent camera write could be adopted mid-drag, cancelling the gesture and
  jumping the view. Writes go to a serialised background dispatcher, and the agent's
  camera is adopted between gestures.
- A socket kept the timeout it was created with, so an import could be cut off at
  20 s or a render left blocking for 180 s; a reply split across a read boundary
  decoded into U+FFFD. Both are fixed at the connection.
- One transient engine-start failure disabled the engine for the life of the
  process, with no retry and nothing said; startup failures now re-arm.
- **PrusaSlicer prints over ~100 g were rejected after slicing.** The G-code policy
  bounded `M74 W` to 0..100 as a percent, but PrusaSlicer emits filament weight in
  grams (`GCode.cpp` `w = volume * density * 0.001`, and the shipped profile emits
  `M74 W[extruded_weight_total]`), so the app's own sanitizer threw on its own
  output.
- **Adaptive bed mesh was lost after any layer-event edit.** The base G-code was
  copied out before the AML block was injected, and re-applying layer events rebuilt
  the published file from that base, so the `C29` region quietly disappeared.
- **"All settings" values were ignored whenever an imported profile was active.**
  Only the standalone settings transport accepted them; the resolved-profile
  transport dropped them silently while the UI listed them as used by every slice.
- "Interface thickness" did nothing without an imported profile: the standalone
  transport overwrote the user's value with `layer height x 4`.
- The Prusa nozzle-path preview kept every move of a large print instead of
  sampling to the cap the Cura twin honours, and labelled the result as sampled.
- An extra "all settings" value was never validated: one blank or malformed entry
  was persisted and re-sent on every later slice, failing them all with a generic
  engine error.
- `PrusaConfigWriter.MANAGED_KEYS` had no reader and disagreed with the list the UI
  annotates from, so the "(managed by the app)" hint was wrong on both sides.
- A failure between starting PrusaSlicer and opening its log sink leaked the child
  process while its workspace was deleted underneath it.
- `scripts/setup.sh` - the advertised clean-clone path - built CuraEngine without
  `APP_JNILIBS_DIR` (so nothing was staged) and never fetched the Cura definitions
  (which are not in the repository at all), so it could not produce a working APK.
- The Gradle APK checks were orphaned: `verifyDebugApkContents` verifies CuraEngine
  only, and the Prusa and Blender checks that hang off it are run by nothing - while
  the README and CI present that command as the three-engine check.
- `fetch-prusa-engine-android.sh` died on an unset `ANDROID_NDK_HOME` halfway
  through a local staging run, and never recorded which run or branch its engine
  came from.
- The PrusaSlicer dependency cache was keyed on a static string, so a change to the
  deps build silently reused the old bundle (the workflow asked a human to bump a
  `-vN` suffix by hand).
- `.build-artifacts/prusa-engine/` (92 MB of a 2.9.6 engine) was committed to the
  repository and read by nothing; the workflow that wrote it now uploads an
  artifact instead.
- The Cura-resource version check in CI could not fail - the fetch script writes
  the file it greps - and the skills publish script only looked for addresses it
  already knew, reported "clean" for anything new, and printed the address it
  found into the log.
- Stale documentation: the front page still said 1.1.0, BumpMesh/filaSim were said
  to be prepared before `preBuild` (they hang off `mergeDebugAssets`), the notices
  pointed at an untracked directory and claimed a trademark licence that does not
  ship, and two runbooks described menu paths the 1.1.0 redesign removed.
- **The engine could still be left dead after a stop.** The addon cleared its
  running flag on `shutdown` but never closed the listening socket, so the park
  loop's rebind of the same port could fail with `EADDRINUSE` - and the app kept
  `started = true`, so it never asked again. The socket is closed now, the app
  waits for the engine to answer before believing it is back, and re-arms when it
  does not.
- A Blender export that arrived while the app was busy was stored from an IO thread
  and cleared on the main thread with a check-then-null, which could drop it after
  the engine had already claimed the revision.
- The exports `FileObserver` was never stopped, so every engine restart left
  another watch and thread behind, and camera publishes could reach the file out of
  order, leaving the agent reading a stale camera.
- **Model paths are escaped before they reach the engine.** They were pasted into
  a Python raw string, so an apostrophe in an agent-named export made every import
  a syntax error (and the retry loop then kept at it for two minutes), and a
  crafted name was Python running inside the app process.
- **The model VBO leaked on every placement change.** `glGenBuffers` ran on every
  rotate, scale, move, lay-flat and import while nothing in the app ever called
  `glDeleteBuffers`: a 200k-triangle model orphaned about 14 MB a tap, and once
  `glBufferData` failed the viewer drew from an empty buffer and the model vanished
  until restart.
- Smart Infill's mismatch guard could never fire - it asked the runtime that had
  just been cleared - so a stale package was never reported or dropped and later
  slices silently lost its density modifiers; its validation flag could also stick
  true and disable Slice until restart.
- The modelling standing brief was never delivered: it was sent in the same frame
  as the asynchronous connect, failed with "Connect to the harness first", and was
  never retried. Rotating on the modelling screen also lost the transcript and any
  in-flight reply.
- The model position fields were unusable in comma-decimal locales (pre-filled
  "12,5" and rejected as input), PrusaSlicer's "auto" extrusion width of 0 printed
  "flow Infinity%", and a rejected "all settings" value explained itself only on the
  Plate tab while the Add button lives on the Settings tab.
- The OctoPrint API key was deleted whenever a decrypt failed - a keystore that was
  briefly unavailable cost the user a credential only OctoPrint's web UI can
  reissue; the harness config was included in cloud backup although it is this
  device's business alone; and the harness launch token was stored, encrypted, for a
  request path that never read it - it is not stored at all now, and a ciphertext or
  Keystore key an earlier build left behind is deleted on the next save.
- Chat prompts were paired to turns by position whenever the counts matched, so a
  turn with no user message next to one with two showed the wrong prompt above an
  answer and dropped another.
- The webcam loopback guard tested an impossible byte pattern, so an address like
  `http://[0:0:0:0:0:0:0:1]` was neither rejected nor rewritten and the app could
  fetch its own loopback; the nozzle-path pan constants were about 2% off the eye
  distance the renderers actually use.
- A unit test covering the probe-points setting had no `@Test` annotation and never
  ran, the "real CuraEngine tests ran" CI proof also matched all-skipped suites,
  and the Blender addon had no automated check at all - CI now compiles it and runs
  a stubbed test of the token, framing, busy and shutdown-socket paths.
- **A failed engine start still ended the app process.** The park decision was read
  off the server's own `headless_driver` flag, which is set only once the bind
  succeeds - so a start that failed, a port still held most likely, looked like the
  desktop case and returned from the startup script. That return is the one path
  that ends Blender's background main, and Blender's teardown calls `exit()`, which
  takes the app and everything unsaved in it. The branch now keys on
  `bpy.app.background`, which is true whether or not the port was free.
- **Send to Blender could never load the model.** The hand-off client was built
  without the engine's token, so the engine refused the import and the retry loop
  kept at it for its full two minutes before reporting that the engine would not
  load it. The client carries the token now, so the import lands on the first
  attempt.
- **"Stop Blender engine" froze the menu.** Once a stop really reaches the engine it
  does socket work - up to two seconds to connect and five to read the reply - and
  that is longest exactly when the engine is busy, which is when a user reaches for
  the button. It ran on the main thread; the click now records the intent and the
  socket work runs off it. The keeper service no longer holds its wake-lock for the
  life of the process either: it takes a ten-minute lease that every engine command
  re-arms, so the engine keeps the CPU awake while it works and lets the device
  sleep when it does not.
- **"Octet" infill printed hollow parts.** The dropdown stored `octet`, which is
  not a value Cura's `infill_pattern` knows: the engine maps an unknown pattern to
  no infill at all and still reports a successful slice, so a part the user expected
  to be filled came off the printer as walls and skins. Cura calls that pattern
  `tetrahedral`, and that is what the dropdown stores now. Honeycomb and octagon
  spacing also ignored Cura's density-dependent factor, so those two patterns
  printed at a density of their own - and `infill_pattern` / `support_pattern`
  could still be set from the "all settings" extras after the line distance had been
  derived from them. They are refused there now, and a persisted value that
  contradicts the derived distance is filtered out on restore.
- **The geometry maths behind three features was wrong.** The conformal vertex key
  packed three quantised axes into one integer without masking them, so a negative Y
  sign-extended into the fields above it and two points that differed only in X
  packed to the same id - and those ids are the builder's only connectivity input,
  so unrelated facets were welded into one region and the wrong boundary measured,
  on any mesh whose coordinates cross the bed centre. Each field is masked to its 21
  bits now. The 3MF transform was checked on a single axis, so a
  degenerate scale on either of the other two reached the plate placement; it is
  checked on every axis. And the orthographic preview derived its pan scale and its
  projection from two different half-heights, so a drag did not match the finger and
  toggling Ortho/Persp rescaled the part under the user; both come from one shared
  value.
- **A save, an export and a slice could each lose work.** The debounced
  support-paint workspace save ran on the main dispatcher, and the painted-mesh
  descriptor has a hard size limit a large paint job reaches - an over-limit save
  threw an uncaught `require()` and took the process with it, so the save runs on IO
  and reports a failure like any other. An engine export that arrived after the UI
  went away was claimed by the departing view model's listener and then dropped with
  its scope; the listener is cleared with the view model now, which leaves the
  export queued for the next one to replay. Saving non-planar or conical settings
  deleted the published `slice-results/` directory on the UI thread, under a slice
  that might be reading it; the eviction goes through the publisher's own lock on
  IO. And a pending document export that failed deleted its file, which may well be
  the only copy the user has - it keeps the file and says the export failed.
- **UI state that did not stick, or stuck too long.** The Prusa setting writes were
  not ordered, so an older snapshot could land after a newer keystroke and revert
  it; they are chained now. Smart Infill's validation flag was owned by the package
  id rather than by the run, so re-validating the same package after moving it could
  clear the *new* run's flag and disable Slice; it belongs to the run. A failed
  settings commit was silent - the values looked saved and vanished on the next
  launch - and is reported now. `MeshPicker.invalidate` had no call site at all, so
  a large off-heap mesh stayed alive after **Clear plate**. And the modelling
  owner's camera write was the one publish that did not go through the serialised,
  rev-guarded writer, which is the write that could land out of order.
- **Nothing that arrives from outside is unbounded any more.** Harness responses
  were read whole, with no ceiling and no check against `Content-Length`; they are
  capped at 16 MiB now. The engine reply had no ceiling either, and was re-decoded
  and re-parsed once per 16 KB chunk, which was quadratic work for a peer holding
  the port open; it is capped at 1 MiB and parsed only once the last significant
  byte can close the object. Cura formulas could nest without limit, and a rejected
  one poisoned the whole profile instead of naming the setting; there is a depth
  limit and the setting is named. The OctoPrint file list recursed into folders
  without limit, the Prusa `.ini` read had no ceiling, and an absurd estimated-time
  comment in imported G-code threw `NumberFormatException` out of the parse - all
  three are bounded, and the comment is parsed defensively.
- **The platform surface shipped more than it needed to.** Auto Backup and device
  transfer carried the extracted engine tree (MCP token included), the models, the
  sliced G-code, the FEA reports and the imported project bundle - hundreds of
  megabytes against a 25 MB quota, all of it regenerated on the new device; they are
  excluded now. The WebView hosts answered any `http(s)` request from anywhere,
  subframes included, and handed any navigation to the browser; they 403 anything
  off the asset origin and pass only main-frame navigations on. `ACCESS_NETWORK_STATE`
  was requested and never used, so it is gone. And the WebView tools laid out under
  the system bars, where the clock and the gesture bar are; they apply the insets
  now.
- **A bare 403 cost the user the OctoPrint API key.** Any 403 erased the stored
  credential, including one from a proxy or a permission the key had nothing to do
  with - and only OctoPrint's own web UI can reissue it. The key is erased only for
  a same-origin API error that names it. The harness address also accepted a
  cleartext URL in silence, though the session cookie and any photo sent with a
  prompt travel in it; the chat says so now.
- **The release APK had no gate that ran in CI.** `verifyReleaseApkEngines` existed
  but CI called only the debug one, so the release variant was never assembled or
  content-checked by a pipeline - and a hand-cut release is exactly the build that
  ships. Running the gate also exposed that `assembleRelease` could not build at
  all, because release lint read the assets directory without depending on whatever
  prepares it. CI runs both gates now.

## [1.1.0] - 2026-09-12

### Removed


- Bead-angle overhangs, wall-anchored infill and bead-chain overhangs were
  removed from the engine, the app and the settings UI; masonry-bonded walls
  remain (their generator keeps its home in BeadAngleOverhang for that).
  Nothing depends on the removed settings; presets/imports carrying the old
  keys ignore them.

### Added

- Printer & onboarding (P5) and foldable layout (P6): More > Printer is now
  a full-screen destination with back navigation, a persistent safety
  checklist (build volume, nozzle, hotend limit, G-code, remote printing;
  PrinterChecklistStore) above the machine profile; a one-shot skippable
  first-run onboarding sets the machine values before the first slice; the
  Plate tab splits into viewer + session pane (summary chips, quick settings,
  actions) at 600 dp+ widths, e.g. on an unfolded foldable.
- Model storage off-heap and expanded-layout cleanup: STL meshes at or above
  200k triangles are parsed straight into a direct native FloatBuffer
  (VertexData), so the vertex data of a multi-million-triangle model no
  longer counts against the 512 MB app Java heap; every consumer (viewer,
  mesh picker, transforms, STL writer, envelope checks) keeps the same
  index/size API, and the parser thresholds are unit-tested
  (VertexDataOffHeapTest). On unfolded/foldable widths (>= 600 dp) the bottom
  Slice/Export action bar is hidden - the session pane owns the actions - so
  the expanded layout no longer shows two Slice buttons; the slice-blocked
  reason is shown in the session pane instead.
- Renderers now draw from GPU memory (VBO), like a game: the model mesh
  uploads once per model (positions+normals interleaved, plus the paint
  color buffer on change) and the nozzle-path geometry uploads once per
  path/color-mode (positions, normals, colors, ambient, travel) into vertex
  buffer objects; frames are pure GPU draws instead of client-side
  re-reads of the CPU arrays, with silent fallback to client pointers if a
  driver allocates no buffer ids. CPU-side native copies stay for mesh
  picking, STL export and transforms.
- Engine switcher with per-engine identity: the user picks the slicer
  engine (Settings > Slicing engine): Cura (blue theme) or PrusaSlicer
  (orange theme) - the entire app recolors instantly and profiles stay
  strictly per-engine (never merged). Persisted via SlicerEngineStore;
  the per-engine palettes live in EnderSlicerTheme.
- Nozzle-path renderer and camera overhaul: beads shade per face with
  analytic normals under a fixed three-light rig (key + fill + rim) plus
  per-vertex ambient occlusion at the bead base, so the path reads as a solid
  printed part from every orbit angle without the zoomed-out moire that
  interpolated normals caused; 4x/2x multisampled EGL config with fallback;
  an orthographic true-width camera mode, zoom level reporting, an explicit
  Fit control, tap-to-inspect move picking, and a shared bead-width resolver
  (renderer and inspector readout use the same flow math, now unit-tested in
  NozzlePathBeadWidthTest).
- UI/UX overhaul (round 1): pinned brand theme (amber engineering-cockpit
  palette, light+dark) replacing wallpaper dynamic colors; persistent bottom
  navigation with four destinations (Plate / Settings / Print / More) replacing
  the menu-driven single screen; Print settings and OctoPrint moved from modal
  sheets to full-screen tabs; new More hub grouping profiles, printer,
  configuration snapshots and experimental tools; the model-viewer turntable
  orbit is restored when the Plate surface view is recreated (see
  docs/ui-style-guide.md and docs/ux-redesign/DESIGN_PROPOSAL.md).

- Support painting now combines with both non-planar pipelines: painted
  enforcer/blocker prisms are warped with the same transform as the model
  (relief-field flatten for CurviSlicer, cone warp around the model centre for
  conical slicing) so CuraEngine generates supports against the warped solid
  and the G-code transform restores both together.
- The OctoPrint webcam card opens the snapshot in a fullscreen viewer with
  pinch zoom (1x-6x), drag-to-pan and a double-tap reset; OctoPrint flip and
  rotation settings are preserved and the view stays live while open.
- UI polish pass: the model summary card uses label/value rows instead of a
  text dump; gesture help is dismissible and separate from status; a swatch
  legend replaces the view-mode explanation paragraph; the layer timeline
  is easier to grab; travel moves are dimmer in the path view; position
  numbers are locale-safe (no dangling decimal separator); the rotate sheet
  labels its fine step row; Start is only offered on an operational printer;
  disabled export explains itself; shared spacing tokens and a style guide
  (docs/ui-style-guide.md) standardize new work.
- The nozzle-path view is now physically based: each move is rendered as a
  3D bead whose width follows the sliced flow (deltaE x filament area /
  length / layer height) and whose height follows the layer height, so
  slicing at 0.12 mm vs 0.20 mm visibly changes the geometry. The palette
  is desaturated with shadowed side walls instead of the glowing outline,
  and the parser now captures per-move flow and layer height for this.
- Nozzle-path beads are shaded with a fixed directional light per side
  face (in the bead's own hue) plus a subtle odd-layer tint, so layers
  separate visually and angled segment joints blend instead of showing
  flat dark triangles. Side walls now use a flat, 15% darker tint of the
  bead colour instead of directional lambert variation - the directional
  range made bead rows crawl into corduroy stripes and chevrons when
  zoomed out, the flat tint keeps every zoom clean. Sub-0.05 mm micro
  segments emit with zero width (their side walls painted tiny dark
  specks) and the side tint is 0.90x.
- The nozzle-path camera now uses the model viewer's turntable controls:
  rotation/zoom/pan orbit around the printed-part centre instead of a
  touch-dependent orbit pivot, with the same sensitivity constants,
  camera fit and a double-tap reset.
- Pinch zoom in the nozzle-path view is now anchored at the point between
  both fingers instead of the first finger's touch point: the world point
  under the pinch focus stays pinned while zooming (pan compensation on
  the gesture focus plane).

### Simplified

- Single source of truth for the non-planar preparation shared by both engine
  transports (NonPlanarPreparation), shared G-code formatting/quantization,
  atomic publication, and cooperative cancellation (GcodeTransformSupport),
  a shared machine-key emission table (MachineCuraKeys), shared Smart Infill
  width keys on the contract object, a shared hex-digest helper, and one
  shared settings-field scaffold for all settings sheets (SettingsFields).
- Removed the abandoned NativeSlicer JNI bridge (Kotlin stub, C++ adapter,
  CMake target) — the APK execs the packaged CuraEngine binary — the unused
  printer metadata fields, the unreachable inward-cone branch in the conical
  transformer, dead built-in G-code/printer assets, the superseded
  fetch-curaengine script, and two low-value tests (a data-class no-op and a
  near-duplicate layer-event test; the unique tab-separated safety case moved
  to the processor suite).

### Added

- Support painting now combines with both non-planar pipelines: painted
  enforcer/blocker prisms are warped with the same transform as the model
  (relief-field flatten for CurviSlicer, cone warp around the model centre for
  conical slicing) so CuraEngine generates supports against the warped solid
  and the G-code transform restores both together.

- **AI assistant:** a floating chat on the Plate tab that talks to a
  [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) over a
  tailnet. The harness protocol (auth bootstrap from `auth.json`, the
  double-wrapped request envelope, session lifecycle) lives in `harness/` and
  was verified against a live server rather than inferred. Sessions are rooted
  at the configured workspace, because skills are discovered from the session's
  working directory and one created elsewhere cannot load them; a stored id is
  checked against the session list before it is adopted, because prompting an
  id the harness has forgotten is accepted and then answers nothing; and the
  conversation is rebuilt when a rotation or process death drops the in-memory
  client, which otherwise leaves an empty chat with the setup panel already
  dismissed. Replies are read from the session log rather than the list
  projection, which clips every turn to about a hundred characters.
- **Photo to 3D:** *Build from image* uploads a photograph and asks the harness
  to model it. The upload is staged and then named in the prompt as a file
  part, which is the only way staged bytes reach the agent - uploading without
  it leaves the agent with a message that merely mentions an image. The
  finished STL arrives through the same export directory as any other Blender
  export, so it reaches the plate with no interaction.
- **Point-to-point annotation:** paint a line, or a chain of them, onto the
  model to show the assistant what to work on. Points are placed on a
  horizontal work plane, which removes the depth ambiguity of a flat tap, and
  can be dragged in z alone without disturbing the other two axes. Handles stay
  grabbable independently of where a segment was drawn from, so an existing
  point can be adjusted without starting a new one, and marker size follows
  perspective so nearer points read larger.
- **Blender export folder manager** (Plate menu, Storage, Blender files): lists
  what the engine has delivered, distinguishes what the app can actually read
  from what it cannot, and clears the folder.
- **Stop** in the chat cancels the running turn *and* tells the agent to drop
  the work a cancel does not reach: `session/cancel` stops the agent, never
  the processes it started, and a background job that finishes afterwards
  delivers a notice that starts a fresh turn on its own.

### Fixed

- The arc/wave overhang generators never triggered on-device: the pinned
  Cura definitions default bridge detection off, and the app never enabled
  it. The standalone transport now sets bridge_settings_enabled when an
  overhang feature is on, and the resolved transport sets it at model-mesh
  scope, so unsupported bottom skins are classified as bridges and the
  overhang fills can replace them.
- Support painting could make slicing take tens of minutes or time out:
  painted triangles become eight-triangle prisms, and the per-layer support
  computation grows superlinearly with the painted region. Painted regions are
  now capped at 5,000 triangles (about 40k prism triangles, a few seconds on
  the host engine) with a clear fail-closed error, painted modifier meshes
  skip the meshfix union pass, and conical prism refinement is capped at one
  level.
- Conical slicing with supports (automatic or painted) lifted the entire model
  off the build plate: support tower bottoms back-transformed below the bed
  and dragged the whole file upward. Support moves are now anchored to the
  plate and below-plate support layers are skipped.
- Conical preparation was effectively uncancellable: the cooperative
  cancellation checks never fired (interval mismatch) and the refine/warp
  loops lacked per-triangle checks; interrupted STL reads now surface as a
  clean cancellation instead of a ClosedByInterruptException.
- Importing a new model could persist the previous model's painted supports,
  which came back as phantom enforcers/blockers on the new model after a
  restart.
- Paint changes were only persisted alongside unrelated saves; recent paint
  could be lost silently on process death. Paint is now persisted with a short
  debounce.
- Strokes painted while a slice was running silently diverged from the
  exported G-code; painting is now ignored while the app is busy.
- Conical slices now auto-select the nozzle Path preview like CurviSlicer
  slices do.
- The Curvi G-code transform is now cooperatively cancellable (per-line and
  per-segment checks), resolved Cura requests reject duplicate modifier mesh
  names, and the settings-leak contract covers painted-support x non-planar
  combinations.

## [1.0.0] - 2026-08-17

First stable release.

### Added

- EasyConical conical slicing: STL cone warp with G-code back-transform for
  tilted-nozzle 4-axis printers (OUTWARD cone direction).
- Support painting: per-region support enforcers/blockers with brush picking,
  transported to CuraEngine as modifier meshes.
- Thickness-adaptive walls: automatic wall reinforcement at tight bends via
  modifier volumes.
- CuraEngine upgraded to 5.14.0-alpha.0 with pinned Cura resources; engine-drift
  defaults seeded for imported flattened project definitions.
- Arc-overhang and wave-overhang engine paths (experimental, off by default),
  smart overhang strategy, and CurviSlicer non-planar slicing.
- Smart Infill workspace with offline filaSim thermal FEA (experimental).
- Settings-leak validation suite pinning that advanced features, when disabled,
  leave core Cura slicing untouched in both engine transports.
- Real Gradle wrapper (9.4.1); JVM unit tests now run on a clean checkout
  without the native engine, downloaded assets, or Rust/wasm-pack.

### Fixed

- Adaptive-wall modifier slabs extended 0.1 mm below the build plate (and above
  the gantry on full-height parts), tripping build-volume validation on every
  adaptive-walls slice.
- APK packaging could ship the x86-64 `libcura-formulae-engine.so` from a shared
  Conan cache; the AArch64 library is now selected by ELF machine and the build
  fails loudly when it is missing.
- Conical slicing: back-transform bed contact, Z radius computed against the
  correct centre axis, adhesion/priming handled per EasyConical requirements.

### Changed

- Conical INWARD cone direction disabled: its warp geometry dips below the
  build plate for any real model; persisted selections are coerced to OUTWARD.

### Known limitations

- Single printable model, single extruder; no duplicate/auto-arrange workflow.
- Smart Infill, thermal FEA, arc/wave overhangs and the smart overhang strategy
  are experimental and need broader physical print validation.
- Non-planar slicing buffers the full transformed G-code in memory; very large
  or dense prints may need a raised Java heap (see README).
