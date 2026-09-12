package com.tomppi.enderslicer.nativebridge

import android.content.Context
import android.os.FileObserver
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import com.tomppi.enderslicer.engine.AssetTreeExtractor
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App-side owner of the embedded Blender MCP engine (arm64-v8a only).
 *
 * Responsibilities (see BLENDER_MCP_INTEGRATION.md section 3):
 *  - first-run materialization of assets/blender/{python,scripts} to
 *    <filesDir>/blender/ (the engine cannot import assets in place),
 *  - starting the engine through BlenderBridge (background mode, MCP socket,
 *    default port 9876) once, idempotently,
 *  - watching <config>/exports/ for new .stl handoffs and forwarding them to
 *    the UI so the app always shows the latest generated model.
 *
 * Export-watch reliability: FileObserver depends on the framework's shared
 * inotify fd registering a watch. On some devices/kernels that registration
 * silently never happens (the fd carries no watches and no event is ever
 * delivered), so the poller below is the AUTHORITATIVE detector: it scans the
 * exports dir every 500 ms and dispatches each new file revision exactly once
 * (deduplicated by path + size + mtime). FileObserver is kept as a latency
 * accelerator only; the same signature dedupe makes the two paths safe to run
 * in parallel.
 */
object BlenderEngine {
    private const val TAG = "BlenderEngine"
    private const val RESOURCES_VERSION = "blender-3.6-resources-v5"
    const val DEFAULT_MCP_PORT = 9876

    /** Scan cadence of the authoritative exports-dir poller. */
    private const val EXPORT_POLL_MS = 500L

    @Volatile private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Receives every completed STL handoff. Runs on a background scope. */
    @Volatile var onStlExported: ((File) -> Unit)? = null
        set(value) {
            field = value
            // An export written before the listener attached (typically the
            // AI finished a generation while the UI was still starting) still
            // shows up: replay the newest one.
            val newest = synchronized(pendingExports) {
                val sorted = pendingExports.sortedBy { it.lastModified() }
                pendingExports.clear()
                sorted.lastOrNull()
            } ?: return
            synchronized(stateLock) {
                delivered.add(signatureOf(newest))
            }
            value?.invoke(newest)
        }

    /** Signatures (path|size|mtime) already handed to the UI (or recorded while unattached). */
    private val delivered = mutableSetOf<String>()

    /** Signatures currently being settled; prevents duplicate dispatch. */
    private val inFlight = mutableSetOf<String>()

    /** Guards [delivered] and [inFlight]. */
    private val stateLock = Any()

    /** Exports found before the UI listener attached; replayed by the setter. */
    private val pendingExports = mutableListOf<File>()

    /** Idempotent: boots extraction + engine on first call, no-op afterwards. */
    fun ensureStarted(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            runCatching {
                // ensureLoaded() performs the (one-time, background)
                // System.loadLibrary of the 1.3 GB engine so the x86_64
                // emulator build skips cleanly instead of crashing.
                if (!BlenderBridge.ensureLoaded()) {
                    Log.w(TAG, "Blender engine library is not packaged for this ABI; skipping")
                    return@launch
                }
                val configDir = File(app.filesDir, "blender")
                prepareResources(app, configDir)
                File(configDir, "3.6/config/datafiles").mkdirs()
                File(app.filesDir, "blender-home").mkdirs()
                // Tailscale Android runs userspace netstack: inbound TCP to
                // app ports is NOT delivered (verified: SYN to the app socket
                // times out over the tailnet), so binding the tailnet IP only
                // breaks the adb-forward loopback path. Keep localhost.
                val bindHost = "localhost"
                Log.i(TAG, "MCP bind host: " + bindHost)
                BlenderBridge.start(
                    home = app.filesDir.absolutePath + "/blender-home",
                    config = configDir.absolutePath,
                    port = DEFAULT_MCP_PORT,
                    host = bindHost,
                )
                ensureWatcher(configDir)
            }.onFailure { error ->
                Log.e(TAG, "Blender engine startup failed", error)
            }
        }
    }

    /**
     * Graceful addon shutdown, called when the main UI is torn down. The
     * engine is also torn down with the process, but this signals the addon
     * to close its socket promptly. Re-arms [ensureStarted] for the next
     * launch in the same process (config-change survival keeps the VM alive;
     * [shutdown] is only reached on explicit finish).
     */
    fun shutdown() {
        BlenderBridge.stop()
        started = false
    }

    /**
     * Tailscale interface IPv4 (CGNAT 100.64.0.0/10, e.g. 100.x.y.z on
     * tun1). When present, bind the MCP socket to this address so the host
     * (and any tailnet peer) can reach it directly -- no adb forward needed.
     * Falls back to null => localhost (adb forward flow).
     */
    private fun tailscaleIpv4(): String? = runCatching {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
        sequence {
            for (iface in Collections.list(ifaces)) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in Collections.list(iface.inetAddresses)) {
                    // Tailscale CGNAT: 100.64.0.0/10 (100.64-127.x.x)
                    val raw = (addr as? Inet4Address)?.address ?: continue
                    if (raw.size == 4 && (raw[0].toInt() and 0xFF) == 100 && (raw[1].toInt() and 0xC0) == 0x40) {
                        yield(addr)
                    }
                }
            }
        }.firstOrNull()?.hostAddress
    }.getOrNull()

    /** First-run extraction with a version marker (around 480 MB, once). */
    private suspend fun prepareResources(context: Context, configDir: File) {
        withContext(Dispatchers.IO) {
            val marker = File(configDir, ".resources-version")
            if (marker.isFile && marker.readText() == RESOURCES_VERSION &&
                File(configDir, "python/lib/python3.11").isDirectory &&
                File(configDir, "scripts/startup/start_blender_mcp.py").isFile
            ) {
                return@withContext
            }
            try {
                configDir.deleteRecursively()
                configDir.mkdirs()
                AssetTreeExtractor.copyTree(context.assets, "blender/python", File(configDir, "python"))
                AssetTreeExtractor.copyTree(context.assets, "blender/scripts", File(configDir, "scripts"))
                marker.writeText(RESOURCES_VERSION)
                Log.i(TAG, "blender resources materialized to " + configDir.absolutePath)
            } catch (error: Throwable) {
                configDir.deleteRecursively()
                throw error
            }
        }
    }

    private fun ensureWatcher(configDir: File) {
        val exports = File(configDir, "exports")
        exports.mkdirs()
        runCatching {
            // Everything already here is history from a previous run, not a new
            // arrival. [delivered] lives in memory, so without this the first
            // poll of every launch treats the whole backlog as freshly exported
            // and hands the UI one model after another before it settles.
            val existing = exports
                .listFiles { f -> f.isFile && f.name.endsWith(".stl", ignoreCase = true) }
                ?.sortedBy { it.lastModified() }
                .orEmpty()
            val newest = existing.lastOrNull()
            synchronized(stateLock) {
                existing.forEach { delivered.add(signatureOf(it)) }
            }
            // The newest still belongs to the UI: an export that finished while
            // the app was dead is exactly the one being waited for. Handed over
            // directly when a listener is attached, queued for the setter when
            // it is not - the same replay the setter already implements.
            newest?.let { file ->
                val listener = onStlExported
                if (listener == null) {
                    synchronized(pendingExports) {
                        if (pendingExports.none { it.absolutePath == file.absolutePath }) {
                            pendingExports.add(file)
                        }
                    }
                } else {
                    listener.invoke(file)
                }
            }
            val observer = object : FileObserver(
                exports.absolutePath,
                FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE,
            ) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null || !path.endsWith(".stl", ignoreCase = true)) return
                    exportReady(File(exports, path))
                }
            }
            observer.startWatching()
            Log.i(TAG, "watching exports dir " + exports.absolutePath)
        }.onFailure { error -> Log.e(TAG, "export watch failed", error) }

        scope.launch {
            Log.i(TAG, "polling exports dir " + exports.absolutePath)
            while (isActive) {
                try {
                    exports.listFiles { f -> f.isFile && f.name.endsWith(".stl", ignoreCase = true) }
                        ?.forEach { file -> exportReady(file) }
                } catch (error: Throwable) {
                    Log.e(TAG, "export poll failed", error)
                }
                delay(EXPORT_POLL_MS)
            }
        }
    }

    /** Marks an export revision delivered so it is dispatched exactly once. */
    private fun claimDelivered(file: File): Boolean = synchronized(stateLock) {
        val signature = signatureOf(file)
        if (delivered.contains(signature)) false else {
            delivered.add(signature)
            true
        }
    }

    private fun signatureOf(file: File): String =
        "${file.absolutePath}|${file.length()}|${file.lastModified()}"

    /** Dispatches a candidate export; deduped across poller and FileObserver. */
    private fun exportReady(file: File) {
        val signature = signatureOf(file)
        synchronized(stateLock) {
            if (delivered.contains(signature) || !inFlight.add(signature)) return
        }
        scope.launch {
            try {
                settle(file)
            } catch (error: Throwable) {
                Log.e(TAG, "export settle failed for " + file.name, error)
            }
            val ready = file.isFile && file.length() > 0L
            synchronized(stateLock) {
                inFlight.remove(signature)
                inFlight.remove(signatureOf(file))
            }
            if (!ready) return@launch
            // Claim BEFORE invoking the listener: a failed parse is a
            // one-shot delivery, never a poll-loop retry storm.
            if (!claimDelivered(file)) return@launch
            val listener = onStlExported
            if (listener != null) {
                listener(file)
            } else {
                synchronized(pendingExports) {
                    if (pendingExports.none { it.absolutePath == file.absolutePath }) {
                        pendingExports.add(file)
                    }
                }
            }
        }
    }

    /** Waits until the STL size is stable for two consecutive probes. */
    private suspend fun settle(file: File) {
        var previous = -1L
        repeat(8) {
            delay(250)
            val size = file.length()
            if (size == previous && size > 0L) return
            previous = size
        }
    }
}
