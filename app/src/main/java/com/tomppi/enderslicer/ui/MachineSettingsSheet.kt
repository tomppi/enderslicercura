package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.model.SlicerSettings

@Composable
internal fun MachineSettingsSheet(
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
        Text("Printer & G-code", style = MaterialTheme.typography.headlineSmall)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Machine profile", style = MaterialTheme.typography.titleMedium)
                Text(
                    "These values control CuraEngine and the build-plate viewer. Check every dimension before using G-code on another printer.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = onResetOverrides,
                    enabled = settings.overriddenSettingKeys.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Reset all app overrides")
                }
            }
        }

        Section("Identity and build volume") {
            StringField("Printer name", settings.printerName, source(state, SlicerSettings.Keys.PRINTER_NAME)) {
                onSettings(SlicerSettings.Keys.PRINTER_NAME) { current -> current.copy(printerName = it.take(120)) }
            }
            NumberField("Build width X (mm)", settings.machineWidthMm, source(state, SlicerSettings.Keys.MACHINE_WIDTH)) {
                onSettings(SlicerSettings.Keys.MACHINE_WIDTH) { current -> current.copy(machineWidthMm = it.coerceIn(1.0, 2000.0)) }
            }
            NumberField("Build depth Y (mm)", settings.machineDepthMm, source(state, SlicerSettings.Keys.MACHINE_DEPTH)) {
                onSettings(SlicerSettings.Keys.MACHINE_DEPTH) { current -> current.copy(machineDepthMm = it.coerceIn(1.0, 2000.0)) }
            }
            NumberField("Build height Z (mm)", settings.machineHeightMm, source(state, SlicerSettings.Keys.MACHINE_HEIGHT)) {
                onSettings(SlicerSettings.Keys.MACHINE_HEIGHT) { current -> current.copy(machineHeightMm = it.coerceIn(1.0, 2000.0)) }
            }
            OptionField(
                label = "Build plate shape",
                value = settings.buildPlateShape,
                options = listOf("rectangular" to "Rectangular", "elliptic" to "Elliptic / round"),
                source = source(state, SlicerSettings.Keys.BUILD_PLATE_SHAPE),
            ) {
                onSettings(SlicerSettings.Keys.BUILD_PLATE_SHAPE) { current -> current.copy(buildPlateShape = it) }
            }
            SwitchRow("Origin is at bed center", settings.originAtCenter, source(state, SlicerSettings.Keys.ORIGIN_AT_CENTER)) {
                onSettings(SlicerSettings.Keys.ORIGIN_AT_CENTER) { current -> current.copy(originAtCenter = it) }
            }
            SwitchRow("Heated bed", settings.heatedBed, source(state, SlicerSettings.Keys.HEATED_BED)) {
                onSettings(SlicerSettings.Keys.HEATED_BED) { current -> current.copy(heatedBed = it) }
            }
            SwitchRow("Heated build chamber", settings.heatedBuildVolume, source(state, SlicerSettings.Keys.HEATED_BUILD_VOLUME)) {
                onSettings(SlicerSettings.Keys.HEATED_BUILD_VOLUME) { current -> current.copy(heatedBuildVolume = it) }
            }
        }

        Section("Extruder and firmware") {
            NumberField("Nozzle diameter (mm)", settings.nozzleSizeMm, source(state, SlicerSettings.Keys.NOZZLE_SIZE)) {
                onSettings(SlicerSettings.Keys.NOZZLE_SIZE) { current -> current.copy(nozzleSizeMm = it.coerceIn(0.05, 5.0)) }
            }
            NumberField("Filament diameter (mm)", settings.filamentDiameterMm, source(state, SlicerSettings.Keys.FILAMENT_DIAMETER)) {
                onSettings(SlicerSettings.Keys.FILAMENT_DIAMETER) { current -> current.copy(filamentDiameterMm = it.coerceIn(0.5, 5.0)) }
            }
            StringField(
                "Cura G-code flavor identifier",
                settings.gcodeFlavor,
                source(state, SlicerSettings.Keys.GCODE_FLAVOR),
            ) {
                onSettings(SlicerSettings.Keys.GCODE_FLAVOR) { current -> current.copy(gcodeFlavor = it.take(80)) }
            }
        }

        Section("Print-head clearance") {
            Text(
                "Used by Cura for machine-head geometry and sequential-print safety.",
                style = MaterialTheme.typography.bodySmall,
            )
            NumberField("Print head X minimum (mm)", settings.printheadXMinMm, source(state, SlicerSettings.Keys.PRINTHEAD_X_MIN)) {
                onSettings(SlicerSettings.Keys.PRINTHEAD_X_MIN) { current -> current.copy(printheadXMinMm = it.coerceIn(-1000.0, 1000.0)) }
            }
            NumberField("Print head Y minimum (mm)", settings.printheadYMinMm, source(state, SlicerSettings.Keys.PRINTHEAD_Y_MIN)) {
                onSettings(SlicerSettings.Keys.PRINTHEAD_Y_MIN) { current -> current.copy(printheadYMinMm = it.coerceIn(-1000.0, 1000.0)) }
            }
            NumberField("Print head X maximum (mm)", settings.printheadXMaxMm, source(state, SlicerSettings.Keys.PRINTHEAD_X_MAX)) {
                onSettings(SlicerSettings.Keys.PRINTHEAD_X_MAX) { current -> current.copy(printheadXMaxMm = it.coerceIn(-1000.0, 1000.0)) }
            }
            NumberField("Print head Y maximum (mm)", settings.printheadYMaxMm, source(state, SlicerSettings.Keys.PRINTHEAD_Y_MAX)) {
                onSettings(SlicerSettings.Keys.PRINTHEAD_Y_MAX) { current -> current.copy(printheadYMaxMm = it.coerceIn(-1000.0, 1000.0)) }
            }
            NumberField("Gantry height (mm)", settings.gantryHeightMm, source(state, SlicerSettings.Keys.GANTRY_HEIGHT)) {
                onSettings(SlicerSettings.Keys.GANTRY_HEIGHT) { current -> current.copy(gantryHeightMm = it.coerceIn(0.0, 2000.0)) }
            }
        }

        Section("Start G-code") {
            Text(
                "The imported or built-in start code remains active until this switch is enabled.",
                style = MaterialTheme.typography.bodySmall,
            )
            SwitchRow(
                "Use custom start G-code",
                settings.customStartGcodeEnabled,
                source(state, SlicerSettings.Keys.CUSTOM_START_GCODE_ENABLED),
            ) { enabled ->
                onSettings(SlicerSettings.Keys.CUSTOM_START_GCODE_ENABLED) { current ->
                    current.copy(
                        customStartGcodeEnabled = enabled,
                        customStartGcode = if (enabled && current.customStartGcode.isEmpty()) {
                            state.startGcode
                        } else {
                            current.customStartGcode
                        },
                    )
                }
            }
            if (settings.customStartGcodeEnabled) {
                GcodeField("Custom start G-code", settings.customStartGcode) {
                    onSettings(SlicerSettings.Keys.CUSTOM_START_GCODE) { current -> current.copy(customStartGcode = it) }
                }
                OutlinedButton(
                    onClick = {
                        onSettings(SlicerSettings.Keys.CUSTOM_START_GCODE) { current ->
                            current.copy(customStartGcode = state.startGcode)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Copy imported/default start G-code")
                }
            }
        }

        Section("End G-code") {
            Text(
                "The imported or built-in end code remains active until this switch is enabled.",
                style = MaterialTheme.typography.bodySmall,
            )
            SwitchRow(
                "Use custom end G-code",
                settings.customEndGcodeEnabled,
                source(state, SlicerSettings.Keys.CUSTOM_END_GCODE_ENABLED),
            ) { enabled ->
                onSettings(SlicerSettings.Keys.CUSTOM_END_GCODE_ENABLED) { current ->
                    current.copy(
                        customEndGcodeEnabled = enabled,
                        customEndGcode = if (enabled && current.customEndGcode.isEmpty()) {
                            state.endGcode
                        } else {
                            current.customEndGcode
                        },
                    )
                }
            }
            if (settings.customEndGcodeEnabled) {
                GcodeField("Custom end G-code", settings.customEndGcode) {
                    onSettings(SlicerSettings.Keys.CUSTOM_END_GCODE) { current -> current.copy(customEndGcode = it) }
                }
                OutlinedButton(
                    onClick = {
                        onSettings(SlicerSettings.Keys.CUSTOM_END_GCODE) { current ->
                            current.copy(customEndGcode = state.endGcode)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Copy imported/default end G-code")
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable Column.() -> Unit) {
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
    onValue: (Double) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString().trimEnd('0').trimEnd('.')) }
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
private fun StringField(
    label: String,
    value: String,
    source: String,
    onValue: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        supportingText = { SettingSource(source) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GcodeField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        minLines = 8,
        maxLines = 18,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth(),
    )
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
            Text(label, modifier = Modifier.weight(1f))
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
