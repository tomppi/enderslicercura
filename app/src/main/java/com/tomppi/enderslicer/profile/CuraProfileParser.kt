package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerSettings
import java.io.InputStream

object CuraProfileParser {
    fun parse(
        input: InputStream,
        sourceName: String,
        baseSettings: SlicerSettings,
    ): ImportedCuraConfig {
        val entries = CuraArchive.readTextEntries(input)
        require(entries.isNotEmpty()) { "The Cura profile archive is empty" }

        data class Layer(val name: String, val position: Int?, val ini: CuraIni)

        val layers = entries.mapNotNull { (_, text) ->
            val ini = runCatching { CuraIniParser.parse(text) }.getOrNull() ?: return@mapNotNull null
            if (ini["values"].isEmpty()) return@mapNotNull null
            Layer(
                name = ini.value("general", "name") ?: sourceName,
                position = ini.value("metadata", "position")?.toIntOrNull(),
                ini = ini,
            )
        }

        require(layers.isNotEmpty()) { "No [values] sections were found in the Cura profile" }

        val merged = linkedMapOf<String, String>()
        layers.sortedWith(compareBy<Layer> { it.position ?: -1 }).forEach { layer ->
            merged.putAll(layer.ini["values"])
        }

        val primary = layers.first()
        val settingVersion = primary.ini.value("metadata", "setting_version")
        val expressionCount = merged.values.count { it.trimStart().startsWith("=") }
        val warnings = buildList {
            if (expressionCount > 0) {
                add("$expressionCount Cura expression values were preserved but are not evaluated by the Android resolver yet")
            }
        }

        return ImportedCuraConfig(
            name = primary.name,
            source = "Cura profile: $sourceName",
            settingVersion = settingVersion,
            rawValues = merged,
            mappedSettings = CuraSettingsMapper.apply(baseSettings, merged),
            warnings = warnings,
        )
    }
}
