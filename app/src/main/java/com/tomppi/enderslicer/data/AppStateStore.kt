package com.tomppi.enderslicer.data

import android.content.Context
import com.tomppi.enderslicer.model.PrusaSliceSettings
import com.tomppi.enderslicer.model.SlicerSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

class AppStateStore(context: Context) {
    data class SavedImport(
        val kind: String,
        val displayName: String,
        val file: File,
    )

    data class SnapshotBaseline(
        val settings: SlicerSettings,
        val startGcode: String,
        val endGcode: String,
        val profileName: String,
        val profileSource: String,
    )

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val stateDirectory = File(appContext.filesDir, "persistent-state").apply { mkdirs() }
    private val legacyImportFile = File(stateDirectory, "current-cura-import.bin")
    private val importBundle = File(stateDirectory, "current-cura-import.bundle")
    private val materializedImport = File(stateDirectory, "current-cura-import.materialized")

    fun stageImport(input: InputStream): File {
        val temporary = File(stateDirectory, "current-cura-import.tmp")
        temporary.delete()
        try {
            input.buffered().use { source ->
                temporary.outputStream().buffered().use { destination ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_CURA_IMPORT_BYTES) {
                            "The imported Cura file exceeds the 128 MiB safety limit"
                        }
                        destination.write(buffer, 0, count)
                    }
                }
            }
            check(temporary.isFile && temporary.length() > 0L) { "The imported Cura file is empty" }
            return temporary
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    @Synchronized
    fun commitImport(staged: File, kind: String, displayName: String) {
        require(kind == KIND_PROJECT || kind == KIND_PROFILE) { "Unsupported Cura import kind: $kind" }
        require(staged.isFile && staged.length() in 1..MAX_CURA_IMPORT_BYTES) {
            "The staged Cura configuration is unavailable"
        }
        val next = File(stateDirectory, "current-cura-import.bundle.next")
        val backup = File(stateDirectory, "current-cura-import.bundle.previous")
        next.delete()
        backup.delete()
        val payloadSha = sha256(staged)
        val metadata = JSONObject()
            .put("version", IMPORT_BUNDLE_VERSION)
            .put("kind", kind)
            .put("displayName", displayName.take(MAX_IMPORT_NAME_CHARS))
            .put("payloadBytes", staged.length())
            .put("payloadSha256", payloadSha)
            .toString()
            .toByteArray(Charsets.UTF_8)
        require(metadata.size <= MAX_IMPORT_METADATA_BYTES) { "Cura import metadata is too large" }

        try {
            FileOutputStream(next).use { fileOutput ->
                val output = DataOutputStream(BufferedOutputStream(fileOutput))
                output.write(IMPORT_BUNDLE_MAGIC)
                output.writeInt(metadata.size)
                output.writeLong(staged.length())
                output.write(metadata)
                staged.inputStream().buffered().use { input -> input.copyTo(output) }
                output.flush()
                fileOutput.fd.sync()
            }
            check(next.isFile && next.length() > staged.length()) { "Unable to stage the Cura import bundle" }
            if (importBundle.exists()) {
                check(importBundle.renameTo(backup)) { "Unable to preserve the previous Cura import bundle" }
            }
            try {
                check(next.renameTo(importBundle)) { "Unable to publish the Cura import bundle" }
            } catch (error: Throwable) {
                importBundle.delete()
                if (backup.exists()) backup.renameTo(importBundle)
                throw error
            }
            backup.delete()
            materializedImport.delete()
            legacyImportFile.delete()
            staged.delete()
            preferences.edit().remove(KEY_IMPORT_KIND).remove(KEY_IMPORT_NAME).commit()
        } finally {
            next.delete()
            if (backup.exists() && importBundle.exists()) backup.delete()
        }
    }

    @Synchronized
    fun savedImport(): SavedImport? {
        recoverImportBundle()
        if (importBundle.isFile) return materializeBundle()

        // One-time compatibility with the pre-bundle format.
        val kind = preferences.getString(KEY_IMPORT_KIND, null) ?: return null
        val displayName = preferences.getString(KEY_IMPORT_NAME, null) ?: "Restored Cura configuration"
        if (!legacyImportFile.isFile || legacyImportFile.length() == 0L) return null
        return SavedImport(kind, displayName, legacyImportFile)
    }

    private fun recoverImportBundle() {
        val next = File(stateDirectory, "current-cura-import.bundle.next")
        val backup = File(stateDirectory, "current-cura-import.bundle.previous")
        if (!importBundle.exists()) {
            when {
                next.isFile -> next.renameTo(importBundle)
                backup.isFile -> backup.renameTo(importBundle)
            }
        }
        if (importBundle.isFile) {
            next.delete()
            backup.delete()
        }
    }

    private fun materializeBundle(): SavedImport {
        DataInputStream(BufferedInputStream(importBundle.inputStream())).use { input ->
            val magic = ByteArray(IMPORT_BUNDLE_MAGIC.size).also(input::readFully)
            require(magic.contentEquals(IMPORT_BUNDLE_MAGIC)) { "Saved Cura import bundle has an invalid header" }
            val metadataSize = input.readInt()
            val payloadSize = input.readLong()
            require(metadataSize in 1..MAX_IMPORT_METADATA_BYTES) { "Saved Cura import metadata is invalid" }
            require(payloadSize in 1..MAX_CURA_IMPORT_BYTES) { "Saved Cura import payload is invalid" }
            val metadata = JSONObject(ByteArray(metadataSize).also(input::readFully).toString(Charsets.UTF_8))
            require(metadata.getInt("version") == IMPORT_BUNDLE_VERSION) { "Unsupported Cura import bundle version" }
            require(metadata.getLong("payloadBytes") == payloadSize) { "Saved Cura import length metadata is inconsistent" }
            val expectedSha = metadata.getString("payloadSha256")
            require(expectedSha.matches(Regex("[0-9a-f]{64}"))) { "Saved Cura import fingerprint is invalid" }
            val kind = metadata.getString("kind")
            require(kind == KIND_PROJECT || kind == KIND_PROFILE) { "Saved Cura import kind is invalid" }
            val displayName = metadata.getString("displayName").take(MAX_IMPORT_NAME_CHARS)

            val existingMatches = materializedImport.isFile && materializedImport.length() == payloadSize &&
                runCatching { sha256(materializedImport) == expectedSha }.getOrDefault(false)
            if (!existingMatches) {
                val next = File(stateDirectory, "current-cura-import.materialized.next")
                next.delete()
                val digest = MessageDigest.getInstance("SHA-256")
                FileOutputStream(next).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var remaining = payloadSize
                    while (remaining > 0L) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        require(count > 0) { "Saved Cura import bundle ended unexpectedly" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        remaining -= count.toLong()
                    }
                    output.fd.sync()
                }
                require(digest.hexDigest() == expectedSha) {
                    "Saved Cura import payload fingerprint does not match"
                }
                materializedImport.delete()
                check(next.renameTo(materializedImport)) { "Unable to materialize the saved Cura import" }
            }
            return SavedImport(kind, displayName, materializedImport)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.hexDigest()
    }

    fun clearSavedSettings() {
        preferences.edit().remove(KEY_SETTINGS).apply()
    }

    fun savePrusaSettings(settings: PrusaSliceSettings): Boolean =
        preferences.edit().putString(KEY_PRUSA_SETTINGS, PrusaSliceSettingsJson.serialize(settings)).commit()

    fun restorePrusaSettings(): PrusaSliceSettings {
        val encoded = preferences.getString(KEY_PRUSA_SETTINGS, null) ?: return PrusaSliceSettings()
        return PrusaSliceSettingsJson.deserialize(encoded) ?: PrusaSliceSettings()
    }

    fun saveSettings(settings: SlicerSettings): Boolean {
        val values = SlicerSettingsJson.serialize(settings)
        val overrides = JSONArray()
        settings.overriddenSettingKeys.sorted().forEach(overrides::put)
        values.put(KEY_OVERRIDES_JSON, overrides)
        // commit() is required here: the caller writes the workspace descriptor
        // (with its settings-derived fingerprint) immediately afterwards. An
        // async apply() could flush the settings after the workspace file, so a
        // process death between the two would restore stale settings against a
        // mismatched workspace fingerprint on the next launch.
        // A failed commit is reported to the caller instead of crashing the app.
        return preferences.edit().putString(KEY_SETTINGS, values.toString()).commit()
    }

    fun restoreSettings(base: SlicerSettings): SlicerSettings {
        val encoded = preferences.getString(KEY_SETTINGS, null) ?: return base.copy(overriddenSettingKeys = emptySet())
        val values = runCatching { JSONObject(encoded) }.getOrNull() ?: return base.copy(overriddenSettingKeys = emptySet())
        val overridesArray = values.optJSONArray(KEY_OVERRIDES_JSON) ?: JSONArray()
        val overrides = buildSet {
            for (index in 0 until overridesArray.length()) {
                overridesArray.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }

        return SlicerSettingsJson.apply(base, values, overrides).copy(overriddenSettingKeys = overrides)
    }

    fun clearImport() {
        importBundle.delete()
        materializedImport.delete()
        legacyImportFile.delete()
        File(stateDirectory, "current-cura-import.bundle.next").delete()
        File(stateDirectory, "current-cura-import.bundle.previous").delete()
        preferences.edit().remove(KEY_IMPORT_KIND).remove(KEY_IMPORT_NAME).apply()
    }

    fun saveSnapshotBaseline(baseline: SnapshotBaseline) {
        val json = JSONObject()
            .put("settings", SlicerSettingsJson.serialize(baseline.settings))
            .put("startGcode", baseline.startGcode)
            .put("endGcode", baseline.endGcode)
            .put("profileName", baseline.profileName)
            .put("profileSource", baseline.profileSource)
        preferences.edit().putString(KEY_SNAPSHOT_BASELINE, json.toString()).apply()
    }

    fun snapshotBaseline(): SnapshotBaseline? {
        val encoded = preferences.getString(KEY_SNAPSHOT_BASELINE, null) ?: return null
        return runCatching {
            val root = JSONObject(encoded)
            val values = root.getJSONObject("settings")
            SnapshotBaseline(
                settings = SlicerSettingsJson.apply(SlicerSettings(), values, SlicerSettingsJson.allKeys),
                startGcode = root.optString("startGcode", ""),
                endGcode = root.optString("endGcode", ""),
                profileName = root.optString("profileName", ""),
                profileSource = root.optString("profileSource", ""),
            )
        }.getOrNull()
    }

    fun clearSnapshotBaseline() {
        preferences.edit().remove(KEY_SNAPSHOT_BASELINE).apply()
    }

    companion object {
        const val KIND_PROJECT = "project"
        const val KIND_PROFILE = "profile"
        private const val IMPORT_BUNDLE_VERSION = 1
        private const val MAX_IMPORT_METADATA_BYTES = 64 * 1024
        private const val MAX_IMPORT_NAME_CHARS = 512
        private val IMPORT_BUNDLE_MAGIC = "ESCIMP2\n".toByteArray(Charsets.US_ASCII)

        private const val PREFERENCES_NAME = "enderslicer-state"
        private const val KEY_IMPORT_KIND = "import-kind"
        private const val KEY_IMPORT_NAME = "import-name"
        private const val KEY_SETTINGS = "settings-json"
        private const val KEY_PRUSA_SETTINGS = "prusa-settings-json"
        private const val KEY_SNAPSHOT_BASELINE = "snapshot-baseline-json"
        private const val KEY_OVERRIDES_JSON = "overrides"
        private const val MAX_CURA_IMPORT_BYTES = 128L * 1024L * 1024L
    }
}
