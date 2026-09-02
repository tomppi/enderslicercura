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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomppi.enderslicer.octoprint.OctoPrintViewModel
import com.tomppi.enderslicer.smartinfill.SmartInfillActivity
import com.tomppi.enderslicer.smartinfill.SmartInfillPackage
import com.tomppi.enderslicer.smartinfill.SmartInfillPackageStore
import com.tomppi.enderslicer.smartinfill.SmartInfillRuntime
import com.tomppi.enderslicer.viewer.StlMeshWriter
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegratedEnderSlicerApp(
    slicerViewModel: MainViewModel,
    octoPrintViewModel: OctoPrintViewModel,
) {
    val slicerState by slicerViewModel.uiState.collectAsStateWithLifecycle()
    val octoPrintState by octoPrintViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val smartInfillStore = remember(context) { SmartInfillPackageStore(context.applicationContext) }
    var smartInfillPackage by remember { mutableStateOf(smartInfillStore.loadActive()) }
    val smartInfillLoadWarning = remember(smartInfillStore) { smartInfillStore.consumeLoadWarning() }
    var smartInfillImporting by remember { mutableStateOf(false) }
    var smartInfillValidating by remember { mutableStateOf(false) }
    var smartInfillOpen by rememberSaveable { mutableStateOf(false) }

    fun deleteHandoff(uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    LaunchedEffect(smartInfillLoadWarning) {
        smartInfillLoadWarning?.let { warning ->
            Toast.makeText(context, warning, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(smartInfillPackage) {
        SmartInfillRuntime.activate(smartInfillPackage)
    }

    // A null mesh is also the normal transient state while MainViewModel
    // restores the workspace after process recreation. Keep the persisted
    // package until a concrete mesh exists, then validate its exact digest.
    LaunchedEffect(slicerState.mesh, smartInfillPackage?.id) {
        val packageValue = smartInfillPackage ?: return@LaunchedEffect
        val mesh = slicerState.mesh ?: return@LaunchedEffect
        smartInfillValidating = true
        SmartInfillRuntime.activate(null)
        try {
            withContext(Dispatchers.IO) {
                val validationFile = File(
                    context.cacheDir,
                    "filasim-source/validation-${packageValue.id}-${UUID.randomUUID()}.stl",
                )
                validationFile.parentFile?.mkdirs()
                try {
                    StlMeshWriter.writeBinary(mesh, validationFile)
                    packageValue.requireMatchesSource(validationFile)
                } finally {
                    validationFile.delete()
                }
            }
            if (smartInfillPackage?.id == packageValue.id) SmartInfillRuntime.activate(packageValue)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // A canceled A validation must never clear a newer B package.
            if (SmartInfillRuntime.current()?.id == packageValue.id) {
                smartInfillStore.clearActive()
                SmartInfillRuntime.activate(null)
                smartInfillPackage = null
                Toast.makeText(
                    context,
                    "Smart Infill was cleared because the model geometry or placement changed",
                    Toast.LENGTH_LONG,
                ).show()
            }
        } finally {
            if (smartInfillPackage == null || smartInfillPackage?.id == packageValue.id) {
                smartInfillValidating = false
            }
        }
    }

    LaunchedEffect(octoPrintState.authorizationDialogLaunchNonce) {
        if (octoPrintState.authorizationDialogLaunchNonce == 0L) return@LaunchedEffect
        val url = octoPrintState.authorizationDialogUrl ?: return@LaunchedEffect
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure { error ->
            Toast.makeText(
                context,
                error.message ?: "Unable to open the OctoPrint authorization page",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun processSmartInfillResult(result: androidx.activity.result.ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK) return
        val data = result.data
        val exportUri = data?.data
        val resultKind = data?.getStringExtra(SmartInfillActivity.EXTRA_RESULT_KIND)
        if (exportUri == null || resultKind.isNullOrBlank()) {
            exportUri?.let(::deleteHandoff)
            Toast.makeText(context, "filaSim returned an incomplete export", Toast.LENGTH_LONG).show()
            return
        }
        if (slicerViewModel.uiState.value.isBusy || smartInfillImporting) {
            deleteHandoff(exportUri)
            Toast.makeText(
                context,
                "Smart Infill export was not applied because another operation is active",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        if (resultKind == SmartInfillActivity.RESULT_SHAPE) {
            scope.launch {
                val previousPath = slicerViewModel.uiState.value.modelPath
                slicerViewModel.importPartTopoResult(exportUri)
                val completed = slicerViewModel.awaitIdleIfBusy()
                val imported = completed.mesh != null && completed.modelPath != previousPath
                if (imported) {
                    val previousPackage = smartInfillPackage
                    smartInfillStore.clearActive()
                    SmartInfillRuntime.activate(null)
                    smartInfillPackage = null
                    smartInfillOpen = false
                    withContext(Dispatchers.IO) {
                        previousPackage?.directory?.deleteRecursively()
                    }
                    Toast.makeText(
                        context,
                        "Imported the filaSim Part Topo shape; inspect and slice it as a new model",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        "The filaSim Part Topo shape could not be imported; the previous model and Smart Infill package were kept",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                deleteHandoff(exportUri)
            }
            return
        }

        if (resultKind != SmartInfillActivity.RESULT_MODIFIERS) {
            deleteHandoff(exportUri)
            Toast.makeText(context, "filaSim returned an unknown export type", Toast.LENGTH_LONG).show()
            return
        }
        val metadata = data.getStringExtra(SmartInfillActivity.EXTRA_METADATA_JSON)
        val sourceSha = data.getStringExtra(SmartInfillActivity.EXTRA_SOURCE_SHA256)
        if (metadata.isNullOrBlank() || sourceSha.isNullOrBlank()) {
            deleteHandoff(exportUri)
            Toast.makeText(context, "filaSim returned incomplete Smart Infill metadata", Toast.LENGTH_LONG).show()
            return
        }
        val previousPackage = smartInfillPackage
        smartInfillImporting = true
        // Keep validation of the previous package from clearing the newly
        // published active-package pointer during the import handoff.
        SmartInfillRuntime.activate(null)
        scope.launch {
            try {
                val packageValue = withContext(Dispatchers.IO) {
                    smartInfillStore.importPackage(exportUri, metadata, sourceSha)
                }
                SmartInfillRuntime.activate(packageValue)
                smartInfillPackage = packageValue
                smartInfillOpen = true
                withContext(Dispatchers.IO) {
                    previousPackage
                        ?.takeIf { it.id != packageValue.id }
                        ?.directory
                        ?.deleteRecursively()
                }
                Toast.makeText(
                    context,
                    "Smart Infill enabled with ${packageValue.modifiers.size} density regions",
                    Toast.LENGTH_LONG,
                ).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                SmartInfillRuntime.activate(previousPackage)
                Toast.makeText(
                    context,
                    error.message ?: "Unable to import the filaSim modifier package",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                smartInfillImporting = false
                deleteHandoff(exportUri)
            }
        }
    }

    val smartInfillLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (slicerViewModel.deferUntilRestoreCompletes { processSmartInfillResult(result) }) {
            return@rememberLauncherForActivityResult
        }
        processSmartInfillResult(result)
    }

    fun clearBuildPlate() {
        if (slicerState.isBusy || smartInfillImporting) {
            Toast.makeText(context, "Finish the current operation first", Toast.LENGTH_SHORT).show()
            return
        }
        val packageToDelete = smartInfillPackage
        scope.launch {
            slicerViewModel.clearBuildPlate()
            val completed = slicerViewModel.awaitIdleIfBusy()
            val cleared = completed.mesh == null && completed.modelPath == null
            if (cleared) {
                smartInfillStore.clearActive()
                SmartInfillRuntime.activate(null)
                smartInfillPackage = null
                smartInfillOpen = false
                withContext(Dispatchers.IO) {
                    packageToDelete?.directory?.deleteRecursively()
                }
            } else {
                Toast.makeText(
                    context,
                    "The build plate could not be cleared; the model and Smart Infill package were kept",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    fun launchSmartInfill() {
        val mesh = slicerState.mesh
        if (mesh == null || slicerState.isBusy || smartInfillImporting) {
            Toast.makeText(context, "Import a model and finish the current operation first", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val source = File(context.cacheDir, "filasim-source/current-displayed.stl")
                    source.parentFile?.mkdirs()
                    StlMeshWriter.writeBinary(mesh, source)
                    source
                }
            }.onSuccess { source ->
                smartInfillLauncher.launch(
                    Intent(context, SmartInfillActivity::class.java)
                        .putExtra(SmartInfillActivity.EXTRA_MODEL_PATH, source.absolutePath)
                        .putExtra(SmartInfillActivity.EXTRA_MODEL_NAME, mesh.displayName),
                )
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    error.message ?: "Unable to prepare the model for filaSim",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    val smartSummary = smartInfillPackage?.summary
    val smartInfillMenuLabel = if (smartSummary == null) {
        "Smart Infill"
    } else {
        "Smart Infill ${smartSummary.baseDensityPercent.toInt()}→${smartSummary.modifierDensitiesPercent.maxOrNull() ?: smartSummary.baseDensityPercent.toInt()}%"
    }
    EnderSlicerApp(
        viewModel = slicerViewModel,
        sliceBlockedReason = when {
            smartInfillImporting -> "Smart Infill import is still being committed"
            smartInfillValidating -> "Smart Infill is being validated for the current model"
            else -> null
        },
        plateOverflowItems = { close ->
            DropdownMenuItem(
                text = { Text("Clear plate") },
                onClick = {
                    close()
                    clearBuildPlate()
                },
                enabled = !slicerState.isBusy && !smartInfillImporting && (
                    slicerState.mesh != null ||
                        slicerState.gcodePath != null ||
                        smartInfillPackage != null
                    ),
            )
        },
        moreExtraItems = {
            MoreRow(
                icon = AppIcons.Bolt,
                title = smartInfillMenuLabel,
                subtitle = if (smartSummary == null) {
                    "Load-optimized density modifiers via filaSim"
                } else {
                    "Base " + smartSummary.baseDensityPercent.toInt() + "% · " + smartSummary.mode + " " + smartSummary.pattern
                },
                enabled = slicerState.mesh != null && !slicerState.isBusy && !smartInfillImporting,
                badge = "EXP",
                onClick = {
                    if (smartInfillPackage == null) {
                        launchSmartInfill()
                    } else {
                        smartInfillOpen = true
                    }
                },
            )
        },
        printTabContent = {
            HardenedOctoPrintSheet(
                state = octoPrintState,
                localGcodePath = slicerState.gcodePath.takeIf {
                    !slicerState.isBusy && slicerState.hasCurrentGcode()
                },
                suggestedFileName = suggestedOctoPrintName(slicerState),
                viewModel = octoPrintViewModel,
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            )
        },
    )

    if (smartInfillOpen) {
        ModalBottomSheet(
            onDismissRequest = { smartInfillOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            SmartInfillSheet(
                packageValue = smartInfillPackage,
                enabled = !slicerState.isBusy && !smartInfillImporting,
                onGenerate = {
                    smartInfillOpen = false
                    launchSmartInfill()
                },
                onRemove = {
                    val packageToDelete = smartInfillPackage
                    smartInfillStore.clearActive()
                    SmartInfillRuntime.activate(null)
                    smartInfillPackage = null
                    smartInfillOpen = false
                    scope.launch(Dispatchers.IO) {
                        packageToDelete?.directory?.deleteRecursively()
                    }
                    Toast.makeText(context, "Smart Infill removed", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }

    if (smartInfillImporting) {
        Dialog(onDismissRequest = {}) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text("Importing and validating Smart Infill…")
                }
            }
        }
    }
}

@Composable
private fun SmartInfillSheet(
    packageValue: SmartInfillPackage?,
    enabled: Boolean,
    onGenerate: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Load-optimized Smart Infill")
        val summary = packageValue?.summary
        if (summary == null) {
            Text(
                "filaSim can create Cura density modifiers for graded/binary infill or return a Part Topo replacement shape.",
            )
            Button(onClick = onGenerate, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("Open filaSim")
            }
        } else {
            Text("${summary.sourceName} · ${summary.mode} ${summary.pattern}")
            Text(
                "Base ${"%.1f".format(summary.baseDensityPercent)}% · modifiers ${summary.modifierDensitiesPercent.joinToString { "$it%" }}",
            )
            Text(
                "${summary.perimeters} walls × ${"%.2f".format(summary.lineWidthMm)} mm · " +
                    "${summary.topBottomLayers} top/bottom layers · ${"%.2f".format(summary.layerHeightMm)} mm layers",
            )
            Text(
                "While Smart Infill is active, these filaSim print assumptions override the matching Cura settings so the sliced part matches the analysis.",
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onClick = onGenerate, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text("Regenerate")
                }
                OutlinedButton(onClick = onRemove, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text("Remove")
                }
            }
        }
    }
}

private fun suggestedOctoPrintName(state: MainUiState): String {
    val rawName = state.mesh?.displayName
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
    val source = rawName
        ?.let { name -> name.substringBeforeLast('.', name) }
        ?.takeIf(String::isNotBlank)
        ?: "enderslicercura"
    return "$source.gcode"
}

private suspend fun MainViewModel.awaitIdleIfBusy(): MainUiState {
    val started = uiState.value.isBusy
    return if (started) uiState.first { state -> !state.isBusy } else uiState.value
}
