package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.PrinterDefinition

/**
 * Produces the editable UI baseline from the complete definition stack embedded
 * in a Cura project. The engine profile itself remains unchanged so slice-time
 * dependency resolution still starts from the original Cura formulas.
 */
internal object CuraImportedSettingsResolver {
    fun resolveForUi(
        config: ImportedCuraConfig,
        printer: PrinterDefinition,
        fallbackStartGcode: String,
        fallbackEndGcode: String,
    ): ImportedCuraConfig {
        val profile = config.engineProfile ?: return config
        if (!profile.usesProjectDefinitions) return config

        return runCatching {
            val resolved = CuraSliceSettingsResolver.resolve(
                profile = profile,
                printer = printer,
                settings = config.mappedSettings.copy(overriddenSettingKeys = emptySet()),
                startGcode = config.startGcode ?: fallbackStartGcode,
                endGcode = config.endGcode ?: fallbackEndGcode,
            )
            val uiValues = linkedMapOf<String, String>().apply {
                putAll(resolved.extruderValues)
                putAll(resolved.globalValues)
            }
            config.copy(
                mappedSettings = CuraSettingsMapper.apply(config.mappedSettings, uiValues)
                    .copy(overriddenSettingKeys = emptySet()),
                warnings = config.warnings.filterNot(::isDeferredFormulaWarning),
            )
        }.getOrElse { error ->
            config.copy(
                warnings = (
                    config.warnings +
                        "Complete Cura formula preview could not be resolved: ${error.message ?: error::class.java.simpleName}"
                    ).distinct(),
            )
        }
    }

    private fun isDeferredFormulaWarning(value: String): Boolean {
        val normalized = value.lowercase()
        return "formula" in normalized &&
            ("at slice time" in normalized || "compatible machine definition" in normalized)
    }
}
