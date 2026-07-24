package com.tomppi.enderslicer.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tomppi.enderslicer.model.withSettings
import com.tomppi.enderslicer.texturizer.BumpMeshActivity
import com.tomppi.enderslicer.viewer.ModelSurfaceView
import com.tomppi.enderslicer.viewer.StlMeshWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class ViewerMode { MODEL, LAYERS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnderSlicerApp(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var machineSettingsOpen by remember { mutableStateOf(false) }
    var modelToolsOpen by remember { mutableStateOf(false) }
    var calibrationOpen by remember { mutableStateOf(false) }
    var layerEventsOpen by remember { mutableStateOf(false) }
    var viewerMode by remember { mutableStateOf(ViewerMode.MODEL) }
    var selectedLayerIndex by remember { mutableStateOf(0) }

    LaunchedEffect(state.baseGcodePath) {
        val preview = state.layerPreview
        if (preview == null) {
            viewerMode = ViewerMode.MODEL
            selectedLayerIndex = 0
        } else {
            val firstSupport = preview.layers.indexOfFirst {
                it.supportSegmentCount > 0 || it.supportInterfaceSegmentCount > 0
            }
            selectedLayerIndex = if (firstSupport >= 0) firstSupport else 0
            viewerMode = ViewerMode.LAYERS
        }
    }

    val stlPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importStl)
    }
    val profilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importCuraProfile)
    }
    val projectPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importCuraProject)
    }
    val textureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let(viewModel::importStl)
        }
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
                title = { Text("enderslicercura") },
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
                                text = { Text("Texture model (BumpMesh)") },
                                onClick = {
                                    val mesh = state.mesh
                                    menuExpanded = false
                                    if (mesh != null) {
                                        scope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    val source = File(
                                                        context.cacheDir,
                                                        "bumpmesh-source/current-displayed.stl",
                                                    )
                                                    StlMeshWriter.writeBinary(mesh, source)
                                                    source
                                                }
                                            }.onSuccess { source ->
                                                textureLauncher.launch(
                                                    Intent(context, BumpMeshActivity::class.java)
                                                        .putExtra(BumpMeshActivity.EXTRA_MODEL_PATH, source.absolutePath)
                                                        .putExtra(BumpMeshActivity.EXTRA_MODEL_NAME, mesh.displayName),
                                                )
                                            }.onFailure { error ->
                                                Toast.makeText(
                                                    context,
                                                    error.message ?: "Unable to prepare the model for BumpMesh",
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                        }
                                    }
                                },
                                enabled = state.mesh != null && !state.isBusy,
                            )
                            DropdownMenuItem(
                                text = { Text("Model position & rotation") },
                                onClick = {
                                    menuExpanded = false
                                    modelToolsOpen = true
                                },
                                enabled = state.mesh != null && !state.isBusy,
                            )
                            DropdownMenuItem(
                                text = { Text("Calibration generator") },
                                onClick = {
                                    menuExpanded = false
                                    calibrationOpen = true
                                },
                                enabled = !state.isBusy,
                            )
                            DropdownMenuItem(
                                text = { Text("Print settings") },
                                onClick = {
                                    menuExpanded = false
                                    settingsOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Printer & G-code") },
                                onClick = {
                                    menuExpanded = false
                                    machineSettingsOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Export configuration snapshot") },
                                onClick = {
                                    menuExpanded = false
                                    configExportPicker.launch("printer-config.json")
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
                onExportGcode = { gcodeExportPicker.launch(GcodeExportName.suggest()) },
            )
        },
    ) { padding ->
        ViewerPanel(
            state = state,
            viewerMode = viewerMode,
            selectedLayerIndex = selectedLayerIndex,
            onViewerMode = { viewerMode = it },
            onLayerSelected = { selectedLayerIndex = it },
            onEditLayerEvents = { layerEventsOpen = true },
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
            CategorizedSettingsSheet(
                state = state,
                onSettings = viewModel::updateSettings,
                onResetOverrides = viewModel::resetAllSettingOverrides,
                modifier = Modifier
                    .fillMaxHeight(0.94f)
                    .navigationBarsPadding(),
            )
        }
    }

    if (machineSettingsOpen) {
        ModalBottomSheet(
            onDismissRequest = { machineSettingsOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            MachineSettingsSheet(
                state = state,
                onSettings = viewModel::updateSettings,
                onResetOverrides = viewModel::resetAllSettingOverrides,
                modifier = Modifier
                    .fillMaxHeight(0.94f)
                    .navigationBarsPadding(),
            )
        }
    }

    if (calibrationOpen) {
        ModalBottomSheet(
            onDismissRequest = { calibrationOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            CalibrationGeneratorSheet(
                isBusy = state.isBusy,
                onGenerate = { spec ->
                    calibrationOpen = false
                    viewModel.generateCalibrationTower(spec)
                },
                modifier = Modifier
                    .fillMaxHeight(0.94f)
                    .navigationBarsPadding(),
            )
        }
    }

    if (layerEventsOpen && state.layerPreview != null) {
        val preview = requireNotNull(state.layerPreview)
        val layer = preview.layers[selectedLayerIndex.coerceIn(preview.layers.indices)]
        ModalBottomSheet(
            onDismissRequest = { layerEventsOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            LayerEventsSheet(
                layer = layer,
                events = state.layerEvents,
                settings = state.settings,
                isBusy = state.isBusy,
                onAdd = { type, value, secondary, text ->
                    viewModel.addLayerEvent(layer.number, layer.z, type, value, secondary, text)
                },
                onRemove = viewModel::removeLayerEvent,
                onClearUserEvents = viewModel::clearLayerEvents,
                modifier = Modifier
                    .fillMaxHeight(0.94f)
                    .navigationBarsPadding(),
            )
        }
    }

    if (modelToolsOpen) {
        ModalBottomSheet(
            onDismissRequest = { modelToolsOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ModelToolsSheet(
                state = state,
                onMove = viewModel::moveModel,
                onRotate = viewModel::rotateModel,
                onDropToBed = viewModel::dropModelToBed,
                onLayFlat = viewModel::layModelFlat,
                onReset = viewModel::resetModelTransform,
                onApplyImportedTransform = viewModel::applyImportedSceneTransform,
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
    viewerMode: ViewerMode,
    selectedLayerIndex: Int,
    onViewerMode: (ViewerMode) -> Unit,
    onLayerSelected: (Int) -> Unit,
    onEditLayerEvents: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val effectivePrinter = state.printer.withSettings(state.settings)
    Box(modifier = modifier) {
        val preview = state.layerPreview
        if (viewerMode == ViewerMode.LAYERS && preview != null) {
            LayerPreviewView(
                preview = preview,
                selectedLayerIndex = selectedLayerIndex,
                events = state.layerEvents,
                onLayerSelected = onLayerSelected,
                onEditEvents = onEditLayerEvents,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            key(effectivePrinter) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context -> ModelSurfaceView(context, effectivePrinter) },
                    update = { view -> view.setMesh(state.mesh) },
                )
            }
        }

        Card(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .widthIn(max = 390.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text(effectivePrinter.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "%.0f × %.0f × %.0f mm · %.2f mm nozzle".format(
                        effectivePrinter.widthMm,
                        effectivePrinter.depthMm,
                        effectivePrinter.heightMm,
                        effectivePrinter.nozzleSizeMm,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
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
                    state.modelPlacement?.let { placement ->
                        Text(
                            "Center %.2f, %.2f · base Z %.2f mm".format(
                                placement.centerXmm,
                                placement.centerYmm,
                                placement.baseZmm,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(placement.source, style = MaterialTheme.typography.labelSmall)
                    }
                }
                state.calibrationDescription?.let { description ->
                    Text(description, style = MaterialTheme.typography.bodySmall)
                }
                state.estimatedPrintSeconds?.let { seconds ->
                    Text("Estimated print: ${formatEstimatedPrintTime(seconds)}", style = MaterialTheme.typography.bodySmall)
                }
                if (state.warnings.isNotEmpty()) {
                    Text("Cura compatibility warnings: ${state.warnings.size}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (preview != null) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (viewerMode == ViewerMode.MODEL) {
                        Button(onClick = { onViewerMode(ViewerMode.MODEL) }) { Text("Model") }
                        OutlinedButton(onClick = { onViewerMode(ViewerMode.LAYERS) }) { Text("Layers") }
                    } else {
                        OutlinedButton(onClick = { onViewerMode(ViewerMode.MODEL) }) { Text("Model") }
                        Button(onClick = { onViewerMode(ViewerMode.LAYERS) }) { Text("Layers") }
                    }
                }
            }
        }

        if (viewerMode == ViewerMode.MODEL) {
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
}

@Composable
private fun ActionBar(
    state: MainUiState,
    onSlice: () -> Unit,
    onExportGcode: () -> Unit,
) {
    Surface(tonalElevation = 4.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            state.estimatedPrintSeconds?.let { seconds ->
                Text(
                    "Estimated print time: ${formatEstimatedPrintTime(seconds)}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
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
}

private fun formatEstimatedPrintTime(totalSeconds: Int): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return buildString {
        if (days > 0) append("${days}d ")
        if (hours > 0 || days > 0) append("${hours}h ")
        append("${minutes}m")
    }.trim()
}
