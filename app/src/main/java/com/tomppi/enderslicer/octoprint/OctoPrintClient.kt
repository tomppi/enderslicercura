package com.tomppi.enderslicer.octoprint

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID
import java.util.zip.GZIPInputStream
import org.json.JSONObject

class OctoPrintClient(
    baseUrl: String,
    private val apiKey: String? = null,
) {
    val normalizedBaseUrl: String
    private val base: URI
    private val activeConnections = Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())

    init {
        base = normalizeBaseUrl(baseUrl)
        normalizedBaseUrl = base.toString().removeSuffix("/")
    }

    fun version(): JSONObject {
        val authenticated = !apiKey.isNullOrBlank()
        return try {
            getJson(apiUrl("api", "version"), authenticated = authenticated)
        } catch (error: OctoPrintHttpException) {
            if (!authenticated && error.statusCode in setOf(401, 403) && probeApplicationKeys()) {
                JSONObject().put("text", "OctoPrint (authorization required)")
            } else {
                throw error
            }
        }
    }

    fun currentUser(): JSONObject? {
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
        val root = apiUrl("api", "files", "local")
        val url = URI(
            root.scheme,
            root.userInfo,
            root.host,
            root.port,
            root.path,
            "recursive=true&force=$force",
            null,
        )
        return OctoPrintJson.parseFiles(getJson(url))
    }

    fun webcamSettings(): OctoPrintWebcamConfig = OctoPrintJson.parseWebcam(
        getJson(apiUrl("api", "settings")),
    )

    fun fetchWebcamSnapshot(url: String): ByteArray {
        val resolved = resolveWebcamSnapshotUrl(url) ?: error("Invalid OctoPrint webcam snapshot URL")
        return fetchWebcamSnapshot(resolved, redirectCount = 0)
    }

    internal fun resolveWebcamSnapshotUrl(value: String): URI? {
        val resolved = resolveServerUrl(value) ?: return null
        return rewriteLoopbackHost(resolved)
    }

    private fun rewriteLoopbackHost(uri: URI): URI {
        val host = uri.host ?: return uri
        if (!isLoopbackHost(host)) return uri
        return runCatching {
            URI(
                uri.scheme,
                uri.userInfo,
                base.host,
                uri.port,
                uri.path,
                uri.query,
                uri.fragment,
            )
        }.getOrNull() ?: uri
    }

    private fun isLoopbackHost(host: String): Boolean =
        host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "::1"

    private fun fetchWebcamSnapshot(url: URI, redirectCount: Int): ByteArray {
        require(redirectCount <= MAX_SNAPSHOT_REDIRECTS) { "Too many webcam redirects" }
        val connection = openConnection(url, "GET", authenticated = true, accept = SNAPSHOT_ACCEPT)
        return try {
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: throw OctoPrintHttpException(code, "Webcam redirect did not include a Location header")
                val next = runCatching { url.resolve(URI(location)) }.getOrNull()
                    ?: error("Invalid webcam redirect URL")
                require(next.scheme.equals("http", true) || next.scheme.equals("https", true)) {
                    "Webcam redirect must use HTTP or HTTPS"
                }
                return fetchWebcamSnapshot(rewriteLoopbackHost(next), redirectCount + 1)
            }
            if (code !in 200..299) throw httpError(connection, code)
            val declared = connection.contentLengthLong
            require(declared < 0L || declared <= MAX_SNAPSHOT_BYTES) { "Webcam snapshot is too large" }
            val input = if (isGzipEncoded(connection)) {
                GZIPInputStream(connection.inputStream)
            } else {
                connection.inputStream
            }
            input.buffered().use {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_SNAPSHOT_BYTES) { "Webcam snapshot is too large" }
                    output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                if (!looksLikeImage(bytes)) {
                    throw IOException("Webcam snapshot did not return an image")
                }
                bytes
            }
        } finally {
            closeConnection(connection)
        }
    }

    private fun isGzipEncoded(connection: HttpURLConnection): Boolean =
        connection.getContentEncoding()?.split(',')?.any {
            it.trim().equals("gzip", ignoreCase = true)
        } == true

    internal fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val jpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        val png = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        val gif = bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte()
        val bmp = bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte()
        val webp = bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
        return jpeg || png || gif || bmp || webp
    }

    fun upload(
        file: File,
        remoteFileName: String,
        remoteDirectory: String = "",
        action: OctoPrintUploadAction,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit,
    ): JSONObject {
        require(file.isFile && file.length() > 0L) { "Generated G-code is unavailable" }
        val cleanFileName = validateRemoteSegment(remoteFileName.trim())
        require(cleanFileName.lowercase().endsWith(".gcode")) { "OctoPrint upload must end in .gcode" }
        val fields = linkedMapOf<String, String>()
        val cleanDirectory = normalizeRemotePath(remoteDirectory, allowBlank = true)
        if (cleanDirectory.isNotBlank()) fields["path"] = cleanDirectory
        when (action) {
            OctoPrintUploadAction.UPLOAD -> Unit
            OctoPrintUploadAction.UPLOAD_AND_SELECT -> fields["select"] = "true"
            OctoPrintUploadAction.UPLOAD_AND_PRINT -> {
                fields["select"] = "true"
                fields["print"] = "true"
            }
        }
        return multipart(
            url = apiUrl("api", "files", "local"),
            fields = fields,
            file = file,
            fileName = cleanFileName,
            onProgress = onProgress,
        )
    }

    fun selectFile(path: String, print: Boolean) {
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
        port?.trim()?.takeIf(String::isNotBlank)?.let { json.put("port", it) }
        baudrate?.let {
            require(it > 0) { "Baud rate must be positive" }
            json.put("baudrate", it)
        }
        printerProfile?.trim()?.takeIf(String::isNotBlank)?.let { json.put("printerProfile", it) }
        json.put("save", save)
        json.put("autoconnect", autoConnect)
        postJson(apiUrl("api", "connection"), json)
    }

    fun disconnect() = postJson(apiUrl("api", "connection"), JSONObject().put("command", "disconnect"))

    fun jog(x: Double? = null, y: Double? = null, z: Double? = null, speedMmPerMinute: Int? = null) {
        require(x != null || y != null || z != null) { "Select an axis to jog" }
        require(listOfNotNull(x, y, z).all(Double::isFinite)) { "Jog distance must be finite" }
        val json = JSONObject().put("command", "jog").put("absolute", false)
        x?.let { json.put("x", it) }
        y?.let { json.put("y", it) }
        z?.let { json.put("z", it) }
        speedMmPerMinute?.let {
            require(it > 0) { "Jog speed must be positive" }
            json.put("speed", it)
        }
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
        require(amountMm.isFinite() && amountMm in -100.0..100.0 && amountMm != 0.0) {
            "Extrusion must be between -100 and 100 mm"
        }
        val json = JSONObject().put("command", "extrude").put("amount", amountMm)
        speedMmPerMinute?.let {
            require(it > 0) { "Extrusion speed must be positive" }
            json.put("speed", it)
        }
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
        val response = execute(
            url = pluginUrl("appkeys", "probe"),
            method = "GET",
            authenticated = false,
            allowedCodes = setOf(204, 404),
        )
        return response.code == 204
    }

    fun requestApplicationKey(username: String?): AppKeyAuthorization {
        val json = JSONObject().put("app", OCTOPRINT_APP_NAME)
        username?.trim()?.takeIf(String::isNotBlank)?.let { json.put("user", it) }
        val response = execute(
            url = pluginUrl("appkeys", "request"),
            method = "POST",
            body = json.toString().toByteArray(Charsets.UTF_8),
            contentType = JSON_CONTENT_TYPE,
            authenticated = false,
            allowedCodes = setOf(201),
        )
        val body = response.body.toString(Charsets.UTF_8)
            .takeIf(String::isNotBlank)
            ?.let(::JSONObject)
            ?: JSONObject()
        val location = response.location
            ?: body.optString("location").takeIf(String::isNotBlank)
            ?: error("OctoPrint did not return an authorization polling URL")
        val authDialog = body.optString("auth_dialog").takeIf(String::isNotBlank)
            ?: error("OctoPrint did not return an authorization dialog URL")
        val polling = resolveServerUrl(location) ?: error("Invalid authorization polling URL")
        val dialog = resolveServerUrl(authDialog) ?: error("Invalid authorization dialog URL")
        require(isSameOrigin(polling)) { "OctoPrint authorization polling must stay on the configured server" }
        require(isSameOrigin(dialog)) { "OctoPrint authorization dialog must stay on the configured server" }
        return AppKeyAuthorization(pollingUrl = polling.toString(), dialogUrl = dialog.toString())
    }

    fun pollApplicationKey(pollingUrl: String): AppKeyPollResult {
        val url = runCatching { URI(pollingUrl) }.getOrNull()?.takeIf(URI::isAbsolute)
            ?: error("Invalid authorization polling URL")
        require(isSameOrigin(url)) { "OctoPrint authorization polling must stay on the configured server" }
        val response = execute(
            url = url,
            method = "GET",
            authenticated = false,
            allowedCodes = setOf(200, 202, 404),
        )
        return when (response.code) {
            200 -> {
                val json = response.body.toString(Charsets.UTF_8)
                    .takeIf(String::isNotBlank)
                    ?.let(::JSONObject)
                    ?: JSONObject()
                AppKeyPollResult.Granted(
                    json.optString("api_key").takeIf(String::isNotBlank)
                        ?: error("OctoPrint authorized the app without returning an API key"),
                )
            }
            202 -> AppKeyPollResult.Pending
            else -> AppKeyPollResult.Denied
        }
    }

    fun resolveServerUrl(value: String): URI? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        return runCatching {
            val parsed = URI(trimmed)
            when {
                parsed.isAbsolute -> parsed
                trimmed.startsWith("//") -> URI("${base.scheme}:$trimmed")
                else -> base.resolve(parsed)
            }
        }.getOrNull()?.takeIf {
            it.scheme.equals("http", true) || it.scheme.equals("https", true)
        }
    }

    private fun postFileCommand(path: String, json: JSONObject) {
        postJson(fileUrl(path), json)
    }

    private fun postJson(url: URI, json: JSONObject) {
        requestJson(
            url = url,
            method = "POST",
            body = json.toString().toByteArray(Charsets.UTF_8),
        )
    }

    private fun getJson(url: URI, authenticated: Boolean = true): JSONObject = requestJson(
        url = url,
        method = "GET",
        authenticated = authenticated,
    )

    private fun requestJson(
        url: URI,
        method: String,
        body: ByteArray? = null,
        authenticated: Boolean = true,
    ): JSONObject {
        val response = execute(
            url = url,
            method = method,
            body = body,
            contentType = if (body == null) null else JSON_CONTENT_TYPE,
            authenticated = authenticated,
        )
        val text = response.body.toString(Charsets.UTF_8)
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun multipart(
        url: URI,
        fields: LinkedHashMap<String, String>,
        file: File? = null,
        fileName: String? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): JSONObject {
        val boundary = "----EnderSlicerCura${UUID.randomUUID().toString().replace("-", "")}" 
        val prefix = ByteArrayOutputStream().apply {
            fields.forEach { (name, value) ->
                write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                write(
                    "Content-Disposition: form-data; name=\"${escapeQuoted(name)}\"\r\n\r\n"
                        .toByteArray(Charsets.UTF_8),
                )
                write(value.toByteArray(Charsets.UTF_8))
                write("\r\n".toByteArray(Charsets.UTF_8))
            }
            if (file != null) {
                write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                write(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"${escapeQuoted(fileName ?: file.name)}\"\r\n"
                        .toByteArray(Charsets.UTF_8),
                )
                write("Content-Type: text/x-gcode\r\n\r\n".toByteArray(Charsets.UTF_8))
            }
        }.toByteArray()
        val suffix = buildString {
            if (file != null) append("\r\n")
            append("--$boundary--\r\n")
        }.toByteArray(Charsets.UTF_8)
        val fileLength = file?.length() ?: 0L
        val totalLength = prefix.size.toLong() + fileLength + suffix.size.toLong()
        val connection = openConnection(
            url = url,
            method = "POST",
            authenticated = true,
            contentType = "multipart/form-data; boundary=$boundary",
            contentLength = totalLength,
            readTimeoutMillis = UPLOAD_TIMEOUT_MILLIS,
        )
        return try {
            connection.outputStream.buffered().use { output ->
                output.write(prefix)
                if (file != null) {
                    var sent = 0L
                    file.inputStream().buffered().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            sent += count
                            onProgress(sent, fileLength)
                        }
                    }
                }
                output.write(suffix)
            }
            val code = connection.responseCode
            if (code !in 200..299) throw httpError(connection, code)
            val bytes = readBody(connection, success = true, MAX_JSON_BODY_BYTES)
            val text = bytes.toString(Charsets.UTF_8)
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            closeConnection(connection)
        }
    }

    private fun execute(
        url: URI,
        method: String,
        body: ByteArray? = null,
        contentType: String? = null,
        authenticated: Boolean = true,
        allowedCodes: Set<Int>? = null,
    ): HttpResponse {
        val connection = openConnection(
            url = url,
            method = method,
            authenticated = authenticated,
            contentType = contentType,
            contentLength = body?.size?.toLong(),
        )
        return try {
            if (body != null) connection.outputStream.buffered().use { it.write(body) }
            val code = connection.responseCode
            val allowed = allowedCodes?.let { code in it } ?: (code in 200..299)
            if (!allowed) throw httpError(connection, code)
            HttpResponse(
                code = code,
                body = readBody(connection, success = true, MAX_JSON_BODY_BYTES),
                location = connection.getHeaderField("Location"),
            )
        } finally {
            closeConnection(connection)
        }
    }

    private fun openConnection(
        url: URI,
        method: String,
        authenticated: Boolean,
        contentType: String? = null,
        contentLength: Long? = null,
        readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
        accept: String = JSON_ACCEPT,
    ): HttpURLConnection {
        require(url.scheme.equals("http", true) || url.scheme.equals("https", true)) {
            "OctoPrint URL must use HTTP or HTTPS"
        }
        val connection = (url.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = readTimeoutMillis
            instanceFollowRedirects = false
            useCaches = false
            doInput = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "$OCTOPRINT_APP_NAME Android")
            if (authenticated && isSameOrigin(url)) {
                apiKey?.takeIf(String::isNotBlank)?.let { setRequestProperty("X-Api-Key", it) }
            }
            contentType?.let { setRequestProperty("Content-Type", it) }
            if (contentLength != null) {
                doOutput = true
                setFixedLengthStreamingMode(contentLength)
            }
        }
        activeConnections += connection
        return connection
    }

    private fun closeConnection(connection: HttpURLConnection) {
        activeConnections -= connection
        connection.disconnect()
    }

    private fun isSameOrigin(url: URI): Boolean =
        base.scheme.equals(url.scheme, ignoreCase = true) &&
            base.host.equals(url.host, ignoreCase = true) &&
            effectivePort(base) == effectivePort(url)

    private fun httpError(connection: HttpURLConnection, code: Int): OctoPrintHttpException {
        val bytes = readBody(connection, success = false, MAX_ERROR_BODY_BYTES)
        val raw = bytes.toString(Charsets.UTF_8).trim()
        val detail = runCatching {
            JSONObject(raw).let { json ->
                json.optString("error").takeIf(String::isNotBlank)
                    ?: json.optString("message").takeIf(String::isNotBlank)
            }
        }.getOrNull() ?: raw.take(300).takeIf(String::isNotBlank)
        val message = buildString {
            append("OctoPrint returned HTTP $code")
            detail?.let { append(": $it") }
        }
        return OctoPrintHttpException(code, message)
    }

    private fun readBody(connection: HttpURLConnection, success: Boolean, limit: Long): ByteArray {
        val stream = if (success) {
            runCatching { connection.inputStream }.getOrNull()
        } else {
            connection.errorStream ?: runCatching { connection.inputStream }.getOrNull()
        } ?: return ByteArray(0)
        return stream.buffered().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "OctoPrint response exceeded the allowed size" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun apiUrl(vararg segments: String): URI = pathUrl(*segments)

    private fun pluginUrl(vararg segments: String): URI = pathUrl("plugin", *segments)

    private fun fileUrl(path: String): URI = pathUrl(
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

    data class AppKeyAuthorization(val pollingUrl: String, val dialogUrl: String)

    sealed interface AppKeyPollResult {
        data object Pending : AppKeyPollResult
        data object Denied : AppKeyPollResult
        data class Granted(val apiKey: String) : AppKeyPollResult
    }

    class OctoPrintHttpException(val statusCode: Int, message: String) : IOException(message)

    private data class HttpResponse(
        val code: Int,
        val body: ByteArray,
        val location: String?,
    )

    companion object {
        private const val JSON_ACCEPT = "application/json"
        private const val SNAPSHOT_ACCEPT = "image/jpeg,image/png,image/gif,image/webp,image/bmp,image/*,*/*;q=0.8"
        private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        private const val CONNECT_TIMEOUT_MILLIS = 12_000
        private const val READ_TIMEOUT_MILLIS = 30_000
        private const val UPLOAD_TIMEOUT_MILLIS = 15 * 60 * 1_000
        private const val MAX_SNAPSHOT_REDIRECTS = 3
        private const val MAX_SNAPSHOT_BYTES = 10L * 1024L * 1024L
        private const val MAX_JSON_BODY_BYTES = 8L * 1024L * 1024L
        private const val MAX_ERROR_BODY_BYTES = 32L * 1024L

        fun normalizeBaseUrl(value: String): URI {
            val trimmed = value.trim()
            require(trimmed.isNotBlank()) { "Enter the OctoPrint server address" }
            val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
            val parsed = runCatching { URI(withScheme) }.getOrNull()
                ?: error("Invalid OctoPrint server address")
            require(parsed.scheme.equals("http", true) || parsed.scheme.equals("https", true)) {
                "OctoPrint URL must use HTTP or HTTPS"
            }
            require(parsed.userInfo.isNullOrBlank()) { "Do not put credentials in the OctoPrint URL" }
            require(parsed.rawQuery.isNullOrBlank() && parsed.rawFragment.isNullOrBlank()) {
                "OctoPrint server URL must not contain a query or fragment"
            }
            require(!parsed.host.isNullOrBlank()) { "Invalid OctoPrint server host" }
            val normalizedPath = when {
                parsed.path.isNullOrBlank() -> "/"
                parsed.path.endsWith('/') -> parsed.path
                else -> "${parsed.path}/"
            }
            return URI(parsed.scheme.lowercase(), null, parsed.host, parsed.port, normalizedPath, null, null)
        }

        internal fun normalizeRemotePath(value: String, allowBlank: Boolean): String {
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
            require(value.isNotBlank() && value != "." && value != "..") {
                "Invalid OctoPrint path segment"
            }
            require('/' !in value && '\\' !in value) { "Invalid OctoPrint path segment" }
            require(value.none(Char::isISOControl)) {
                "OctoPrint paths cannot contain control characters"
            }
            return value
        }

        private fun effectivePort(uri: URI): Int = when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }

        private fun encodePathSegment(value: String): String = URLEncoder
            .encode(value, StandardCharsets.UTF_8.name())
            .replace("+", "%20")

        private fun escapeQuoted(value: String): String = value
            .replace("\\", "_")
            .replace("\"", "_")
            .replace("\r", "_")
            .replace("\n", "_")
    }
}
