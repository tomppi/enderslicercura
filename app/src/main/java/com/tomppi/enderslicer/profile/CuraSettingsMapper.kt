package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerSettings

object CuraSettingsMapper {
    fun apply(base: SlicerSettings, values: Map<String, String>): SlicerSettings {
        fun number(key: String): Double? = values[key]
            ?.trim()
            ?.takeUnless { it.startsWith("=") }
            ?.toDoubleOrNull()

        fun bool(key: String): Boolean? = when (values[key]?.trim()?.lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }

        return base.copy(
            layerHeightMm = number("layer_height") ?: base.layerHeightMm,
            initialLayerHeightMm = number("layer_height_0") ?: base.initialLayerHeightMm,
            lineWidthMm = number("line_width") ?: base.lineWidthMm,
            printSpeedMmPerSecond = number("speed_print") ?: base.printSpeedMmPerSecond,
            nozzleTemperatureC = number("material_print_temperature")?.toInt() ?: base.nozzleTemperatureC,
            initialNozzleTemperatureC = number("material_print_temperature_layer_0")?.toInt()
                ?: base.initialNozzleTemperatureC,
            bedTemperatureC = number("material_bed_temperature")?.toInt() ?: base.bedTemperatureC,
            infillDensityPercent = number("infill_sparse_density") ?: base.infillDensityPercent,
            supportsEnabled = bool("support_enable") ?: base.supportsEnabled,
            supportPlacement = values["support_type"] ?: base.supportPlacement,
            supportStructure = values["support_structure"] ?: base.supportStructure,
            supportAngleDegrees = number("support_angle") ?: base.supportAngleDegrees,
            supportDensityPercent = number("support_infill_rate") ?: base.supportDensityPercent,
            supportPattern = values["support_pattern"] ?: base.supportPattern,
            supportInterfaceEnabled = bool("support_interface_enable") ?: base.supportInterfaceEnabled,
            supportInterfaceDensityPercent = number("support_interface_density")
                ?: base.supportInterfaceDensityPercent,
            supportZDistanceMm = number("support_z_distance") ?: base.supportZDistanceMm,
            supportXyDistanceMm = number("support_xy_distance") ?: base.supportXyDistanceMm,
            supportSpeedMmPerSecond = number("speed_support") ?: base.supportSpeedMmPerSecond,
            supportInterfaceSpeedMmPerSecond = number("speed_support_interface")
                ?: base.supportInterfaceSpeedMmPerSecond,
            adhesionType = values["adhesion_type"] ?: base.adhesionType,
            retractionDistanceMm = number("retraction_amount") ?: base.retractionDistanceMm,
            retractionSpeedMmPerSecond = number("retraction_speed") ?: base.retractionSpeedMmPerSecond,
            retractAtLayerChange = bool("retract_at_layer_change") ?: base.retractAtLayerChange,
            zHopEnabled = bool("retraction_hop_enabled") ?: base.zHopEnabled,
            firmwareRetraction = bool("machine_firmware_retract") ?: base.firmwareRetraction,
            fanSpeedPercent = number("cool_fan_speed") ?: base.fanSpeedPercent,
            materialFlowPercent = number("material_flow") ?: base.materialFlowPercent,
            overriddenSettingKeys = emptySet(),
        )
    }
}
