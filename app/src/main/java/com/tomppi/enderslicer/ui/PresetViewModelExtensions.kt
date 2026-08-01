package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.profile.PresetApplication
import com.tomppi.enderslicer.profile.PresetKind

internal fun MainViewModel.applyPreset(kind: PresetKind, valuesJson: String): Boolean {
    if (uiState.value.isBusy) return false

    val before = uiState.value.settings
    val plan = PresetApplication.prepare(kind, before, valuesJson)

    // MainViewModel.updateSettings owns persistence and stale-output
    // invalidation, but records one explicit override key per call. Register the
    // complete key set while values are still unchanged, then switch all values
    // in one final update. A process interruption can therefore leave the old
    // values marked as modified, never a partially applied preset.
    plan.appliedKeys.forEach { key ->
        updateSettings(key) { current -> current }
    }

    val markerKey = plan.appliedKeys.first()
    updateSettings(markerKey) { current ->
        check(current.copy(overriddenSettingKeys = before.overriddenSettingKeys) == before) {
            "Settings changed while the preset was being applied"
        }
        plan.settings
    }

    check(uiState.value.settings == plan.settings) {
        "The preset could not be applied while another operation was active"
    }
    return true
}
