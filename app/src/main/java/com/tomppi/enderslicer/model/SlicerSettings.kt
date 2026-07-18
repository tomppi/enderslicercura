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
    val adhesionType: String = "none",
    val retractionDistanceMm: Double = 1.5,
    val retractionSpeedMmPerSecond: Double = 120.0,
    val retractAtLayerChange: Boolean = true,
    val zHopEnabled: Boolean = false,
    val firmwareRetraction: Boolean = true,
    val fanSpeedPercent: Double = 100.0,
    val materialFlowPercent: Double = 100.0,
)
