package com.tomppi.enderslicer.engine

import android.content.Context
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.profile.CuraEngineProfile
import com.tomppi.enderslicer.profile.CuraResolvedSettingsWriter
import com.tomppi.enderslicer.profile.CuraSliceSettingsResolver
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

class CuraEngineRunner(private val context: Context) {
    data class SliceResult(
        val gcodeFile: File,
        val logFile: File,
        val elapsedMilliseconds: Long,
    )

    class SliceException(
        message: String,
        val logFile: File,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    private data class PreparedDefinitions(
        val directory: File,
        val machineDefinition: File,
        val extruderDefinition: File,
        val source: String,
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
        profile: CuraEngineProfile? = null,
    ): SliceResult {
        val workDirectory = File(context.cacheDir, "curaengine").apply { mkdirs() }
        val outputFile = File(workDirectory, "current.gcode")
        val resolvedSettingsFile = File(workDirectory, "resolved-settings.json")
        val resolvedModelFile = File(workDirectory, "current.stl")
        val logFile = File(context.filesDir, "logs/curaengine-last.log").apply {
            parentFile?.mkdirs()
        }

        outputFile.delete()
        resolvedSettingsFile.delete()
        resolvedModelFile.delete()
        logFile.writeText(
            buildString {
                appendLine("EnderSlicer CuraEngine diagnostic log")
                appendLine("Started: ${Instant.now()}")
                appendLine("Engine: ${executable.absolutePath}")
                appendLine("Engine status: ${status()}")
                appendLine("Model: ${modelFile.name} (${modelFile.length()} bytes)")
                appendLine("Printer: ${printer.name}")
                appendLine("Build volume: ${printer.widthMm} x ${printer.depthMm} x ${printer.heightMm} mm")
                appendLine("Nozzle: ${printer.nozzleSizeMm} mm")
                appendLine("Layer height: ${settings.layerHeightMm} mm")
                appendLine("Print speed: ${settings.printSpeedMmPerSecond} mm/s")
                appendLine("Supports: ${settings.supportsEnabled} / ${settings.supportStructure} / ${settings.supportPlacement}")
                appendLine("Imported Cura global values: ${profile?.globalValues?.size ?: 0}")
                appendLine("Imported Cura extruder values: ${profile?.extruderValues?.size ?: 0}")
                appendLine("Raw Cura global overrides: ${profile?.rawGlobalValues?.size ?: 0}")
                appendLine("Raw Cura extruder overrides: ${profile?.rawExtruderValues?.size ?: 0}")
                appendLine("Parsed material values: ${profile?.materialValueCount ?: 0}")
                appendLine()
            },
        )

        val startedAt = System.nanoTime()

        try {
            require(isAvailable()) { status() }
            require(modelFile.isFile && modelFile.length() > 0L) { "The imported STL is no longer available" }

            val definitions = prepareDefinitions(workDirectory, logFile, profile)
            var resolved: CuraSliceSettingsResolver.Result? = null
            val command = if (profile?.usesProjectDefinitions == true) {
                modelFile.copyTo(resolvedModelFile, overwrite = true)
                check(resolvedModelFile.isFile && resolvedModelFile.length() == modelFile.length()) {
                    "Unable to stage the STL for resolved Cura slicing"
                }
                resolved = CuraSliceSettingsResolver.resolve(
                    profile = profile,
                    printer = printer,
                    settings = settings,
                    startGcode = startGcode,
                    endGcode = endGcode,
                )
                CuraResolvedSettingsWriter.write(
                    destination = resolvedSettingsFile,
                    modelFileName = resolvedModelFile.name,
                    resolved = resolved,
                )
                CuraEngineCommand.buildResolved(
                    executablePath = executable.absolutePath,
                    definitionsDirectory = definitions.directory.absolutePath,
                    resolvedSettingsPath = resolvedSettingsFile.absolutePath,
                    outputPath = outputFile.absolutePath,
                )
            } else {
                CuraEngineCommand.build(
                    executablePath = executable.absolutePath,
                    definitionsDirectory = definitions.directory.absolutePath,
                    machineDefinitionPath = definitions.machineDefinition.absolutePath,
                    extruderDefinitionPath = definitions.extruderDefinition.absolutePath,
                    modelPath = modelFile.absolutePath,
                    outputPath = outputFile.absolutePath,
                    printer = printer,
                    settings = settings,
                    startGcode = startGcode,
                    endGcode = endGcode,
                    profile = profile,
                )
            }

            appendLog(
                logFile,
                buildString {
                    appendLine("Definition source: ${definitions.source}")
                    appendLine("Machine definition: ${definitions.machineDefinition.name}")
                    appendLine("Extruder definition: ${definitions.extruderDefinition.name}")
                    if (resolved != null) {
                        appendLine("Settings transport: CuraEngine resolved JSON (-r)")
                        appendLine("Resolved definition expressions: ${resolved.expressionCount}")
                        appendLine("Resolution passes: ${resolved.passes}")
                        appendLine("Resolved global settings: ${resolved.globalValues.size}")
                        appendLine("Resolved extruder settings: ${resolved.extruderValues.size}")
                        appendLine("Resolved layer height: ${resolved.globalValues["layer_height"]}")
                        appendLine("Resolved wall lines: ${resolved.extruderValues["wall_line_count"]}")
                        appendLine("Resolved top/bottom layers: ${resolved.extruderValues["top_layers"]}/${resolved.extruderValues["bottom_layers"]}")
                        appendLine("Resolved infill pattern/distance: ${resolved.extruderValues["infill_pattern"]}/${resolved.extruderValues["infill_line_distance"]}")
                        appendLine("Resolved fan start/full layer: ${resolved.extruderValues["cool_fan_speed_0"]}/${resolved.extruderValues["cool_fan_full_layer"]}")
                        appendLine("Resolved nozzle temperatures: ${resolved.extruderValues["material_print_temperature_layer_0"]}/${resolved.extruderValues["material_print_temperature"]}/${resolved.extruderValues["cool_min_temperature"]}")
                        appendLine("Resolved settings JSON: ${resolvedSettingsFile.length()} bytes")
                    } else {
                        appendLine("Settings transport: fallback command-line overrides")
                    }
                    appendLine()
                    appendLine("--- Command ---")
                    command.forEachIndexed { index, argument -> appendLine("[$index] $argument") }
                    appendLine()
                    appendLine("--- CuraEngine output ---")
                },
            )

            val process = ProcessBuilder(command)
                .directory(workDirectory)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
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
                throw SliceException(
                    "CuraEngine timed out after $SLICE_TIMEOUT_MINUTES minutes. Export the error log for details.",
                    logFile,
                )
            }

            val exitCode = process.exitValue()
            appendLog(logFile, "\n--- Process result ---\nExit code: $exitCode\n")
            if (exitCode != 0) {
                throw SliceException(
                    "CuraEngine failed with exit code $exitCode. Export the error log for full details.",
                    logFile,
                )
            }

            if (!outputFile.isFile || outputFile.length() < MINIMUM_GCODE_BYTES) {
                throw SliceException(
                    "CuraEngine finished without producing a valid G-code file. Export the error log for details.",
                    logFile,
                )
            }

            val header = outputFile.inputStream().bufferedReader().use { reader ->
                buildString {
                    repeat(20) {
                        val line = reader.readLine() ?: return@repeat
                        appendLine(line)
                    }
                }
            }
            if (!header.contains(";FLAVOR:") && !header.contains(";Generated with Cura")) {
                throw SliceException(
                    "The engine output did not contain a Cura G-code header. Export the error log for details.",
                    logFile,
                )
            }

            val summary = GcodeSanitizer.validateAndRepair(outputFile)
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            appendLog(
                logFile,
                buildString {
                    appendLine("--- Validated G-code ---")
                    appendLine("Layers: ${summary.layerCount}")
                    appendLine("Estimated seconds: ${summary.estimatedSeconds ?: "unknown"}")
                    appendLine("Model filament millimeters: ${summary.filamentMillimeters}")
                    appendLine("Extrusion bounds: X ${summary.minX}..${summary.maxX}, Y ${summary.minY}..${summary.maxY}, Z ${summary.minZ}..${summary.maxZ}")
                    appendLine("G-code bytes: ${outputFile.length()}")
                    appendLine("Elapsed milliseconds: $elapsed")
                    appendLine("Completed: ${Instant.now()}")
                    appendLine("Result: success")
                },
            )

            return SliceResult(
                gcodeFile = outputFile,
                logFile = logFile,
                elapsedMilliseconds = elapsed,
            )
        } catch (error: Throwable) {
            outputFile.delete()
            appendLog(
                logFile,
                buildString {
                    appendLine()
                    appendLine("--- EnderSlicer failure ---")
                    appendLine("Finished: ${Instant.now()}")
                    appendLine(error.stackTraceToString())
                },
            )
            if (error is SliceException) throw error
            throw SliceException(
                error.message ?: "CuraEngine failed before slicing started",
                logFile,
                error,
            )
        }
    }

    private fun prepareDefinitions(
        workDirectory: File,
        logFile: File,
        profile: CuraEngineProfile?,
    ): PreparedDefinitions {
        val destination = File(workDirectory, "definitions").apply {
            deleteRecursively()
            mkdirs()
        }

        if (profile?.usesProjectDefinitions == true) {
            profile.definitionFiles.forEach { (rawName, content) ->
                val name = safeDefinitionName(rawName)
                val target = File(destination, name)
                target.writeText(content)
                check(target.length() > 0L) { "Imported Cura definition is empty: $name" }
            }
            val machine = File(destination, safeDefinitionName(requireNotNull(profile.machineDefinitionFileName)))
            val extruder = File(destination, safeDefinitionName(requireNotNull(profile.extruderDefinitionFileName)))
            check(machine.isFile) { "Imported machine definition is missing: ${machine.name}" }
            check(extruder.isFile) { "Imported extruder definition is missing: ${extruder.name}" }
            logDefinitions(logFile, "Imported Cura project definitions", destination)
            return PreparedDefinitions(destination, machine, extruder, "imported Cura project")
        }

        BUNDLED_DEFINITION_FILES.forEach { name ->
            val target = File(destination, name)
            context.assets.open("cura/definitions/$name").use { input ->
                target.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            check(target.length() > 0L) { "Bundled Cura definition is empty: $name" }
        }
        logDefinitions(logFile, "Bundled Cura definitions", destination)
        return PreparedDefinitions(
            directory = destination,
            machineDefinition = File(destination, BUNDLED_MACHINE_DEFINITION),
            extruderDefinition = File(destination, BUNDLED_EXTRUDER_DEFINITION),
            source = "bundled Cura 5.11 fallback",
        )
    }

    private fun logDefinitions(logFile: File, heading: String, directory: File) {
        appendLog(
            logFile,
            buildString {
                appendLine("--- $heading ---")
                directory.listFiles()
                    .orEmpty()
                    .sortedBy { it.name }
                    .forEach { appendLine("${it.name} (${it.length()} bytes)") }
                appendLine()
            },
        )
    }

    private fun safeDefinitionName(rawName: String): String {
        val name = rawName.substringAfterLast('/').substringAfterLast('\\')
        require(name.endsWith(".def.json")) { "Invalid Cura definition filename: $rawName" }
        require(name.matches(Regex("[A-Za-z0-9._ #+%()-]+"))) { "Unsafe Cura definition filename: $rawName" }
        return name
    }

    private fun appendLog(file: File, text: String) {
        runCatching { file.appendText(text) }
    }

    private companion object {
        const val ENGINE_LIBRARY_NAME = "libcuraengine_exec.so"
        const val SLICE_TIMEOUT_MINUTES = 30L
        const val MINIMUM_GCODE_BYTES = 128L
        const val BUNDLED_MACHINE_DEFINITION = "creality_ender3.def.json"
        const val BUNDLED_EXTRUDER_DEFINITION = "creality_base_extruder_0.def.json"
        val BUNDLED_DEFINITION_FILES = listOf(
            "fdmprinter.def.json",
            "fdmextruder.def.json",
            "creality_base.def.json",
            BUNDLED_EXTRUDER_DEFINITION,
            BUNDLED_MACHINE_DEFINITION,
        )
    }
}
