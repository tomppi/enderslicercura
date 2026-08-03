package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.nonplanar.NonPlanarSettings
import com.tomppi.enderslicer.nonplanar.NonPlanarSettingsStore

@Composable
internal fun NonPlanarSettingsSheet(
    initial: NonPlanarSettings,
    layerHeightMm: Double,
    nozzleDiameterMm: Double,
    onSave: (NonPlanarSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var settings by remember(initial) { mutableStateOf(initial.validated()) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Non Planar", style = MaterialTheme.typography.headlineSmall)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(NonPlanarSettingsStore.BACKEND_NAME, style = MaterialTheme.typography.titleMedium)
                Text("Ready · fully offline · Android ARM64", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "EnderSlicer flattens the displayed STL with a monotone, slope-limited CurviSlicer field, slices the flattened solid with CuraEngine, then restores continuously varying Z paths. Extrusion and Z speed are compensated before validation.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Active Cura layer height: %.3f mm · nozzle: %.2f mm".format(layerHeightMm, nozzleDiameterMm),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        SettingSwitch(
            title = "Enable CurviSlicer",
            description = "Generate curved layers for the next slice",
            checked = settings.enabled,
            onChecked = { settings = settings.copy(enabled = it) },
        )
        DecimalSettingField("Curvature strength (%)", settings.strengthPercent) {
            settings = settings.copy(strengthPercent = it)
        }
        DecimalSettingField("Surface smoothing radius (mm)", settings.smoothingRadiusMm) {
            settings = settings.copy(smoothingRadiusMm = it)
        }
        DecimalSettingField("Maximum path slope (degrees)", settings.maximumSlopeDegrees) {
            settings = settings.copy(maximumSlopeDegrees = it)
        }
        DecimalSettingField("Nozzle clearance angle (degrees)", settings.nozzleClearanceAngleDegrees) {
            settings = settings.copy(nozzleClearanceAngleDegrees = it)
        }
        DecimalSettingField("Nozzle clearance height (mm)", settings.nozzleClearanceHeightMm) {
            settings = settings.copy(nozzleClearanceHeightMm = it)
        }
        IntegerSettingField("Flat base layers", settings.flatBaseLayers) {
            settings = settings.copy(flatBaseLayers = it)
        }
        IntegerSettingField("Curvature field resolution", settings.fieldResolution) {
            settings = settings.copy(fieldResolution = it)
        }
        DecimalSettingField("Maximum generated move length (mm)", settings.maximumSegmentLengthMm) {
            settings = settings.copy(maximumSegmentLengthMm = it)
        }
        DecimalSettingField("Maximum Z speed (mm/s)", settings.maximumZSpeedMmPerSecond) {
            settings = settings.copy(maximumZSpeedMmPerSecond = it)
        }
        SettingSwitch(
            title = "Extrusion length compensation",
            description = "Scale positive extrusion for the true curved 3D path length",
            checked = settings.compensateExtrusion,
            onChecked = { settings = settings.copy(compensateExtrusion = it) },
        )
        SettingSwitch(
            title = "Warp Smart Infill modifiers",
            description = "Keep filaSim density regions aligned with the flattened model",
            checked = settings.warpSmartInfillModifiers,
            onChecked = { settings = settings.copy(warpSmartInfillModifiers = it) },
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Printer safety", style = MaterialTheme.typography.titleSmall)
                Text(
                    "The applied strength is automatically reduced if the requested field could cross layers or exceed the effective nozzle-clearance slope. Final G-code is rejected if it goes below the bed, above build height, uses unsupported fitted arcs, or exceeds the configured path budget.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Preview the complete Path view before printing. Physical fan ducts, probes and heater blocks can require a lower clearance angle than the bare nozzle.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Button(onClick = { onSave(settings.validated()) }, modifier = Modifier.fillMaxWidth()) {
            Text("Save non-planar options")
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(description, style = MaterialTheme.typography.labelSmall)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun DecimalSettingField(
    label: String,
    value: Double,
    onValue: (Double) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            text = next
            next.toDoubleOrNull()?.takeIf(Double::isFinite)?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun IntegerSettingField(
    label: String,
    value: Int,
    onValue: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            text = next
            next.toIntOrNull()?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
