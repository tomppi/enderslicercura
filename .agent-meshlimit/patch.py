from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text()
    if old not in text:
        raise SystemExit(f"Expected patch anchor missing in {path}: {old[:80]!r}")
    target.write_text(text.replace(old, new, 1))


replace_once(
    "app/src/main/java/com/tomppi/enderslicer/MainActivity.kt",
    "import com.tomppi.enderslicer.ui.MainViewModel\n",
    "import com.tomppi.enderslicer.ui.MainViewModel\nimport com.tomppi.enderslicer.mesh.MeshTriangleLimits\n",
)
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/MainActivity.kt",
    "        super.onCreate(savedInstanceState)\n        enableEdgeToEdge()\n",
    "        super.onCreate(savedInstanceState)\n        MeshTriangleLimits.initialize(this)\n        enableEdgeToEdge()\n",
)

replace_once(
    "app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt",
    "import com.tomppi.enderslicer.model.ModelPlacement\n",
    "import com.tomppi.enderslicer.mesh.MeshTriangleLimits\nimport com.tomppi.enderslicer.model.ModelPlacement\n",
)
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt",
    """                    val mesh = StlParser.parse(app.contentResolver, uri)
                    val modelFile = materializeModel(uri)
                    mesh to modelFile
""",
    """                    val triangleLimit = MeshTriangleLimits.current()
                    val modelFile = materializeModel(uri, triangleLimit)
                    val mesh = StlParser.parse(modelFile, displayName(uri), triangleLimit)
                    mesh to modelFile
""",
)
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt",
    """    private fun materializeModel(uri: Uri): File {
        val directory = File(app.filesDir, "models").apply { mkdirs() }
        val target = File(directory, "current.stl")
        val temporary = File(directory, "current.stl.tmp")
        temporary.delete()
        app.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
            temporary.outputStream().buffered().use { output -> input.copyTo(output) }
        } ?: error("Unable to copy the selected STL")
        check(temporary.length() > 0L) { "The selected STL is empty" }
        if (target.exists()) target.delete()
        check(temporary.renameTo(target)) { "Unable to store the selected STL locally" }
        return target
    }
""",
    """    private fun materializeModel(uri: Uri, maxTriangles: Int): File {
        val directory = File(app.filesDir, "models").apply { mkdirs() }
        val target = File(directory, "current.stl")
        val temporary = File(directory, "current.stl.tmp")
        val maxBytes = MeshTriangleLimits.maxInputFileBytes(maxTriangles)
        temporary.delete()
        app.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
            temporary.outputStream().buffered().use { output ->
                val buffer = ByteArray(128 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maxBytes) {
                        "STL is larger than ${MeshTriangleLimits.formatBytes(maxBytes)} for the ${MeshTriangleLimits.formatCount(maxTriangles)}-triangle limit"
                    }
                    output.write(buffer, 0, count)
                }
            }
        } ?: error("Unable to copy the selected STL")
        check(temporary.length() > 0L) { "The selected STL is empty" }
        if (target.exists()) target.delete()
        check(temporary.renameTo(target)) { "Unable to store the selected STL locally" }
        return target
    }
""",
)
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt",
    "            .put(\"calibration\", state.calibrationDescription)\n",
    "            .put(\"calibration\", state.calibrationDescription)\n            .put(\"maxMeshTriangles\", MeshTriangleLimits.current())\n",
)

replace_once(
    "app/src/main/java/com/tomppi/enderslicer/ui/EnderSlicerApp.kt",
    "import com.tomppi.enderslicer.model.withSettings\n",
    "import com.tomppi.enderslicer.mesh.MeshTriangleLimits\nimport com.tomppi.enderslicer.model.withSettings\n",
)
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/ui/EnderSlicerApp.kt",
    "    var layerEventsOpen by remember { mutableStateOf(false) }\n    var viewerMode by remember { mutableStateOf(ViewerMode.MODEL) }\n",
    "    var layerEventsOpen by remember { mutableStateOf(false) }\n    var meshLimitOpen by remember { mutableStateOf(false) }\n    var viewerMode by remember { mutableStateOf(ViewerMode.MODEL) }\n",
)
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/ui/EnderSlicerApp.kt",
    """                            DropdownMenuItem(
                                text = { Text("Model position & rotation") },
""",
    """                            DropdownMenuItem(
                                text = { Text("Mesh triangle limit") },
                                onClick = {
                                    menuExpanded = false
                                    meshLimitOpen = true
                                },
                                enabled = !state.isBusy,
                            )
                            DropdownMenuItem(
                                text = { Text("Model position & rotation") },
""",
)
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/ui/EnderSlicerApp.kt",
    "    if (machineSettingsOpen) {\n",
    """    if (meshLimitOpen) {
        ModalBottomSheet(
            onDismissRequest = { meshLimitOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            MeshTriangleLimitSheet(
                currentLimit = MeshTriangleLimits.current(),
                currentModelTriangles = state.mesh?.triangleCount,
                onSave = { limit ->
                    val saved = MeshTriangleLimits.save(context, limit)
                    meshLimitOpen = false
                    Toast.makeText(
                        context,
                        "Mesh triangle limit set to ${MeshTriangleLimits.formatCount(saved)}",
                        Toast.LENGTH_LONG,
                    ).show()
                },
                modifier = Modifier
                    .fillMaxHeight(0.94f)
                    .navigationBarsPadding(),
            )
        }
    }

    if (machineSettingsOpen) {
""",
)

replace_once(
    "app/src/main/AndroidManifest.xml",
    "        android:label=\"@string/app_name\"\n",
    "        android:label=\"@string/app_name\"\n        android:largeHeap=\"true\"\n",
)

replace_once(
    "app/build.gradle.kts",
    "        versionCode = 34\n        versionName = \"0.7.0-dev\"\n",
    "        versionCode = 35\n        versionName = \"0.8.0-dev\"\n",
)

replace_once(
    "README.md",
    "The current development line is `0.7.0-dev` and uses CuraEngine `5.11.0-beta.1` with matching Cura resources.",
    "The current development line is `0.8.0-dev` and uses CuraEngine `5.11.0-beta.1` with matching Cura resources.",
)
replace_once(
    "README.md",
    "- Binary and ASCII STL import\n",
    "- Binary and ASCII STL import with streamed parsing for high-density meshes\n- Persisted mesh triangle-limit presets from 1.5 million through an experimental 8 million, plus custom values\n",
)
replace_once(
    "README.md",
    "The Android integration caps BumpMesh output at 1,500,000 triangles, matching the current STL parser limit. Very fine texture settings can still consume substantial memory and processing time on a phone.\n",
    """Open **Menu → Mesh triangle limit** to choose Compatible (1.5 million), High detail (3 million), Very high detail (5 million), Extreme (8 million), or a custom value between 100,000 and 8 million triangles. The persisted value is shared by normal STL imports, the BumpMesh maximum-triangle control, and Android's validation of textured STL exports.

High-density STL parsing now streams the staged file from disk instead of retaining the complete STL byte array beside the parsed vertex buffer. The app requests Android's large heap because a five-million-triangle parsed mesh alone is roughly 343 MiB, and rendering, transforms, BumpMesh, export and WebView processing may hold several additional buffers. Free system RAM and ZRAM do not guarantee that every extreme operation will fit inside the app or WebView heap, so the 8-million preset remains explicitly experimental.
""",
)
