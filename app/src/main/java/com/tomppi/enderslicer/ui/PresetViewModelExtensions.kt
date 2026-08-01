package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.profile.PresetApplication
import com.tomppi.enderslicer.profile.PresetKind

internal fun MainViewModel.applyPreset(kind: PresetKind, valuesJson: String): Boolean {
    if (uiState.value.isBusy) return false

    val before = uiState.value.settings
    val plan = PresetApplication.prepare(kind, before, valuesJson)

    // MainViewModel.updateSettings deliberately owns persistence and stale-output
    // invalidation, but it records one explicit override key per call. Apply the
    // validated values once, then synchronously register every key carried by the
    // preset so the complete selection survives app restart and Cura resolution.
    plan.appliedKeys.forEachIndexed { index, key ->
        updateSettings(key) { current ->
            if (index == 0) {
                check(current == before) { "Settings changed while the preset was being applied" }
                plan.settings
            } else {
                current
            }
        }
    }

    check(uiState.value.settings == plan.settings) {
        "The preset could not be applied while another operation was active"
    }
    return true
}
