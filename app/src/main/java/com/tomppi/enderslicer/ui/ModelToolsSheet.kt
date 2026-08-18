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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.profile.CuraComputedSettings
import com.tomppi.enderslicer.profile.CuraComputedSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ModelToolsSheet(
    state: MainUiState,
    onMove: (Double, Double, Double) -> Unit,
    onRotate: (ModelPlacement.Axis, Double) -> Unit,
    onScale: (Double) -> Unit,
    onDropToBed: () -> Unit,
    onLayFlat: () -> Unit,
    onReset: () -> Unit,
    onApplyImportedTransform: () -> Unit,
    onOpenSupportPaintUi: () -> Unit,
    onBrushRadius: (Double) -> Unit,
    onClearPaint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val placement = state.modelPlacement
    var xText by rememberSaveable(placement) { mutableStateOf(placement?.centerXmm?.formatPosition().orEmpty()) }
    var yText by rememberSaveable(placement) { mutableStateOf(placement?.centerYmm?.formatPosition().orEmpty()) }
    var zText by rememberSaveable(placement) { mutableStateOf(placement?.baseZmm?.formatPosition().orEmpty()) }
    var scaleText by rememberSaveable(placement) { mutableStateOf("100") }
    var brushText by rememberSaveable(state.supportPaint.brushRadiusMm) {
        mutableStateOf(state.supportPaint.brushRadiusMm.formatPosition())
    }
    val computedSnapshot by produceState<CuraComputedSnapshot?>(
        initialValue = null,
        state.engineProfile,
        state.settings,
        state.startGcode,
        state.endGcode,
    ) {
        val profile = state.engineProfile
        value = if (profile == null) {
            null
        } else {
            withContext(Dispatchers.Default) {
                runCatching {
                    CuraComputedSettings.resolve(
                        profile = profile,
                        printer = state.printer,
                        settings = state.settings,
                        startGcode = state.startGcode,
                        endGcode = state.endGcode,
                    )
                }.getOrNull()
            }
        }
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
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(axis.name, modifier = Modifier.padding(top = 12.dp))
                    listOf(-90.0, -5.0, 5.0, 90.0).forEach { amount ->
                        OutlinedButton(
                            onClick = { onRotate(axis, amount) },
                            modifier = Modifier.weight(1f),
                        ) { Text("${if (amount > 0) "+" else ""}${amount.toInt()}°") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(-1.0, 1.0).forEach { amount ->
                        OutlinedButton(
                            onClick = { onRotate(axis, amount) },
                            modifier = Modifier.weight(1f),
                        ) { Text("${if (amount > 0) "+" else ""}${amount.toInt()}°") }
                    }
                }
            }
        }

        HorizontalDivider()
        Text("Scale model", style = MaterialTheme.typography.titleMedium)
        Text(
            "Multiplies the current size around its position. 100% keeps the model unchanged; 200% doubles it.",
            style = MaterialTheme.typography.bodySmall,
        )
        listOf("50", "75", "125", "150").chunked(2).forEach { presets ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                presets.forEach { preset ->
                    OutlinedButton(
                        onClick = { scaleText = preset },
                        modifier = Modifier.weight(1f),
                    ) { Text("$preset%") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PositionField("Scale (%)", scaleText, { scaleText = it }, Modifier.weight(1f))
            Button(
                onClick = {
                    val percent = scaleText.toDoubleOrNull()
                    if (percent == null || !percent.isFinite() || percent < 1.0 || percent > 1000.0) {
                        return@Button
                    }
                    onScale(percent)
                },
                modifier = Modifier.weight(1f),
            ) { Text("Apply scale") }
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

        HorizontalDivider()
        Text("Support painting", style = MaterialTheme.typography.titleMedium)
        Text(
            "Set the brush radius and tap Apply brush to open the paint controls. Tap Draw or Block, then drag on the model to paint supports (green) or blockers (red); tap Erase to remove paint. Use two fingers to rotate and zoom the camera.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PositionField("Brush radius (mm)", brushText, { brushText = it }, Modifier.weight(1f))
            Button(
                onClick = {
                    brushText.toDoubleOrNull()?.let(onBrushRadius)
                    onOpenSupportPaintUi()
                },
                modifier = Modifier.weight(1f),
            ) { Text("Apply brush") }
        }
        if (!state.supportPaint.isEmpty) {
            Text(
                "${state.supportPaint.enforcerTriangles.size} support + ${state.supportPaint.blockerTriangles.size} block triangles painted",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onClearPaint, modifier = Modifier.fillMaxWidth()) {
            Text("Clear painted supports")
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

        computedSnapshot?.let { snapshot ->
            if (snapshot.values.isNotEmpty()) {
                HorizontalDivider()
                Text("Computed Cura values", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${snapshot.expressionCount} formulas resolved in ${snapshot.passes} passes. These values are read-only and recalculate when their source settings change.",
                    style = MaterialTheme.typography.bodySmall,
                )
                snapshot.values.forEach { computed ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${computed.label}: ${computed.value}", style = MaterialTheme.typography.bodyMedium)
                        Text(computed.key, style = MaterialTheme.typography.labelSmall)
                        Text(computed.source, style = MaterialTheme.typography.labelSmall)
                    }
                }
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
