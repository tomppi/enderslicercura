package com.tomppi.enderslicer.profile

import android.content.Context
import com.tomppi.enderslicer.model.SlicerSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class UserPresetStore(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "persistent-state").apply { mkdirs() }
    private val file = File(directory, "user-presets.json")
    private val backup = File(directory, "user-presets.previous.json")

    @Synchronized
    fun load(): PresetLibrary {
        recoverInterruptedWrite()
        val root = readDocument(file) ?: readDocument(backup) ?: return PresetLibrary()
        val candidates = buildList {
            val array = root.optJSONArray(KEY_PRESETS) ?: JSONArray()
            for (index in 0 until minOf(array.length(), MAX_RECORDS_TO_SCAN)) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString(KEY_ID).trim().takeIf {
                    it.isNotEmpty() && it.length <= MAX_ID_LENGTH && it.none(Char::isISOControl)
                } ?: continue
                val name = runCatching { validateName(item.optString(KEY_NAME)) }.getOrNull() ?: continue
                val kind = runCatching { PresetKind.valueOf(item.optString(KEY_KIND)) }.getOrNull() ?: continue
                val rawValues = item.optJSONObject(KEY_VALUES) ?: continue
                val values = runCatching { PresetValueSanitizer.sanitize(kind, rawValues) }.getOrNull() ?: continue
                val createdAt = item.optLong(KEY_CREATED_AT, 0L).coerceAtLeast(0L)
                val updatedAt = item.optLong(KEY_UPDATED_AT, createdAt).coerceAtLeast(createdAt)
                add(
                    UserPreset(
                        id = id,
                        kind = kind,
                        name = name,
                        valuesJson = values.toString(),
                        createdAtEpochMillis = createdAt,
                        updatedAtEpochMillis = updatedAt,
                    ),
                )
            }
        }
        val presets = UserPresetLibraryNormalizer.normalizePresets(candidates, MAX_PRESETS_PER_KIND)
        val activePrint = root.optString(KEY_ACTIVE_PRINT).takeIf(String::isNotBlank)
            ?.takeIf { id -> presets.any { it.id == id && it.kind == PresetKind.PRINT } }
        val activeFilament = root.optString(KEY_ACTIVE_FILAMENT).takeIf(String::isNotBlank)
            ?.takeIf { id -> presets.any { it.id == id && it.kind == PresetKind.FILAMENT } }
        return PresetLibrary(presets, activePrint, activeFilament)
    }

    @Synchronized
    fun create(kind: PresetKind, name: String, settings: SlicerSettings): PresetLibrary {
        val current = load()
        val cleanName = validateName(name)
        require(current.presets.count { it.kind == kind } < MAX_PRESETS_PER_KIND) {
            "A maximum of $MAX_PRESETS_PER_KIND ${kind.pluralLabel.lowercase()} can be saved"
        }
        require(current.presets.none { it.kind == kind && it.name.equals(cleanName, ignoreCase = true) }) {
            "A ${kind.label.lowercase()} named ‘$cleanName’ already exists"
        }
        val now = System.currentTimeMillis()
        val created = UserPreset(
            id = UUID.randomUUID().toString(),
            kind = kind,
            name = cleanName,
            valuesJson = PresetSettings.capture(kind, settings).toString(),
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        return save(current.withPreset(created).withActive(kind, created.id))
    }

    @Synchronized
    fun update(id: String, settings: SlicerSettings): PresetLibrary {
        val current = load()
        val existing = current.presets.firstOrNull { it.id == id } ?: error("The selected preset no longer exists")
        val updated = existing.copy(
            valuesJson = PresetSettings.capture(existing.kind, settings).toString(),
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
        return save(current.withPreset(updated))
    }

    @Synchronized
    fun rename(id: String, name: String): PresetLibrary {
        val current = load()
        val existing = current.presets.firstOrNull { it.id == id } ?: error("The selected preset no longer exists")
        val cleanName = validateName(name)
        require(
            current.presets.none {
                it.id != id && it.kind == existing.kind && it.name.equals(cleanName, ignoreCase = true)
            },
        ) { "A ${existing.kind.label.lowercase()} named ‘$cleanName’ already exists" }
        return save(
            current.withPreset(
                existing.copy(name = cleanName, updatedAtEpochMillis = System.currentTimeMillis()),
            ),
        )
    }

    @Synchronized
    fun delete(id: String): PresetLibrary {
        val current = load()
        require(current.presets.any { it.id == id }) { "The selected preset no longer exists" }
        return save(
            current.copy(
                presets = current.presets.filterNot { it.id == id },
                activePrintPresetId = current.activePrintPresetId.takeUnless { it == id },
                activeFilamentPresetId = current.activeFilamentPresetId.takeUnless { it == id },
            ),
        )
    }

    @Synchronized
    fun setActive(kind: PresetKind, id: String?): PresetLibrary {
        val current = load()
        if (id != null) {
            require(current.presets.any { it.id == id && it.kind == kind }) {
                "The selected ${kind.label.lowercase()} no longer exists"
            }
        }
        return save(current.withActive(kind, id))
    }

    @Synchronized
    fun clearActiveSelections(): PresetLibrary = save(
        load().copy(activePrintPresetId = null, activeFilamentPresetId = null),
    )

    private fun save(library: PresetLibrary): PresetLibrary {
        recoverInterruptedWrite()
        val normalized = UserPresetLibraryNormalizer.normalize(library, MAX_PRESETS_PER_KIND)
        val root = JSONObject()
            .put(KEY_VERSION, FORMAT_VERSION)
            .put(KEY_ACTIVE_PRINT, normalized.activePrintPresetId ?: JSONObject.NULL)
            .put(KEY_ACTIVE_FILAMENT, normalized.activeFilamentPresetId ?: JSONObject.NULL)
        val presets = JSONArray()
        normalized.presets.forEach { preset ->
            val values = PresetValueSanitizer.sanitize(preset.kind, preset.values())
            presets.put(
                JSONObject()
                    .put(KEY_ID, preset.id)
                    .put(KEY_KIND, preset.kind.name)
                    .put(KEY_NAME, preset.name)
                    .put(KEY_VALUES, values)
                    .put(KEY_CREATED_AT, preset.createdAtEpochMillis)
                    .put(KEY_UPDATED_AT, preset.updatedAtEpochMillis),
            )
        }
        root.put(KEY_PRESETS, presets)
        val encoded = root.toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_BYTES) {
            "Saved presets exceed the storage safety limit"
        }

        val temporary = File(directory, "user-presets.next.json")
        temporary.delete()
        temporary.writeText(encoded)
        check(temporary.isFile && temporary.length() > 0L) { "Unable to stage the preset library" }
        backup.delete()
        try {
            if (file.exists()) {
                check(file.renameTo(backup) || copyAndDelete(file, backup, overwrite = true)) {
                    "Unable to preserve the previous preset library"
                }
            }
            try {
                check(temporary.renameTo(file) || copyAndDelete(temporary, file, overwrite = true)) {
                    "Unable to save the preset library"
                }
            } catch (error: Throwable) {
                file.delete()
                if (backup.exists()) {
                    backup.renameTo(file) || copyAndDelete(backup, file, overwrite = true)
                }
                throw error
            }
            backup.delete()
        } finally {
            temporary.delete()
            if (backup.exists() && file.exists() && readDocument(file) != null) backup.delete()
        }
        return normalized
    }

    private fun recoverInterruptedWrite() {
        val primary = readDocument(file)
        val previous = readDocument(backup)
        when {
            primary != null -> if (backup.exists()) backup.delete()
            previous != null -> {
                file.delete()
                if (!backup.renameTo(file)) {
                    runCatching {
                        backup.copyTo(file, overwrite = true)
                        backup.delete()
                    }
                }
            }
        }
    }

    private fun copyAndDelete(source: File, destination: File, overwrite: Boolean): Boolean = runCatching {
        source.copyTo(destination, overwrite = overwrite)
        check(source.delete()) { "Unable to remove ${source.name} after copying" }
        true
    }.getOrDefault(false)

    private fun readDocument(source: File): JSONObject? {
        if (!source.isFile || source.length() <= 0L || source.length() > MAX_DOCUMENT_BYTES) return null
        return runCatching { JSONObject(source.readText()) }
            .getOrNull()
            ?.takeIf { it.optInt(KEY_VERSION, -1) == FORMAT_VERSION }
    }

    private fun validateName(value: String): String {
        val clean = value.trim().replace(Regex("\\s+"), " ")
        require(clean.isNotBlank()) { "Enter a preset name" }
        require(clean.length <= MAX_NAME_LENGTH) { "Preset names can contain at most $MAX_NAME_LENGTH characters" }
        require(clean.none(Char::isISOControl)) { "Preset names cannot contain control characters" }
        return clean
    }

    private fun PresetLibrary.withPreset(preset: UserPreset): PresetLibrary = copy(
        presets = presets.filterNot { it.id == preset.id } + preset,
    )

    private fun PresetLibrary.withActive(kind: PresetKind, id: String?): PresetLibrary = when (kind) {
        PresetKind.PRINT -> copy(activePrintPresetId = id)
        PresetKind.FILAMENT -> copy(activeFilamentPresetId = id)
    }

    companion object {
        private const val FORMAT_VERSION = 1
        private const val MAX_PRESETS_PER_KIND = 100
        private const val MAX_RECORDS_TO_SCAN = 1000
        private const val MAX_ID_LENGTH = 128
        private const val MAX_NAME_LENGTH = 60
        private const val MAX_DOCUMENT_BYTES = 2L * 1024L * 1024L
        private const val KEY_VERSION = "version"
        private const val KEY_PRESETS = "presets"
        private const val KEY_ID = "id"
        private const val KEY_KIND = "kind"
        private const val KEY_NAME = "name"
        private const val KEY_VALUES = "values"
        private const val KEY_CREATED_AT = "createdAt"
        private const val KEY_UPDATED_AT = "updatedAt"
        private const val KEY_ACTIVE_PRINT = "activePrintPresetId"
        private const val KEY_ACTIVE_FILAMENT = "activeFilamentPresetId"
    }
}
