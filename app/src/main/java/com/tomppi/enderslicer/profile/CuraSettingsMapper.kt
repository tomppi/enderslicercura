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
            printerName = values["machine_name"] ?: base.printerName,
            machineWidthMm = number("machine_width") ?: base.machineWidthMm,
            machineDepthMm = number("machine_depth") ?: base.machineDepthMm,
            machineHeightMm = number("machine_height") ?: base.machineHeightMm,
            buildPlateShape = values["machine_shape"] ?: base.buildPlateShape,
            originAtCenter = bool("machine_center_is_zero") ?: base.originAtCenter,
            heatedBed = bool("machine_heated_bed") ?: base.heatedBed,
            heatedBuildVolume = bool("machine_heated_build_volume") ?: base.heatedBuildVolume,
            gcodeFlavor = values["machine_gcode_flavor"] ?: base.gcodeFlavor,
            nozzleSizeMm = number("machine_nozzle_size") ?: base.nozzleSizeMm,
            filamentDiameterMm = number("material_diameter") ?: base.filamentDiameterMm,
            gantryHeightMm = number("gantry_height") ?: base.gantryHeightMm,
            layerHeightMm = number("layer_height") ?: base.layerHeightMm,
            initialLayerHeightMm = number("layer_height_0") ?: base.initialLayerHeightMm,
            lineWidthMm = number("line_width") ?: base.lineWidthMm,
            wallLineCount = number("wall_line_count")?.toInt() ?: base.wallLineCount,
            topLayers = number("top_layers")?.toInt() ?: base.topLayers,
            bottomLayers = number("bottom_layers")?.toInt() ?: base.bottomLayers,
            infillDensityPercent = number("infill_sparse_density") ?: base.infillDensityPercent,
            infillPattern = values["infill_pattern"] ?: base.infillPattern,
            printSpeedMmPerSecond = number("speed_print") ?: base.printSpeedMmPerSecond,
            wallSpeedMmPerSecond = number("speed_wall") ?: base.wallSpeedMmPerSecond,
            outerWallSpeedMmPerSecond = number("speed_wall_0") ?: base.outerWallSpeedMmPerSecond,
            innerWallSpeedMmPerSecond = number("speed_wall_x") ?: base.innerWallSpeedMmPerSecond,
            infillSpeedMmPerSecond = number("speed_infill") ?: base.infillSpeedMmPerSecond,
            topBottomSpeedMmPerSecond = number("speed_topbottom") ?: base.topBottomSpeedMmPerSecond,
            travelSpeedMmPerSecond = number("speed_travel") ?: base.travelSpeedMmPerSecond,
            initialLayerSpeedMmPerSecond = number("speed_layer_0") ?: base.initialLayerSpeedMmPerSecond,
            nozzleTemperatureC = number("material_print_temperature")?.toInt() ?: base.nozzleTemperatureC,
            initialNozzleTemperatureC = number("material_print_temperature_layer_0")?.toInt()
                ?: base.initialNozzleTemperatureC,
            bedTemperatureC = number("material_bed_temperature")?.toInt() ?: base.bedTemperatureC,
            materialFlowPercent = number("material_flow") ?: base.materialFlowPercent,
            fanSpeedPercent = number("cool_fan_speed") ?: base.fanSpeedPercent,
            initialFanSpeedPercent = number("cool_fan_speed_0") ?: base.initialFanSpeedPercent,
            fanFullAtLayer = number("cool_fan_full_layer")?.toInt() ?: base.fanFullAtLayer,
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
            retractionDistanceMm = number("retraction_amount") ?: base.retractionDistanceMm,
            retractionSpeedMmPerSecond = number("retraction_speed") ?: base.retractionSpeedMmPerSecond,
            retractionMinimumTravelMm = number("retraction_min_travel") ?: base.retractionMinimumTravelMm,
            retractAtLayerChange = bool("retract_at_layer_change") ?: base.retractAtLayerChange,
            combingMode = values["retraction_combing"] ?: base.combingMode,
            avoidPrintedParts = bool("travel_avoid_other_parts") ?: base.avoidPrintedParts,
            travelAvoidDistanceMm = number("travel_avoid_distance") ?: base.travelAvoidDistanceMm,
            zHopEnabled = bool("retraction_hop_enabled") ?: base.zHopEnabled,
            zHopHeightMm = number("retraction_hop") ?: base.zHopHeightMm,
            firmwareRetraction = bool("machine_firmware_retract") ?: base.firmwareRetraction,
            adhesionType = values["adhesion_type"] ?: base.adhesionType,
            skirtLineCount = number("skirt_line_count")?.toInt() ?: base.skirtLineCount,
            brimWidthMm = number("brim_width") ?: base.brimWidthMm,
            ironingEnabled = bool("ironing_enabled") ?: base.ironingEnabled,
            ironingFlowPercent = number("ironing_flow") ?: base.ironingFlowPercent,
            ironingSpeedMmPerSecond = number("speed_ironing") ?: base.ironingSpeedMmPerSecond,
            overriddenSettingKeys = emptySet(),
        )
    }
}
