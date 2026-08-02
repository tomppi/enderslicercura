package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.calibration.CalibrationTestType
import com.tomppi.enderslicer.calibration.CalibrationTowerSpec
import java.util.Locale

@Composable
internal fun CalibrationGeneratorSheet(
    isBusy: Boolean,
    onGenerate: (CalibrationTowerSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    var type by rememberSaveable { mutableStateOf(CalibrationTestType.TEMPERATURE) }
    var start by rememberSaveable(type) { mutableStateOf(format(type.defaultStart)) }
    var step by rememberSaveable(type) { mutableStateOf(format(type.defaultStep)) }
    var levels by rememberSaveable(type) { mutableStateOf(type.defaultLevels.toString()) }
    var sectionHeight by rememberSaveable { mutableStateOf("4") }
    var width by rememberSaveable { mutableStateOf("16") }
    var typeMenu by rememberSaveable { mutableStateOf(false) }

    val spec = runCatching {
        CalibrationTowerSpec(
            type = type,
            startValue = start.toDoubleInput(),
            stepValue = step.toDoubleInput(),
            levels = levels.toInt(),
            sectionHeightMm = sectionHeight.toDoubleInput(),
            towerWidthMm = width.toDoubleInput(),
        )
    }.getOrNull()
    val values = spec?.let { value -> List(value.levels.coerceIn(0, 20)) { value.startValue + it * value.stepValue } }.orEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Calibration generator", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Creates a compact purpose-built, support-free calibration model and automatically schedules the matching value change at every section.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(type.displayName)
            }
            DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                CalibrationTestType.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.displayName) },
                        onClick = {
                            type = option
                            typeMenu = false
                        },
                    )
                }
            }
        }
        Text(
            type.designDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Calibration slices keep normal profile behavior unless it directly invalidates the selected test. Support, adaptive layers, arc overhangs and ironing are disabled; speed-sensitive tests also bypass minimum-layer-time slowdown.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        NumberInput("Start value (${type.unit})", start) { start = it }
        NumberInput("Step per section (${type.unit})", step) { step = it }
        NumberInput("Number of sections", levels) { levels = it }
        NumberInput("Section height (mm)", sectionHeight) { sectionHeight = it }
        NumberInput("Model width (mm)", width) { width = it }

        if (values.isNotEmpty()) {
            Text(
                "Bottom → top: ${values.joinToString { format(it) }} ${type.unit}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (type) {
            CalibrationTestType.RETRACTION -> Text(
                "This model changes M207 firmware-retraction distance. Firmware retraction is enabled only for this slice, while your normal cooling, coasting, combing, wipe, hop and travel settings remain active.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CalibrationTestType.PRESSURE_ADVANCE -> Text(
                "This model changes Marlin Linear Advance with M900 K. The firmware must be built with LIN_ADVANCE or FT_MOTION support. The first K value is restored after the print.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CalibrationTestType.JUNCTION_DEVIATION -> Text(
                "This model changes Marlin junction deviation with M205 J. It only applies when the firmware uses junction deviation instead of CLASSIC_JERK. The first value is restored after the print.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Unit
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = { spec?.let(onGenerate) },
                enabled = spec != null && !isBusy,
            ) { Text("Generate model") }
        }
    }
}

@Composable
private fun NumberInput(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun String.toDoubleInput(): Double = replace(',', '.').toDouble()
private fun format(value: Double): String = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
