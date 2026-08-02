package com.tomppi.enderslicer.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomppi.enderslicer.octoprint.OctoPrintViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegratedEnderSlicerApp(
    slicerViewModel: MainViewModel,
    octoPrintViewModel: OctoPrintViewModel,
) {
    val slicerState by slicerViewModel.uiState.collectAsStateWithLifecycle()
    val octoPrintState by octoPrintViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var octoPrintOpen by rememberSaveable { mutableStateOf(false) }

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
