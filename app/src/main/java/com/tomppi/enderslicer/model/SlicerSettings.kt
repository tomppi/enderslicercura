package com.tomppi.enderslicer.model

data class SlicerSettings(
    val layerHeightMm: Double = 0.20,
    val initialLayerHeightMm: Double = 0.28,
    val lineWidthMm: Double = 0.40,
    val printSpeedMmPerSecond: Double = 200.0,
    val nozzleTemperatureC: Int = 210,
    val initialNozzleTemperatureC: Int = 235,
    val bedTemperatureC: Int = 60,
    val infillDensityPercent: Double = 10.0,
    val supportsEnabled: Boolean = true,
    val supportPlacement: String = "everywhere",
    val supportStructure: String = "tree",
    val supportAngleDegrees: Double = 56.0,
    val supportDensityPercent: Double = 15.0,
    val supportPattern: String = "zigzag",
    val supportInterfaceEnabled: Boolean = true,
    val supportInterfaceDensityPercent: Double = 100.0,
    val supportZDistanceMm: Double = 0.20,
    val supportXyDistanceMm: Double = 0.80,
    val supportSpeedMmPerSecond: Double = 100.0,
    val supportInterfaceSpeedMmPerSecond: Double = 100.0,
    val adhesionType: String = "none",
    val retractionDistanceMm: Double = 1.5,
    val retractionSpeedMmPerSecond: Double = 120.0,
    val retractAtLayerChange: Boolean = true,
    val zHopEnabled: Boolean = false,
    val firmwareRetraction: Boolean = true,
    val fanSpeedPercent: Double = 100.0,
    val materialFlowPercent: Double = 100.0,
    val overriddenSettingKeys: Set<String> = emptySet(),
) {
    fun isOverridden(key: String): Boolean = key in overriddenSettingKeys

    object Keys {
        const val LAYER_HEIGHT = "layerHeightMm"
        const val INITIAL_LAYER_HEIGHT = "initialLayerHeightMm"
        const val LINE_WIDTH = "lineWidthMm"
        const val PRINT_SPEED = "printSpeedMmPerSecond"
        const val NOZZLE_TEMPERATURE = "nozzleTemperatureC"
        const val INITIAL_NOZZLE_TEMPERATURE = "initialNozzleTemperatureC"
        const val BED_TEMPERATURE = "bedTemperatureC"
        const val INFILL_DENSITY = "infillDensityPercent"
        const val SUPPORTS_ENABLED = "supportsEnabled"
        const val SUPPORT_PLACEMENT = "supportPlacement"
        const val SUPPORT_STRUCTURE = "supportStructure"
        const val SUPPORT_ANGLE = "supportAngleDegrees"
        const val SUPPORT_DENSITY = "supportDensityPercent"
        const val SUPPORT_PATTERN = "supportPattern"
        const val SUPPORT_INTERFACE_ENABLED = "supportInterfaceEnabled"
        const val SUPPORT_INTERFACE_DENSITY = "supportInterfaceDensityPercent"
        const val SUPPORT_Z_DISTANCE = "supportZDistanceMm"
        const val SUPPORT_XY_DISTANCE = "supportXyDistanceMm"
        const val SUPPORT_SPEED = "supportSpeedMmPerSecond"
        const val SUPPORT_INTERFACE_SPEED = "supportInterfaceSpeedMmPerSecond"
        const val ADHESION_TYPE = "adhesionType"
        const val RETRACTION_DISTANCE = "retractionDistanceMm"
        const val RETRACTION_SPEED = "retractionSpeedMmPerSecond"
        const val RETRACT_AT_LAYER_CHANGE = "retractAtLayerChange"
        const val Z_HOP = "zHopEnabled"
        const val FIRMWARE_RETRACTION = "firmwareRetraction"
        const val FAN_SPEED = "fanSpeedPercent"
        const val MATERIAL_FLOW = "materialFlowPercent"
    }
}
