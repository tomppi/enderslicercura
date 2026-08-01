package com.tomppi.enderslicer.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.profile.PresetKind
import com.tomppi.enderslicer.profile.PresetLibrary
import com.tomppi.enderslicer.profile.PresetSettings
import com.tomppi.enderslicer.profile.UserPreset
import com.tomppi.enderslicer.profile.UserPresetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PresetNameAction { CREATE, RENAME }

@Composable
fun ProfileManagementSheet(
    state: MainUiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember(context) { UserPresetStore(context) }
    val scope = rememberCoroutineScope()
    var library by remember { mutableStateOf(PresetLibrary()) }
    var kind by remember { mutableStateOf(PresetKind.PRINT) }
    var isLoading by remember { mutableStateOf(true) }
    var nameAction by remember { mutableStateOf<PresetNameAction?>(null) }
    var nameTarget by remember { mutableStateOf<UserPreset?>(null) }
    var nameText by remember { mutableStateOf("") }
    var pendingApply by remember { mutableStateOf<UserPreset?>(null) }
    var pendingDelete by remember { mutableStateOf<UserPreset?>(null) }

    fun showError(error: Throwable) {
        Toast.makeText(context, error.message ?: error::class.java.simpleName, Toast.LENGTH_LONG).show()
    }

    fun refresh() {
        scope.launch {
            isLoading = true
            runCatching { withContext(Dispatchers.IO) { store.load() } }
                .onSuccess { library = it }
                .onFailure(::showError)
            isLoading = false
        }
    }

    fun saveCurrentAs(name: String) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { store.create(kind, name, state.settings) }
            }.onSuccess {
                library = it
                nameAction = null
                Toast.makeText(context, "Saved ${kind.label.lowercase()} ‘${name.trim()}’", Toast.LENGTH_SHORT).show()
            }.onFailure(::showError)
        }
    }

    fun updatePreset(preset: UserPreset, thenApply: UserPreset? = null) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { store.update(preset.id, state.settings) }
            }.onSuccess { updatedLibrary ->
                library = updatedLibrary
                if (thenApply != null) {
                    val latestTarget = updatedLibrary.presets.firstOrNull { it.id == thenApply.id } ?: thenApply
                    if (viewModel.applyPreset(latestTarget.kind, latestTarget.valuesJson)) {
                        runCatching {
                            withContext(Dispatchers.IO) { store.setActive(latestTarget.kind, latestTarget.id) }
                        }.onSuccess { library = it }.onFailure(::showError)
                    }
                } else {
                    Toast.makeText(context, "Saved changes to ‘${preset.name}’", Toast.LENGTH_SHORT).show()
                }
            }.onFailure(::showError)
        }
    }

    fun applyPreset(preset: UserPreset) {
        if (!viewModel.applyPreset(preset.kind, preset.valuesJson)) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { store.setActive(preset.kind, preset.id) }
            }.onSuccess { library = it }.onFailure(::showError)
        }
    }

    fun renamePreset(preset: UserPreset, name: String) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { store.rename(preset.id, name) }
            }.onSuccess {
                library = it
                nameAction = null
                nameTarget = null
            }.onFailure(::showError)
        }
    }

    fun deletePreset(preset: UserPreset) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { store.delete(preset.id) }
            }.onSuccess {
                library = it
                pendingDelete = null
                Toast.makeText(context, "Deleted ‘${preset.name}’", Toast.LENGTH_SHORT).show()
            }.onFailure(::showError)
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val currentValues = remember(kind, state.settings) { PresetSettings.capture(kind, state.settings) }
    val active = library.active(kind)
    val activeDirty = active?.let { !PresetSettings.matchesValues(kind, currentValues, it.values()) } ?: true
    val presets = library.presets(kind)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text("Profiles & filament", style = MaterialTheme.typography.titleLarge)
        Text(
            "Save the current settings as named presets. Print profiles and filament profiles are independent, so switching filament will not replace quality, infill or support settings.",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetKind.entries.forEach { candidate ->
                if (candidate == kind) {
                    Button(onClick = { kind = candidate }, modifier = Modifier.weight(1f)) {
                        Text(candidate.pluralLabel)
                    }
                } else {
                    OutlinedButton(onClick = { kind = candidate }, modifier = Modifier.weight(1f)) {
                        Text(candidate.pluralLabel)
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Current ${kind.label.lowercase()}", style = MaterialTheme.typography.titleMedium)
                Text(active?.name ?: "Custom settings — not linked to a saved preset")
                if (active != null) {
                    Text(
                        if (activeDirty) "Current settings differ from the saved preset" else "Current settings match the saved preset",
                        color = if (activeDirty) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            nameAction = PresetNameAction.CREATE
                            nameTarget = null
                            nameText = ""
                        },
                        enabled = !state.isBusy,
                    ) {
                        Text("Save current as…")
                    }
                    OutlinedButton(
                        onClick = { active?.let { updatePreset(it) } },
                        enabled = active != null && activeDirty && !state.isBusy,
                    ) {
                        Text("Save changes")
                    }
                }
                Text(
                    when (kind) {
                        PresetKind.PRINT -> "Includes quality, walls, infill, speed, supports, travel, adhesion, arc-overhang and ironing settings."
                        PresetKind.FILAMENT -> "Includes diameter, temperatures, flow, cooling, retraction, Z-hop, firmware retraction and coasting."
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        HorizontalDivider()
        Text("Saved ${kind.pluralLabel.lowercase()}", style = MaterialTheme.typography.titleMedium)

        if (isLoading) {
            Text("Loading saved presets…", style = MaterialTheme.typography.bodySmall)
        } else if (presets.isEmpty()) {
            Text("No saved ${kind.pluralLabel.lowercase()} yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(presets, key = UserPreset::id) { preset ->
                    val isActive = preset.id == library.activeId(kind)
                    val dirty = isActive && !PresetSettings.matchesValues(kind, currentValues, preset.values())
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(preset.name, style = MaterialTheme.typography.titleSmall)
                                    if (isActive) {
                                        Text(
                                            if (dirty) "Active · modified" else "Active",
                                            color = if (dirty) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                                Button(
                                    onClick = {
                                        if (isActive && !dirty) return@Button
                                        if (activeDirty) pendingApply = preset else applyPreset(preset)
                                    },
                                    enabled = !state.isBusy,
                                ) {
                                    Text(if (isActive) "Reload" else "Apply")
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (isActive) {
                                    OutlinedButton(
                                        onClick = { updatePreset(preset) },
                                        enabled = dirty && !state.isBusy,
                                    ) {
                                        Text("Save changes")
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        nameAction = PresetNameAction.RENAME
                                        nameTarget = preset
                                        nameText = preset.name
                                    },
                                ) {
                                    Text("Rename")
                                }
                                TextButton(onClick = { pendingDelete = preset }) {
                                    Text("Delete")
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }

    if (nameAction != null) {
        AlertDialog(
            onDismissRequest = { nameAction = null; nameTarget = null },
            title = {
                Text(if (nameAction == PresetNameAction.CREATE) "Name the ${kind.label.lowercase()}" else "Rename preset")
            },
            text = {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it.take(60) },
                    label = { Text("Preset name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        when (nameAction) {
                            PresetNameAction.CREATE -> saveCurrentAs(nameText)
                            PresetNameAction.RENAME -> nameTarget?.let { renamePreset(it, nameText) }
                            null -> Unit
                        }
                    },
                    enabled = nameText.isNotBlank(),
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { nameAction = null; nameTarget = null }) { Text("Cancel") }
            },
        )
    }

    pendingApply?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingApply = null },
            title = { Text("Replace current ${kind.label.lowercase()} settings?") },
            text = {
                Text(
                    if (active != null && activeDirty) {
                        "Current changes to ‘${active.name}’ have not been saved. Applying ‘${target.name}’ will replace those ${kind.label.lowercase()} settings."
                    } else {
                        "Applying ‘${target.name}’ will replace the current ${kind.label.lowercase()} settings."
                    },
                )
            },
            confirmButton = {
                Button(onClick = { pendingApply = null; applyPreset(target) }) { Text("Discard & apply") }
            },
            dismissButton = {
                Row {
                    if (active != null && activeDirty) {
                        TextButton(
                            onClick = {
                                pendingApply = null
                                updatePreset(active, thenApply = target)
                            },
                        ) { Text("Save & apply") }
                    }
                    TextButton(onClick = { pendingApply = null }) { Text("Cancel") }
                }
            },
        )
    }

    pendingDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ‘${preset.name}’?") },
            text = {
                Text("The saved ${preset.kind.label.lowercase()} will be removed. Current slicer settings will not be changed.")
            },
            confirmButton = {
                Button(onClick = { deletePreset(preset) }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}
