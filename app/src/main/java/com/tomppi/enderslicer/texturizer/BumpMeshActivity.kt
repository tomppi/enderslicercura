package com.tomppi.enderslicer.texturizer

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
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BumpMeshActivity : ComponentActivity() {
    private lateinit var sourceFile: File
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sourcePath = intent.getStringExtra(EXTRA_MODEL_PATH)
        sourceFile = sourcePath?.let(::File) ?: run {
            finishWithError("No STL was supplied to BumpMesh")
            return
        }
        if (!sourceFile.isFile || sourceFile.length() < STL_HEADER_BYTES) {
            finishWithError("The STL supplied to BumpMesh is missing or empty")
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(8), dp(4))
        }
        toolbar.addView(
            TextView(this).apply {
                text = "Texture model with BumpMesh"
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
            addJavascriptInterface(
                ExportBridge(
                    activity = this@BumpMeshActivity,
                    sourceName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: sourceFile.name,
                ),
                JS_BRIDGE_NAME,
            )
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                    return assetLoader.shouldInterceptRequest(request.url)
                }

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

        webView.loadUrl(BUMPMESH_URL)
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
        private val activity: BumpMeshActivity,
        private val sourceName: String,
    ) {
        private var output: File? = null
        private var stream: OutputStream? = null
        private var expectedBytes: Long = 0
        private var writtenBytes: Long = 0

        @JavascriptInterface
        fun sourceFileName(): String = sourceName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .let { name -> if (name.lowercase().endsWith(".stl")) name else "$name.stl" }

        @JavascriptInterface
        @Synchronized
        fun beginExport(filename: String, sizeBytes: Double): Boolean {
            cancelLocked()
            if (!sizeBytes.isFinite()) return false
            val size = sizeBytes.toLong()
            if (size !in STL_HEADER_BYTES..MAX_EXPORT_BYTES) return false

            val directory = File(activity.cacheDir, "bumpmesh-exports").apply { mkdirs() }
            val safeBase = filename
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifBlank { "textured.stl" }
                .let { if (it.lowercase().endsWith(".stl")) it else "$it.stl" }
            val target = File(directory, "${System.currentTimeMillis()}-$safeBase")

            return runCatching {
                expectedBytes = size
                writtenBytes = 0
                output = target
                stream = BufferedOutputStream(target.outputStream(), 128 * 1024)
                true
            }.getOrElse {
                cancelLocked()
                false
            }
        }

        @JavascriptInterface
        @Synchronized
        fun appendExportChunk(encoded: String): Boolean {
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
        fun finishExport(): Boolean {
            val completed = synchronized(this) {
                val file = output ?: return false
                val active = stream ?: return false
                runCatching {
                    active.flush()
                    active.close()
                }.onFailure {
                    cancelLocked()
                    return false
                }
                stream = null
                output = null
                if (writtenBytes != expectedBytes || !isValidBinaryStl(file)) {
                    file.delete()
                    expectedBytes = 0
                    writtenBytes = 0
                    return false
                }
                expectedBytes = 0
                writtenBytes = 0
                file
            }

            activity.runOnUiThread {
                val uri = FileProvider.getUriForFile(
                    activity,
                    "${BuildConfig.APPLICATION_ID}.files",
                    completed,
                )
                val result = Intent()
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                activity.setResult(Activity.RESULT_OK, result)
                activity.finish()
            }
            return true
        }

        @JavascriptInterface
        @Synchronized
        fun cancelExport() {
            cancelLocked()
        }

        private fun cancelLocked() {
            runCatching { stream?.close() }
            stream = null
            output?.delete()
            output = null
            expectedBytes = 0
            writtenBytes = 0
        }

        private fun isValidBinaryStl(file: File): Boolean {
            if (!file.isFile || file.length() < STL_HEADER_BYTES || file.length() > MAX_EXPORT_BYTES) return false
            return runCatching {
                RandomAccessFile(file, "r").use { input ->
                    input.seek(80)
                    val countBytes = ByteArray(4)
                    input.readFully(countBytes)
                    val triangleCount = ByteBuffer.wrap(countBytes)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .int
                        .toLong() and 0xffffffffL
                    triangleCount in 1..MAX_OUTPUT_TRIANGLES &&
                        file.length() == STL_HEADER_BYTES + triangleCount * STL_TRIANGLE_BYTES
                }
            }.getOrDefault(false)
        }
    }

    companion object {
        const val EXTRA_MODEL_PATH = "com.tomppi.enderslicercura.extra.MODEL_PATH"
        const val EXTRA_MODEL_NAME = "com.tomppi.enderslicercura.extra.MODEL_NAME"

        private const val JS_BRIDGE_NAME = "EnderSlicerAndroid"
        private const val BUMPMESH_URL =
            "https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/bumpmesh/index.html?android=1"
        private const val MAX_OUTPUT_TRIANGLES = 1_500_000L
        private const val STL_HEADER_BYTES = 84L
        private const val STL_TRIANGLE_BYTES = 50L
        private const val MAX_EXPORT_BYTES = STL_HEADER_BYTES + MAX_OUTPUT_TRIANGLES * STL_TRIANGLE_BYTES
    }
}
