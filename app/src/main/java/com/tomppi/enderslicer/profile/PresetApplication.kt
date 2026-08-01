package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerSettings
import org.json.JSONObject

internal object PresetApplication {
    data class Plan(
        val settings: SlicerSettings,
        val appliedKeys: LinkedHashSet<String>,
    )

    fun prepare(
        kind: PresetKind,
        current: SlicerSettings,
        valuesJson: String,
    ): Plan {
        val sanitized = PresetValueSanitizer.sanitize(kind, JSONObject(valuesJson))
        val appliedKeys = PresetSettings.keys(kind)
            .filterTo(linkedSetOf()) { key -> sanitized.has(key) && !sanitized.isNull(key) }
        require(appliedKeys.isNotEmpty()) { "The preset has no usable ${kind.label.lowercase()} values" }

        val changed = PresetSettings.apply(kind, current, sanitized)
        PresetValueSanitizer.validateMerged(kind, changed)
        return Plan(changed, appliedKeys)
    }
}
