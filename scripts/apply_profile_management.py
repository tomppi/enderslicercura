from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {path}, found {count}: {old[:80]!r}")
    path.write_text(text.replace(old, new, 1))


main_view_model = Path("app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt")
main_text = main_view_model.read_text()
if "fun applyPreset(kind: PresetKind" not in main_text:
    replace_once(
        main_view_model,
        "import com.tomppi.enderslicer.profile.ImportedCuraConfig\n",
        "import com.tomppi.enderslicer.profile.ImportedCuraConfig\n"
        "import com.tomppi.enderslicer.profile.PresetKind\n"
        "import com.tomppi.enderslicer.profile.PresetSettings\n"
        "import com.tomppi.enderslicer.profile.UserPresetStore\n",
    )
    replace_once(
        main_view_model,
        "    private val stateStore = AppStateStore(app)\n",
        "    private val stateStore = AppStateStore(app)\n"
        "    private val presetStore = UserPresetStore(app)\n",
    )
    replace_once(
        main_view_model,
        "\n    fun resetAllSettingOverrides() {\n",
        """
    fun applyPreset(kind: PresetKind, valuesJson: String): Boolean {
        if (_uiState.value.isBusy) return false
        return runCatching {
            val values = JSONObject(valuesJson)
            PresetSettings.validateComplete(kind, values)
            val changed = PresetSettings.apply(kind, _uiState.value.settings, values)
            stateStore.saveSettings(changed)
            _uiState.update { current ->
                current.copy(
                    settings = changed,
                    gcodePath = null,
                    baseGcodePath = null,
                    layerPreview = null,
                    layerEvents = emptyList(),
                    estimatedPrintSeconds = null,
                    sliceLogPath = null,
                    sliceDurationMilliseconds = null,
                    statusMessage = "Applied ${kind.label.lowercase()}; slice again to export G-code",
                )
            }
        }.onFailure(::showOperationFailure).isSuccess
    }

    fun resetAllSettingOverrides() {
""",
    )
    replace_once(
        main_view_model,
        """    fun resetAllSettingOverrides() {
        if (_uiState.value.isBusy) return
        val baseline = importedSettingsBaseline ?: SlicerSettings()
""",
        """    fun resetAllSettingOverrides() {
        if (_uiState.value.isBusy) return
        presetStore.clearActiveSelections()
        val baseline = importedSettingsBaseline ?: SlicerSettings()
""",
    )
    replace_once(
        main_view_model,
        """            stateStore.commitImport(pending.stagedFile, pending.kind, pending.displayName)
            stateStore.clearSavedSettings()
""",
        """            stateStore.commitImport(pending.stagedFile, pending.kind, pending.displayName)
            stateStore.clearSavedSettings()
            presetStore.clearActiveSelections()
""",
    )

app = Path("app/src/main/java/com/tomppi/enderslicer/ui/EnderSlicerApp.kt")
app_text = app.read_text()
if "var profilesOpen by remember" not in app_text:
    replace_once(
        app,
        "    var settingsOpen by remember { mutableStateOf(false) }\n",
        "    var settingsOpen by remember { mutableStateOf(false) }\n"
        "    var profilesOpen by remember { mutableStateOf(false) }\n",
    )
    replace_once(
        app,
        """                            DropdownMenuItem(
                                text = { Text("Print settings") },
""",
        """                            DropdownMenuItem(
                                text = { Text("Profiles & filament") },
                                onClick = {
                                    menuExpanded = false
                                    profilesOpen = true
                                },
                                enabled = !state.isBusy,
                            )
                            DropdownMenuItem(
                                text = { Text("Print settings") },
""",
    )
    replace_once(
        app,
        "\n    if (settingsOpen) {\n",
        """
    if (profilesOpen) {
        ModalBottomSheet(
            onDismissRequest = { profilesOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ProfileManagementSheet(
                state = state,
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxHeight(0.94f)
                    .navigationBarsPadding(),
            )
        }
    }

    if (settingsOpen) {
""",
    )

print("Profile and filament management integration patch is present")
