package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.profile.PresetKind
import com.tomppi.enderslicer.profile.PresetSettings
import org.json.JSONObject

internal fun MainViewModel.applyPreset(kind: PresetKind, valuesJson: String): Boolean {
    if (uiState.value.isBusy) return false
    return runCatching {
        val values = JSONObject(valuesJson)
        PresetSettings.validateUsable(kind, values)
        val before = uiState.value.settings
        val expected = PresetSettings.apply(kind, before, values)
        val markerKey = PresetSettings.keys(kind).first { key ->
            if (!values.has(key) || values.isNull(key)) return@first false
            runCatching {
                PresetSettings.apply(kind, before, JSONObject().put(key, values.get(key)))
            }.isSuccess
        }
        updateSettings(markerKey) { expected }
        check(uiState.value.settings == expected) {
            "The preset could not be applied while another operation was active"
        }
    }.isSuccess
}
