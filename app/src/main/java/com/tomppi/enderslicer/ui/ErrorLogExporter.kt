package com.tomppi.enderslicer.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomppi.enderslicer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

@Composable
fun ErrorLogExporter(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { destination: Uri? ->
        if (destination == null) return@rememberLauncherForActivityResult
        val snapshot = state
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { writeDiagnosticReport(context, destination, snapshot) }
            }
            Toast.makeText(
                context,
                result.fold(
                    onSuccess = { "Error log exported" },
                    onFailure = { "Could not export log: ${it.message}" },
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    Button(
        onClick = { exporter.launch("enderslicer-error-log.txt") },
        modifier = modifier.navigationBarsPadding(),
    ) {
        Text("Export error log")
    }
}

private fun writeDiagnosticReport(
    context: Context,
    destination: Uri,
    state: MainUiState,
) {
    val engineLog = File(context.filesDir, "logs/curaengine-last.log")
    val modelFile = state.modelPath?.let(::File)
    val gcodeFile = state.gcodePath?.let(::File)

    context.contentResolver.openOutputStream(destination, "wt")?.bufferedWriter()?.use { writer ->
        writer.appendLine("EnderSlicer diagnostic report")
        writer.appendLine("Exported: ${Instant.now()}")
        writer.appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        writer.appendLine("Android: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        writer.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        writer.appendLine()

        writer.appendLine("--- Current app state ---")
        writer.appendLine("Status: ${state.statusMessage}")
        writer.appendLine("Busy: ${state.isBusy}")
        writer.appendLine("Engine: ${state.engineStatus}")
        writer.appendLine("Engine available: ${state.engineAvailable}")
        writer.appendLine("Profile: ${state.profileName}")
        writer.appendLine("Profile source: ${state.profileSource}")
        writer.appendLine("Cura version: ${state.curaVersion ?: "unknown"}")
        writer.appendLine("Setting version: ${state.settingVersion ?: "unknown"}")
        writer.appendLine("Imported layered values: ${state.importedRawSettingCount}")
        writer.appendLine("Warnings: ${state.warnings.joinToString(" | ").ifBlank { "none" }}")
        writer.appendLine("Estimated print seconds: ${state.estimatedPrintSeconds ?: "not available"}")
        val preview = state.layerPreview
        writer.appendLine("Layer preview available: ${preview != null}")
        if (preview != null) {
            writer.appendLine("Layer preview layers: ${preview.layers.size}")
            writer.appendLine("Layer preview segments: ${preview.totalSegmentCount}")
            writer.appendLine("Layer preview speed range: ${preview.minSpeedMmPerSecond}..${preview.maxSpeedMmPerSecond} mm/s")
            writer.appendLine("Layer preview truncated: ${preview.truncated}")
        }
        writer.appendLine()

        writer.appendLine("--- Printer and slice settings ---")
        writer.appendLine("Printer: ${state.printer.name}")
        writer.appendLine("Build volume: ${state.printer.widthMm} x ${state.printer.depthMm} x ${state.printer.heightMm} mm")
        writer.appendLine("Nozzle: ${state.printer.nozzleSizeMm} mm")
        writer.appendLine("Filament: ${state.printer.filamentDiameterMm} mm")
        writer.appendLine("Direct drive: ${state.printer.directDrive}")
        writer.appendLine("Dual Z: ${state.printer.dualZ}")
        writer.appendLine("Z probe: ${state.printer.zProbe}")
        writer.appendLine("Bed leveling: ${state.printer.bedLeveling}, slot ${state.printer.ublMeshSlot}")
        writer.appendLine("Layer height: ${state.settings.layerHeightMm} mm")
        writer.appendLine("Initial layer: ${state.settings.initialLayerHeightMm} mm")
        writer.appendLine("Print speed: ${state.settings.printSpeedMmPerSecond} mm/s")
        writer.appendLine("Nozzle temperature: ${state.settings.nozzleTemperatureC} C")
        writer.appendLine("Initial nozzle temperature: ${state.settings.initialNozzleTemperatureC} C")
        writer.appendLine("Bed temperature: ${state.settings.bedTemperatureC} C")
        writer.appendLine("Infill: ${state.settings.infillDensityPercent}%")
        writer.appendLine("Supports: ${state.settings.supportsEnabled} / ${state.settings.supportStructure} / ${state.settings.supportPlacement}")
        writer.appendLine("Retraction: ${state.settings.retractionDistanceMm} mm at ${state.settings.retractionSpeedMmPerSecond} mm/s")
        writer.appendLine("Firmware retraction: ${state.settings.firmwareRetraction}")
        writer.appendLine()

        writer.appendLine("--- Files ---")
        writer.appendLine(
            "Model: " + if (modelFile?.isFile == true) {
                "${modelFile.name} (${modelFile.length()} bytes)"
            } else {
                "not available"
            },
        )
        writer.appendLine(
            "G-code: " + if (gcodeFile?.isFile == true) {
                "${gcodeFile.name} (${gcodeFile.length()} bytes)"
            } else {
                "not available"
            },
        )
        writer.appendLine("Last slice duration: ${state.sliceDurationMilliseconds?.let { "$it ms" } ?: "not available"}")
        writer.appendLine()

        writer.appendLine("--- Complete CuraEngine log ---")
        if (engineLog.isFile && engineLog.length() > 0L) {
            engineLog.bufferedReader().useLines { lines ->
                lines.forEach { line -> writer.appendLine(line) }
            }
        } else {
            writer.appendLine("No CuraEngine process log exists yet.")
            writer.appendLine("The current app status above may still identify a pre-slicing error.")
        }
    } ?: error("Unable to open the selected log destination")
}
