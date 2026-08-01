package com.tomppi.enderslicer.engine

import java.io.File

/**
 * Narrow delegate used by CuraEngineRunner. Keeping process creation behind this
 * package-local builder ensures the native slicer is forked from Android's
 * current main/top-app thread while all blocking work remains on Dispatchers.IO.
 */
internal class ProcessBuilder(command: List<String>) {
    private val delegate = java.lang.ProcessBuilder(command)

    fun directory(directory: File): ProcessBuilder = apply {
        delegate.directory(directory)
    }

    fun redirectErrorStream(redirect: Boolean): ProcessBuilder = apply {
        delegate.redirectErrorStream(redirect)
    }

    fun redirectOutput(redirect: java.lang.ProcessBuilder.Redirect): ProcessBuilder = apply {
        delegate.redirectOutput(redirect)
    }

    fun environment(): MutableMap<String, String> = delegate.environment()

    fun start(): Process = delegate.startFromForegroundThread()

    internal object Redirect {
        fun appendTo(file: File): java.lang.ProcessBuilder.Redirect =
            java.lang.ProcessBuilder.Redirect.appendTo(file)
    }
}
