from pathlib import Path

ROOT = Path('.')

def replace_once(path: str, old: str, new: str):
    p = ROOT / path
    text = p.read_text()
    if old not in text:
        raise SystemExit(f'missing expected snippet in {path}: {old[:120]!r}')
    if text.count(old) != 1:
        raise SystemExit(f'expected one occurrence in {path}, got {text.count(old)}')
    p.write_text(text.replace(old, new, 1))

models = 'app/src/main/java/com/tomppi/enderslicer/octoprint/OctoPrintModels.kt'
replace_once(models,
'''    val authorizationPending: Boolean = false,
    val authorizationDialogUrl: String? = null,
    val lastUpdatedEpochMillis: Long? = null,''',
'''    val authorizationPending: Boolean = false,
    val authorizationDialogUrl: String? = null,
    val authorizationDialogLaunchNonce: Long = 0L,
    val lastUpdatedEpochMillis: Long? = null,''')
replace_once(models,
'''    val isReady: Boolean get() = config.isConfigured && hasApiKey
    val isPrinting: Boolean get() = printer.printing || job.state.equals("Printing", ignoreCase = true)
    val isPaused: Boolean get() = printer.paused || job.state.equals("Paused", ignoreCase = true)
}''',
'''    val isReady: Boolean get() = config.isConfigured && hasApiKey
    val isPrinting: Boolean get() = printer.printing || job.state.equals("Printing", ignoreCase = true)
    val isPaused: Boolean get() = printer.paused || job.state.equals("Paused", ignoreCase = true)
    val isTransitioning: Boolean get() =
        printer.pausing || printer.cancelling ||
            job.state.equals("Pausing", ignoreCase = true) ||
            job.state.equals("Cancelling", ignoreCase = true)
    val hasActiveJob: Boolean get() = isPrinting || isPaused || isTransitioning
}''')

client = 'app/src/main/java/com/tomppi/enderslicer/octoprint/OctoPrintClient.kt'
replace_once(client, 'import java.util.UUID\n', 'import java.util.Collections\nimport java.util.UUID\n')
replace_once(client,
'''    val normalizedBaseUrl: String
    private val base: URI
''',
'''    val normalizedBaseUrl: String
    private val base: URI
    private val activeConnections = Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())
''')
replace_once(client,
'''    fun currentUser(): JSONObject? {
        if (apiKey.isNullOrBlank()) return null
        return requestJson(
            url = apiUrl("api", "login"),
            method = "POST",
            body = JSONObject().put("passive", true).toString().toByteArray(Charsets.UTF_8),
        )
    }
''',
'''    fun currentUser(): JSONObject? {
        if (apiKey.isNullOrBlank()) return null
        return try {
            getJson(apiUrl("api", "currentuser"))
        } catch (error: OctoPrintHttpException) {
            if (error.statusCode != 404) throw error
            requestJson(
                url = apiUrl("api", "login"),
                method = "POST",
                body = JSONObject().put("passive", true).toString().toByteArray(Charsets.UTF_8),
            )
        }
    }

    fun cancelActiveRequests() {
        val connections = synchronized(activeConnections) { activeConnections.toList() }
        connections.forEach(::closeConnection)
    }
''')
replace_once(client,
'''        val fields = linkedMapOf<String, String>()
        if (remoteDirectory.isNotBlank()) fields["path"] = remoteDirectory.trim('/')
''',
'''        val fields = linkedMapOf<String, String>()
        val cleanDirectory = normalizeRemotePath(remoteDirectory, allowBlank = true)
        if (cleanDirectory.isNotBlank()) fields["path"] = cleanDirectory
''')
replace_once(client,
'''    fun selectFile(path: String, print: Boolean) {
        postFileCommand(path, JSONObject().put("command", "select").put("print", print))
    }

    fun unselectFile(path: String) {
        postFileCommand(path, JSONObject().put("command", "unselect"))
    }

    fun deleteFile(path: String) {
        execute(url = fileUrl(path), method = "DELETE")
    }

    fun createFolder(parentPath: String, folderName: String) {
        require(folderName.isNotBlank() && '/' !in folderName && '\\' !in folderName) { "Enter a valid folder name" }
        multipart(
            url = apiUrl("api", "files", "local"),
            fields = linkedMapOf<String, String>().apply {
                put("foldername", folderName.trim())
                if (parentPath.isNotBlank()) put("path", parentPath.trim('/'))
            },
        )
    }

    fun moveFile(path: String, destination: String) {
        postFileCommand(path, JSONObject().put("command", "move").put("destination", destination.trim('/')))
    }

    fun copyFile(path: String, destination: String) {
        postFileCommand(path, JSONObject().put("command", "copy").put("destination", destination.trim('/')))
    }
''',
'''    fun selectFile(path: String, print: Boolean) {
        postFileCommand(path, JSONObject().put("command", "select").put("print", print))
    }

    fun unselectFile(path: String) {
        postFileCommand(path, JSONObject().put("command", "unselect"))
    }

    fun deleteFile(path: String) {
        execute(url = fileUrl(path), method = "DELETE")
    }

    fun createFolder(parentPath: String, folderName: String) {
        val cleanName = validateRemoteSegment(folderName.trim())
        val cleanParent = normalizeRemotePath(parentPath, allowBlank = true)
        multipart(
            url = apiUrl("api", "files", "local"),
            fields = linkedMapOf<String, String>().apply {
                put("foldername", cleanName)
                if (cleanParent.isNotBlank()) put("path", cleanParent)
            },
        )
    }

    fun moveFile(path: String, destination: String) {
        val cleanDestination = normalizeRemotePath(destination, allowBlank = true)
        postFileCommand(path, JSONObject().put("command", "move").put("destination", cleanDestination))
    }

    fun copyFile(path: String, destination: String) {
        val cleanDestination = normalizeRemotePath(destination, allowBlank = true)
        postFileCommand(path, JSONObject().put("command", "copy").put("destination", cleanDestination))
    }
''')
replace_once(client,
'''        baudrate?.let { json.put("baudrate", it) }
''',
'''        baudrate?.let {
            require(it > 0) { "Baud rate must be positive" }
            json.put("baudrate", it)
        }
''')
replace_once(client,
'''        speedMmPerMinute?.let { json.put("speed", it) }
        postJson(apiUrl("api", "printer", "printhead"), json)
''',
'''        speedMmPerMinute?.let {
            require(it > 0) { "Jog speed must be positive" }
            json.put("speed", it)
        }
        postJson(apiUrl("api", "printer", "printhead"), json)
''')
replace_once(client,
'''        speedMmPerMinute?.let { json.put("speed", it) }
        postJson(apiUrl("api", "printer", "tool"), json)
''',
'''        speedMmPerMinute?.let {
            require(it > 0) { "Extrusion speed must be positive" }
            json.put("speed", it)
        }
        postJson(apiUrl("api", "printer", "tool"), json)
''')
replace_once(client, '            connection.disconnect()\n', '            closeConnection(connection)\n')
replace_once(client, '            connection.disconnect()\n', '            closeConnection(connection)\n')
replace_once(client, '            connection.disconnect()\n', '            closeConnection(connection)\n')
replace_once(client,
'''        require(url.scheme == "http" || url.scheme == "https") { "OctoPrint URL must use HTTP or HTTPS" }
        return (url.toURL().openConnection() as HttpURLConnection).apply {
''',
'''        require(url.scheme == "http" || url.scheme == "https") { "OctoPrint URL must use HTTP or HTTPS" }
        val connection = (url.toURL().openConnection() as HttpURLConnection).apply {
''')
replace_once(client,
'''            }
        }
    }

    private fun isSameOrigin(url: URI): Boolean =
''',
'''            }
        }
        activeConnections += connection
        return connection
    }

    private fun closeConnection(connection: HttpURLConnection) {
        activeConnections -= connection
        connection.disconnect()
    }

    private fun isSameOrigin(url: URI): Boolean =
''')
replace_once(client,
'''    private fun fileUrl(path: String): URI = pathUrl(
        "api",
        "files",
        "local",
        *path.trim('/').split('/').filter(String::isNotBlank).toTypedArray(),
    )

    private fun pathUrl(vararg segments: String): URI {
        val relative = segments.filter(String::isNotBlank).joinToString("/") { encodePathSegment(it) }
        return base.resolve(relative)
    }
''',
'''    private fun fileUrl(path: String): URI = pathUrl(
        "api",
        "files",
        "local",
        *normalizeRemotePath(path, allowBlank = false).split('/').toTypedArray(),
    )

    private fun pathUrl(vararg segments: String): URI {
        val cleanSegments = segments.filter(String::isNotBlank).map(::validateRemoteSegment)
        val relative = cleanSegments.joinToString("/") { encodePathSegment(it) }
        return base.resolve(relative)
    }
''')
replace_once(client,
'''            require(parsed.userInfo.isNullOrBlank()) { "Do not put credentials in the OctoPrint URL" }
            require(!parsed.host.isNullOrBlank()) { "Invalid OctoPrint server host" }
''',
'''            require(parsed.userInfo.isNullOrBlank()) { "Do not put credentials in the OctoPrint URL" }
            require(parsed.rawQuery.isNullOrBlank() && parsed.rawFragment.isNullOrBlank()) {
                "OctoPrint server URL must not contain a query or fragment"
            }
            require(!parsed.host.isNullOrBlank()) { "Invalid OctoPrint server host" }
''')
replace_once(client,
'''        private fun effectivePort(uri: URI): Int = when {
''',
'''        internal fun normalizeRemotePath(value: String, allowBlank: Boolean): String {
            val trimmed = value.trim().trim('/')
            if (trimmed.isBlank()) {
                require(allowBlank) { "OctoPrint file path cannot be empty" }
                return ""
            }
            require('\\' !in trimmed) { "OctoPrint paths must use forward slashes" }
            val segments = trimmed.split('/')
            require(segments.none(String::isBlank)) { "OctoPrint path contains an empty segment" }
            return segments.joinToString("/") { validateRemoteSegment(it) }
        }

        private fun validateRemoteSegment(value: String): String {
            require(value.isNotBlank() && value != "." && value != "..") { "Invalid OctoPrint path segment" }
            require('/' !in value && '\\' !in value) { "Invalid OctoPrint path segment" }
            require(value.none(Char::isISOControl)) { "OctoPrint paths cannot contain control characters" }
            return value
        }

        private fun effectivePort(uri: URI): Int = when {
''')

repo_path = ROOT / 'app/src/main/java/com/tomppi/enderslicer/octoprint/OctoPrintRepository.kt'
repo_path.write_text(r'''package com.tomppi.enderslicer.octoprint

import android.content.Context
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class OctoPrintRepository(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val store = OctoPrintSecretStore(appContext)
    private val refreshMutex = Mutex()
    private val commandMutex = Mutex()

    private var generation = 0L
    private var setupRequestId = 0L
    private var testRequestId = 0L
    private var authorizationLaunchNonce = 0L
    private var webcamRequestId = 0L

    private var sessionJob = SupervisorJob(scope.coroutineContext[Job])
    private var sessionScope = CoroutineScope(scope.coroutineContext + sessionJob)
    private var pollingJob: Job? = null
    private var authorizationJob: Job? = null
    private var webcamJob: Job? = null
    private var webcamVisible = false

    private var activeClient: OctoPrintClient? = null
    private var activeClientKey: String? = null
    private var cachedServerInfo: OctoPrintServerInfo? = null
    private var cachedWebcam: OctoPrintWebcamConfig? = null
    private var lastStaticRefreshMillis = 0L

    private val initialConfig = store.loadConfig()
    private val initialApiKey = store.loadApiKey()
    private val _state = MutableStateFlow(
        OctoPrintUiState(
            config = initialConfig,
            hasApiKey = initialApiKey != null,
            statusMessage = if (initialConfig.isConfigured && initialApiKey != null) {
                "Connecting to OctoPrint…"
            } else {
                "Configure OctoPrint to begin"
            },
        ),
    )
    val state: StateFlow<OctoPrintUiState> = _state.asStateFlow()

    init {
        if (_state.value.isReady) restartPolling(immediate = true)
    }

    fun testConnection(baseUrl: String, apiKey: String?) {
        val requestId = ++testRequestId
        val requestGeneration = generation
        scope.launch {
            setStatus("Testing OctoPrint connection…")
            runCatching {
                withContext(Dispatchers.IO) {
                    val client = OctoPrintClient(baseUrl, apiKey?.trim()?.takeIf(String::isNotBlank))
                    try {
                        val version = client.version()
                        val user = if (apiKey.isNullOrBlank()) null else client.currentUser()
                        OctoPrintJson.parseServerInfo(version, user)
                    } finally {
                        client.cancelActiveRequests()
                    }
                }
            }.onSuccess { info ->
                if (requestId != testRequestId || requestGeneration != generation) return@onSuccess
                _state.update {
                    it.copy(
                        serverInfo = info,
                        statusMessage = "Connected to ${info.displayText ?: info.serverVersion ?: "OctoPrint"}",
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                if (requestId == testRequestId && requestGeneration == generation && error !is CancellationException) {
                    setError(error)
                }
            }
        }
    }

    fun saveManualConfiguration(
        baseUrl: String,
        username: String,
        apiKey: String,
        snapshotUrlOverride: String,
        pollIntervalSeconds: Int,
    ) {
        val requestId = ++setupRequestId
        authorizationJob?.cancel()
        authorizationJob = null
        scope.launch {
            setStatus("Validating OctoPrint credentials…")
            runCatching {
                withContext(Dispatchers.IO) {
                    val normalized = OctoPrintClient.normalizeBaseUrl(baseUrl).toString().removeSuffix("/")
                    val cleanKey = apiKey.trim()
                    require(cleanKey.isNotBlank()) { "Enter an OctoPrint API key" }
                    val config = OctoPrintConfig(
                        baseUrl = normalized,
                        username = username.trim(),
                        snapshotUrlOverride = snapshotUrlOverride.trim(),
                        pollIntervalSeconds = pollIntervalSeconds.coerceIn(1, 30),
                    )
                    val client = OctoPrintClient(config.baseUrl, cleanKey)
                    try {
                        val info = OctoPrintJson.parseServerInfo(client.version(), client.currentUser())
                        Triple(config, cleanKey, info)
                    } finally {
                        client.cancelActiveRequests()
                    }
                }
            }.onSuccess { (config, key, info) ->
                if (requestId != setupRequestId) return@onSuccess
                activateConfiguration(config, key, info)
            }.onFailure { error ->
                if (requestId == setupRequestId && error !is CancellationException) setError(error)
            }
        }
    }

    fun beginApplicationAuthorization(
        baseUrl: String,
        username: String,
        snapshotUrlOverride: String,
        pollIntervalSeconds: Int,
    ) {
        val requestId = ++setupRequestId
        authorizationJob?.cancel()
        authorizationJob = scope.launch {
            setStatus("Requesting OctoPrint authorization…")
            runCatching {
                withContext(Dispatchers.IO) {
                    val normalized = OctoPrintClient.normalizeBaseUrl(baseUrl).toString().removeSuffix("/")
                    val config = OctoPrintConfig(
                        baseUrl = normalized,
                        username = username.trim(),
                        snapshotUrlOverride = snapshotUrlOverride.trim(),
                        pollIntervalSeconds = pollIntervalSeconds.coerceIn(1, 30),
                    )
                    val client = OctoPrintClient(config.baseUrl)
                    try {
                        require(client.probeApplicationKeys()) {
                            "This OctoPrint server does not expose the Application Keys workflow; use a user API key instead"
                        }
                        config to client.requestApplicationKey(config.username.takeIf(String::isNotBlank))
                    } finally {
                        client.cancelActiveRequests()
                    }
                }
            }.onSuccess { (config, authorization) ->
                if (requestId != setupRequestId) return@onSuccess
                authorizationLaunchNonce++
                _state.update {
                    it.copy(
                        authorizationPending = true,
                        authorizationDialogUrl = authorization.dialogUrl,
                        authorizationDialogLaunchNonce = authorizationLaunchNonce,
                        statusMessage = "Approve enderslicercura in the OctoPrint authorization page",
                        errorMessage = null,
                    )
                }
                pollAuthorization(config, authorization.pollingUrl, requestId)
            }.onFailure { error ->
                if (requestId == setupRequestId && error !is CancellationException) setError(error)
            }
        }
    }

    fun acknowledgeAuthorizationDialog() = Unit

    fun reopenAuthorizationDialog() {
        val current = _state.value
        if (!current.authorizationPending || current.authorizationDialogUrl.isNullOrBlank()) return
        authorizationLaunchNonce++
        _state.update { it.copy(authorizationDialogLaunchNonce = authorizationLaunchNonce) }
    }

    fun cancelAuthorization() {
        setupRequestId++
        authorizationJob?.cancel()
        authorizationJob = null
        _state.update {
            it.copy(
                authorizationPending = false,
                authorizationDialogUrl = null,
                authorizationDialogLaunchNonce = 0L,
                statusMessage = if (it.isReady) "OctoPrint authorization cancelled; existing configuration kept" else "OctoPrint authorization cancelled",
                errorMessage = null,
            )
        }
    }

    fun clearConfiguration() {
        setupRequestId++
        testRequestId++
        authorizationJob?.cancel()
        authorizationJob = null
        resetSession()
        store.clearAll()
        _state.value = OctoPrintUiState(statusMessage = "OctoPrint configuration removed")
    }

    fun refresh() {
        sessionScope.launch { refreshAll(showSpinner = true, forceStatic = true) }
    }

    fun refreshFiles(force: Boolean = true) {
        val requestGeneration = generation
        sessionScope.launch {
            _state.update { it.copy(isFileListRefreshing = true, errorMessage = null) }
            runCatching {
                withContext(Dispatchers.IO) { requireClient().files(force) }
            }.onSuccess { (files, freeBytes) ->
                if (!isCurrent(requestGeneration)) return@onSuccess
                _state.update {
                    it.copy(
                        files = files,
                        freeBytes = freeBytes,
                        isFileListRefreshing = false,
                        statusMessage = "Loaded ${files.size} OctoPrint file entries",
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                if (!isCurrent(requestGeneration) || error is CancellationException) return@onFailure
                _state.update { it.copy(isFileListRefreshing = false) }
                handleSessionError(error, requestGeneration, forbiddenMeansInvalidKey = false)
            }
        }
    }

    fun uploadGcode(
        path: String?,
        suggestedName: String,
        remoteDirectory: String,
        action: OctoPrintUploadAction,
    ) {
        val source = path?.let(::File)
        if (source == null || !source.isFile) {
            setError(IllegalStateException("Slice the model before sending G-code to OctoPrint"))
            return
        }
        if (_state.value.isUploading) {
            setError(IllegalStateException("A G-code upload is already in progress"))
            return
        }
        if (action == OctoPrintUploadAction.UPLOAD_AND_PRINT && _state.value.hasActiveJob) {
            setError(IllegalStateException("Finish or cancel the active OctoPrint job before uploading and printing"))
            return
        }
        val cleanDirectory = runCatching {
            OctoPrintClient.normalizeRemotePath(remoteDirectory, allowBlank = true)
        }.getOrElse {
            setError(it)
            return
        }
        val remoteName = sanitizeGcodeName(suggestedName.ifBlank { source.name })
        val requestGeneration = generation
        _state.update {
            it.copy(
                isUploading = true,
                uploadProgress = 0f,
                uploadFileName = remoteName,
                statusMessage = "Uploading $remoteName to OctoPrint…",
                errorMessage = null,
            )
        }
        sessionScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val uploadSource = snapshotGcodeForUpload(source)
                    try {
                        requireClient().upload(uploadSource, remoteName, cleanDirectory, action) { sent, total ->
                            if (!isCurrent(requestGeneration)) return@upload
                            val progress = if (total <= 0L) null else {
                                (sent.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
                            }
                            _state.update { current -> current.copy(uploadProgress = progress) }
                        }
                    } finally {
                        uploadSource.delete()
                    }
                }
            }.onSuccess { response ->
                if (!isCurrent(requestGeneration)) return@onSuccess
                val effectivePrint = response.optBoolean("effectivePrint", false)
                val effectiveSelect = response.optBoolean("effectiveSelect", false)
                val warning = when {
                    action == OctoPrintUploadAction.UPLOAD_AND_PRINT && !effectivePrint ->
                        "$remoteName was uploaded, but OctoPrint did not start printing. Check printer state and select the file manually."
                    action == OctoPrintUploadAction.UPLOAD_AND_SELECT && !effectiveSelect ->
                        "$remoteName was uploaded, but OctoPrint did not select it."
                    else -> null
                }
                _state.update {
                    it.copy(
                        isUploading = false,
                        uploadProgress = 1f,
                        statusMessage = warning ?: when {
                            effectivePrint -> "$remoteName uploaded and printing started"
                            effectiveSelect -> "$remoteName uploaded and selected"
                            else -> "$remoteName uploaded to OctoPrint"
                        },
                        errorMessage = warning,
                    )
                }
                refreshFiles(force = true)
                refreshAll(showSpinner = false, forceStatic = false)
            }.onFailure { error ->
                if (!isCurrent(requestGeneration) || error is CancellationException) return@onFailure
                _state.update { it.copy(isUploading = false, uploadProgress = null) }
                handleSessionError(error, requestGeneration, forbiddenMeansInvalidKey = false)
            }
        }
    }

    fun selectFile(path: String, print: Boolean) = operation(
        message = if (print) "Starting OctoPrint file…" else "Selecting OctoPrint file…",
        guard = if (print) noActiveJobGuard("starting another print") else null,
    ) { selectFile(path, print) }

    fun deleteFile(path: String) = operation("Deleting OctoPrint file…", refreshFileList = true) { deleteFile(path) }
    fun createFolder(parentPath: String, name: String) = operation("Creating OctoPrint folder…", refreshFileList = true) { createFolder(parentPath, name) }
    fun moveFile(path: String, destination: String) = operation("Moving OctoPrint file…", refreshFileList = true) { moveFile(path, destination) }
    fun copyFile(path: String, destination: String) = operation("Copying OctoPrint file…", refreshFileList = true) { copyFile(path, destination) }

    fun startJob() = operation("Starting print…", guard = noActiveJobGuard("starting a print")) { jobCommand("start") }
    fun pauseJob() = operation("Pausing print…", guard = { if (!it.isPrinting) "No active print is available to pause" else null }) { jobCommand("pause", "pause") }
    fun resumeJob() = operation("Resuming print…", guard = { if (!it.isPaused) "No paused print is available to resume" else null }) { jobCommand("pause", "resume") }
    fun cancelJob() = operation("Cancelling print…", guard = { if (!it.hasActiveJob) "No active print is available to cancel" else null }) { jobCommand("cancel") }
    fun restartJob() = operation("Restarting print…", guard = { if (!it.isPaused) "Restart is only available for a paused print" else null }) { jobCommand("restart") }

    fun connect(
        port: String?,
        baudrate: Int?,
        printerProfile: String?,
        save: Boolean,
        autoConnect: Boolean,
    ) = operation(
        "Connecting OctoPrint to the printer…",
        guard = noActiveJobGuard("reconnecting the printer"),
    ) { connect(port, baudrate, printerProfile, save, autoConnect) }

    fun disconnect() = operation(
        "Disconnecting printer…",
        guard = noActiveJobGuard("disconnecting the printer"),
    ) { disconnect() }

    fun jog(x: Double? = null, y: Double? = null, z: Double? = null) = operation(
        "Jogging printer…",
        guard = idlePrinterGuard("Jogging"),
    ) { jog(x, y, z) }

    fun home(axes: Set<String>) = operation(
        "Homing ${axes.joinToString("").uppercase(Locale.US)}…",
        guard = idlePrinterGuard("Homing"),
    ) { home(axes) }

    fun setToolTemperature(tool: String, target: Int) = operation(
        "Setting nozzle target…",
        guard = operationalGuard("Nozzle temperature control"),
    ) { setToolTemperature(tool, target) }

    fun setBedTemperature(target: Int) = operation(
        "Setting bed target…",
        guard = operationalGuard("Bed temperature control"),
    ) { setBedTemperature(target) }

    fun extrude(amountMm: Double) = operation(
        if (amountMm > 0) "Extruding filament…" else "Retracting filament…",
        guard = idlePrinterGuard(if (amountMm > 0) "Extrusion" else "Retraction"),
    ) { extrude(amountMm) }

    fun setFeedRate(percent: Int) = operation(
        "Setting feed rate…",
        guard = operationalGuard("Feed-rate control"),
    ) { setFeedRate(percent) }

    fun setFlowRate(percent: Int) = operation(
        "Setting flow rate…",
        guard = operationalGuard("Flow-rate control"),
    ) { setFlowRate(percent) }

    fun sendGcode(command: String) = operation(
        "Sending G-code command…",
        guard = idlePrinterGuard("Terminal commands"),
    ) { sendGcode(command) }

    fun setWebcamVisible(visible: Boolean) {
        webcamVisible = visible
        webcamRequestId++
        if (visible) {
            restartWebcamPolling()
        } else {
            webcamJob?.cancel()
            webcamJob = null
            _state.update { it.copy(webcamFrame = null) }
        }
    }

    fun saveSnapshotOverride(value: String) {
        val config = _state.value.config.copy(snapshotUrlOverride = value.trim())
        store.saveConfig(config)
        webcamRequestId++
        _state.update { it.copy(config = config, webcamFrame = null) }
        if (webcamVisible) restartWebcamPolling()
    }

    private suspend fun pollAuthorization(config: OctoPrintConfig, pollingUrl: String, requestId: Long) {
        runCatching {
            withTimeout(AUTHORIZATION_TIMEOUT_MILLIS) {
                val client = OctoPrintClient(config.baseUrl)
                try {
                    while (isActive && requestId == setupRequestId) {
                        when (val result = withContext(Dispatchers.IO) { client.pollApplicationKey(pollingUrl) }) {
                            OctoPrintClient.AppKeyPollResult.Pending -> delay(1_000L)
                            OctoPrintClient.AppKeyPollResult.Denied -> error("OctoPrint authorization was denied or expired")
                            is OctoPrintClient.AppKeyPollResult.Granted -> {
                                val authorizedClient = OctoPrintClient(config.baseUrl, result.apiKey)
                                try {
                                    val info = withContext(Dispatchers.IO) {
                                        OctoPrintJson.parseServerInfo(authorizedClient.version(), authorizedClient.currentUser())
                                    }
                                    return@withTimeout Triple(result.apiKey, info, Unit)
                                } finally {
                                    authorizedClient.cancelActiveRequests()
                                }
                            }
                        }
                    }
                    error("OctoPrint authorization cancelled")
                } finally {
                    client.cancelActiveRequests()
                }
            }
        }.onSuccess { (key, info) ->
            if (requestId != setupRequestId) return@onSuccess
            activateConfiguration(config, key, info)
        }.onFailure { error ->
            if (requestId != setupRequestId || error is CancellationException) return@onFailure
            _state.update {
                it.copy(
                    authorizationPending = false,
                    authorizationDialogUrl = null,
                    authorizationDialogLaunchNonce = 0L,
                )
            }
            setError(error)
        }
    }

    private fun activateConfiguration(config: OctoPrintConfig, key: String, info: OctoPrintServerInfo) {
        authorizationJob?.cancel()
        authorizationJob = null
        resetSession()
        store.saveApiKey(key)
        store.saveConfig(config)
        cachedServerInfo = info
        lastStaticRefreshMillis = System.currentTimeMillis()
        _state.value = OctoPrintUiState(
            config = config,
            hasApiKey = true,
            serverInfo = info,
            statusMessage = "OctoPrint configured",
        )
        restartPolling(immediate = true)
    }

    private fun resetSession() {
        generation++
        pollingJob?.cancel()
        webcamJob?.cancel()
        activeClient?.cancelActiveRequests()
        sessionJob.cancel()
        sessionJob = SupervisorJob(scope.coroutineContext[Job])
        sessionScope = CoroutineScope(scope.coroutineContext + sessionJob)
        pollingJob = null
        webcamJob = null
        activeClient = null
        activeClientKey = null
        cachedServerInfo = null
        cachedWebcam = null
        lastStaticRefreshMillis = 0L
        webcamRequestId++
    }

    private fun restartPolling(immediate: Boolean) {
        pollingJob?.cancel()
        if (!_state.value.isReady) return
        val requestGeneration = generation
        pollingJob = sessionScope.launch {
            if (!immediate) delay(1_000L)
            while (isActive && isCurrent(requestGeneration) && _state.value.isReady) {
                refreshAll(showSpinner = false, forceStatic = false)
                val seconds = if (_state.value.hasActiveJob) ACTIVE_PRINT_POLL_SECONDS else {
                    _state.value.config.pollIntervalSeconds.coerceIn(1, 30)
                }
                delay(seconds * 1_000L)
            }
        }
        if (webcamVisible) restartWebcamPolling()
    }

    private suspend fun refreshAll(showSpinner: Boolean, forceStatic: Boolean) {
        if (!_state.value.isReady) return
        val requestGeneration = generation
        refreshMutex.withLock {
            if (!isCurrent(requestGeneration)) return
            if (showSpinner) _state.update { it.copy(isRefreshing = true, errorMessage = null) }
            runCatching {
                coroutineScope {
                    val client = requireClient()
                    val now = System.currentTimeMillis()
                    val refreshStatic = forceStatic || cachedServerInfo == null || now - lastStaticRefreshMillis >= STATIC_REFRESH_INTERVAL_MILLIS
                    val static = if (refreshStatic) {
                        async(Dispatchers.IO) {
                            val server = OctoPrintJson.parseServerInfo(client.version(), client.currentUser())
                            val webcam = runCatching { client.webcamSettings() }.getOrDefault(cachedWebcam ?: _state.value.webcam)
                            server to webcam
                        }
                    } else null
                    val job = async(Dispatchers.IO) { client.jobState() }
                    val connection = async(Dispatchers.IO) { client.connectionState() }
                    val printer = async(Dispatchers.IO) {
                        try {
                            client.printerState()
                        } catch (error: OctoPrintClient.OctoPrintHttpException) {
                            if (error.statusCode != 409) throw error
                            OctoPrintPrinterState(text = connection.await().state)
                        }
                    }
                    val staticResult = static?.await()
                    RefreshSnapshot(
                        server = staticResult?.first ?: cachedServerInfo ?: _state.value.serverInfo,
                        job = job.await(),
                        connection = connection.await(),
                        printer = printer.await(),
                        webcam = staticResult?.second ?: cachedWebcam ?: _state.value.webcam,
                        refreshedStatic = staticResult != null,
                    )
                }
            }.onSuccess { snapshot ->
                if (!isCurrent(requestGeneration)) return@onSuccess
                if (snapshot.refreshedStatic) {
                    cachedServerInfo = snapshot.server
                    cachedWebcam = snapshot.webcam
                    lastStaticRefreshMillis = System.currentTimeMillis()
                }
                _state.update {
                    it.copy(
                        serverInfo = snapshot.server,
                        job = snapshot.job,
                        connection = snapshot.connection,
                        printer = snapshot.printer,
                        webcam = snapshot.webcam,
                        isRefreshing = false,
                        lastUpdatedEpochMillis = System.currentTimeMillis(),
                        statusMessage = snapshot.job.error
                            ?: snapshot.printer.text.takeIf(String::isNotBlank)
                            ?: snapshot.connection.state,
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                if (!isCurrent(requestGeneration) || error is CancellationException) return@onFailure
                _state.update { it.copy(isRefreshing = false) }
                handleSessionError(error, requestGeneration, forbiddenMeansInvalidKey = true)
            }
        }
    }

    private fun restartWebcamPolling() {
        webcamJob?.cancel()
        if (!webcamVisible || !_state.value.isReady) return
        val requestGeneration = generation
        val requestId = ++webcamRequestId
        webcamJob = sessionScope.launch {
            while (isActive && webcamVisible && isCurrent(requestGeneration) && requestId == webcamRequestId) {
                val snapshotUrl = _state.value.config.snapshotUrlOverride.takeIf(String::isNotBlank)
                    ?: _state.value.webcam.snapshotUrl
                if (snapshotUrl.isNullOrBlank()) {
                    delay(3_000L)
                    continue
                }
                runCatching {
                    withContext(Dispatchers.IO) { requireClient().fetchWebcamSnapshot(snapshotUrl) }
                }.onSuccess { bytes ->
                    if (isCurrent(requestGeneration) && requestId == webcamRequestId) {
                        _state.update { it.copy(webcamFrame = bytes) }
                    }
                }.onFailure { error ->
                    if (error is OctoPrintClient.OctoPrintHttpException && error.statusCode == 401) {
                        handleSessionError(error, requestGeneration, forbiddenMeansInvalidKey = false)
                    }
                }
                delay(WEBCAM_POLL_MILLIS)
            }
        }
    }

    private fun operation(
        message: String,
        refreshFileList: Boolean = false,
        guard: ((OctoPrintUiState) -> String?)? = null,
        block: OctoPrintClient.() -> Unit,
    ) {
        val requestGeneration = generation
        sessionScope.launch {
            commandMutex.withLock {
                if (!isCurrent(requestGeneration)) return@withLock
                guard?.invoke(_state.value)?.let { reason ->
                    setError(IllegalStateException(reason))
                    return@withLock
                }
                setStatus(message)
                runCatching {
                    withContext(Dispatchers.IO) { requireClient().block() }
                }.onSuccess {
                    if (!isCurrent(requestGeneration)) return@onSuccess
                    if (refreshFileList) refreshFiles(force = true)
                    refreshAll(showSpinner = false, forceStatic = false)
                }.onFailure { error ->
                    if (error !is CancellationException) {
                        handleSessionError(error, requestGeneration, forbiddenMeansInvalidKey = false)
                    }
                }
            }
        }
    }

    private fun idlePrinterGuard(action: String): (OctoPrintUiState) -> String? = { current ->
        when {
            !current.isReady -> "Configure and connect OctoPrint before $action"
            !current.printer.operational || current.hasActiveJob -> "$action is only available while the printer is operational and idle"
            else -> null
        }
    }

    private fun operationalGuard(action: String): (OctoPrintUiState) -> String? = { current ->
        when {
            !current.isReady -> "Configure and connect OctoPrint before $action"
            !current.printer.operational -> "$action requires an operational printer connection"
            else -> null
        }
    }

    private fun noActiveJobGuard(action: String): (OctoPrintUiState) -> String? = { current ->
        when {
            !current.isReady -> "Configure OctoPrint before $action"
            current.hasActiveJob -> "Finish or cancel the active OctoPrint job before $action"
            else -> null
        }
    }

    private fun requireClient(): OctoPrintClient {
        val current = _state.value
        require(current.config.isConfigured) { "Configure the OctoPrint server first" }
        val key = store.loadApiKey()
        if (key == null) {
            error("OctoPrint API key is missing or unreadable; authorize the app again")
        }
        val cached = activeClient
        if (cached != null && activeClientKey == key) return cached
        cached?.cancelActiveRequests()
        return OctoPrintClient(current.config.baseUrl, key).also {
            activeClient = it
            activeClientKey = key
        }
    }

    private fun handleSessionError(error: Throwable, requestGeneration: Long, forbiddenMeansInvalidKey: Boolean) {
        if (!isCurrent(requestGeneration) || error is CancellationException) return
        val http = error as? OctoPrintClient.OctoPrintHttpException
        if (http?.statusCode == 401 || (forbiddenMeansInvalidKey && http?.statusCode == 403)) {
            invalidateAuthorization(error.message ?: "OctoPrint authorization is no longer valid")
        } else {
            setError(error)
        }
    }

    private fun invalidateAuthorization(message: String) {
        val config = _state.value.config
        resetSession()
        store.clearApiKey()
        _state.value = OctoPrintUiState(
            config = config,
            hasApiKey = false,
            statusMessage = "$message. Authorize OctoPrint again.",
            errorMessage = "$message. Authorize OctoPrint again.",
        )
    }

    private fun isCurrent(requestGeneration: Long): Boolean = requestGeneration == generation

    private fun setStatus(message: String) {
        _state.update { it.copy(statusMessage = message, errorMessage = null) }
    }

    private fun setError(error: Throwable) {
        val message = error.message ?: error::class.java.simpleName
        _state.update { it.copy(statusMessage = message, errorMessage = message) }
    }

    private fun snapshotGcodeForUpload(source: File): File {
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
                snapshot.outputStream().buffered().use { output -> input.copyTo(output) }
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
        val leafName = value.substringAfterLast('/').substringAfterLast('\\')
        val stem = leafName
            .substringBeforeLast('.', leafName)
            .replace(Regex("[^A-Za-z0-9._ -]+"), "_")
            .trim(' ', '.', '_')
            .take(96)
            .ifBlank { "enderslicercura-${System.currentTimeMillis()}" }
        return "$stem.gcode"
    }

    private data class RefreshSnapshot(
        val server: OctoPrintServerInfo,
        val job: OctoPrintJobState,
        val connection: OctoPrintConnectionState,
        val printer: OctoPrintPrinterState,
        val webcam: OctoPrintWebcamConfig,
        val refreshedStatic: Boolean,
    )

    private companion object {
        const val ACTIVE_PRINT_POLL_SECONDS = 2
        const val AUTHORIZATION_TIMEOUT_MILLIS = 5L * 60L * 1_000L
        const val UPLOAD_SNAPSHOT_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L
        const val STATIC_REFRESH_INTERVAL_MILLIS = 5L * 60L * 1_000L
        const val WEBCAM_POLL_MILLIS = 1_500L
    }
}
''')

vm = 'app/src/main/java/com/tomppi/enderslicer/octoprint/OctoPrintViewModel.kt'
replace_once(vm,
'''    fun acknowledgeAuthorizationDialog() = repository.acknowledgeAuthorizationDialog()
    fun cancelAuthorization() = repository.cancelAuthorization()
''',
'''    fun acknowledgeAuthorizationDialog() = repository.acknowledgeAuthorizationDialog()
    fun reopenAuthorizationDialog() = repository.reopenAuthorizationDialog()
    fun cancelAuthorization() = repository.cancelAuthorization()
''')

integrated = 'app/src/main/java/com/tomppi/enderslicer/ui/IntegratedEnderSlicerApp.kt'
replace_once(integrated,
'''    LaunchedEffect(octoPrintState.authorizationDialogUrl) {
        val url = octoPrintState.authorizationDialogUrl ?: return@LaunchedEffect
''',
'''    LaunchedEffect(octoPrintState.authorizationDialogLaunchNonce) {
        if (octoPrintState.authorizationDialogLaunchNonce == 0L) return@LaunchedEffect
        val url = octoPrintState.authorizationDialogUrl ?: return@LaunchedEffect
''')
replace_once(integrated,
'''                    octoPrintState.isPaused -> "OctoPrint paused"
                    octoPrintState.isReady -> "OctoPrint"
''',
'''                    octoPrintState.isPaused -> "OctoPrint paused"
                    octoPrintState.isTransitioning -> "OctoPrint busy"
                    octoPrintState.isReady -> "OctoPrint"
''')

sheet = 'app/src/main/java/com/tomppi/enderslicer/ui/OctoPrintSheet.kt'
replace_once(sheet,
'''    DisposableEffect(Unit) {
        viewModel.setWebcamVisible(true)
        onDispose { viewModel.setWebcamVisible(false) }
    }

''', '')
replace_once(sheet,
'''    var remoteDirectory by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
''',
'''    var remoteDirectory by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    DisposableEffect(Unit) {
        viewModel.setWebcamVisible(true)
        onDispose { viewModel.setWebcamVisible(false) }
    }
''')
replace_once(sheet,
'''                        state.job.fileName != null -> Button(onClick = onConfirmStart, modifier = Modifier.weight(1f)) {
''',
'''                        state.job.fileName != null && !state.hasActiveJob -> Button(onClick = onConfirmStart, modifier = Modifier.weight(1f)) {
''')
replace_once(sheet,
'''                        enabled = localGcodePath != null && !state.isUploading && !state.isPrinting,
''',
'''                        enabled = localGcodePath != null && !state.isUploading && !state.hasActiveJob,
''')
replace_once(sheet,
'''        value = withContext(Dispatchers.Default) {
            bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
''',
'''        value = withContext(Dispatchers.Default) {
            bytes?.let(::decodeWebcamBitmap)
        }
''')
replace_once(sheet,
'''        if (state.authorizationPending) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.height(24.dp))
                Text("Waiting for approval in OctoPrint…")
                TextButton(onClick = viewModel::cancelAuthorization) {
                    Text("Cancel")
                }
            }
        }
''',
'''        if (state.authorizationPending) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.height(24.dp))
                Text("Waiting for approval in OctoPrint…")
                TextButton(onClick = viewModel::reopenAuthorizationDialog) {
                    Text("Open approval page")
                }
                TextButton(onClick = viewModel::cancelAuthorization) {
                    Text("Cancel")
                }
            }
        }
''')
replace_once(sheet,
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
''')
replace_once(sheet,
'''                Button(onClick = { viewModel.sendGcode(command) }, enabled = command.isNotBlank()) {
''',
'''                Button(
                    onClick = { viewModel.sendGcode(command) },
                    enabled = command.isNotBlank() && state.printer.operational && !state.hasActiveJob,
                ) {
''')
replace_once(sheet,
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
''')

tests = 'app/src/test/java/com/tomppi/enderslicer/octoprint/OctoPrintClientTest.kt'
replace_once(tests,
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
            runCatching { OctoPrintClient.normalizeRemotePath("models\\cube.gcode", false) }
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
    fun parsesPrinterTemperaturesAndFlags() {
''')

doc = ROOT / 'docs/bug-audit.md'
text = doc.read_text()
marker = '## Second OctoPrint hardening pass'
if marker not in text:
    doc.write_text(text.rstrip() + r'''

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
''')

print('OctoPrint second-pass patch applied')
