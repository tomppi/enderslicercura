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
        val root = readDocument(file) ?: readDocument(backup) ?: return PresetLibrary()
        val presets = buildList {
            val array = root.optJSONArray(KEY_PRESETS) ?: JSONArray()
            for (index in 0 until minOf(array.length(), MAX_PRESETS_TOTAL)) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString(KEY_ID).takeIf(String::isNotBlank) ?: continue
                val name = item.optString(KEY_NAME).trim().takeIf(String::isNotBlank) ?: continue
                val kind = runCatching { PresetKind.valueOf(item.optString(KEY_KIND)) }.getOrNull() ?: continue
                val values = item.optJSONObject(KEY_VALUES) ?: continue
                if (runCatching { PresetSettings.validateUsable(kind, values) }.isFailure) continue
                add(
                    UserPreset(
                        id = id,
                        kind = kind,
                        name = name,
                        valuesJson = values.toString(),
                        createdAtEpochMillis = item.optLong(KEY_CREATED_AT, 0L).coerceAtLeast(0L),
                        updatedAtEpochMillis = item.optLong(KEY_UPDATED_AT, 0L).coerceAtLeast(0L),
                    ),
                )
            }
        }
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
        val root = JSONObject()
            .put(KEY_VERSION, FORMAT_VERSION)
            .put(KEY_ACTIVE_PRINT, library.activePrintPresetId ?: JSONObject.NULL)
            .put(KEY_ACTIVE_FILAMENT, library.activeFilamentPresetId ?: JSONObject.NULL)
        val presets = JSONArray()
        library.presets
            .sortedWith(
                compareBy<UserPreset> { it.kind.name }
                    .thenBy { it.name.lowercase(java.util.Locale.ROOT) },
            )
            .forEach { preset ->
                presets.put(
                    JSONObject()
                        .put(KEY_ID, preset.id)
                        .put(KEY_KIND, preset.kind.name)
                        .put(KEY_NAME, preset.name)
                        .put(KEY_VALUES, preset.values())
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
                check(file.renameTo(backup) || file.copyTo(backup, overwrite = true).let { file.delete(); true }) {
                    "Unable to preserve the previous preset library"
                }
            }
            try {
                check(temporary.renameTo(file) || temporary.copyTo(file, overwrite = true).let { temporary.delete(); true }) {
                    "Unable to save the preset library"
                }
            } catch (error: Throwable) {
                file.delete()
                if (backup.exists()) {
                    backup.renameTo(file) || backup.copyTo(file, overwrite = true).let { backup.delete(); true }
                }
                throw error
            }
            backup.delete()
        } finally {
            temporary.delete()
            if (backup.exists() && file.exists()) backup.delete()
        }
        return library
    }

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
        private const val MAX_PRESETS_TOTAL = MAX_PRESETS_PER_KIND * 2
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
