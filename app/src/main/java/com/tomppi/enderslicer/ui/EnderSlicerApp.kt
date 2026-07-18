package com.tomppi.enderslicer.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
    var menuExpanded by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EnderSlicer") },
                actions = {
                    Box {
                        TextButton(onClick = { menuExpanded = true }) { Text("Menu") }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Import STL") },
                                onClick = {
                                    menuExpanded = false
                                    stlPicker.launch(arrayOf("*/*"))
                                },
                                enabled = !state.isBusy,
                            )
                            DropdownMenuItem(
                                text = { Text("Import Cura project (.3mf)") },
                                onClick = {
                                    menuExpanded = false
                                    projectPicker.launch(
                                        arrayOf(
                                            "model/3mf",
                                            "application/vnd.ms-package.3dmanufacturing-3dmodel+xml",
                                            "*/*",
                                        ),
                                    )
                                },
                                enabled = !state.isBusy,
                            )
                            DropdownMenuItem(
                                text = { Text("Import Cura profile") },
                                onClick = {
                                    menuExpanded = false
                                    profilePicker.launch(arrayOf("*/*"))
                                },
                                enabled = !state.isBusy,
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Print settings") },
                                onClick = {
                                    menuExpanded = false
                                    settingsOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Export configuration snapshot") },
                                onClick = {
                                    menuExpanded = false
                                    configExportPicker.launch("ender3v2-config.json")
                                },
                                enabled = !state.isBusy,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            ActionBar(
                state = state,
                onSlice = viewModel::sliceModel,
                onExportGcode = { gcodeExportPicker.launch("ender3v2-print.gcode") },
            )
        },
    ) { padding ->
        ViewerPanel(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }

    if (settingsOpen) {
        ModalBottomSheet(
            onDismissRequest = { settingsOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            SettingsSheet(
                state = state,
                onSettings = viewModel::updateSettings,
                onResetOverrides = viewModel::resetAllSettingOverrides,
                modifier = Modifier
                    .fillMaxHeight(0.94f)
                    .navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun ViewerPanel(
    state: MainUiState,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context -> ModelSurfaceView(context, state.printer) },
            update = { view -> view.setMesh(state.mesh) },
        )

        Card(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .widthIn(max = 360.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text(state.printer.name, style = MaterialTheme.typography.titleSmall)
                val mesh = state.mesh
                if (mesh == null) {
                    Text("Import an STL from Menu", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("${mesh.displayName} · ${mesh.triangleCount} triangles", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "%.1f × %.1f × %.1f mm".format(
                            mesh.bounds.width,
                            mesh.bounds.depth,
                            mesh.bounds.height,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Card(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .widthIn(max = 520.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(state.statusMessage, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Drag orbit · Pinch zoom · Two-finger pan · Double-tap reset",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun ActionBar(
    state: MainUiState,
    onSlice: () -> Unit,
    onExportGcode: () -> Unit,
) {
    Surface(tonalElevation = 4.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.isBusy) CircularProgressIndicator(modifier = Modifier.height(28.dp))
            Button(
                onClick = onSlice,
                enabled = state.engineAvailable && state.modelPath != null && !state.isBusy,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.isBusy) "Working…" else "Slice")
            }
            OutlinedButton(
                onClick = onExportGcode,
                enabled = state.gcodePath != null && !state.isBusy,
                modifier = Modifier.weight(1f),
            ) {
                Text("Export G-code")
            }
        }
    }
}

@Composable
private fun SettingsSheet(
    state: MainUiState,
    onSettings: (String, (SlicerSettings) -> SlicerSettings) -> Unit,
    onResetOverrides: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Print settings", style = MaterialTheme.typography.headlineSmall)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.profileName, style = MaterialTheme.typography.titleMedium)
                Text(state.profileSource, style = MaterialTheme.typography.bodySmall)
                if (state.importedRawSettingCount > 0) {
                    Text(
                        "${state.importedRawSettingCount} imported values · ${settings.overriddenSettingKeys.size} app overrides",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.warnings.forEach { warning -> Text("Warning: $warning", color = MaterialTheme.colorScheme.error) }
                OutlinedButton(
                    onClick = onResetOverrides,
                    enabled = settings.overriddenSettingKeys.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Reset all app overrides")
                }
            }
        }

        SettingsSection("Quality and material") {
            NumberField("Layer height (mm)", settings.layerHeightMm, source(state, SlicerSettings.Keys.LAYER_HEIGHT)) {
                onSettings(SlicerSettings.Keys.LAYER_HEIGHT) { current -> current.copy(layerHeightMm = it.coerceIn(0.01, 5.0)) }
            }
            NumberField("Initial layer height (mm)", settings.initialLayerHeightMm, source(state, SlicerSettings.Keys.INITIAL_LAYER_HEIGHT)) {
                onSettings(SlicerSettings.Keys.INITIAL_LAYER_HEIGHT) { current -> current.copy(initialLayerHeightMm = it.coerceIn(0.01, 5.0)) }
            }
            NumberField("Line width (mm)", settings.lineWidthMm, source(state, SlicerSettings.Keys.LINE_WIDTH)) {
                onSettings(SlicerSettings.Keys.LINE_WIDTH) { current -> current.copy(lineWidthMm = it.coerceIn(0.01, 5.0)) }
            }
            NumberField("Print speed (mm/s)", settings.printSpeedMmPerSecond, source(state, SlicerSettings.Keys.PRINT_SPEED)) {
                onSettings(SlicerSettings.Keys.PRINT_SPEED) { current -> current.copy(printSpeedMmPerSecond = it.coerceAtLeast(0.1)) }
            }
            NumberField("Infill (%)", settings.infillDensityPercent, source(state, SlicerSettings.Keys.INFILL_DENSITY)) {
                onSettings(SlicerSettings.Keys.INFILL_DENSITY) { current -> current.copy(infillDensityPercent = it.coerceIn(0.0, 100.0)) }
            }
            NumberField("Nozzle temperature (°C)", settings.nozzleTemperatureC.toDouble(), source(state, SlicerSettings.Keys.NOZZLE_TEMPERATURE), decimals = 0) {
                onSettings(SlicerSettings.Keys.NOZZLE_TEMPERATURE) { current -> current.copy(nozzleTemperatureC = it.toInt().coerceIn(150, 500)) }
            }
            NumberField("Initial nozzle temperature (°C)", settings.initialNozzleTemperatureC.toDouble(), source(state, SlicerSettings.Keys.INITIAL_NOZZLE_TEMPERATURE), decimals = 0) {
                onSettings(SlicerSettings.Keys.INITIAL_NOZZLE_TEMPERATURE) { current -> current.copy(initialNozzleTemperatureC = it.toInt().coerceIn(150, 500)) }
            }
            NumberField("Bed temperature (°C)", settings.bedTemperatureC.toDouble(), source(state, SlicerSettings.Keys.BED_TEMPERATURE), decimals = 0) {
                onSettings(SlicerSettings.Keys.BED_TEMPERATURE) { current -> current.copy(bedTemperatureC = it.toInt().coerceIn(0, 200)) }
            }
            NumberField("Fan speed (%)", settings.fanSpeedPercent, source(state, SlicerSettings.Keys.FAN_SPEED)) {
                onSettings(SlicerSettings.Keys.FAN_SPEED) { current -> current.copy(fanSpeedPercent = it.coerceIn(0.0, 100.0)) }
            }
            NumberField("Material flow (%)", settings.materialFlowPercent, source(state, SlicerSettings.Keys.MATERIAL_FLOW)) {
                onSettings(SlicerSettings.Keys.MATERIAL_FLOW) { current -> current.copy(materialFlowPercent = it.coerceIn(1.0, 300.0)) }
            }
        }

        SettingsSection("Supports") {
            SwitchRow("Generate supports", settings.supportsEnabled, source(state, SlicerSettings.Keys.SUPPORTS_ENABLED)) {
                onSettings(SlicerSettings.Keys.SUPPORTS_ENABLED) { current -> current.copy(supportsEnabled = it) }
            }
            if (settings.supportsEnabled) {
                OptionField(
                    label = "Structure",
                    value = settings.supportStructure,
                    options = listOf("tree" to "Tree", "normal" to "Normal"),
                    source = source(state, SlicerSettings.Keys.SUPPORT_STRUCTURE),
                ) {
                    onSettings(SlicerSettings.Keys.SUPPORT_STRUCTURE) { current -> current.copy(supportStructure = it) }
                }
                OptionField(
                    label = "Placement",
                    value = settings.supportPlacement,
                    options = listOf("everywhere" to "Everywhere", "buildplate" to "Build plate only"),
                    source = source(state, SlicerSettings.Keys.SUPPORT_PLACEMENT),
                ) {
                    onSettings(SlicerSettings.Keys.SUPPORT_PLACEMENT) { current -> current.copy(supportPlacement = it) }
                }
                NumberField("Overhang angle (°)", settings.supportAngleDegrees, source(state, SlicerSettings.Keys.SUPPORT_ANGLE)) {
                    onSettings(SlicerSettings.Keys.SUPPORT_ANGLE) { current -> current.copy(supportAngleDegrees = it.coerceIn(0.0, 90.0)) }
                }
                NumberField("Support density (%)", settings.supportDensityPercent, source(state, SlicerSettings.Keys.SUPPORT_DENSITY)) {
                    onSettings(SlicerSettings.Keys.SUPPORT_DENSITY) { current -> current.copy(supportDensityPercent = it.coerceIn(0.0, 100.0)) }
                }
                OptionField(
                    label = "Support pattern",
                    value = settings.supportPattern,
                    options = listOf(
                        "zigzag" to "Zig zag",
                        "lines" to "Lines",
                        "grid" to "Grid",
                        "triangles" to "Triangles",
                        "concentric" to "Concentric",
                        "cross" to "Cross",
                        "gyroid" to "Gyroid",
                    ),
                    source = source(state, SlicerSettings.Keys.SUPPORT_PATTERN),
                ) {
                    onSettings(SlicerSettings.Keys.SUPPORT_PATTERN) { current -> current.copy(supportPattern = it) }
                }
                SwitchRow("Support interface", settings.supportInterfaceEnabled, source(state, SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED)) {
                    onSettings(SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED) { current -> current.copy(supportInterfaceEnabled = it) }
                }
                if (settings.supportInterfaceEnabled) {
                    NumberField("Interface density (%)", settings.supportInterfaceDensityPercent, source(state, SlicerSettings.Keys.SUPPORT_INTERFACE_DENSITY)) {
                        onSettings(SlicerSettings.Keys.SUPPORT_INTERFACE_DENSITY) { current -> current.copy(supportInterfaceDensityPercent = it.coerceIn(0.0, 100.0)) }
                    }
                    NumberField("Interface speed (mm/s)", settings.supportInterfaceSpeedMmPerSecond, source(state, SlicerSettings.Keys.SUPPORT_INTERFACE_SPEED)) {
                        onSettings(SlicerSettings.Keys.SUPPORT_INTERFACE_SPEED) { current -> current.copy(supportInterfaceSpeedMmPerSecond = it.coerceAtLeast(0.1)) }
                    }
                }
                NumberField("Z distance (mm)", settings.supportZDistanceMm, source(state, SlicerSettings.Keys.SUPPORT_Z_DISTANCE)) {
                    onSettings(SlicerSettings.Keys.SUPPORT_Z_DISTANCE) { current -> current.copy(supportZDistanceMm = it.coerceIn(0.0, 20.0)) }
                }
                NumberField("XY distance (mm)", settings.supportXyDistanceMm, source(state, SlicerSettings.Keys.SUPPORT_XY_DISTANCE)) {
                    onSettings(SlicerSettings.Keys.SUPPORT_XY_DISTANCE) { current -> current.copy(supportXyDistanceMm = it.coerceIn(0.0, 20.0)) }
                }
                NumberField("Support speed (mm/s)", settings.supportSpeedMmPerSecond, source(state, SlicerSettings.Keys.SUPPORT_SPEED)) {
                    onSettings(SlicerSettings.Keys.SUPPORT_SPEED) { current -> current.copy(supportSpeedMmPerSecond = it.coerceAtLeast(0.1)) }
                }
            }
        }

        SettingsSection("Retraction and adhesion") {
            OptionField(
                label = "Build plate adhesion",
                value = settings.adhesionType,
                options = listOf("none" to "None", "skirt" to "Skirt", "brim" to "Brim", "raft" to "Raft"),
                source = source(state, SlicerSettings.Keys.ADHESION_TYPE),
            ) {
                onSettings(SlicerSettings.Keys.ADHESION_TYPE) { current -> current.copy(adhesionType = it) }
            }
            NumberField("Retraction distance (mm)", settings.retractionDistanceMm, source(state, SlicerSettings.Keys.RETRACTION_DISTANCE)) {
                onSettings(SlicerSettings.Keys.RETRACTION_DISTANCE) { current -> current.copy(retractionDistanceMm = it.coerceAtLeast(0.0)) }
            }
            NumberField("Retraction speed (mm/s)", settings.retractionSpeedMmPerSecond, source(state, SlicerSettings.Keys.RETRACTION_SPEED)) {
                onSettings(SlicerSettings.Keys.RETRACTION_SPEED) { current -> current.copy(retractionSpeedMmPerSecond = it.coerceAtLeast(0.0)) }
            }
            SwitchRow("Retract at layer change", settings.retractAtLayerChange, source(state, SlicerSettings.Keys.RETRACT_AT_LAYER_CHANGE)) {
                onSettings(SlicerSettings.Keys.RETRACT_AT_LAYER_CHANGE) { current -> current.copy(retractAtLayerChange = it) }
            }
            SwitchRow("Z hop", settings.zHopEnabled, source(state, SlicerSettings.Keys.Z_HOP)) {
                onSettings(SlicerSettings.Keys.Z_HOP) { current -> current.copy(zHopEnabled = it) }
            }
            SwitchRow("Firmware retraction", settings.firmwareRetraction, source(state, SlicerSettings.Keys.FIRMWARE_RETRACTION)) {
                onSettings(SlicerSettings.Keys.FIRMWARE_RETRACTION) { current -> current.copy(firmwareRetraction = it) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable Column.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Double,
    source: String,
    decimals: Int = 2,
    onValue: (Double) -> Unit,
) {
    var text by remember(value) {
        mutableStateOf(if (decimals == 0) value.toInt().toString() else value.toString().trimEnd('0').trimEnd('.'))
    }
    Column {
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
        SettingSource(source)
    }
}

@Composable
private fun OptionField(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    source: String,
    onValue: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val display = options.firstOrNull { it.first == value }?.second ?: value
    Column {
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text("$label: $display")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (optionValue, optionLabel) ->
                    DropdownMenuItem(
                        text = { Text(optionLabel) },
                        onClick = {
                            expanded = false
                            onValue(optionValue)
                        },
                    )
                }
            }
        }
        SettingSource(source)
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    source: String,
    onChecked: (Boolean) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label)
            Switch(checked = checked, onCheckedChange = onChecked)
        }
        SettingSource(source)
    }
}

@Composable
private fun SettingSource(source: String) {
    Text(source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun source(state: MainUiState, key: String): String = when {
    state.settings.isOverridden(key) -> "App override"
    state.engineProfile != null -> "Imported Cura value"
    else -> "Built-in default"
}
