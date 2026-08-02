package com.tomppi.enderslicer.engine

import java.util.concurrent.TimeUnit

/** Owns a native child from creation through exit, timeout, or interruption. */
internal object OwnedProcessRunner {
    class ProcessTimeoutException : Exception("Process timed out")

    fun run(
        start: () -> Process,
        timeout: Long,
        unit: TimeUnit,
        shutdownGraceMillis: Long = DEFAULT_SHUTDOWN_GRACE_MILLIS,
    ): Int {
        require(timeout > 0L) { "Process timeout must be positive" }
        require(shutdownGraceMillis >= 0L) { "Process shutdown grace must not be negative" }

        var process: Process? = null
        var interrupted = false
        try {
            process = start()
            if (Thread.currentThread().isInterrupted) {
                interrupted = true
                throw InterruptedException("Process launch completed after cancellation")
            }
            if (!process.waitFor(timeout, unit)) throw ProcessTimeoutException()
            return process.exitValue()
        } catch (error: InterruptedException) {
            interrupted = true
            throw error
        } finally {
            process?.let { terminate(it, shutdownGraceMillis) }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun terminate(process: Process, shutdownGraceMillis: Long) {
        if (!process.isAlive) return
        process.destroy()
        try {
            if (process.isAlive && !process.waitFor(shutdownGraceMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                if (shutdownGraceMillis > 0L) {
                    process.waitFor(shutdownGraceMillis, TimeUnit.MILLISECONDS)
                }
            }
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
        }
    }

    private const val DEFAULT_SHUTDOWN_GRACE_MILLIS = 3_000L
}
