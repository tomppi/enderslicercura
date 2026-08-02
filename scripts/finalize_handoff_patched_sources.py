#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    content = path.read_text()
    count = content.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    path.write_text(content.replace(old, new, 1))


main = ROOT / "app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt"
runner = ROOT / "app/src/main/java/com/tomppi/enderslicer/engine/CuraEngineRunner.kt"

replace_once(
    main,
    """                sliceResultId = null,
                sliceResultId = null,
""",
    """                sliceResultId = null,
""",
    "duplicate restored result identity",
)

replace_once(
    runner,
    """            val validEvents = events
                .filter { it.layerNumber in layers }
                .distinctBy(LayerEvent::id)
                .sortedWith(compareBy(LayerEvent::layerNumber, LayerEvent::source, LayerEvent::id))
""",
    """            val validEvents = LayerEventOrdering.normalize(
                events.filter { it.layerNumber in layers },
            )
""",
    "stable reapply event ordering",
)

replace_once(
    main,
    """import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
""",
    """import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
""",
    "settings persistence Job import",
)

replace_once(
    main,
    """    private var plannedCalibrationEvents: List<PlannedLayerEvent> = emptyList()
    private val layerEventSequence = AtomicLong(0L)
""",
    """    private var plannedCalibrationEvents: List<PlannedLayerEvent> = emptyList()
    private var settingsPersistenceJob: Job? = null
    private val layerEventSequence = AtomicLong(0L)
""",
    "settings persistence job field",
)

replace_once(
    main,
    """    fun updateSettings(
        key: String,
        transform: (SlicerSettings) -> SlicerSettings,
    ) {
        if (_uiState.value.isBusy) return
        _uiState.update { current ->
            val changed = transform(current.settings).copy(
                overriddenSettingKeys = current.settings.overriddenSettingKeys + key,
            )
            persistSettings(changed)
            current.copy(
                settings = changed,
                sliceResultId = null,
                gcodePath = null,
                baseGcodePath = null,
                layerPreview = null,
                layerEvents = emptyList(),
                estimatedPrintSeconds = null,
                sliceLogPath = null,
                sliceDurationMilliseconds = null,
                statusMessage = "Settings changed; slice again to export G-code",
            )
        }
    }
""",
    """    fun updateSettings(
        key: String,
        transform: (SlicerSettings) -> SlicerSettings,
    ) {
        val current = _uiState.value
        if (current.isBusy) return
        val changed = transform(current.settings).copy(
            overriddenSettingKeys = current.settings.overriddenSettingKeys + key,
        )
        _uiState.update { state ->
            state.copy(
                settings = changed,
                sliceResultId = null,
                gcodePath = null,
                baseGcodePath = null,
                layerPreview = null,
                layerEvents = emptyList(),
                estimatedPrintSeconds = null,
                sliceLogPath = null,
                sliceDurationMilliseconds = null,
                statusMessage = "Settings changed; slice again to export G-code",
            )
        }
        persistSettings(changed)
    }
""",
    "side-effect-free settings state update",
)

replace_once(
    main,
    """    private fun persistSettings(settings: SlicerSettings) {
        viewModelScope.launch(Dispatchers.IO) {
            stateStore.saveSettings(settings)
            persistCurrentWorkspace(_uiState.value.copy(settings = settings))
        }
    }
""",
    """    private fun persistSettings(settings: SlicerSettings) {
        val stateSnapshot = _uiState.value.copy(settings = settings)
        val previousWrite = settingsPersistenceJob
        settingsPersistenceJob = viewModelScope.launch(Dispatchers.IO) {
            previousWrite?.join()
            stateStore.saveSettings(settings)
            persistCurrentWorkspace(stateSnapshot)
        }
    }
""",
    "serialized settings persistence",
)

replace_once(
    main,
    """    private suspend fun commitImportedConfig(pending: PendingImport) {
        runCatching {
            withContext(Dispatchers.IO) {
                stateStore.commitImport(pending.stagedFile, pending.kind, pending.displayName)
                stateStore.clearSavedSettings()
            }
""",
    """    private suspend fun commitImportedConfig(pending: PendingImport) {
        val pendingSettingsWrite = settingsPersistenceJob
        runCatching {
            withContext(Dispatchers.IO) {
                pendingSettingsWrite?.join()
                stateStore.commitImport(pending.stagedFile, pending.kind, pending.displayName)
                stateStore.clearSavedSettings()
            }
""",
    "import waits for older settings writes",
)

print("Finalized reviewed lifecycle sources")
