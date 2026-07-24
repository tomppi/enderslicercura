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
    var type by remember { mutableStateOf(CalibrationTestType.TEMPERATURE) }
    var start by remember(type) { mutableStateOf(format(type.defaultStart)) }
    var step by remember(type) { mutableStateOf(format(type.defaultStep)) }
    var levels by remember { mutableStateOf("8") }
    var sectionHeight by remember { mutableStateOf("8") }
    var width by remember { mutableStateOf("20") }
    var typeMenu by remember { mutableStateOf(false) }

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
            "Creates a printable stepped tower and automatically schedules the matching value change at every section.",
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
        NumberInput("Start value (${type.unit})", start) { start = it }
        NumberInput("Step per section (${type.unit})", step) { step = it }
        NumberInput("Number of sections", levels) { levels = it }
        NumberInput("Section height (mm)", sectionHeight) { sectionHeight = it }
        NumberInput("Tower width (mm)", width) { width = it }

        if (values.isNotEmpty()) {
            Text(
                "Bottom → top: ${values.joinToString { format(it) }} ${type.unit}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (type == CalibrationTestType.RETRACTION) {
            Text(
                "This tower changes M207 firmware-retraction distance. Firmware retraction will be enabled automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = { spec?.let(onGenerate) },
                enabled = spec != null && !isBusy,
            ) { Text("Generate tower") }
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
