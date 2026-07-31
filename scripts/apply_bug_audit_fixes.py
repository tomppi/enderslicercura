#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one match in {path}, found {count}: {old[:100]!r}")
    write(path, text.replace(old, new, 1))


MODELS = "app/src/main/java/com/tomppi/enderslicer/octoprint/OctoPrintModels.kt"
replace_once(
    MODELS,
    "import org.json.JSONObject\n",
    "import org.json.JSONObject\nimport java.util.Locale\nimport kotlin.math.roundToLong\n",
)
replace_once(
    MODELS,
    '        return output to root.optLongOrNull("free")\n',
    '        return output to parseByteCount(root.opt("free"))\n',
)
replace_once(
    MODELS,
    "    private fun parseTemperature(root: JSONObject): OctoPrintTemperature = OctoPrintTemperature(\n",
    '''    private fun parseByteCount(raw: Any?): Long? {
        if (raw == null || raw == JSONObject.NULL) return null
        if (raw is Number) return raw.toLong().takeIf { it >= 0L }
        val value = raw.toString().trim()
        value.toLongOrNull()?.let { return it.takeIf { bytes -> bytes >= 0L } }
        val match = Regex(
            pattern = "^([0-9]+(?:\\.[0-9]+)?)\\s*([KMGTPE]?I?B)$",
            option = RegexOption.IGNORE_CASE,
        ).matchEntire(value) ?: return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].uppercase(Locale.US)
        val multiplier = when (unit) {
            "B" -> 1.0
            "KB" -> 1_000.0
            "MB" -> 1_000_000.0
            "GB" -> 1_000_000_000.0
            "TB" -> 1_000_000_000_000.0
            "PB" -> 1_000_000_000_000_000.0
            "EB" -> 1_000_000_000_000_000_000.0
            "KIB" -> 1_024.0
            "MIB" -> 1_048_576.0
            "GIB" -> 1_073_741_824.0
            "TIB" -> 1_099_511_627_776.0
            "PIB" -> 1_125_899_906_842_624.0
            "EIB" -> 1_152_921_504_606_846_976.0
            else -> return null
        }
        val bytes = amount * multiplier
        return bytes.takeIf { it.isFinite() && it >= 0.0 && it <= Long.MAX_VALUE.toDouble() }?.roundToLong()
    }

    private fun parseTemperature(root: JSONObject): OctoPrintTemperature = OctoPrintTemperature(
''',
)

CLIENT = "app/src/main/java/com/tomppi/enderslicer/octoprint/OctoPrintClient.kt"
replace_once(
    CLIENT,
    '''        val location = response.location
            ?: body.optString("location").takeIf(String::isNotBlank)
            ?: error("OctoPrint did not return an authorization polling URL")
        val authDialog = body.optString("auth_dialog").takeIf(String::isNotBlank)
            ?: error("OctoPrint did not return an authorization dialog URL")
        return AppKeyAuthorization(
            pollingUrl = resolveServerUrl(location)?.toString() ?: error("Invalid authorization polling URL"),
            dialogUrl = resolveServerUrl(authDialog)?.toString() ?: error("Invalid authorization dialog URL"),
        )
''',
    '''        val location = response.location
            ?: body.optString("location").takeIf(String::isNotBlank)
            ?: error("OctoPrint did not return an authorization polling URL")
        val authDialog = body.optString("auth_dialog").takeIf(String::isNotBlank)
            ?: error("OctoPrint did not return an authorization dialog URL")
        val polling = resolveServerUrl(location) ?: error("Invalid authorization polling URL")
        require(isSameOrigin(polling)) { "OctoPrint authorization polling must stay on the configured server" }
        return AppKeyAuthorization(
            pollingUrl = polling.toString(),
            dialogUrl = resolveServerUrl(authDialog)?.toString() ?: error("Invalid authorization dialog URL"),
        )
''',
)
replace_once(
    CLIENT,
    '''        val url = runCatching { URI(pollingUrl) }.getOrNull()?.takeIf(URI::isAbsolute)
            ?: error("Invalid authorization polling URL")
        val response = execute(
''',
    '''        val url = runCatching { URI(pollingUrl) }.getOrNull()?.takeIf(URI::isAbsolute)
            ?: error("Invalid authorization polling URL")
        require(isSameOrigin(url)) { "OctoPrint authorization polling must stay on the configured server" }
        val response = execute(
''',
)
replace_once(
    CLIENT,
    '''            contentType = "multipart/form-data; boundary=$boundary",
            contentLength = totalLength,
        )
''',
    '''            contentType = "multipart/form-data; boundary=$boundary",
            contentLength = totalLength,
            readTimeoutMillis = UPLOAD_TIMEOUT_MILLIS,
        )
''',
)
replace_once(
    CLIENT,
    '''        contentType: String? = null,
        contentLength: Long? = null,
    ): HttpURLConnection {
''',
    '''        contentType: String? = null,
        contentLength: Long? = null,
        readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
    ): HttpURLConnection {
''',
)
replace_once(
    CLIENT,
    '            readTimeout = if (method == "POST") WRITE_OPERATION_TIMEOUT_MILLIS else READ_TIMEOUT_MILLIS\n',
    '            readTimeout = readTimeoutMillis\n',
)
replace_once(
    CLIENT,
    '        private const val WRITE_OPERATION_TIMEOUT_MILLIS = 15 * 60 * 1_000\n',
    '        private const val UPLOAD_TIMEOUT_MILLIS = 15 * 60 * 1_000\n',
)

REPOSITORY = "app/src/main/java/com/tomppi/enderslicer/octoprint/OctoPrintRepository.kt"
replace_once(
    REPOSITORY,
    '''    private val store = OctoPrintSecretStore(context.applicationContext)
    private val refreshMutex = Mutex()
''',
    '''    private val appContext = context.applicationContext
    private val store = OctoPrintSecretStore(appContext)
    private val refreshMutex = Mutex()
    private val commandMutex = Mutex()
''',
)
replace_once(
    REPOSITORY,
    '''    private val initialConfig = store.loadConfig()
    private val _state = MutableStateFlow(
''',
    '''    private val initialConfig = store.loadConfig()
    private val initialApiKey = store.loadApiKey()
    private val _state = MutableStateFlow(
''',
)
replace_once(
    REPOSITORY,
    '''            hasApiKey = store.hasApiKey(),
            statusMessage = if (initialConfig.isConfigured && store.hasApiKey()) {
''',
    '''            hasApiKey = initialApiKey != null,
            statusMessage = if (initialConfig.isConfigured && initialApiKey != null) {
''',
)
replace_once(
    REPOSITORY,
    '                if (error is CancellationException && authorizationJob?.isCancelled == true) return@onFailure\n',
    '                if (error is CancellationException) return@onFailure\n',
)
replace_once(
    REPOSITORY,
    '            if (error is CancellationException && authorizationJob?.isCancelled == true) return@onFailure\n',
    '            if (error is CancellationException) return@onFailure\n',
)
replace_once(
    REPOSITORY,
    '''                    requireClient().upload(source, remoteName, remoteDirectory, action) { sent, total ->
                        val progress = if (total <= 0L) {
                            null
                        } else {
                            (sent.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
                        }
                        _state.update { current -> current.copy(uploadProgress = progress) }
                    }
''',
    '''                    val uploadSource = snapshotGcodeForUpload(source)
                    try {
                        requireClient().upload(uploadSource, remoteName, remoteDirectory, action) { sent, total ->
                            val progress = if (total <= 0L) {
                                null
                            } else {
                                (sent.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
                            }
                            _state.update { current -> current.copy(uploadProgress = progress) }
                        }
                    } finally {
                        uploadSource.delete()
                    }
''',
)
replace_once(
    REPOSITORY,
    '''    fun jog(x: Double? = null, y: Double? = null, z: Double? = null) = operation("Jogging printer…") {
        jog(x, y, z)
    }

    fun home(axes: Set<String>) = operation("Homing ${axes.joinToString("").uppercase(Locale.US)}…") {
        home(axes)
    }
''',
    '''    fun jog(x: Double? = null, y: Double? = null, z: Double? = null) {
        if (!requireIdlePrinterAction("Jogging")) return
        operation("Jogging printer…") { jog(x, y, z) }
    }

    fun home(axes: Set<String>) {
        if (!requireIdlePrinterAction("Homing")) return
        operation("Homing ${axes.joinToString("").uppercase(Locale.US)}…") { home(axes) }
    }
''',
)
replace_once(
    REPOSITORY,
    '''    fun extrude(amountMm: Double) = operation(
        if (amountMm > 0) "Extruding filament…" else "Retracting filament…",
    ) {
        extrude(amountMm)
    }
''',
    '''    fun extrude(amountMm: Double) {
        if (!requireIdlePrinterAction(if (amountMm > 0) "Extrusion" else "Retraction")) return
        operation(if (amountMm > 0) "Extruding filament…" else "Retracting filament…") {
            extrude(amountMm)
        }
    }
''',
)
replace_once(
    REPOSITORY,
    '''    private fun operation(
        message: String,
        refreshFileList: Boolean = false,
        block: OctoPrintClient.() -> Unit,
    ) {
        scope.launch {
            setStatus(message)
            runCatching {
                withContext(Dispatchers.IO) { requireClient().block() }
            }.onSuccess {
                if (refreshFileList) refreshFiles(force = true)
                refreshAll(showSpinner = false)
            }.onFailure(::setError)
        }
    }

    private fun requireClient(): OctoPrintClient {
        val current = _state.value
        require(current.config.isConfigured) { "Configure the OctoPrint server first" }
        val key = store.loadApiKey() ?: error("OctoPrint API key is missing; authorize the app again")
        return OctoPrintClient(current.config.baseUrl, key)
    }
''',
    '''    private fun operation(
        message: String,
        refreshFileList: Boolean = false,
        block: OctoPrintClient.() -> Unit,
    ) {
        scope.launch {
            commandMutex.withLock {
                setStatus(message)
                runCatching {
                    withContext(Dispatchers.IO) { requireClient().block() }
                }.onSuccess {
                    if (refreshFileList) refreshFiles(force = true)
                    refreshAll(showSpinner = false)
                }.onFailure(::setError)
            }
        }
    }

    private fun requireIdlePrinterAction(action: String): Boolean {
        val current = _state.value
        if (!current.isReady) {
            setError(IllegalStateException("Configure and connect OctoPrint before $action"))
            return false
        }
        if (!current.printer.operational || current.isPrinting || current.isPaused) {
            setError(IllegalStateException("$action is only available while the printer is operational and idle"))
            return false
        }
        return true
    }

    private fun requireClient(): OctoPrintClient {
        val current = _state.value
        require(current.config.isConfigured) { "Configure the OctoPrint server first" }
        val key = store.loadApiKey()
        if (key == null) {
            val message = "OctoPrint API key is missing or unreadable; authorize the app again"
            _state.update {
                it.copy(
                    hasApiKey = false,
                    authorizationPending = false,
                    statusMessage = message,
                    errorMessage = message,
                )
            }
            error(message)
        }
        return OctoPrintClient(current.config.baseUrl, key)
    }
''',
)
replace_once(
    REPOSITORY,
    '''                isUploading = false,
                authorizationPending = false,
                statusMessage = message,
''',
    '''                isUploading = false,
                statusMessage = message,
''',
)
replace_once(
    REPOSITORY,
    '''    private fun sanitizeGcodeName(value: String): String {
''',
    '''    private fun snapshotGcodeForUpload(source: File): File {
        require(source.isFile && source.length() > 0L) { "The validated G-code is unavailable; slice again" }
        val beforeLength = source.length()
        val beforeModified = source.lastModified()
        val directory = File(appContext.cacheDir, "octoprint-uploads").apply { mkdirs() }
        directory.listFiles()?.forEach { candidate ->
            if (candidate.isFile && System.currentTimeMillis() - candidate.lastModified() > UPLOAD_SNAPSHOT_MAX_AGE_MILLIS) {
                candidate.delete()
            }
        }
        val snapshot = File.createTempFile("validated-", ".gcode", directory)
        try {
            source.inputStream().buffered().use { input ->
                snapshot.outputStream().buffered().use(input::copyTo)
            }
            check(
                source.isFile &&
                    source.length() == beforeLength &&
                    source.lastModified() == beforeModified &&
                    snapshot.length() == beforeLength,
            ) { "The G-code changed while preparing the upload; try again" }
            return snapshot
        } catch (error: Throwable) {
            snapshot.delete()
            throw error
        }
    }

    private fun sanitizeGcodeName(value: String): String {
''',
)
replace_once(
    REPOSITORY,
    '''        const val AUTHORIZATION_TIMEOUT_MILLIS = 5L * 60L * 1_000L
''',
    '''        const val AUTHORIZATION_TIMEOUT_MILLIS = 5L * 60L * 1_000L
        const val UPLOAD_SNAPSHOT_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L
''',
)

MAIN_VM = "app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt"
replace_once(
    MAIN_VM,
    '''            engineStatus = engine.status(),
            engineAvailable = engine.isAvailable(),
        ),
''',
    '''            engineStatus = engine.status(),
            engineAvailable = engine.isAvailable(),
            statusMessage = "Restoring saved configuration…",
            isBusy = true,
        ),
''',
)
replace_once(
    MAIN_VM,
    '''                            statusMessage = if (restored.settings.overriddenSettingKeys.isEmpty()) {
                                it.statusMessage
                            } else {
                                "Restored ${restored.settings.overriddenSettingKeys.size} saved app setting overrides"
                            },
''',
    '''                            isBusy = false,
                            statusMessage = if (restored.settings.overriddenSettingKeys.isEmpty()) {
                                "Import an STL to begin"
                            } else {
                                "Restored ${restored.settings.overriddenSettingKeys.size} saved app setting overrides"
                            },
''',
)
replace_once(
    MAIN_VM,
    '''                _uiState.update {
                    it.copy(statusMessage = "Saved Cura configuration could not be restored: ${error.message}")
                }
''',
    '''                _uiState.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = "Saved Cura configuration could not be restored: ${error.message}",
                    )
                }
''',
)

SHEET = "app/src/main/java/com/tomppi/enderslicer/ui/OctoPrintSheet.kt"
replace_once(
    SHEET,
    "import android.graphics.BitmapFactory\n",
    "import android.graphics.Bitmap\nimport android.graphics.BitmapFactory\n",
)
replace_once(
    SHEET,
    "import androidx.compose.runtime.mutableStateOf\n",
    "import androidx.compose.runtime.mutableStateOf\nimport androidx.compose.runtime.produceState\n",
)
replace_once(
    SHEET,
    "import java.util.Locale\n",
    "import java.util.Locale\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.withContext\n",
)
replace_once(
    SHEET,
    '''    var confirmCancel by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<OctoPrintFileEntry?>(null) }
''',
    '''    var confirmStart by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<OctoPrintFileEntry?>(null) }
''',
)
replace_once(
    SHEET,
    '''                onConfirmUploadPrint = { pendingUploadPrintDirectory = it },
                onConfirmCancel = { confirmCancel = true },
''',
    '''                onConfirmUploadPrint = { pendingUploadPrintDirectory = it },
                onConfirmStart = { confirmStart = true },
                onConfirmRestart = { confirmRestart = true },
                onConfirmCancel = { confirmCancel = true },
''',
)
replace_once(
    SHEET,
    '''    if (confirmCancel) {
        ConfirmDialog(
            title = "Cancel the active print?",
            text = "OctoPrint will stop the current print job. This cannot be resumed.",
            confirmLabel = "Cancel print",
            onDismiss = { confirmCancel = false },
            onConfirm = {
                confirmCancel = false
                viewModel.cancelJob()
            },
        )
    }

    pendingDelete?.let { entry ->
''',
    '''    if (confirmStart) {
        ConfirmDialog(
            title = "Start the selected print?",
            text = "OctoPrint will start the selected G-code immediately. Verify the printer, bed, filament and first layer.",
            confirmLabel = "Start print",
            onDismiss = { confirmStart = false },
            onConfirm = {
                confirmStart = false
                viewModel.startJob()
            },
        )
    }

    if (confirmRestart) {
        ConfirmDialog(
            title = "Restart the current print?",
            text = "OctoPrint will restart the selected job from the beginning.",
            confirmLabel = "Restart print",
            onDismiss = { confirmRestart = false },
            onConfirm = {
                confirmRestart = false
                viewModel.restartJob()
            },
        )
    }

    if (confirmCancel) {
        ConfirmDialog(
            title = "Cancel the active print?",
            text = "OctoPrint will stop the current print job. This cannot be resumed.",
            confirmLabel = "Cancel print",
            onDismiss = { confirmCancel = false },
            onConfirm = {
                confirmCancel = false
                viewModel.cancelJob()
            },
        )
    }

    pendingDelete?.let { entry ->
''',
)
replace_once(
    SHEET,
    '''    onConfirmUploadPrint: (String) -> Unit,
    onConfirmCancel: () -> Unit,
''',
    '''    onConfirmUploadPrint: (String) -> Unit,
    onConfirmStart: () -> Unit,
    onConfirmRestart: () -> Unit,
    onConfirmCancel: () -> Unit,
''',
)
replace_once(
    SHEET,
    '                        state.job.fileName != null -> Button(onClick = viewModel::startJob, modifier = Modifier.weight(1f)) {\n',
    '                        state.job.fileName != null -> Button(onClick = onConfirmStart, modifier = Modifier.weight(1f)) {\n',
)
replace_once(
    SHEET,
    '                        OutlinedButton(onClick = viewModel::restartJob, modifier = Modifier.weight(1f)) {\n',
    '                        OutlinedButton(onClick = onConfirmRestart, modifier = Modifier.weight(1f)) {\n',
)
replace_once(
    SHEET,
    '''    val bytes = state.webcamFrame
    val bitmap = remember(bytes) {
        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
''',
    '''    val bytes = state.webcamFrame
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = bytes) {
        value = withContext(Dispatchers.Default) {
            bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
    }
''',
)
replace_once(
    SHEET,
    '    var autoConnect by remember { mutableStateOf(false) }\n',
    '    var autoConnect by remember { mutableStateOf(false) }\n    var autoConnectEdited by remember { mutableStateOf(false) }\n',
)
replace_once(
    SHEET,
    '        autoConnect = state.connection.autoConnect\n',
    '        if (!autoConnectEdited) autoConnect = state.connection.autoConnect\n',
)
replace_once(
    SHEET,
    '                    Checkbox(checked = autoConnect, onCheckedChange = { autoConnect = it })\n',
    '                    Checkbox(checked = autoConnect, onCheckedChange = { autoConnect = it; autoConnectEdited = true })\n',
)

MANIFEST = "app/src/main/AndroidManifest.xml"
replace_once(
    MANIFEST,
    '''        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
''',
    '''        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
''',
)
write(
    "app/src/main/res/xml/backup_rules.xml",
    '''<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <exclude domain="sharedpref" path="octoprint_client.xml" />
</full-backup-content>
''',
)
write(
    "app/src/main/res/xml/data_extraction_rules.xml",
    '''<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="sharedpref" path="octoprint_client.xml" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="octoprint_client.xml" />
    </device-transfer>
</data-extraction-rules>
''',
)

TEST = "app/src/test/java/com/tomppi/enderslicer/octoprint/OctoPrintClientTest.kt"
replace_once(TEST, '                  "free": 999999,\n', '                  "free": "3.2GB",\n')
replace_once(TEST, '        assertEquals(999999L, freeBytes)\n', '        assertEquals(3_200_000_000L, freeBytes)\n')
replace_once(
    TEST,
    '''    }
}
''',
    '''    }

    @Test
    fun parsesNumericAndBinaryFreeSpaceValues() {
        assertEquals(
            999_999L,
            OctoPrintJson.parseFiles(JSONObject("{\\"free\\":999999,\\"files\\":[]}" )).second,
        )
        assertEquals(
            1_610_612_736L,
            OctoPrintJson.parseFiles(JSONObject("{\\"free\\":\\"1.5GiB\\",\\"files\\":[]}" )).second,
        )
        assertNull(
            OctoPrintJson.parseFiles(JSONObject("{\\"free\\":\\"unknown\\",\\"files\\":[]}" )).second,
        )
    }
}
''',
)

write(
    "docs/bug-audit.md",
    '''# Whole-app bug audit

This audit covers the current slicer, Cura import and persistence paths, model and G-code lifecycle, calibration state, layer preview/event rewriting, BumpMesh handoff, and the new OctoPrint subsystem.

## Fixed in this pass

- Parse OctoPrint's documented human-readable `free` storage field as bytes while retaining numeric compatibility.
- Validate the stored API key by actually decrypting it at startup instead of treating an unreadable preference as a usable credential.
- Keep Application Keys polling on the configured OctoPrint origin and handle authorization cancellation without replacing the user-facing status with a coroutine cancellation error.
- Use the long timeout only for G-code uploads; ordinary commands return to the normal API timeout.
- Copy validated G-code to a checked immutable upload snapshot so re-slicing or editing layer events cannot alter a file while it is being transmitted.
- Serialize printer commands and reject motion, homing, extrusion and retraction unless the printer is operational and idle.
- Require confirmation for status-page start and restart actions, not only file-browser and upload print starts.
- Decode webcam frames away from the Compose main thread.
- Preserve a user's edited auto-connect checkbox instead of overwriting it on every status poll.
- Exclude encrypted OctoPrint preferences from Android backup and device transfer because the Android Keystore key is device-bound.
- Block normal app interaction until persisted Cura configuration restoration finishes, preventing a late restore from overwriting a fast user action after launch.

## Verified existing behavior

- Model imports, model transforms, Cura profile/project imports and print-setting changes invalidate the current G-code path before OctoPrint can upload it.
- Failed imports keep the last valid model and G-code instead of partially committing new state.
- Cura archive/XML input limits, STL size/triangle limits, G-code validation, layer-event rebuilding and calibration state cleanup remain isolated from the OctoPrint subsystem.
- OctoPrint credentials are sent only to the configured origin; external webcam snapshot URLs and redirects do not receive the API key.

## Remaining device validation

Automated tests can verify parsing, compilation and state-independent logic. A real OctoPrint server and printer are still required to validate authorization UI, reverse-proxy behavior, serial connection options, webcam compatibility, command permissions and a guarded small print from upload through completion.
''',
)

print("Applied whole-app bug audit fixes")
