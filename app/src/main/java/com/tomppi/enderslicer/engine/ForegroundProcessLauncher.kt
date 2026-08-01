package com.tomppi.enderslicer.engine

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val PROCESS_START_TIMEOUT_SECONDS = 5L

private val foregroundHandler: Handler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    Handler(Looper.getMainLooper())
}

/**
 * Starts a native child from Android's main/top-app thread so the child inherits
 * the foreground scheduling group instead of a coroutine worker's background
 * cpuset. Only the short process creation step runs on the main thread; callers
 * continue waiting for and processing the child from their existing worker.
 */
internal fun ProcessBuilder.startFromForegroundThread(): Process {
    if (Looper.myLooper() == Looper.getMainLooper()) return start()

    val launch = FutureTask<Process> { start() }
    check(foregroundHandler.post(launch)) {
        "Unable to schedule CuraEngine process creation on the foreground thread"
    }

    return try {
        launch.get(PROCESS_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (error: InterruptedException) {
        foregroundHandler.removeCallbacks(launch)
        launch.cancel(false)
        Thread.currentThread().interrupt()
        throw error
    } catch (error: TimeoutException) {
        foregroundHandler.removeCallbacks(launch)
        launch.cancel(false)
        throw IllegalStateException("Timed out while starting CuraEngine on the foreground thread", error)
    } catch (error: ExecutionException) {
        when (val cause = error.cause) {
            is Exception -> throw cause
            is Error -> throw cause
            else -> throw error
        }
    }
}
