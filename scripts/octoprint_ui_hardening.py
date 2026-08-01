from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise RuntimeError(f"missing snippet in {path}: {old[:100]!r}")
    if text.count(old) != 1:
        raise RuntimeError(f"ambiguous snippet in {path}: {text.count(old)} matches")
    p.write_text(text.replace(old, new, 1))


sheet = "app/src/main/java/com/tomppi/enderslicer/ui/OctoPrintSheet.kt"
replace_once(
    sheet,
    '''    DisposableEffect(Unit) {
        viewModel.setWebcamVisible(true)
        onDispose { viewModel.setWebcamVisible(false) }
    }

''',
    '',
)
replace_once(
    sheet,
    '''    var remoteDirectory by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
''',
    '''    var remoteDirectory by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    DisposableEffect(Unit) {
        viewModel.setWebcamVisible(true)
        onDispose { viewModel.setWebcamVisible(false) }
    }
''',
)
replace_once(
    sheet,
    '''                        state.job.fileName != null -> Button(onClick = onConfirmStart, modifier = Modifier.weight(1f)) {
''',
    '''                        state.job.fileName != null && !state.hasActiveJob -> Button(
                            onClick = onConfirmStart,
                            modifier = Modifier.weight(1f),
                        ) {
''',
)
replace_once(
    sheet,
    '''                    if (state.isPrinting || state.isPaused) {
''',
    '''                    if (state.isPrinting || state.isPaused || state.printer.pausing) {
''',
)
replace_once(
    sheet,
    '''                        enabled = localGcodePath != null && !state.isUploading && !state.isPrinting,
''',
    '''                        enabled = localGcodePath != null && !state.isUploading && !state.hasActiveJob,
''',
)
replace_once(
    sheet,
    '''        value = withContext(Dispatchers.Default) {
            bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
''',
    '''        value = withContext(Dispatchers.Default) {
            bytes?.let(::decodeWebcamBitmap)
        }
''',
)
replace_once(
    sheet,
    '''                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Connect")
                    }
                    OutlinedButton(onClick = viewModel::disconnect, modifier = Modifier.weight(1f)) {
''',
    '''                        enabled = state.isReady && !state.hasActiveJob,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Connect")
                    }
                    OutlinedButton(
                        onClick = viewModel::disconnect,
                        enabled = state.isReady && !state.hasActiveJob,
                        modifier = Modifier.weight(1f),
                    ) {
''',
)
replace_once(
    sheet,
    '''                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { viewModel.jog(x = -jogStep) }) { Text("X−") }
                    OutlinedButton(onClick = { viewModel.jog(x = jogStep) }) { Text("X+") }
                    OutlinedButton(onClick = { viewModel.jog(y = -jogStep) }) { Text("Y−") }
                    OutlinedButton(onClick = { viewModel.jog(y = jogStep) }) { Text("Y+") }
                    OutlinedButton(onClick = { viewModel.jog(z = -jogStep) }) { Text("Z−") }
                    OutlinedButton(onClick = { viewModel.jog(z = jogStep) }) { Text("Z+") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { viewModel.home(setOf("x")) }) { Text("Home X") }
                    OutlinedButton(onClick = { viewModel.home(setOf("y")) }) { Text("Home Y") }
                    OutlinedButton(onClick = { viewModel.home(setOf("z")) }) { Text("Home Z") }
                    Button(onClick = { viewModel.home(setOf("x", "y", "z")) }) { Text("Home all") }
                }
''',
    '''                val idleControls = state.printer.operational && !state.hasActiveJob
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { viewModel.jog(x = -jogStep) }, enabled = idleControls) { Text("X−") }
                    OutlinedButton(onClick = { viewModel.jog(x = jogStep) }, enabled = idleControls) { Text("X+") }
                    OutlinedButton(onClick = { viewModel.jog(y = -jogStep) }, enabled = idleControls) { Text("Y−") }
                    OutlinedButton(onClick = { viewModel.jog(y = jogStep) }, enabled = idleControls) { Text("Y+") }
                    OutlinedButton(onClick = { viewModel.jog(z = -jogStep) }, enabled = idleControls) { Text("Z−") }
                    OutlinedButton(onClick = { viewModel.jog(z = jogStep) }, enabled = idleControls) { Text("Z+") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { viewModel.home(setOf("x")) }, enabled = idleControls) { Text("Home X") }
                    OutlinedButton(onClick = { viewModel.home(setOf("y")) }, enabled = idleControls) { Text("Home Y") }
                    OutlinedButton(onClick = { viewModel.home(setOf("z")) }, enabled = idleControls) { Text("Home Z") }
                    Button(onClick = { viewModel.home(setOf("x", "y", "z")) }, enabled = idleControls) { Text("Home all") }
                }
''',
)
replace_once(
    sheet,
    '''                    Button(onClick = { toolTarget.toIntOrNull()?.let { viewModel.setToolTemperature("tool0", it) } }) {
''',
    '''                    Button(
                        onClick = { toolTarget.toIntOrNull()?.let { viewModel.setToolTemperature("tool0", it) } },
                        enabled = state.printer.operational,
                    ) {
''',
)
replace_once(
    sheet,
    '''                    OutlinedButton(onClick = { viewModel.setToolTemperature("tool0", 0) }) {
''',
    '''                    OutlinedButton(
                        onClick = { viewModel.setToolTemperature("tool0", 0) },
                        enabled = state.printer.operational,
                    ) {
''',
)
replace_once(
    sheet,
    '''                    Button(onClick = { bedTarget.toIntOrNull()?.let(viewModel::setBedTemperature) }) {
''',
    '''                    Button(
                        onClick = { bedTarget.toIntOrNull()?.let(viewModel::setBedTemperature) },
                        enabled = state.printer.operational,
                    ) {
''',
)
replace_once(
    sheet,
    '''                    OutlinedButton(onClick = { viewModel.setBedTemperature(0) }) {
''',
    '''                    OutlinedButton(
                        onClick = { viewModel.setBedTemperature(0) },
                        enabled = state.printer.operational,
                    ) {
''',
)
replace_once(
    sheet,
    '''                    Button(onClick = { extrusion.toDoubleOrNull()?.let { viewModel.extrude(kotlin.math.abs(it)) } }) {
''',
    '''                    Button(
                        onClick = { extrusion.toDoubleOrNull()?.let { viewModel.extrude(kotlin.math.abs(it)) } },
                        enabled = state.printer.operational && !state.hasActiveJob,
                    ) {
''',
)
replace_once(
    sheet,
    '''                    OutlinedButton(onClick = { extrusion.toDoubleOrNull()?.let { viewModel.extrude(-kotlin.math.abs(it)) } }) {
''',
    '''                    OutlinedButton(
                        onClick = { extrusion.toDoubleOrNull()?.let { viewModel.extrude(-kotlin.math.abs(it)) } },
                        enabled = state.printer.operational && !state.hasActiveJob,
                    ) {
''',
)
replace_once(
    sheet,
    '''                    Button(onClick = { feedRate.toIntOrNull()?.let(viewModel::setFeedRate) }) {
''',
    '''                    Button(
                        onClick = { feedRate.toIntOrNull()?.let(viewModel::setFeedRate) },
                        enabled = state.printer.operational,
                    ) {
''',
)
replace_once(
    sheet,
    '''                    Button(onClick = { flowRate.toIntOrNull()?.let(viewModel::setFlowRate) }) {
''',
    '''                    Button(
                        onClick = { flowRate.toIntOrNull()?.let(viewModel::setFlowRate) },
                        enabled = state.printer.operational,
                    ) {
''',
)
replace_once(
    sheet,
    '''                Button(onClick = { viewModel.sendGcode(command) }, enabled = command.isNotBlank()) {
''',
    '''                Button(
                    onClick = { viewModel.sendGcode(command) },
                    enabled = command.isNotBlank() && state.printer.operational && !state.hasActiveJob,
                ) {
''',
)
replace_once(
    sheet,
    '''                Text("Waiting for approval in OctoPrint…")
                TextButton(onClick = viewModel::cancelAuthorization) {
''',
    '''                Text("Waiting for approval in OctoPrint…")
                TextButton(onClick = viewModel::reopenAuthorizationDialog) {
                    Text("Open approval page")
                }
                TextButton(onClick = viewModel::cancelAuthorization) {
''',
)
replace_once(
    sheet,
    '''private fun formatDuration(seconds: Int?): String {
''',
    '''private fun decodeWebcamBitmap(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) return null
    var sampleSize = 1
    while (
        width / sampleSize > MAX_WEBCAM_WIDTH ||
        height / sampleSize > MAX_WEBCAM_HEIGHT ||
        (width.toLong() / sampleSize) * (height.toLong() / sampleSize) > MAX_WEBCAM_PIXELS
    ) {
        if (sampleSize >= 128) return null
        sampleSize *= 2
    }
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    )
}

private const val MAX_WEBCAM_WIDTH = 1920
private const val MAX_WEBCAM_HEIGHT = 1080
private const val MAX_WEBCAM_PIXELS = 2_500_000L

private fun formatDuration(seconds: Int?): String {
''',
)

tests = "app/src/test/java/com/tomppi/enderslicer/octoprint/OctoPrintClientTest.kt"
replace_once(
    tests,
    '''    @Test
    fun parsesPrinterTemperaturesAndFlags() {
''',
    '''    @Test
    fun rejectsServerUrlQueriesAndRemotePathTraversal() {
        assertTrue(
            runCatching { OctoPrintClient.normalizeBaseUrl("http://octopi.local/?token=secret") }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertEquals("models/cube.gcode", OctoPrintClient.normalizeRemotePath("/models/cube.gcode/", false))
        assertTrue(
            runCatching { OctoPrintClient.normalizeRemotePath("models/../api", false) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { OctoPrintClient.normalizeRemotePath("models\\\\cube.gcode", false) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { OctoPrintClient.normalizeRemotePath("models//cube.gcode", false) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun transitionalPrinterStateCountsAsActiveJob() {
        val state = OctoPrintUiState(
            printer = OctoPrintPrinterState(operational = true, pausing = true),
        )
        assertTrue(state.isTransitioning)
        assertTrue(state.hasActiveJob)
    }

    @Test
    fun unsafeServerFilePathsAreSkipped() {
        val (files, _) = OctoPrintJson.parseFiles(
            JSONObject(
                """
                {
                  "files": [
                    {"name":"safe.gcode","path":"safe.gcode","type":"machinecode"},
                    {"name":"escape.gcode","path":"../api/version","type":"machinecode"}
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("safe.gcode"), files.map { it.path })
    }

    @Test
    fun parsesPrinterTemperaturesAndFlags() {
''',
)

doc = Path("docs/bug-audit.md")
doc_text = doc.read_text().rstrip()
marker = "## Second OctoPrint hardening pass"
if marker not in doc_text:
    doc.write_text(
        doc_text
        + '''

## Second OctoPrint hardening pass

A second focused review fixed additional runtime issues that parsing and compile tests could not expose:

- asynchronous responses are tied to a configuration generation so an old server cannot overwrite a new or cleared setup;
- a working key/configuration remains active until replacement Application Keys authorization is approved;
- all remote file and folder paths reject dot segments, empty segments, backslashes and control characters;
- active HTTP connections are tracked and disconnected when the configuration/session is reset;
- polling stops and reauthorization is required after authentication failures;
- reconnect, disconnect and safety-sensitive controls are checked again inside the serialized command section;
- pausing and cancelling are treated as active transitional job states;
- upload-and-print/select reports when OctoPrint did not make the requested action effective;
- static server/user/webcam settings are cached instead of fetched every active-print poll;
- webcam polling runs only on the Status page and decoded images are downsampled to bounded dimensions;
- the Application Keys approval page can be reopened while authorization is pending.
'''
    )

print("OctoPrint UI/tests/docs hardening applied")
