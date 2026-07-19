package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.model.ModelPlacement

@Composable
fun ModelToolsSheet(
    state: MainUiState,
    onMove: (Double, Double, Double) -> Unit,
    onRotate: (ModelPlacement.Axis, Double) -> Unit,
    onDropToBed: () -> Unit,
    onLayFlat: () -> Unit,
    onReset: () -> Unit,
    onApplyImportedTransform: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val placement = state.modelPlacement
    var xText by remember { mutableStateOf(placement?.centerXmm?.formatPosition().orEmpty()) }
    var yText by remember { mutableStateOf(placement?.centerYmm?.formatPosition().orEmpty()) }
    var zText by remember { mutableStateOf(placement?.baseZmm?.formatPosition().orEmpty()) }

    LaunchedEffect(placement) {
        xText = placement?.centerXmm?.formatPosition().orEmpty()
        yText = placement?.centerYmm?.formatPosition().orEmpty()
        zText = placement?.baseZmm?.formatPosition().orEmpty()
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Model position & rotation", style = MaterialTheme.typography.headlineSmall)
        if (placement == null || state.mesh == null) {
            Text("Import an STL before changing model placement.")
            return@Column
        }

        Text(placement.source, style = MaterialTheme.typography.bodyMedium)
        Text(
            "X and Y are the model bounds center. Z is the lowest point of the transformed model.",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PositionField("Center X (mm)", xText, { xText = it }, Modifier.weight(1f))
            PositionField("Center Y (mm)", yText, { yText = it }, Modifier.weight(1f))
            PositionField("Base Z (mm)", zText, { zText = it }, Modifier.weight(1f))
        }
        Button(
            onClick = {
                val x = xText.toDoubleOrNull() ?: return@Button
                val y = yText.toDoubleOrNull() ?: return@Button
                val z = zText.toDoubleOrNull() ?: return@Button
                onMove(x, y, z)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Apply position") }

        HorizontalDivider()
        Text("Rotate model", style = MaterialTheme.typography.titleMedium)
        ModelPlacement.Axis.entries.forEach { axis ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Text(axis.name, modifier = Modifier.padding(top = 12.dp))
                listOf(-90.0, -5.0, 5.0, 90.0).forEach { amount ->
                    OutlinedButton(
                        onClick = { onRotate(axis, amount) },
                        modifier = Modifier.weight(1f),
                    ) { Text("${if (amount > 0) "+" else ""}${amount.toInt()}°") }
                }
            }
        }

        HorizontalDivider()
        Button(onClick = onLayFlat, modifier = Modifier.fillMaxWidth()) {
            Text("Lay flat on largest face")
        }
        OutlinedButton(onClick = onDropToBed, modifier = Modifier.fillMaxWidth()) {
            Text("Drop to build plate")
        }
        OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text("Reset and center model")
        }

        if (state.importedSceneTransformAvailable) {
            HorizontalDivider()
            Text(
                "Imported Cura scene transform${state.importedSceneModelName?.let { " for $it" }.orEmpty()}",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(onClick = onApplyImportedTransform, modifier = Modifier.fillMaxWidth()) {
                Text("Apply imported Cura transform")
            }
        }

        if (state.warnings.isNotEmpty()) {
            HorizontalDivider()
            Text("Cura compatibility audit", style = MaterialTheme.typography.titleMedium)
            state.warnings.forEach { warning ->
                Text("• $warning", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PositionField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { candidate ->
            if (candidate.length <= 16 && candidate.all { it.isDigit() || it in ".-+" }) {
                onValueChange(candidate)
            }
        },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}

private fun Double.formatPosition(): String = "%.3f".format(this).trimEnd('0').trimEnd('.')
