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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomppi.enderslicer.octoprint.OctoPrintViewModel
import com.tomppi.enderslicer.smartinfill.SmartInfillActivity
import com.tomppi.enderslicer.smartinfill.SmartInfillPackage
import com.tomppi.enderslicer.smartinfill.SmartInfillPackageStore
import com.tomppi.enderslicer.smartinfill.SmartInfillRuntime
import com.tomppi.enderslicer.viewer.StlMeshWriter
import java.io.File
import kotlinx.coroutines.Dispatchers
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
    var octoPrintOpen by rememberSaveable { mutableStateOf(false) }
    var smartInfillOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(smartInfillPackage) {
        SmartInfillRuntime.activate(smartInfillPackage)
    }

    // The modifier field is valid only for the exact transformed STL filaSim
    // analyzed. Model import, rotation, movement, lay-flat, BumpMesh or a
    // calibration model all change this deterministic binary snapshot and
    // automatically invalidate the active package.
    LaunchedEffect(slicerState.mesh, smartInfillPackage?.id) {
        val packageValue = smartInfillPackage ?: return@LaunchedEffect
        val mesh = slicerState.mesh
        if (mesh == null) {
            smartInfillStore.clearActive()
            smartInfillPackage = null
            return@LaunchedEffect
        }
        runCatching {
            withContext(Dispatchers.IO) {
                val validationFile = File(context.cacheDir, "filasim-source/current-validation.stl")
                validationFile.parentFile?.mkdirs()
                StlMeshWriter.writeBinary(mesh, validationFile)
                packageValue.requireMatchesSource(validationFile)
            }
        }.onFailure {
            smartInfillStore.clearActive()
            smartInfillPackage = null
            Toast.makeText(
                context,
                "Smart Infill was cleared because the model geometry or placement changed",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    LaunchedEffect(octoPrintState.authorizationDialogLaunchNonce) {
        if (octoPrintState.authorizationDialogLaunchNonce == 0L) return@LaunchedEffect
        val url = octoPrintState.authorizationDialogUrl ?: return@LaunchedEffect
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onSuccess {
            octoPrintViewModel.acknowledgeAuthorizationDialog()
        }.onFailure { error ->
            Toast.makeText(
                context,
                error.message ?: "Unable to open the OctoPrint authorization page",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val smartInfillLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val data = result.data
        val archiveUri = data?.data
        val metadata = data?.getStringExtra(SmartInfillActivity.EXTRA_METADATA_JSON)
        val sourceSha = data?.getStringExtra(SmartInfillActivity.EXTRA_SOURCE_SHA256)
        if (archiveUri == null || metadata.isNullOrBlank() || sourceSha.isNullOrBlank()) {
            Toast.makeText(context, "filaSim returned an incomplete Smart Infill package", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    smartInfillStore.importPackage(archiveUri, metadata, sourceSha)
                }
            }.onSuccess { packageValue ->
                smartInfillPackage = packageValue
                smartInfillOpen = true
                Toast.makeText(
                    context,
                    "Smart Infill enabled with ${packageValue.modifiers.size} density regions",
                    Toast.LENGTH_LONG,
                ).show()
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    error.message ?: "Unable to import the filaSim modifier package",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    fun launchSmartInfill() {
        val mesh = slicerState.mesh
        if (mesh == null || slicerState.isBusy) {
            Toast.makeText(context, "Import a model before opening Smart Infill", Toast.LENGTH_SHORT).show()
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

    Box(modifier = Modifier.fillMaxSize()) {
        EnderSlicerApp(slicerViewModel)
        ExtendedFloatingActionButton(
            onClick = { octoPrintOpen = true },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp),
        ) {
            Text(
                when {
                    octoPrintState.isPrinting -> "OctoPrint ${octoPrintState.job.completionPercent?.toInt() ?: 0}%"
                    octoPrintState.isPaused -> "OctoPrint paused"
                    octoPrintState.isTransitioning -> "OctoPrint busy"
                    octoPrintState.isReady -> "OctoPrint"
                    else -> "Set up OctoPrint"
                },
            )
        }
        ExtendedFloatingActionButton(
            onClick = {
                if (smartInfillPackage == null) launchSmartInfill() else smartInfillOpen = true
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 72.dp, end = 12.dp),
        ) {
            val summary = smartInfillPackage?.summary
            Text(
                if (summary == null) {
                    "Smart Infill"
                } else {
                    "Smart ${summary.baseDensityPercent.toInt()}→${summary.modifierDensitiesPercent.maxOrNull() ?: summary.baseDensityPercent.toInt()}%"
                },
            )
        }
    }

    if (octoPrintOpen) {
        ModalBottomSheet(
            onDismissRequest = { octoPrintOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            HardenedOctoPrintSheet(
                state = octoPrintState,
                localGcodePath = slicerState.gcodePath.takeUnless { slicerState.isBusy },
                suggestedFileName = suggestedOctoPrintName(slicerState),
                viewModel = octoPrintViewModel,
                modifier = Modifier
                    .fillMaxHeight(0.96f)
                    .navigationBarsPadding(),
            )
        }
    }

    if (smartInfillOpen) {
        ModalBottomSheet(
            onDismissRequest = { smartInfillOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            SmartInfillSheet(
                packageValue = smartInfillPackage,
                onGenerate = {
                    smartInfillOpen = false
                    launchSmartInfill()
                },
                onRemove = {
                    smartInfillStore.clearActive()
                    smartInfillPackage = null
                    smartInfillOpen = false
                    Toast.makeText(context, "Smart Infill removed", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun SmartInfillSheet(
    packageValue: SmartInfillPackage?,
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
            Text("filaSim analyzes the current model and creates Cura modifier volumes with regional infill densities.")
            Button(onClick = onGenerate, modifier = Modifier.fillMaxWidth()) {
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
                Button(onClick = onGenerate, modifier = Modifier.weight(1f)) {
                    Text("Regenerate")
                }
                OutlinedButton(onClick = onRemove, modifier = Modifier.weight(1f)) {
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
