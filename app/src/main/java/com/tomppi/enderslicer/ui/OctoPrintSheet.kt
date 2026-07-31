package com.tomppi.enderslicer.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.octoprint.OctoPrintFileEntry
import com.tomppi.enderslicer.octoprint.OctoPrintUiState
import com.tomppi.enderslicer.octoprint.OctoPrintUploadAction
import com.tomppi.enderslicer.octoprint.OctoPrintViewModel
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class OctoPrintPage(val label: String) {
    STATUS("Status"),
    FILES("Files"),
    CONTROL("Control"),
    SETUP("Setup"),
}

@Composable
fun OctoPrintSheet(
    state: OctoPrintUiState,
    localGcodePath: String?,
    suggestedFileName: String,
    viewModel: OctoPrintViewModel,
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableStateOf(if (state.isReady) OctoPrintPage.STATUS else OctoPrintPage.SETUP) }
    var pendingUploadPrintDirectory by remember { mutableStateOf<String?>(null) }
    var confirmStart by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<OctoPrintFileEntry?>(null) }
    var pendingPrint by remember { mutableStateOf<OctoPrintFileEntry?>(null) }

    DisposableEffect(Unit) {
        viewModel.setWebcamVisible(true)
        onDispose { viewModel.setWebcamVisible(false) }
    }

    LaunchedEffect(state.isReady) {
        if (!state.isReady) page = OctoPrintPage.SETUP
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(),
    ) {
        OctoPrintHeader(state = state, onRefresh = viewModel::refresh)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OctoPrintPage.entries.forEach { candidate ->
                if (candidate == page) {
                    Button(onClick = { page = candidate }, modifier = Modifier.weight(1f)) {
                        Text(candidate.label)
                    }
                } else {
                    OutlinedButton(onClick = { page = candidate }, modifier = Modifier.weight(1f)) {
                        Text(candidate.label)
                    }
                }
            }
        }
        HorizontalDivider()

        when (page) {
            OctoPrintPage.STATUS -> StatusPage(
                state = state,
                localGcodePath = localGcodePath,
                suggestedFileName = suggestedFileName,
                viewModel = viewModel,
                onConfirmUploadPrint = { pendingUploadPrintDirectory = it },
                onConfirmStart = { confirmStart = true },
                onConfirmRestart = { confirmRestart = true },
                onConfirmCancel = { confirmCancel = true },
                modifier = Modifier.weight(1f),
            )
            OctoPrintPage.FILES -> FilesPage(
                state = state,
                viewModel = viewModel,
                onDelete = { pendingDelete = it },
                onPrint = { pendingPrint = it },
                modifier = Modifier.weight(1f),
            )
            OctoPrintPage.CONTROL -> ControlPage(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.weight(1f),
            )
            OctoPrintPage.SETUP -> SetupPage(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.weight(1f),
            )
        }
    }

    pendingUploadPrintDirectory?.let { remoteDirectory ->
        ConfirmDialog(
            title = "Upload and start printing?",
            text = buildString {
                append("The current validated G-code will be uploaded to OctoPrint")
                if (remoteDirectory.isNotBlank()) append(" in folder ‘${remoteDirectory.trim('/')}’")
                append(" and printing will start immediately if the printer is operational.")
            },
            confirmLabel = "Upload & print",
            onDismiss = { pendingUploadPrintDirectory = null },
            onConfirm = {
                pendingUploadPrintDirectory = null
                viewModel.uploadGcode(
                    localGcodePath,
                    suggestedFileName,
                    remoteDirectory = remoteDirectory,
                    action = OctoPrintUploadAction.UPLOAD_AND_PRINT,
                )
            },
        )
    }

    if (confirmStart) {
        ConfirmDialog(
            title = "Start the selected print?",
            text = "OctoPrint will start the selected G-code immediately. Verify the printer, bed, filament and first layer.",
            confirmLabel = "Start print",
            onDismiss = { confirmStart = false },
            onConfirm = {
                confirmStart = false
                viewModel.startJob()
            },
        )
    }

    if (confirmRestart) {
        ConfirmDialog(
            title = "Restart the current print?",
            text = "OctoPrint will restart the selected job from the beginning.",
            confirmLabel = "Restart print",
            onDismiss = { confirmRestart = false },
            onConfirm = {
                confirmRestart = false
                viewModel.restartJob()
            },
        )
    }

    if (confirmCancel) {
        ConfirmDialog(
            title = "Cancel the active print?",
            text = "OctoPrint will stop the current print job. This cannot be resumed.",
            confirmLabel = "Cancel print",
            onDismiss = { confirmCancel = false },
            onConfirm = {
                confirmCancel = false
                viewModel.cancelJob()
            },
        )
    }

    pendingDelete?.let { entry ->
        ConfirmDialog(
            title = "Delete ${entry.name}?",
            text = if (entry.isFolder) {
                "The OctoPrint folder must be empty before it can be deleted."
            } else {
                "This removes the file from OctoPrint storage."
            },
            confirmLabel = "Delete",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                viewModel.deleteFile(entry.path)
            },
        )
    }

    pendingPrint?.let { entry ->
        ConfirmDialog(
            title = "Print ${entry.name}?",
            text = "OctoPrint will select this file and start printing immediately.",
            confirmLabel = "Start print",
            onDismiss = { pendingPrint = null },
            onConfirm = {
                pendingPrint = null
                viewModel.selectFile(entry.path, print = true)
            },
        )
    }
}

@Composable
private fun OctoPrintHeader(state: OctoPrintUiState, onRefresh: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.isRefreshing || state.isUploading || state.authorizationPending) {
                CircularProgressIndicator(modifier = Modifier.height(28.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.serverInfo.displayText ?: state.config.baseUrl.ifBlank { "OctoPrint" },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(state.statusMessage, style = MaterialTheme.typography.bodySmall)
                state.errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(onClick = onRefresh, enabled = state.isReady && !state.isRefreshing) {
                Text("Refresh")
            }
        }
        if (state.isUploading) {
            val progress = state.uploadProgress
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                buildString {
                    append(state.uploadFileName ?: "G-code")
                    progress?.let { append(" · ${(it * 100f).toInt()}%") }
                },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun StatusPage(
    state: OctoPrintUiState,
    localGcodePath: String?,
    suggestedFileName: String,
    viewModel: OctoPrintViewModel,
    onConfirmUploadPrint: (String) -> Unit,
    onConfirmStart: () -> Unit,
    onConfirmRestart: () -> Unit,
    onConfirmCancel: () -> Unit,
    modifier: Modifier,
) {
    var remoteDirectory by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!state.isReady) {
            Text("Configure OctoPrint on the Setup page.")
            return@Column
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Printer", style = MaterialTheme.typography.titleMedium)
                Text("${state.printer.text} · serial ${state.connection.state}")
                state.connection.port?.let { port ->
                    Text(
                        "$port · ${state.connection.baudrate ?: 0} baud · ${state.connection.printerProfile.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.printer.tools.forEach { (name, temperature) ->
                    TemperatureLine(name.uppercase(Locale.US), temperature.actual, temperature.target)
                }
                state.printer.bed?.let { TemperatureLine("BED", it.actual, it.target) }
                state.printer.chamber?.let { TemperatureLine("CHAMBER", it.actual, it.target) }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Current job", style = MaterialTheme.typography.titleMedium)
                Text(state.job.fileName ?: "No file selected")
                Text(state.job.state, style = MaterialTheme.typography.bodySmall)
                state.job.completionPercent?.let { completion ->
                    LinearProgressIndicator(
                        progress = { (completion / 100.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${"%.1f".format(completion)}%", style = MaterialTheme.typography.labelSmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Elapsed ${formatDuration(state.job.elapsedSeconds)}")
                    Text("Left ${formatDuration(state.job.remainingSeconds)}")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when {
                        state.isPrinting -> Button(onClick = viewModel::pauseJob, modifier = Modifier.weight(1f)) {
                            Text("Pause")
                        }
                        state.isPaused -> Button(onClick = viewModel::resumeJob, modifier = Modifier.weight(1f)) {
                            Text("Resume")
                        }
                        state.job.fileName != null -> Button(onClick = onConfirmStart, modifier = Modifier.weight(1f)) {
                            Text("Start")
                        }
                    }
                    if (state.isPrinting || state.isPaused) {
                        OutlinedButton(onClick = onConfirmCancel, modifier = Modifier.weight(1f)) {
                            Text("Cancel")
                        }
                    }
                    if (state.isPaused) {
                        OutlinedButton(onClick = onConfirmRestart, modifier = Modifier.weight(1f)) {
                            Text("Restart")
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Send current slice", style = MaterialTheme.typography.titleMedium)
                Text(suggestedFileName, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = remoteDirectory,
                    onValueChange = { remoteDirectory = it },
                    label = { Text("OctoPrint folder (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            viewModel.uploadGcode(
                                localGcodePath,
                                suggestedFileName,
                                remoteDirectory,
                                OctoPrintUploadAction.UPLOAD,
                            )
                        },
                        enabled = localGcodePath != null && !state.isUploading,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Upload")
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.uploadGcode(
                                localGcodePath,
                                suggestedFileName,
                                remoteDirectory,
                                OctoPrintUploadAction.UPLOAD_AND_SELECT,
                            )
                        },
                        enabled = localGcodePath != null && !state.isUploading,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Select")
                    }
                    Button(
                        onClick = { onConfirmUploadPrint(remoteDirectory) },
                        enabled = localGcodePath != null && !state.isUploading && !state.isPrinting,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Print")
                    }
                }
                Text(
                    "Printing always requires confirmation. Verify the printer, bed, filament and first layer before starting remotely.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        WebcamCard(state)
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun WebcamCard(state: OctoPrintUiState) {
    val bytes = state.webcamFrame
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = bytes) {
        value = withContext(Dispatchers.Default) {
            bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Webcam", style = MaterialTheme.typography.titleMedium)
            if (bitmap == null) {
                Text(
                    if (state.config.snapshotUrlOverride.isNotBlank() || state.webcam.snapshotUrl != null) {
                        "Waiting for a webcam snapshot…"
                    } else {
                        "No snapshot URL is available. Add one on the Setup page."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "OctoPrint webcam",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(230.dp)
                        .graphicsLayer(
                            scaleX = if (state.webcam.flipHorizontal) -1f else 1f,
                            scaleY = if (state.webcam.flipVertical) -1f else 1f,
                            rotationZ = if (state.webcam.rotate90) 90f else 0f,
                        ),
                )
            }
        }
    }
}

@Composable
private fun FilesPage(
    state: OctoPrintUiState,
    viewModel: OctoPrintViewModel,
    onDelete: (OctoPrintFileEntry) -> Unit,
    onPrint: (OctoPrintFileEntry) -> Unit,
    modifier: Modifier,
) {
    var filter by remember { mutableStateOf("") }
    var parentPath by remember { mutableStateOf("") }
    var folderName by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<OctoPrintFileEntry?>(null) }
    var destination by remember { mutableStateOf("") }
    val visibleFiles = state.files.filter {
        filter.isBlank() || it.name.contains(filter, ignoreCase = true) || it.path.contains(filter, ignoreCase = true)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                label = { Text("Filter files") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = viewModel::refreshFiles, enabled = state.isReady && !state.isFileListRefreshing) {
                Text("Reload")
            }
        }
        state.freeBytes?.let {
            Text("Free space: ${formatBytes(it)}", style = MaterialTheme.typography.bodySmall)
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            items(visibleFiles, key = { "${it.origin}:${it.path}" }) { entry ->
                FileRow(
                    entry = entry,
                    selected = selected?.path == entry.path,
                    onSelectRow = { selected = entry },
                    onSelect = { viewModel.selectFile(entry.path, print = false) },
                    onPrint = { onPrint(entry) },
                    onDelete = { onDelete(entry) },
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("File management", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = parentPath,
                        onValueChange = { parentPath = it },
                        label = { Text("Parent folder") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        label = { Text("New folder") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { viewModel.createFolder(parentPath, folderName) },
                        enabled = state.isReady && folderName.isNotBlank(),
                    ) {
                        Text("Create")
                    }
                }
                Text("Selected: ${selected?.path ?: "none"}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = destination,
                        onValueChange = { destination = it },
                        label = { Text("Destination folder") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { selected?.let { viewModel.copyFile(it.path, destination) } },
                        enabled = state.isReady && selected != null,
                    ) {
                        Text("Copy")
                    }
                    OutlinedButton(
                        onClick = { selected?.let { viewModel.moveFile(it.path, destination) } },
                        enabled = state.isReady && selected != null,
                    ) {
                        Text("Move")
                    }
                }
            }
        }
    }

    LaunchedEffect(state.isReady) {
        if (state.isReady && state.files.isEmpty()) viewModel.refreshFiles()
    }
}

@Composable
private fun FileRow(
    entry: OctoPrintFileEntry,
    selected: Boolean,
    onSelectRow: () -> Unit,
    onSelect: () -> Unit,
    onPrint: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(onClick = onSelectRow, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (entry.isFolder) "DIR" else "G", style = MaterialTheme.typography.labelMedium)
            Column(modifier = Modifier.weight(1f)) {
                Text("${"  ".repeat(entry.depth)}${entry.name}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    buildString {
                        append(entry.path)
                        entry.sizeBytes?.let { append(" · ${formatBytes(it)}") }
                        entry.estimatedPrintSeconds?.let { append(" · ${formatDuration(it)}") }
                        if (selected) append(" · selected")
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (!entry.isFolder) {
                TextButton(onClick = onSelect) {
                    Text("Select")
                }
                TextButton(onClick = onPrint) {
                    Text("Print")
                }
            }
            TextButton(onClick = onDelete) {
                Text("Delete")
            }
        }
    }
}

@Composable
private fun ControlPage(
    state: OctoPrintUiState,
    viewModel: OctoPrintViewModel,
    modifier: Modifier,
) {
    var port by remember { mutableStateOf("") }
    var baudrate by remember { mutableStateOf("") }
    var profile by remember { mutableStateOf("") }
    var saveConnection by remember { mutableStateOf(false) }
    var autoConnect by remember { mutableStateOf(false) }
    var autoConnectEdited by remember { mutableStateOf(false) }
    var jogStep by remember { mutableStateOf(1.0) }
    var toolTarget by remember { mutableStateOf("200") }
    var bedTarget by remember { mutableStateOf("60") }
    var extrusion by remember { mutableStateOf("5") }
    var feedRate by remember { mutableStateOf("100") }
    var flowRate by remember { mutableStateOf("100") }
    var command by remember { mutableStateOf("") }

    LaunchedEffect(state.connection) {
        if (port.isBlank()) port = state.connection.portPreference ?: state.connection.port.orEmpty()
        if (baudrate.isBlank()) {
            baudrate = (state.connection.baudratePreference ?: state.connection.baudrate)?.toString().orEmpty()
        }
        if (profile.isBlank()) {
            profile = state.connection.printerProfilePreference ?: state.connection.printerProfile.orEmpty()
        }
        if (!autoConnectEdited) autoConnect = state.connection.autoConnect
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Serial connection", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Available ports: ${state.connection.ports.joinToString().ifBlank { "not reported" }}",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "Available baud rates: ${state.connection.baudrates.joinToString().ifBlank { "not reported" }}",
                    style = MaterialTheme.typography.labelSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Port / AUTO") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = baudrate,
                        onValueChange = { baudrate = it.filter(Char::isDigit) },
                        label = { Text("Baud") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = profile,
                        onValueChange = { profile = it },
                        label = { Text("Profile") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = saveConnection, onCheckedChange = { saveConnection = it })
                    Text("Save settings")
                    Checkbox(checked = autoConnect, onCheckedChange = { autoConnect = it; autoConnectEdited = true })
                    Text("Auto-connect")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.connect(
                                port.takeIf(String::isNotBlank),
                                baudrate.toIntOrNull(),
                                profile.takeIf(String::isNotBlank),
                                saveConnection,
                                autoConnect,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Connect")
                    }
                    OutlinedButton(onClick = viewModel::disconnect, modifier = Modifier.weight(1f)) {
                        Text("Disconnect")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Motion", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.1, 1.0, 10.0).forEach { step ->
                        if (jogStep == step) {
                            Button(onClick = { jogStep = step }) {
                                Text("$step mm")
                            }
                        } else {
                            OutlinedButton(onClick = { jogStep = step }) {
                                Text("$step mm")
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { viewModel.jog(x = -jogStep) }) { Text("X−") }
                    OutlinedButton(onClick = { viewModel.jog(x = jogStep) }) { Text("X+") }
                    OutlinedButton(onClick = { viewModel.jog(y = -jogStep) }) { Text("Y−") }
                    OutlinedButton(onClick = { viewModel.jog(y = jogStep) }) { Text("Y+") }
                    OutlinedButton(onClick = { viewModel.jog(z = -jogStep) }) { Text("Z−") }
                    OutlinedButton(onClick = { viewModel.jog(z = jogStep) }) { Text("Z+") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { viewModel.home(setOf("x")) }) { Text("Home X") }
                    OutlinedButton(onClick = { viewModel.home(setOf("y")) }) { Text("Home Y") }
                    OutlinedButton(onClick = { viewModel.home(setOf("z")) }) { Text("Home Z") }
                    Button(onClick = { viewModel.home(setOf("x", "y", "z")) }) { Text("Home all") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Temperature and extrusion", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = toolTarget,
                        onValueChange = { toolTarget = it.filter { ch -> ch.isDigit() || ch == '-' } },
                        label = { Text("Tool 0 °C") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { toolTarget.toIntOrNull()?.let { viewModel.setToolTemperature("tool0", it) } }) {
                        Text("Set")
                    }
                    OutlinedButton(onClick = { viewModel.setToolTemperature("tool0", 0) }) {
                        Text("Off")
                    }
                    OutlinedTextField(
                        value = bedTarget,
                        onValueChange = { bedTarget = it.filter { ch -> ch.isDigit() || ch == '-' } },
                        label = { Text("Bed °C") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { bedTarget.toIntOrNull()?.let(viewModel::setBedTemperature) }) {
                        Text("Set")
                    }
                    OutlinedButton(onClick = { viewModel.setBedTemperature(0) }) {
                        Text("Off")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = extrusion,
                        onValueChange = { extrusion = it.filter { ch -> ch.isDigit() || ch == '.' || ch == '-' } },
                        label = { Text("Filament mm") },
                        singleLine = true,
                        modifier = Modifier.widthIn(max = 180.dp),
                    )
                    Button(onClick = { extrusion.toDoubleOrNull()?.let { viewModel.extrude(kotlin.math.abs(it)) } }) {
                        Text("Extrude")
                    }
                    OutlinedButton(onClick = { extrusion.toDoubleOrNull()?.let { viewModel.extrude(-kotlin.math.abs(it)) } }) {
                        Text("Retract")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Live overrides", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = feedRate,
                        onValueChange = { feedRate = it.filter(Char::isDigit) },
                        label = { Text("Feed %") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { feedRate.toIntOrNull()?.let(viewModel::setFeedRate) }) {
                        Text("Apply")
                    }
                    OutlinedTextField(
                        value = flowRate,
                        onValueChange = { flowRate = it.filter(Char::isDigit) },
                        label = { Text("Flow %") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { flowRate.toIntOrNull()?.let(viewModel::setFlowRate) }) {
                        Text("Apply")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Terminal", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = command,
                    onValueChange = { if ('\n' !in it && '\r' !in it) command = it.take(256) },
                    label = { Text("Single G-code command") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { viewModel.sendGcode(command) }, enabled = command.isNotBlank()) {
                    Text("Send command")
                }
                Text(
                    "Raw commands can move axes, heat the printer, alter EEPROM or stop a print. Send only commands you understand.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SetupPage(
    state: OctoPrintUiState,
    viewModel: OctoPrintViewModel,
    modifier: Modifier,
) {
    var baseUrl by remember { mutableStateOf(state.config.baseUrl) }
    var username by remember { mutableStateOf(state.config.username) }
    var apiKey by remember { mutableStateOf("") }
    var snapshotUrl by remember { mutableStateOf(state.config.snapshotUrlOverride) }
    var pollSeconds by remember { mutableStateOf(state.config.pollIntervalSeconds.toString()) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(state.config) {
        baseUrl = state.config.baseUrl
        username = state.config.username
        snapshotUrl = state.config.snapshotUrlOverride
        pollSeconds = state.config.pollIntervalSeconds.toString()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text("OctoPrint server", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Server URL or IP address") },
            placeholder = { Text("http://octopi.local") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("OctoPrint username (recommended)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it.trim() },
            label = { Text("User API key (manual fallback)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = snapshotUrl,
            onValueChange = { snapshotUrl = it },
            label = { Text("Webcam snapshot URL override (optional)") },
            placeholder = { Text("/webcam/?action=snapshot") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pollSeconds,
            onValueChange = { pollSeconds = it.filter(Char::isDigit).take(2) },
            label = { Text("Idle refresh interval, seconds") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(240.dp),
        )
        Text(
            "Use the Application Keys workflow when possible. It creates a revocable key specifically for enderslicercura. The key is encrypted with Android Keystore before it is stored.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (baseUrl.trim().startsWith("http://", ignoreCase = true) || (baseUrl.isNotBlank() && "://" !in baseUrl)) {
            Text(
                "HTTP is unencrypted. Use it only on a trusted local network, or configure HTTPS/reverse proxy access for OctoPrint.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { viewModel.testConnection(baseUrl, apiKey.takeIf(String::isNotBlank)) },
                enabled = baseUrl.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text("Test server")
            }
            Button(
                onClick = {
                    viewModel.beginApplicationAuthorization(
                        baseUrl,
                        username,
                        snapshotUrl,
                        pollSeconds.toIntOrNull() ?: 3,
                    )
                },
                enabled = baseUrl.isNotBlank() && !state.authorizationPending,
                modifier = Modifier.weight(1f),
            ) {
                Text("Authorize app")
            }
            OutlinedButton(
                onClick = {
                    viewModel.saveManualConfiguration(
                        baseUrl,
                        username,
                        apiKey,
                        snapshotUrl,
                        pollSeconds.toIntOrNull() ?: 3,
                    )
                },
                enabled = baseUrl.isNotBlank() && apiKey.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text("Save API key")
            }
        }
        if (state.authorizationPending) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.height(24.dp))
                Text("Waiting for approval in OctoPrint…")
                TextButton(onClick = viewModel::cancelAuthorization) {
                    Text("Cancel")
                }
            }
        }
        if (state.isReady) {
            HorizontalDivider()
            Text("Connected configuration", style = MaterialTheme.typography.titleMedium)
            Text("Server: ${state.config.baseUrl}")
            Text("User: ${state.serverInfo.userName ?: state.config.username.ifBlank { "unknown" }}")
            Text("Server version: ${state.serverInfo.serverVersion ?: "unknown"}")
            Text(
                "API permissions: ${state.serverInfo.permissions.sorted().joinToString().ifBlank { "not reported" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { viewModel.saveSnapshotOverride(snapshotUrl) }) {
                Text("Save webcam override")
            }
            OutlinedButton(onClick = { confirmClear = true }) {
                Text("Remove OctoPrint configuration")
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Remove OctoPrint configuration?",
            text = "The saved server details and encrypted API key will be deleted from this device.",
            confirmLabel = "Remove",
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                viewModel.clearConfiguration()
            },
        )
    }
}

@Composable
private fun TemperatureLine(label: String, actual: Double?, target: Double?) {
    Text(
        "$label ${actual?.let { "%.1f".format(it) } ?: "—"} °C / ${target?.let { "%.0f".format(it) } ?: "—"} °C",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Back")
            }
        },
    )
}

private fun formatDuration(seconds: Int?): String {
    if (seconds == null || seconds < 0) return "—"
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return buildString {
        if (days > 0) append("${days}d ")
        if (hours > 0 || days > 0) append("${hours}h ")
        append("${minutes}m")
    }.trim()
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GiB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}
