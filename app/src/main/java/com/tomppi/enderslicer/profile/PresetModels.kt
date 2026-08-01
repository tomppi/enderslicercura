package com.tomppi.enderslicer.profile

import org.json.JSONObject

enum class PresetKind(val label: String, val pluralLabel: String) {
    PRINT("Print profile", "Print profiles"),
    FILAMENT("Filament", "Filaments"),
}

data class UserPreset(
    val id: String,
    val kind: PresetKind,
    val name: String,
    val valuesJson: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    fun values(): JSONObject = JSONObject(valuesJson)
}

data class PresetLibrary(
    val presets: List<UserPreset> = emptyList(),
    val activePrintPresetId: String? = null,
    val activeFilamentPresetId: String? = null,
) {
    fun presets(kind: PresetKind): List<UserPreset> = presets
        .asSequence()
        .filter { it.kind == kind }
        .sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
        .toList()

    fun activeId(kind: PresetKind): String? = when (kind) {
        PresetKind.PRINT -> activePrintPresetId
        PresetKind.FILAMENT -> activeFilamentPresetId
    }

    fun active(kind: PresetKind): UserPreset? {
        val id = activeId(kind) ?: return null
        return presets.firstOrNull { it.id == id && it.kind == kind }
    }
}
