package com.tomppi.enderslicer.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.viewer.ModelSurfaceView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnderSlicerApp(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val stlPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importStl)
    }
    val profilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importCuraProfile)
    }
    val projectPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importCuraProject)
    }
    val configExportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        uri?.let(viewModel::exportConfiguration)
    }
    val gcodeExportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/x-gcode"),
    ) { uri: Uri? ->
        uri?.let(viewModel::exportGcode)
    }

    val controls: @Composable () -> Unit = {
        Controls(
            state = state,
            onImportStl = { stlPicker.launch(arrayOf("*/*")) },
            onImportProfile = { profilePicker.launch(arrayOf("*/*")) },
            onImportProject = {
                projectPicker.launch(
                    arrayOf(
                        "model/3mf",
                        "application/vnd.ms-package.3dmanufacturing-3dmodel+xml",
                        "*/*",
                    ),
                )
            },
            onExportConfiguration = { configExportPicker.launch("ender3v2-config.json") },
            onSlice = viewModel::sliceModel,
            onExportGcode = { gcodeExportPicker.launch("ender3v2-print.gcode") },
            onSettings = viewModel::updateSettings,
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("EnderSlicer") }) },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            val wide = maxWidth >= 820.dp
            if (wide) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1.25f)) {
                        ViewerPanel(state)
                    }
                    Column(
                        modifier = Modifier
                            .weight(0.85f)
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        controls()
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ViewerPanel(state)
                    controls()
                }
            }
        }
    }
}

@Composable
private fun ViewerPanel(state: MainUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(state.printer.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "${state.printer.widthMm.toInt()} × ${state.printer.depthMm.toInt()} × ${state.printer.heightMm.toInt()} mm · " +
                    "${state.printer.nozzleSizeMm} mm nozzle · direct drive · UBL slot ${state.printer.ublMeshSlot}",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(430.dp),
                factory = { context -> ModelSurfaceView(context, state.printer) },
                update = { view -> view.setMesh(state.mesh) },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Drag: unrestricted orbit · Pinch: zoom · Two fingers: pan · Double tap: reset",
                style = MaterialTheme.typography.bodySmall,
            )
            val mesh = state.mesh
            if (mesh == null) {
                Text("No STL loaded. The 230 × 230 mm bed grid is ready.")
            } else {
                Text("${mesh.displayName} · ${mesh.triangleCount} triangles")
                Text(
                    "Model bounds: %.1f × %.1f × %.1f mm".format(
                        mesh.bounds.width,
                        mesh.bounds.depth,
                        mesh.bounds.height,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Controls(
    state: MainUiState,
    onImportStl: () -> Unit,
    onImportProfile: () -> Unit,
    onImportProject: () -> Unit,
    onExportConfiguration: () -> Unit,
    onSlice: () -> Unit,
    onExportGcode: () -> Unit,
    onSettings: ((SlicerSettings) -> SlicerSettings) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Files", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onImportStl, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                Text("Import STL")
            }
            OutlinedButton(onClick = onImportProfile, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                Text("Import .curaprofile")
            }
            OutlinedButton(onClick = onImportProject, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                Text("Import Cura project .3mf")
            }
            OutlinedButton(onClick = onExportConfiguration, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                Text("Export configuration snapshot")
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Active Cura configuration", style = MaterialTheme.typography.titleMedium)
            Text(state.profileName)
            Text(state.profileSource, style = MaterialTheme.typography.bodySmall)
            if (state.curaVersion != null || state.settingVersion != null) {
                Text(
                    "Cura ${state.curaVersion ?: "unknown"} · setting version ${state.settingVersion ?: "unknown"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.importedRawSettingCount > 0) {
                Text("${state.importedRawSettingCount} layered values imported", style = MaterialTheme.typography.bodySmall)
            }
            state.warnings.forEach { warning -> Text("Warning: $warning", color = MaterialTheme.colorScheme.error) }
        }
    }

    SettingsCard(state.settings, onSettings)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("CuraEngine", style = MaterialTheme.typography.titleMedium)
            Text(state.engineStatus, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = onSlice,
                enabled = state.engineAvailable && state.modelPath != null && !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isBusy) "Working…" else "Slice to G-code")
            }
            OutlinedButton(
                onClick = onExportGcode,
                enabled = state.gcodePath != null && !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Export G-code")
            }
            if (state.gcodePath != null) {
                Text("A validated Cura G-code file is ready for export.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.isBusy) CircularProgressIndicator()
            Text(state.statusMessage)
        }
    }
}

@Composable
private fun SettingsCard(
    settings: SlicerSettings,
    onSettings: ((SlicerSettings) -> SlicerSettings) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Essential settings", style = MaterialTheme.typography.titleMedium)
            NumberField("Layer height (mm)", settings.layerHeightMm) { value ->
                onSettings { it.copy(layerHeightMm = value) }
            }
            NumberField("Initial layer (mm)", settings.initialLayerHeightMm) { value ->
                onSettings { it.copy(initialLayerHeightMm = value) }
            }
            NumberField("Print speed (mm/s)", settings.printSpeedMmPerSecond) { value ->
                onSettings { it.copy(printSpeedMmPerSecond = value) }
            }
            NumberField("Nozzle temperature (°C)", settings.nozzleTemperatureC.toDouble(), decimals = 0) { value ->
                onSettings { it.copy(nozzleTemperatureC = value.toInt()) }
            }
            NumberField("Initial nozzle temperature (°C)", settings.initialNozzleTemperatureC.toDouble(), decimals = 0) { value ->
                onSettings { it.copy(initialNozzleTemperatureC = value.toInt()) }
            }
            NumberField("Bed temperature (°C)", settings.bedTemperatureC.toDouble(), decimals = 0) { value ->
                onSettings { it.copy(bedTemperatureC = value.toInt()) }
            }
            NumberField("Infill (%)", settings.infillDensityPercent) { value ->
                onSettings { it.copy(infillDensityPercent = value.coerceIn(0.0, 100.0)) }
            }
            HorizontalDivider()
            SwitchRow("Supports", settings.supportsEnabled) { enabled ->
                onSettings { it.copy(supportsEnabled = enabled) }
            }
            if (settings.supportsEnabled) {
                Text("Support placement")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (settings.supportPlacement == "buildplate") {
                        Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Build plate") }
                        OutlinedButton(
                            onClick = { onSettings { it.copy(supportPlacement = "everywhere") } },
                            modifier = Modifier.weight(1f),
                        ) { Text("Everywhere") }
                    } else {
                        OutlinedButton(
                            onClick = { onSettings { it.copy(supportPlacement = "buildplate") } },
                            modifier = Modifier.weight(1f),
                        ) { Text("Build plate") }
                        Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Everywhere") }
                    }
                }
            }
            NumberField("Retraction distance (mm)", settings.retractionDistanceMm) { value ->
                onSettings { it.copy(retractionDistanceMm = value.coerceAtLeast(0.0)) }
            }
            NumberField("Retraction speed (mm/s)", settings.retractionSpeedMmPerSecond) { value ->
                onSettings { it.copy(retractionSpeedMmPerSecond = value.coerceAtLeast(0.0)) }
            }
            SwitchRow("Firmware retraction", settings.firmwareRetraction) { enabled ->
                onSettings { it.copy(firmwareRetraction = enabled) }
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Double,
    decimals: Int = 2,
    onValue: (Double) -> Unit,
) {
    var text by remember(value) {
        mutableStateOf(if (decimals == 0) value.toInt().toString() else value.toString().trimEnd('0').trimEnd('.'))
    }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            input.replace(',', '.').toDoubleOrNull()?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
