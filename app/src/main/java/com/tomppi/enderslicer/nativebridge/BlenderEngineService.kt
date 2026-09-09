package com.tomppi.enderslicer.nativebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.tomppi.enderslicer.MainActivity

/**
 * Foreground keeper for the embedded Blender MCP engine.
 *
 * Without a foreground service the app process is an ordinary cached process:
 * the moment the screen locks (or the activity goes to background), Android
 * culls it -> the engine (in-process libblender_exec) and its MCP socket die
 * mid-generation. This service pins the process to foreground priority with a
 * persistent notification and holds a partial wake lock for the CPU while the
 * engine is running, so generation continues with the screen off.
 *
 * Started by [EnderSlicerApplication] / MainActivity alongside
 * [BlenderEngine.ensureStarted]; stopped via [stop] on engine shutdown.
 */
class BlenderEngineService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        acquireWakeLock()
        Log.i(TAG, "engine keeper foreground, wakelock held")
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        Log.i(TAG, "engine keeper stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "enderslicercura:blender-engine").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val TAG = "BlenderEngineService"
        private const val CHANNEL_ID = "blender-engine"
        private const val NOTIF_ID = 0x6C61

        /** Idempotent start; safe to call before the app is foregrounded. */
        fun start(context: Context) {
            val intent = Intent(context, BlenderEngineService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.i(TAG, "start requested")
            } catch (error: Throwable) {
                Log.e(TAG, "start failed", error)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, BlenderEngineService::class.java))
            } catch (error: Throwable) {
                Log.e(TAG, "stop failed", error)
            }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Blender engine",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = "Keeps the embedded Blender MCP engine running" },
                )
            }
        }

        private fun buildNotification(context: Context): Notification {
            val contentIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
            return builder
                .setContentTitle("Blender engine running")
                .setContentText("Generation socket active; use the MCP port to drive it")
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setOngoing(true)
                .setContentIntent(contentIntent)
                .build()
        }
    }
}
