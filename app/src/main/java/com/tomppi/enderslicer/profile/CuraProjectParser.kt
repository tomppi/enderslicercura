package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerSettings
import java.io.InputStream

object CuraProjectParser {
    private data class Container(
        val path: String,
        val baseName: String,
        val ini: CuraIni,
    )

    fun parse(
        input: InputStream,
        sourceName: String,
        baseSettings: SlicerSettings,
    ): ImportedCuraConfig {
        val entries = CuraArchive.readTextEntries(input, accept = { it.startsWith("Cura/") })
        require(entries.keys.any { it.startsWith("Cura/") }) {
            "This 3MF has no Cura project configuration. It may be a model-only 3MF export."
        }

        val containers = entries
            .filterKeys { it.startsWith("Cura/") && it.endsWith(".cfg") }
            .map { (path, text) ->
                Container(path, stripKnownSuffix(path.substringAfterLast('/')), CuraIniParser.parse(text))
            }

        val global = containers.firstOrNull { it.path.endsWith(".global.cfg") }
            ?: error("No Cura machine .global.cfg container was found")

        val warnings = mutableListOf<String>()
        val definitionIds = entries.keys
            .filter { it.startsWith("Cura/") && it.endsWith(".def.json") }
            .map { it.substringAfterLast('/').removeSuffix(".def.json") }
            .toSet()
        val globalValues = resolveStack(global, containers, definitionIds, warnings)

        val extruder = containers.firstOrNull { it.path.endsWith(".extruder.cfg") }
        val extruderValues = if (extruder != null) {
            resolveStack(extruder, containers, definitionIds, warnings)
        } else {
            warnings += "No extruder stack was found; only global settings were imported"
            emptyMap()
        }

        val merged = linkedMapOf<String, String>()
        merged.putAll(globalValues)
        merged.putAll(extruderValues)

        val version = entries["Cura/version.ini"]
            ?.let(CuraIniParser::parse)
            ?.value("versions", "cura_version")
        val machineName = global.ini.value("general", "name") ?: sourceName
        val settingVersion = global.ini.value("metadata", "setting_version")
        val expressionCount = merged.values.count { it.trimStart().startsWith("=") }
        if (definitionIds.isNotEmpty()) {
            warnings += "Defaults and formulas from ${definitionIds.size} Cura JSON definitions are preserved for the engine milestone but not evaluated by the Kotlin resolver yet"
        }
        if (expressionCount > 0) {
            warnings += "$expressionCount Cura expression values were preserved but are not evaluated yet"
        }

        return ImportedCuraConfig(
            name = machineName,
            source = "Cura project: $sourceName",
            curaVersion = version,
            settingVersion = settingVersion,
            rawValues = merged,
            mappedSettings = CuraSettingsMapper.apply(baseSettings, merged),
            startGcode = merged["machine_start_gcode"],
            endGcode = merged["machine_end_gcode"],
            warnings = warnings.distinct(),
        )
    }

    private fun resolveStack(
        stack: Container,
        all: List<Container>,
        definitionIds: Set<String>,
        warnings: MutableList<String>,
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val listed = stack.ini["containers"]
            .mapNotNull { (index, id) -> index.toIntOrNull()?.let { it to id.trim() } }
            .sortedByDescending { it.first }

        for ((_, id) in listed) {
            if (id.startsWith("empty_")) continue
            val container = findContainer(id, all)
            if (container == null) {
                if (id !in definitionIds && !id.contains("material", ignoreCase = true)) {
                    warnings += "Missing referenced Cura container: $id"
                }
                continue
            }
            result.putAll(container.ini["values"])
        }
        return result
    }

    private fun findContainer(id: String, all: List<Container>): Container? {
        return all.firstOrNull { it.baseName == id }
            ?: all.firstOrNull { it.ini.value("general", "id") == id }
            ?: all.firstOrNull { it.ini.value("general", "name") == id }
    }

    private fun stripKnownSuffix(filename: String): String = when {
        filename.endsWith(".global.cfg") -> filename.removeSuffix(".global.cfg")
        filename.endsWith(".extruder.cfg") -> filename.removeSuffix(".extruder.cfg")
        filename.endsWith(".inst.cfg") -> filename.removeSuffix(".inst.cfg")
        filename.endsWith(".cfg") -> filename.removeSuffix(".cfg")
        else -> filename
    }
}
