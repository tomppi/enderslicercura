package com.tomppi.enderslicer.harness

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Client for the DeepSeek harness HTTP API.
 *
 * Every shape here was verified against a live harness rather than inferred,
 * because the conventions are not guessable:
 *
 *  - **Routes use slashes.** `/api/session/list`, not `/api/session.list`. The
 *    dot form 404s, and the envelope's `method` must match the endpoint string
 *    exactly - the gateway rejects any mismatch by name.
 *  - **The payload is double-wrapped:** `{ "args": { <field>: { ... } } }`.
 *    A bare object is refused with "must contain exactly one plain-object args
 *    field", and the field is named per endpoint (`_request` for session/list,
 *    `request` for create and prompt).
 *  - **Nothing is returned unless `result.ok`** - failures arrive as a
 *    structured error beside it, not as an HTTP status.
 *
 * Authentication is a three-step bootstrap that needs no user input:
 * `auth.json` (a public asset) names a `?token=` URL per origin; loading that
 * URL answers a 303 whose Set-Cookie is a 30-day session. The cookie outlives
 * harness restarts, so this runs once.
 */
class HarnessClient(private val baseUrl: String) {

    /** Session cookie from the token exchange; every later call carries it. */
    @Volatile
    var cookie: String? = null
        private set

    /**
     * Bootstraps authentication from the harness's own `auth.json`.
     *
     * @throws HarnessException when the asset is missing (the harness was
     * started without its launcher script) or names no URL for this origin.
     */
    fun authenticate() {
        val origin = origin()
        val document = JSONObject(get("$origin/auth.json"))
        val urls = document.optJSONObject("urls")
            ?: throw HarnessException("auth.json has no urls", "auth")
        // Exact origin first: the document lists an entry per authority, and a
        // hostname match would pick the wrong scheme or port.
        val tokenUrl = urls.optString(origin, "").takeIf(String::isNotEmpty)
            ?: urls.keys().asSequence()
                .firstOrNull { authorityOf(it) == authorityOf(origin) }
                ?.let { urls.optString(it) }
                ?.takeIf(String::isNotEmpty)
            ?: throw HarnessException("auth.json names no url for $origin", "auth")
        cookie = redeem(tokenUrl)
        if (cookie == null) throw HarnessException("token was not accepted", "auth")
    }

    /**
     * One Remote call.
     *
     * @param endpoint - slash form, e.g. `session/list`.
     * @param field - the args field this endpoint names, e.g. `_request`.
     * @param request - the request body for that field.
     * @throws HarnessException on a refused call or a transport failure.
     */
    fun call(endpoint: String, field: String, request: JSONObject): JSONObject {
        val rpcId = UUID.randomUUID().toString()
        val envelope = JSONObject()
            .put("type", "client-request")
            .put("rpcId", rpcId)
            .put("method", endpoint)
            .put(
                "payload",
                JSONObject().put("args", JSONObject().put(field, request)),
            )
        val body = post(
            path = "/api/$endpoint",
            contentType = "application/json; charset=utf-8",
            bytes = envelope.toString().toByteArray(Charsets.UTF_8),
        )
        val parsed = JSONObject(body)
        val echoed = parsed.optString("rpcId")
        check(echoed == rpcId) { "harness rpcId mismatch for $endpoint: sent $rpcId, got $echoed" }
        val result = parsed.optJSONObject("result") ?: JSONObject()
        if (!result.optBoolean("ok")) {
            val error = result.optJSONObject("error")
            throw HarnessException(
                error?.optString("message")?.takeIf(String::isNotEmpty)
                    ?: "Harness refused $endpoint",
                error?.optString("code").orEmpty(),
            )
        }
        return result.optJSONObject("value") ?: JSONObject()
    }

    /** Sessions known to the harness. */
    fun listSessions(): JSONObject = call("session/list", "_request", JSONObject())

    /**
     * Creates a session, optionally rooted at [cwd].
     *
     * @returns the new `sessionId`.
     */
    fun createSession(cwd: String? = null): String {
        val request = JSONObject()
        cwd?.let { request.put("cwd", it) }
        return call("session/create", "request", request).optString("sessionId")
            .takeIf(String::isNotEmpty)
            ?: throw HarnessException("session/create returned no sessionId", "shape")
    }

    /**
     * One backwards page of a session's log, with each message verbatim.
     *
     * `session/list` carries only the `turnOutline` projection, which clips
     * every turn to roughly a hundred characters - enough to drive a chat, not
     * enough to read an answer that runs to several thousand. This reads the
     * same log through `session/page`.
     *
     * @param throughSeq inclusive log cut. `session/list` reports it as
     *   `projections.asOfSeq`. It is required: passing `-1` collapses the
     *   range to nothing rather than meaning "latest".
     */
    fun pageMessages(sessionId: String, throughSeq: Long, maxMessages: Int): JSONObject {
        val request = JSONObject()
            .put("address", JSONObject().put("kind", "session").put("sessionId", sessionId))
            .put("throughSeq", throughSeq)
            .put("maxMessages", maxMessages)
        return call("session/page", "request", request)
    }

    /**
     * Stops a session's running turn.
     *
     * Accepting the call is not the same as stopping: the harness acknowledges
     * before the turn unwinds, and this only ever stops the *agent* - never the
     * processes it launched. A background job that finishes afterwards delivers
     * a completion notice, and a notice into a session with no live turn starts
     * a new one.
     */
    fun cancelSession(sessionId: String): JSONObject =
        call("session/cancel", "request", JSONObject().put("sessionId", sessionId))

    /**
     * Sends a message into a session.
     *
     * `requestId` is client-minted and required - omitting it fails boundary
     * validation with no hint as to which field is missing.
     */
    fun prompt(sessionId: String, text: String, receiptId: String? = null): JSONObject {
        val request = JSONObject()
            .put("requestId", UUID.randomUUID().toString())
            .put("sessionId", sessionId)
            .put("mode", "queue")
            .put("content", promptContent(text, receiptId))
        return call("session/prompt", "request", request)
    }

    /**
     * Stages raw bytes against a session, which is how an image reaches the
     * harness. A plain HTTP route, not a Remote call: it refuses anything that
     * is not `application/octet-stream`.
     */
    fun uploadFile(sessionId: String, name: String, bytes: ByteArray): JSONObject {
        val query = "?sessionId=" + encode(sessionId) + "&name=" + encode(name)
        val response = post(
            path = "/api/session/uploadFileBinary$query",
            contentType = "application/octet-stream",
            bytes = bytes,
        )
        val parsed = JSONObject(response)
        if (!parsed.optBoolean("ok")) {
            val error = parsed.optJSONObject("error")
            throw HarnessException(
                error?.optString("message")?.takeIf(String::isNotEmpty) ?: "Upload rejected",
                error?.optString("code").orEmpty(),
            )
        }
        return parsed.optJSONObject("value") ?: JSONObject()
    }

    /**
     * Loads the token URL and keeps the cookie its redirect sets.
     *
     * Redirects are not followed: the cookie arrives on the 303 itself, and the
     * destination page is of no interest to an API client.
     */
    private fun redeem(tokenUrl: String): String? {
        val connection = open(URL(tokenUrl).let { it.path + (it.query?.let { q -> "?$q" } ?: "") }, "GET")
        return try {
            cookieOf(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun get(path: String): String {
        val connection = open(path, "GET")
        return try {
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.use { readAll(it) }.orEmpty()
            if (code !in 200..299) throw HarnessException("Harness HTTP $code for $path", "http-$code")
            text
        } finally {
            connection.disconnect()
        }
    }

    private fun post(path: String, contentType: String, bytes: ByteArray): String {
        val connection = open(path, "POST")
        return try {
            connection.setRequestProperty("Content-Type", contentType)
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.use { readAll(it) }.orEmpty()
            if (code !in 200..299) {
                throw HarnessException("Harness HTTP $code for $path: " + text.take(200), "http-$code")
            }
            text
        } finally {
            connection.disconnect()
        }
    }

    private fun open(path: String, method: String): HttpURLConnection {
        val url = if (path.startsWith("http")) URL(path) else URL(baseUrl.trimEnd('/') + path)
        require(url.protocol == "http" || url.protocol == "https") {
            "Harness URL must be http or https"
        }
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            // Read the 303 rather than chasing it; the cookie is on the response.
            instanceFollowRedirects = false
            useCaches = false
            doInput = true
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Connection", "close")
            cookie?.let { setRequestProperty("Cookie", it) }
        }
    }

    private fun cookieOf(connection: HttpURLConnection): String? =
        connection.headerFields
            ?.entries
            ?.firstOrNull { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
            ?.value
            ?.firstOrNull()
            ?.substringBefore(';')

    private fun readAll(stream: java.io.InputStream): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val read = stream.read(chunk)
            if (read <= 0) break
            buffer.write(chunk, 0, read)
        }
        return buffer.toString("UTF-8")
    }

    private fun origin(): String {
        val url = URL(baseUrl.trimEnd('/'))
        val port = if (url.port == -1) "" else ":" + url.port
        return url.protocol + "://" + url.host + port
    }

    private fun authorityOf(value: String): String? =
        runCatching { URL(value).host }.getOrNull()

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 60_000

        /**
         * The content blocks for one prompt: the attachment first, then the text.
         *
         * A staged upload reaches the agent only through a `file` part naming
         * the receipt that `uploadFileBinary` returned. The receipt is
         * single-use - the harness binds it to the prompt that names it - and it
         * is scoped to the session it was uploaded against.
         */
        fun promptContent(text: String, receiptId: String?): JSONArray {
            val content = JSONArray()
            receiptId?.takeIf(String::isNotBlank)?.let {
                content.put(JSONObject().put("type", "file").put("receiptId", it))
            }
            content.put(JSONObject().put("type", "text").put("text", text))
            return content
        }
    }
}

/** A harness call that failed, with the harness's own error code when it gave one. */
class HarnessException(message: String, val code: String = "") : Exception(message)
