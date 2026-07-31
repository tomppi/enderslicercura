package com.tomppi.enderslicer.octoprint

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.io.File
import java.util.Locale

class OctoPrintRepository(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val store = OctoPrintSecretStore(appContext)
    private val refreshMutex = Mutex()
    private val commandMutex = Mutex()
    private var pollingJob: Job? = null
    private var authorizationJob: Job? = null
    private var webcamJob: Job? = null
    private var webcamVisible: Boolean = false

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
        scope.launch {
            setStatus("Testing OctoPrint connection…")
            runCatching {
                withContext(Dispatchers.IO) {
                    val client = OctoPrintClient(baseUrl, apiKey?.trim()?.takeIf(String::isNotBlank))
                    val version = client.version()
                    val user = if (apiKey.isNullOrBlank()) null else client.currentUser()
                    OctoPrintJson.parseServerInfo(version, user)
                }
            }.onSuccess { info ->
                _state.update {
                    it.copy(
                        serverInfo = info,
                        statusMessage = "Connected to ${info.displayText ?: info.serverVersion ?: "OctoPrint"}",
                        errorMessage = null,
                    )
                }
            }.onFailure(::setError)
        }
    }

    fun saveManualConfiguration(
        baseUrl: String,
        username: String,
        apiKey: String,
        snapshotUrlOverride: String,
        pollIntervalSeconds: Int,
    ) {
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
                    val info = OctoPrintJson.parseServerInfo(client.version(), client.currentUser())
                    Triple(config, cleanKey, info)
                }
            }.onSuccess { (config, key, info) ->
                store.saveConfig(config)
                store.saveApiKey(key)
                _state.update {
                    it.copy(
                        config = config,
                        hasApiKey = true,
                        serverInfo = info,
                        statusMessage = "OctoPrint configured",
                        errorMessage = null,
                    )
                }
                restartPolling(immediate = true)
            }.onFailure(::setError)
        }
    }

    fun beginApplicationAuthorization(
        baseUrl: String,
        username: String,
        snapshotUrlOverride: String,
        pollIntervalSeconds: Int,
    ) {
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
                    require(client.probeApplicationKeys()) {
                        "This OctoPrint server does not expose the Application Keys workflow; use a user API key instead"
                    }
                    config to client.requestApplicationKey(config.username.takeIf(String::isNotBlank))
                }
            }.onSuccess { (config, authorization) ->
                store.saveConfig(config)
                store.clearApiKey()
                _state.update {
                    it.copy(
                        config = config,
                        hasApiKey = false,
                        authorizationPending = true,
                        authorizationDialogUrl = authorization.dialogUrl,
                        statusMessage = "Approve enderslicercura in the OctoPrint authorization page",
                        errorMessage = null,
                    )
                }
                pollAuthorization(config, authorization.pollingUrl)
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                setError(error)
            }
        }
    }

    fun acknowledgeAuthorizationDialog() {
        _state.update { it.copy(authorizationDialogUrl = null) }
    }

    fun cancelAuthorization() {
        authorizationJob?.cancel()
        authorizationJob = null
        _state.update {
            it.copy(
                authorizationPending = false,
                authorizationDialogUrl = null,
                statusMessage = "OctoPrint authorization cancelled",
                errorMessage = null,
            )
        }
    }

    fun clearConfiguration() {
        pollingJob?.cancel()
        authorizationJob?.cancel()
        webcamJob?.cancel()
        pollingJob = null
        authorizationJob = null
        webcamJob = null
        store.clearAll()
        _state.value = OctoPrintUiState(statusMessage = "OctoPrint configuration removed")
    }

    fun refresh() {
        scope.launch { refreshAll(showSpinner = true) }
    }

    fun refreshFiles(force: Boolean = true) {
        scope.launch {
            _state.update { it.copy(isFileListRefreshing = true, errorMessage = null) }
            runCatching {
                withContext(Dispatchers.IO) { requireClient().files(force) }
            }.onSuccess { (files, freeBytes) ->
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
                _state.update { it.copy(isFileListRefreshing = false) }
                setError(error)
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
        val remoteName = sanitizeGcodeName(suggestedName.ifBlank { source.name })
        scope.launch {
            _state.update {
                it.copy(
                    isUploading = true,
                    uploadProgress = 0f,
                    uploadFileName = remoteName,
                    statusMessage = "Uploading $remoteName to OctoPrint…",
                    errorMessage = null,
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    val uploadSource = snapshotGcodeForUpload(source)
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
                }
            }.onSuccess { response ->
                val effectivePrint = response.optBoolean("effectivePrint", false)
                val effectiveSelect = response.optBoolean("effectiveSelect", false)
                _state.update {
                    it.copy(
                        isUploading = false,
                        uploadProgress = 1f,
                        statusMessage = when {
                            effectivePrint -> "$remoteName uploaded and printing started"
                            effectiveSelect -> "$remoteName uploaded and selected"
                            else -> "$remoteName uploaded to OctoPrint"
                        },
                        errorMessage = null,
                    )
                }
                refreshFiles(force = true)
                refreshAll(showSpinner = false)
            }.onFailure { error ->
                _state.update { it.copy(isUploading = false, uploadProgress = null) }
                setError(error)
            }
        }
    }

    fun selectFile(path: String, print: Boolean) = operation(
        if (print) "Starting OctoPrint file…" else "Selecting OctoPrint file…",
    ) {
        selectFile(path, print)
    }

    fun deleteFile(path: String) = operation(
        message = "Deleting OctoPrint file…",
        refreshFileList = true,
    ) {
        deleteFile(path)
    }

    fun createFolder(parentPath: String, name: String) = operation(
        message = "Creating OctoPrint folder…",
        refreshFileList = true,
    ) {
        createFolder(parentPath, name)
    }

    fun moveFile(path: String, destination: String) = operation(
        message = "Moving OctoPrint file…",
        refreshFileList = true,
    ) {
        moveFile(path, destination)
    }

    fun copyFile(path: String, destination: String) = operation(
        message = "Copying OctoPrint file…",
        refreshFileList = true,
    ) {
        copyFile(path, destination)
    }

    fun startJob() = operation("Starting print…") { jobCommand("start") }
    fun pauseJob() = operation("Pausing print…") { jobCommand("pause", "pause") }
    fun resumeJob() = operation("Resuming print…") { jobCommand("pause", "resume") }
    fun cancelJob() = operation("Cancelling print…") { jobCommand("cancel") }
    fun restartJob() = operation("Restarting print…") { jobCommand("restart") }

    fun connect(
        port: String?,
        baudrate: Int?,
        printerProfile: String?,
        save: Boolean,
        autoConnect: Boolean,
    ) = operation("Connecting OctoPrint to the printer…") {
        connect(port, baudrate, printerProfile, save, autoConnect)
    }

    fun disconnect() = operation("Disconnecting printer…") { disconnect() }

    fun jog(x: Double? = null, y: Double? = null, z: Double? = null) {
        if (!requireIdlePrinterAction("Jogging")) return
        operation("Jogging printer…") { jog(x, y, z) }
    }

    fun home(axes: Set<String>) {
        if (!requireIdlePrinterAction("Homing")) return
        operation("Homing ${axes.joinToString("").uppercase(Locale.US)}…") { home(axes) }
    }

    fun setToolTemperature(tool: String, target: Int) = operation("Setting nozzle target…") {
        setToolTemperature(tool, target)
    }

    fun setBedTemperature(target: Int) = operation("Setting bed target…") {
        setBedTemperature(target)
    }

    fun extrude(amountMm: Double) {
        if (!requireIdlePrinterAction(if (amountMm > 0) "Extrusion" else "Retraction")) return
        operation(if (amountMm > 0) "Extruding filament…" else "Retracting filament…") {
            extrude(amountMm)
        }
    }

    fun setFeedRate(percent: Int) = operation("Setting feed rate…") { setFeedRate(percent) }
    fun setFlowRate(percent: Int) = operation("Setting flow rate…") { setFlowRate(percent) }
    fun sendGcode(command: String) = operation("Sending G-code command…") { sendGcode(command) }

    fun setWebcamVisible(visible: Boolean) {
        webcamVisible = visible
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
        _state.update { it.copy(config = config, webcamFrame = null) }
        if (webcamVisible) restartWebcamPolling()
    }

    private suspend fun pollAuthorization(config: OctoPrintConfig, pollingUrl: String) {
        runCatching {
            withTimeout(AUTHORIZATION_TIMEOUT_MILLIS) {
                val client = OctoPrintClient(config.baseUrl)
                while (isActive) {
                    when (val result = withContext(Dispatchers.IO) { client.pollApplicationKey(pollingUrl) }) {
                        OctoPrintClient.AppKeyPollResult.Pending -> delay(1_000L)
                        OctoPrintClient.AppKeyPollResult.Denied -> error("OctoPrint authorization was denied or expired")
                        is OctoPrintClient.AppKeyPollResult.Granted -> return@withTimeout result.apiKey
                    }
                }
                error("OctoPrint authorization cancelled")
            }
        }.onSuccess { key ->
            store.saveApiKey(key)
            _state.update {
                it.copy(
                    config = config,
                    hasApiKey = true,
                    authorizationPending = false,
                    authorizationDialogUrl = null,
                    statusMessage = "OctoPrint authorization approved",
                    errorMessage = null,
                )
            }
            restartPolling(immediate = true)
        }.onFailure { error ->
            if (error is CancellationException) return@onFailure
            _state.update { it.copy(authorizationPending = false, authorizationDialogUrl = null) }
            setError(error)
        }
    }

    private fun restartPolling(immediate: Boolean) {
        pollingJob?.cancel()
        if (!_state.value.isReady) return
        pollingJob = scope.launch {
            if (!immediate) delay(1_000L)
            while (isActive && _state.value.isReady) {
                refreshAll(showSpinner = false)
                val seconds = if (_state.value.isPrinting || _state.value.isPaused) {
                    ACTIVE_PRINT_POLL_SECONDS
                } else {
                    _state.value.config.pollIntervalSeconds.coerceIn(1, 30)
                }
                delay(seconds * 1_000L)
            }
        }
        if (webcamVisible) restartWebcamPolling()
    }

    private suspend fun refreshAll(showSpinner: Boolean) {
        if (!_state.value.isReady) return
        refreshMutex.withLock {
            if (showSpinner) _state.update { it.copy(isRefreshing = true, errorMessage = null) }
            runCatching {
                coroutineScope {
                    val client = requireClient()
                    val version = async(Dispatchers.IO) { client.version() }
                    val user = async(Dispatchers.IO) { client.currentUser() }
                    val job = async(Dispatchers.IO) { client.jobState() }
                    val connection = async(Dispatchers.IO) { client.connectionState() }
                    val printer = async(Dispatchers.IO) {
                        runCatching { client.printerState() }.getOrElse {
                            OctoPrintPrinterState(text = connection.await().state)
                        }
                    }
                    val webcam = async(Dispatchers.IO) {
                        runCatching { client.webcamSettings() }.getOrDefault(_state.value.webcam)
                    }
                    RefreshSnapshot(
                        server = OctoPrintJson.parseServerInfo(version.await(), user.await()),
                        job = job.await(),
                        connection = connection.await(),
                        printer = printer.await(),
                        webcam = webcam.await(),
                    )
                }
            }.onSuccess { snapshot ->
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
                _state.update { it.copy(isRefreshing = false) }
                setError(error)
            }
        }
    }

    private fun restartWebcamPolling() {
        webcamJob?.cancel()
        if (!webcamVisible || !_state.value.isReady) return
        webcamJob = scope.launch {
            while (isActive && webcamVisible && _state.value.isReady) {
                val snapshotUrl = _state.value.config.snapshotUrlOverride.takeIf(String::isNotBlank)
                    ?: _state.value.webcam.snapshotUrl
                if (snapshotUrl.isNullOrBlank()) {
                    delay(3_000L)
                    continue
                }
                runCatching {
                    withContext(Dispatchers.IO) { requireClient().fetchWebcamSnapshot(snapshotUrl) }
                }.onSuccess { bytes ->
                    _state.update { it.copy(webcamFrame = bytes) }
                }
                delay(1_000L)
            }
        }
    }

    private fun operation(
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

    private fun setStatus(message: String) {
        _state.update { it.copy(statusMessage = message, errorMessage = null) }
    }

    private fun setError(error: Throwable) {
        val message = error.message ?: error::class.java.simpleName
        _state.update {
            it.copy(
                isRefreshing = false,
                isFileListRefreshing = false,
                isUploading = false,
                statusMessage = message,
                errorMessage = message,
            )
        }
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
        val leafName = value
            .substringAfterLast('/')
            .substringAfterLast('\\')
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
    )

    private companion object {
        const val ACTIVE_PRINT_POLL_SECONDS = 2
        const val AUTHORIZATION_TIMEOUT_MILLIS = 5L * 60L * 1_000L
        const val UPLOAD_SNAPSHOT_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
