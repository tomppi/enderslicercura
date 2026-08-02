package com.tomppi.enderslicer.smartinfill

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import com.tomppi.enderslicer.BuildConfig
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/** Hosts the pinned filaSim web app entirely inside the APK. */
class SmartInfillActivity : ComponentActivity() {
    private lateinit var sourceFile: File
    private lateinit var sourceFingerprint: String
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sourcePath = intent.getStringExtra(EXTRA_MODEL_PATH)
        sourceFile = sourcePath?.let(::File) ?: run {
            finishWithError("No STL was supplied to Smart Infill")
            return
        }
        if (!sourceFile.isFile || sourceFile.length() < STL_HEADER_BYTES) {
            finishWithError("The STL supplied to Smart Infill is missing or empty")
            return
        }
        sourceFingerprint = runCatching { sha256(sourceFile) }.getOrElse {
            finishWithError("Unable to fingerprint the STL supplied to Smart Infill")
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(31, 31, 29))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(8), dp(4))
            setBackgroundColor(Color.rgb(31, 31, 29))
        }
        toolbar.addView(
            TextView(this).apply {
                text = "Smart Infill · filaSim"
                textSize = 17f
                setTextColor(Color.WHITE)
            },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { gravity = Gravity.CENTER_VERTICAL },
        )
        toolbar.addView(
            Button(this).apply {
                text = "Cancel"
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)),
        )
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/model/") { path ->
                if (path == "current.stl") {
                    WebResourceResponse("model/stl", null, sourceFile.inputStream().buffered())
                } else {
                    null
                }
            }
            .build()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.databaseEnabled = false
            settings.setSupportMultipleWindows(false)
            settings.mediaPlaybackRequiresUserGesture = true
            addJavascriptInterface(
                ExportBridge(
                    activity = this@SmartInfillActivity,
                    sourceName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: sourceFile.name,
                    sourceSha256 = sourceFingerprint,
                ),
                JS_BRIDGE_NAME,
            )
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? =
                    assetLoader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                    val target = request.url
                    if (target.host == WebViewAssetLoader.DEFAULT_DOMAIN) return false
                    if (target.scheme == "about" || target.scheme == "blob") return false
                    openExternal(target)
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript("window.EnderSlicerBridge?.loadModelFromAndroid?.()", null)
                }
            }
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        webView.loadUrl(FILASIM_URL)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface(JS_BRIDGE_NAME)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun openExternal(uri: Uri) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show() }
    }

    private fun finishWithError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class ExportBridge(
        private val activity: SmartInfillActivity,
        private val sourceName: String,
        private val sourceSha256: String,
    ) {
        private var output: File? = null
        private var stream: OutputStream? = null
        private var expectedBytes: Long = 0L
        private var writtenBytes: Long = 0L
        private var metadataJson: String? = null

        @JavascriptInterface
        fun sourceFileName(): String = sourceName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .let { name -> if (name.lowercase().endsWith(".stl")) name else "$name.stl" }

        @JavascriptInterface
        fun sourceSha256(): String = sourceSha256

        @JavascriptInterface
        fun upstreamCommit(): String = FILASIM_COMMIT

        @JavascriptInterface
        @Synchronized
        fun beginModifierExport(filename: String, sizeBytes: Double, metadata: String): Boolean {
            cancelLocked()
            if (!sizeBytes.isFinite()) return false
            val size = sizeBytes.toLong()
            if (size !in MINIMUM_ZIP_BYTES..MAX_EXPORT_BYTES) return false
            if (metadata.length !in 2..MAX_METADATA_CHARS) return false

            val directory = File(activity.cacheDir, "smart-infill-exports").apply { mkdirs() }
            val safeBase = filename
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifBlank { "smart-infill-modifiers.zip" }
                .let { if (it.lowercase().endsWith(".zip")) it else "$it.zip" }
            val target = File(directory, "${System.currentTimeMillis()}-$safeBase")

            return runCatching {
                expectedBytes = size
                writtenBytes = 0L
                metadataJson = metadata
                output = target
                stream = BufferedOutputStream(target.outputStream(), CHUNK_BUFFER_BYTES)
                true
            }.getOrElse {
                cancelLocked()
                false
            }
        }

        @JavascriptInterface
        @Synchronized
        fun appendModifierChunk(encoded: String): Boolean {
            val active = stream ?: return false
            return runCatching {
                val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                check(writtenBytes + bytes.size <= expectedBytes)
                check(writtenBytes + bytes.size <= MAX_EXPORT_BYTES)
                active.write(bytes)
                writtenBytes += bytes.size
                true
            }.getOrElse {
                cancelLocked()
                false
            }
        }

        @JavascriptInterface
        fun finishModifierExport(): Boolean {
            val completed = synchronized(this) {
                val file = output ?: return false
                val active = stream ?: return false
                val metadata = metadataJson ?: return false
                runCatching {
                    active.flush()
                    active.close()
                }.onFailure {
                    cancelLocked()
                    return false
                }
                stream = null
                output = null
                metadataJson = null
                if (writtenBytes != expectedBytes || !isValidModifierZip(file)) {
                    file.delete()
                    expectedBytes = 0L
                    writtenBytes = 0L
                    return false
                }
                expectedBytes = 0L
                writtenBytes = 0L
                file to metadata
            }

            activity.runOnUiThread {
                val uri = FileProvider.getUriForFile(
                    activity,
                    "${BuildConfig.APPLICATION_ID}.files",
                    completed.first,
                )
                val result = Intent()
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(EXTRA_METADATA_JSON, completed.second)
                    .putExtra(EXTRA_SOURCE_SHA256, sourceSha256)
                activity.setResult(Activity.RESULT_OK, result)
                activity.finish()
            }
            return true
        }

        @JavascriptInterface
        @Synchronized
        fun cancelModifierExport() {
            cancelLocked()
        }

        private fun cancelLocked() {
            runCatching { stream?.close() }
            stream = null
            output?.delete()
            output = null
            metadataJson = null
            expectedBytes = 0L
            writtenBytes = 0L
        }

        private fun isValidModifierZip(file: File): Boolean {
            if (!file.isFile || file.length() !in MINIMUM_ZIP_BYTES..MAX_EXPORT_BYTES) return false
            return runCatching {
                RandomAccessFile(file, "r").use { input ->
                    val signature = ByteArray(4)
                    input.readFully(signature)
                    check(
                        signature[0] == 'P'.code.toByte() && signature[1] == 'K'.code.toByte() &&
                            signature[2] == 3.toByte() && signature[3] == 4.toByte(),
                    )
                }
                ZipFile(file).use { zip ->
                    val entries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
                    entries.isNotEmpty() && entries.size <= MAX_MODIFIERS &&
                        entries.all { MODIFIER_ENTRY.matches(it.name) }
                }
            }.getOrDefault(false)
        }
    }

    companion object {
        const val EXTRA_MODEL_PATH = "com.tomppi.enderslicercura.extra.SMART_INFILL_MODEL_PATH"
        const val EXTRA_MODEL_NAME = "com.tomppi.enderslicercura.extra.SMART_INFILL_MODEL_NAME"
        const val EXTRA_METADATA_JSON = "com.tomppi.enderslicercura.extra.SMART_INFILL_METADATA"
        const val EXTRA_SOURCE_SHA256 = "com.tomppi.enderslicercura.extra.SMART_INFILL_SOURCE_SHA256"

        const val FILASIM_COMMIT = "e7485ec22d4ebe8baca04190404fbb877c90e031"

        private const val JS_BRIDGE_NAME = "EnderSlicerAndroid"
        private const val FILASIM_URL =
            "https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/filasim/index.html?android=1"
        private const val STL_HEADER_BYTES = 84L
        private const val MINIMUM_ZIP_BYTES = 22L
        private const val MAX_EXPORT_BYTES = 512L * 1024L * 1024L
        private const val MAX_METADATA_CHARS = 64 * 1024
        private const val MAX_MODIFIERS = 16
        private const val CHUNK_BUFFER_BYTES = 128 * 1024
        private val MODIFIER_ENTRY = Regex("modifier_\\d{1,3}pct\\.stl", RegexOption.IGNORE_CASE)
    }
}
