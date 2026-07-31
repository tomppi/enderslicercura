package com.tomppi.enderslicer.octoprint

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class OctoPrintClient(
    baseUrl: String,
    private val apiKey: String? = null,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    val normalizedBaseUrl: String
    private val base: HttpUrl

    init {
        base = normalizeBaseUrl(baseUrl)
        normalizedBaseUrl = base.toString().removeSuffix("/")
    }

    fun version(): JSONObject = getJson(apiUrl("api", "version"), authenticated = false)

    fun currentUser(): JSONObject? {
        if (apiKey.isNullOrBlank()) return null
        return executeJson(
            Request.Builder()
                .url(apiUrl("api", "login"))
                .post(JSONObject().put("passive", true).toString().jsonBody())
                .authenticated()
                .build(),
        )
    }

    fun printerState(): OctoPrintPrinterState = OctoPrintJson.parsePrinter(
        getJson(apiUrl("api", "printer")),
    )

    fun jobState(): OctoPrintJobState = OctoPrintJson.parseJob(
        getJson(apiUrl("api", "job")),
    )

    fun connectionState(): OctoPrintConnectionState = OctoPrintJson.parseConnection(
        getJson(apiUrl("api", "connection")),
    )

    fun files(force: Boolean = false): Pair<List<OctoPrintFileEntry>, Long?> {
        val url = apiUrl("api", "files", "local").newBuilder()
            .addQueryParameter("recursive", "true")
            .addQueryParameter("force", force.toString())
            .build()
        return OctoPrintJson.parseFiles(getJson(url))
    }

    fun webcamSettings(): OctoPrintWebcamConfig = OctoPrintJson.parseWebcam(
        getJson(apiUrl("api", "settings")),
    )

    fun fetchWebcamSnapshot(url: String): ByteArray {
        val resolved = resolveServerUrl(url) ?: error("Invalid OctoPrint webcam snapshot URL")
        val request = Request.Builder().url(resolved).get().authenticated().build()
        return execute(request) { response ->
            val declared = response.body.contentLength()
            require(declared <= MAX_SNAPSHOT_BYTES || declared < 0L) { "Webcam snapshot is too large" }
            response.body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_SNAPSHOT_BYTES) { "Webcam snapshot is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }

    fun upload(
        file: File,
        remoteFileName: String,
        remoteDirectory: String = "",
        action: OctoPrintUploadAction,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit,
    ): JSONObject {
        require(file.isFile && file.length() > 0L) { "Generated G-code is unavailable" }
        require(remoteFileName.lowercase().endsWith(".gcode")) { "OctoPrint upload must end in .gcode" }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                remoteFileName,
                ProgressFileRequestBody(file, GCODE_MEDIA_TYPE, onProgress),
            )
            .apply {
                if (remoteDirectory.isNotBlank()) addFormDataPart("path", remoteDirectory.trim('/'))
                when (action) {
                    OctoPrintUploadAction.UPLOAD -> Unit
                    OctoPrintUploadAction.UPLOAD_AND_SELECT -> addFormDataPart("select", "true")
                    OctoPrintUploadAction.UPLOAD_AND_PRINT -> {
                        addFormDataPart("select", "true")
                        addFormDataPart("print", "true")
                    }
                }
            }
            .build()
        return executeJson(
            Request.Builder()
                .url(apiUrl("api", "files", "local"))
                .post(body)
                .authenticated()
                .build(),
        )
    }

    fun selectFile(path: String, print: Boolean) {
        postFileCommand(path, JSONObject().put("command", "select").put("print", print))
    }

    fun unselectFile(path: String) {
        postFileCommand(path, JSONObject().put("command", "unselect"))
    }

    fun deleteFile(path: String) {
        executeNoContent(
            Request.Builder()
                .url(fileUrl(path))
                .delete()
                .authenticated()
                .build(),
        )
    }

    fun createFolder(parentPath: String, folderName: String) {
        require(folderName.isNotBlank() && '/' !in folderName && '\\' !in folderName) { "Enter a valid folder name" }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("foldername", folderName.trim())
            .apply { if (parentPath.isNotBlank()) addFormDataPart("path", parentPath.trim('/')) }
            .build()
        executeJson(
            Request.Builder()
                .url(apiUrl("api", "files", "local"))
                .post(body)
                .authenticated()
                .build(),
        )
    }

    fun moveFile(path: String, destination: String) {
        postFileCommand(path, JSONObject().put("command", "move").put("destination", destination.trim('/')))
    }

    fun copyFile(path: String, destination: String) {
        postFileCommand(path, JSONObject().put("command", "copy").put("destination", destination.trim('/')))
    }

    fun jobCommand(command: String, action: String? = null) {
        require(command in setOf("start", "restart", "pause", "cancel")) { "Unsupported job command" }
        val json = JSONObject().put("command", command)
        action?.let { json.put("action", it) }
        postJson(apiUrl("api", "job"), json)
    }

    fun connect(
        port: String? = null,
        baudrate: Int? = null,
        printerProfile: String? = null,
        save: Boolean = false,
        autoConnect: Boolean = false,
    ) {
        val json = JSONObject().put("command", "connect")
        port?.takeIf(String::isNotBlank)?.let { json.put("port", it) }
        baudrate?.let { json.put("baudrate", it) }
        printerProfile?.takeIf(String::isNotBlank)?.let { json.put("printerProfile", it) }
        json.put("save", save)
        json.put("autoconnect", autoConnect)
        postJson(apiUrl("api", "connection"), json)
    }

    fun disconnect() = postJson(apiUrl("api", "connection"), JSONObject().put("command", "disconnect"))

    fun jog(x: Double? = null, y: Double? = null, z: Double? = null, speedMmPerMinute: Int? = null) {
        require(x != null || y != null || z != null) { "Select an axis to jog" }
        val json = JSONObject().put("command", "jog").put("absolute", false)
        x?.let { json.put("x", it) }
        y?.let { json.put("y", it) }
        z?.let { json.put("z", it) }
        speedMmPerMinute?.let { json.put("speed", it) }
        postJson(apiUrl("api", "printer", "printhead"), json)
    }

    fun home(axes: Set<String>) {
        require(axes.isNotEmpty() && axes.all { it in setOf("x", "y", "z") }) { "Invalid homing axes" }
        postJson(
            apiUrl("api", "printer", "printhead"),
            JSONObject().put("command", "home").put("axes", axes.toList()),
        )
    }

    fun setFeedRate(percent: Int) {
        require(percent in 50..200) { "Feed rate must be between 50% and 200%" }
        postJson(
            apiUrl("api", "printer", "printhead"),
            JSONObject().put("command", "feedrate").put("factor", percent),
        )
    }

    fun setFlowRate(percent: Int) {
        require(percent in 75..125) { "Flow rate must be between 75% and 125%" }
        postJson(
            apiUrl("api", "printer", "tool"),
            JSONObject().put("command", "flowrate").put("factor", percent),
        )
    }

    fun setToolTemperature(tool: String, targetCelsius: Int) {
        require(tool.matches(Regex("tool\\d+"))) { "Invalid OctoPrint tool" }
        require(targetCelsius in 0..500) { "Nozzle target must be between 0 and 500 °C" }
        postJson(
            apiUrl("api", "printer", "tool"),
            JSONObject().put("command", "target").put("targets", JSONObject().put(tool, targetCelsius)),
        )
    }

    fun setBedTemperature(targetCelsius: Int) {
        require(targetCelsius in 0..200) { "Bed target must be between 0 and 200 °C" }
        postJson(
            apiUrl("api", "printer", "bed"),
            JSONObject().put("command", "target").put("target", targetCelsius),
        )
    }

    fun extrude(amountMm: Double, speedMmPerMinute: Int? = null) {
        require(amountMm in -100.0..100.0 && amountMm != 0.0) { "Extrusion must be between -100 and 100 mm" }
        val json = JSONObject().put("command", "extrude").put("amount", amountMm)
        speedMmPerMinute?.let { json.put("speed", it) }
        postJson(apiUrl("api", "printer", "tool"), json)
    }

    fun sendGcode(command: String) {
        val clean = command.trim()
        require(clean.isNotBlank() && clean.length <= 256 && '\n' !in clean && '\r' !in clean) {
            "Enter one G-code command up to 256 characters"
        }
        postJson(apiUrl("api", "printer", "command"), JSONObject().put("command", clean))
    }

    fun probeApplicationKeys(): Boolean {
        val request = Request.Builder().url(pluginUrl("appkeys", "probe")).get().build()
        return executeAllowing(request, setOf(204, 404)) { it.code == 204 }
    }

    fun requestApplicationKey(username: String?): AppKeyAuthorization {
        val json = JSONObject().put("app", OCTOPRINT_APP_NAME)
        username?.trim()?.takeIf(String::isNotBlank)?.let { json.put("user", it) }
        val request = Request.Builder()
            .url(pluginUrl("appkeys", "request"))
            .post(json.toString().jsonBody())
            .build()
        return execute(request) { response ->
            require(response.code == 201) { errorMessage(response) }
            val body = response.body.string().takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
            val location = response.header("Location")
                ?: body.optString("location").takeIf(String::isNotBlank)
                ?: error("OctoPrint did not return an authorization polling URL")
            val authDialog = body.optString("auth_dialog").takeIf(String::isNotBlank)
                ?: error("OctoPrint did not return an authorization dialog URL")
            AppKeyAuthorization(
                pollingUrl = resolveServerUrl(location)?.toString() ?: error("Invalid authorization polling URL"),
                dialogUrl = resolveServerUrl(authDialog)?.toString() ?: error("Invalid authorization dialog URL"),
            )
        }
    }

    fun pollApplicationKey(pollingUrl: String): AppKeyPollResult {
        val url = pollingUrl.toHttpUrlOrNull() ?: error("Invalid authorization polling URL")
        val request = Request.Builder().url(url).get().build()
        return executeAllowing(request, setOf(200, 202, 404)) { response ->
            when (response.code) {
                200 -> {
                    val json = response.body.string().takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
                    AppKeyPollResult.Granted(
                        json.optString("api_key").takeIf(String::isNotBlank)
                            ?: error("OctoPrint authorized the app without returning an API key"),
                    )
                }
                202 -> AppKeyPollResult.Pending
                else -> AppKeyPollResult.Denied
            }
        }
    }

    fun resolveServerUrl(value: String): HttpUrl? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        trimmed.toHttpUrlOrNull()?.let { return it }
        if (trimmed.startsWith("//")) {
            return "${base.scheme}:$trimmed".toHttpUrlOrNull()
        }
        return base.resolve(trimmed)
    }

    private fun postFileCommand(path: String, json: JSONObject) {
        postJson(fileUrl(path), json)
    }

    private fun postJson(url: HttpUrl, json: JSONObject) {
        executeNoContent(
            Request.Builder()
                .url(url)
                .post(json.toString().jsonBody())
                .authenticated()
                .build(),
        )
    }

    private fun getJson(url: HttpUrl, authenticated: Boolean = true): JSONObject {
        val builder = Request.Builder().url(url).get()
        if (authenticated) builder.authenticated()
        return executeJson(builder.build())
    }

    private fun executeJson(request: Request): JSONObject = execute(request) { response ->
        val text = response.body.string()
        if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun executeNoContent(request: Request) {
        execute(request) { Unit }
    }

    private fun <T> execute(request: Request, block: (Response) -> T): T {
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw OctoPrintHttpException(response.code, errorMessage(response))
            return block(response)
        }
    }

    private fun <T> executeAllowing(request: Request, allowed: Set<Int>, block: (Response) -> T): T {
        httpClient.newCall(request).execute().use { response ->
            if (response.code !in allowed) throw OctoPrintHttpException(response.code, errorMessage(response))
            return block(response)
        }
    }

    private fun errorMessage(response: Response): String {
        val body = runCatching { response.peekBody(MAX_ERROR_BODY_BYTES).string() }.getOrDefault("").trim()
        val detail = runCatching {
            JSONObject(body).let { json ->
                json.optString("error").takeIf(String::isNotBlank)
                    ?: json.optString("message").takeIf(String::isNotBlank)
            }
        }.getOrNull() ?: body.take(300).takeIf(String::isNotBlank)
        return buildString {
            append("OctoPrint returned HTTP ${response.code}")
            detail?.let { append(": $it") }
        }
    }

    private fun apiUrl(vararg segments: String): HttpUrl = pathUrl(*segments)

    private fun pluginUrl(vararg segments: String): HttpUrl = pathUrl("plugin", *segments)

    private fun fileUrl(path: String): HttpUrl {
        val builder = base.newBuilder().addPathSegment("api").addPathSegment("files").addPathSegment("local")
        path.trim('/').split('/').filter(String::isNotBlank).forEach(builder::addPathSegment)
        return builder.build()
    }

    private fun pathUrl(vararg segments: String): HttpUrl {
        val builder = base.newBuilder()
        segments.filter(String::isNotBlank).forEach(builder::addPathSegment)
        return builder.build()
    }

    private fun Request.Builder.authenticated(): Request.Builder = apply {
        apiKey?.takeIf(String::isNotBlank)?.let { header("X-Api-Key", it) }
        header("User-Agent", "$OCTOPRINT_APP_NAME Android")
        header("Accept", "application/json")
    }

    data class AppKeyAuthorization(val pollingUrl: String, val dialogUrl: String)

    sealed interface AppKeyPollResult {
        data object Pending : AppKeyPollResult
        data object Denied : AppKeyPollResult
        data class Granted(val apiKey: String) : AppKeyPollResult
    }

    class OctoPrintHttpException(val statusCode: Int, message: String) : IOException(message)

    private class ProgressFileRequestBody(
        private val file: File,
        private val mediaType: MediaType,
        private val onProgress: (Long, Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType = mediaType
        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            var sent = 0L
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    sink.write(buffer, 0, count)
                    sent += count
                    onProgress(sent, total)
                }
            }
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val GCODE_MEDIA_TYPE = "text/x-gcode".toMediaType()
        private const val MAX_SNAPSHOT_BYTES = 10L * 1024L * 1024L
        private const val MAX_ERROR_BODY_BYTES = 32L * 1024L

        fun normalizeBaseUrl(value: String): HttpUrl {
            val trimmed = value.trim()
            require(trimmed.isNotBlank()) { "Enter the OctoPrint server address" }
            val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
            val parsed = withScheme.toHttpUrlOrNull() ?: error("Invalid OctoPrint server address")
            require(parsed.scheme == "http" || parsed.scheme == "https") { "OctoPrint URL must use HTTP or HTTPS" }
            require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "Do not put credentials in the OctoPrint URL" }
            val path = parsed.encodedPath
            val builder = parsed.newBuilder().query(null).fragment(null)
            if (!path.endsWith('/')) builder.addPathSegment("")
            return builder.build()
        }

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.MINUTES)
            .callTimeout(16, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()

        private fun String.jsonBody(): RequestBody = toRequestBody(JSON_MEDIA_TYPE)
    }
}
