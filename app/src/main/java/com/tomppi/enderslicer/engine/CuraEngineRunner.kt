package com.tomppi.enderslicer.engine

import android.content.Context
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import java.io.File
import java.util.concurrent.TimeUnit

class CuraEngineRunner(private val context: Context) {
    data class SliceResult(
        val gcodeFile: File,
        val logFile: File,
        val elapsedMilliseconds: Long,
    )

    private val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
    private val executable = File(nativeDirectory, ENGINE_LIBRARY_NAME)

    fun isAvailable(): Boolean = executable.isFile && executable.length() > 0L

    fun status(): String = when {
        !executable.exists() -> "CuraEngine 5.11 ARM64 is not packaged in this APK"
        !executable.isFile -> "CuraEngine package path is invalid"
        executable.length() == 0L -> "CuraEngine package is empty"
        else -> "CuraEngine 5.11 ARM64 ready"
    }

    fun slice(
        modelFile: File,
        printer: PrinterDefinition,
        settings: SlicerSettings,
        startGcode: String,
        endGcode: String,
    ): SliceResult {
        require(isAvailable()) { status() }
        require(modelFile.isFile && modelFile.length() > 0L) { "The imported STL is no longer available" }

        val workDirectory = File(context.cacheDir, "curaengine").apply { mkdirs() }
        val definitionsDirectory = prepareDefinitions(workDirectory)
        val outputFile = File(workDirectory, "current.gcode")
        val logFile = File(workDirectory, "curaengine.log")
        outputFile.delete()
        logFile.delete()

        val command = CuraEngineCommand.build(
            executablePath = executable.absolutePath,
            definitionsDirectory = definitionsDirectory.absolutePath,
            modelPath = modelFile.absolutePath,
            outputPath = outputFile.absolutePath,
            printer = printer,
            settings = settings,
            startGcode = startGcode,
            endGcode = endGcode,
        )

        val startedAt = System.nanoTime()
        val process = ProcessBuilder(command)
            .directory(workDirectory)
            .redirectErrorStream(true)
            .redirectOutput(logFile)
            .apply {
                environment()["LD_LIBRARY_PATH"] = nativeDirectory.absolutePath
                environment()["TMPDIR"] = workDirectory.absolutePath
                environment()["HOME"] = context.filesDir.absolutePath
                environment()["CURAENGINE_LOG_LEVEL"] = "info"
            }
            .start()

        val completed = process.waitFor(SLICE_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        if (!completed) {
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
            error("CuraEngine timed out after $SLICE_TIMEOUT_MINUTES minutes")
        }

        val exitCode = process.exitValue()
        val logTail = readTail(logFile, MAX_LOG_ERROR_BYTES)
        check(exitCode == 0) {
            "CuraEngine failed with exit code $exitCode${logTail.takeIf(String::isNotBlank)?.let { ":\n$it" }.orEmpty()}"
        }
        check(outputFile.isFile && outputFile.length() >= MINIMUM_GCODE_BYTES) {
            "CuraEngine finished without producing a valid G-code file${logTail.takeIf(String::isNotBlank)?.let { ":\n$it" }.orEmpty()}"
        }

        val header = outputFile.inputStream().bufferedReader().use { reader ->
            buildString {
                repeat(20) {
                    val line = reader.readLine() ?: return@repeat
                    appendLine(line)
                }
            }
        }
        check(header.contains(";FLAVOR:") || header.contains(";Generated with Cura")) {
            "The engine output did not contain a Cura G-code header"
        }

        return SliceResult(
            gcodeFile = outputFile,
            logFile = logFile,
            elapsedMilliseconds = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
        )
    }

    private fun prepareDefinitions(workDirectory: File): File {
        val destination = File(workDirectory, "definitions").apply { mkdirs() }
        DEFINITION_FILES.forEach { name ->
            val target = File(destination, name)
            context.assets.open("cura/definitions/$name").use { input ->
                target.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            check(target.length() > 0L) { "Bundled Cura definition is empty: $name" }
        }
        return destination
    }

    private fun readTail(file: File, maximumBytes: Int): String {
        if (!file.isFile || file.length() == 0L) return ""
        val bytes = file.readBytes()
        val start = (bytes.size - maximumBytes).coerceAtLeast(0)
        return bytes.copyOfRange(start, bytes.size).toString(Charsets.UTF_8).trim()
    }

    private companion object {
        const val ENGINE_LIBRARY_NAME = "libcuraengine_exec.so"
        const val SLICE_TIMEOUT_MINUTES = 30L
        const val MINIMUM_GCODE_BYTES = 128L
        const val MAX_LOG_ERROR_BYTES = 16 * 1024
        val DEFINITION_FILES = listOf(
            "fdmprinter.def.json",
            "creality_base.def.json",
            "creality_ender3.def.json",
        )
    }
}
