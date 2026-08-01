package com.tomppi.enderslicer.profile

import java.util.Locale

internal object UserPresetLibraryNormalizer {
    fun normalize(
        library: PresetLibrary,
        maxPerKind: Int,
    ): PresetLibrary {
        require(maxPerKind > 0) { "Preset limit must be positive" }
        val normalizedPresets = normalizePresets(library.presets, maxPerKind)
        val activePrint = library.activePrintPresetId?.takeIf { id ->
            normalizedPresets.any { it.id == id && it.kind == PresetKind.PRINT }
        }
        val activeFilament = library.activeFilamentPresetId?.takeIf { id ->
            normalizedPresets.any { it.id == id && it.kind == PresetKind.FILAMENT }
        }
        return PresetLibrary(normalizedPresets, activePrint, activeFilament)
    }

    fun normalizePresets(
        presets: List<UserPreset>,
        maxPerKind: Int,
    ): List<UserPreset> {
        require(maxPerKind > 0) { "Preset limit must be positive" }
        val ranked = presets.sortedWith(
            compareByDescending<UserPreset> { it.updatedAtEpochMillis }
                .thenByDescending { it.createdAtEpochMillis }
                .thenBy { it.id },
        )
        val seenIds = hashSetOf<String>()
        val seenNames = hashSetOf<String>()
        val counts = PresetKind.entries.associateWith { 0 }.toMutableMap()
        val selected = ArrayList<UserPreset>(minOf(ranked.size, maxPerKind * PresetKind.entries.size))

        ranked.forEach { preset ->
            if (preset.id.isBlank() || preset.name.isBlank()) return@forEach
            if (preset.id in seenIds) return@forEach
            val nameKey = "${preset.kind.name}\u0000${preset.name.lowercase(Locale.ROOT)}"
            if (nameKey in seenNames) return@forEach
            if (counts.getValue(preset.kind) >= maxPerKind) return@forEach

            seenIds += preset.id
            seenNames += nameKey
            counts[preset.kind] = counts.getValue(preset.kind) + 1
            selected += preset
        }

        return selected.sortedWith(
            compareBy<UserPreset> { it.kind.name }
                .thenBy { it.name.lowercase(Locale.ROOT) }
                .thenBy { it.id },
        )
    }
}
