package com.tomppi.enderslicer.calibration

import com.tomppi.enderslicer.engine.LayerEventType
import com.tomppi.enderslicer.engine.PlannedLayerEvent
import com.tomppi.enderslicer.viewer.StlMesh

enum class CalibrationModelFeature {
    SUPPORT_FREE,
    BRIDGES,
    OVERHANGS,
    FINE_DETAILS,
    THIN_WALLS,
    TOP_SURFACES,
    DIMENSIONAL_RIBS,
    SHARP_CORNERS,
    DIRECTION_CHANGES,
    TALL_WALLS,
    CANTILEVERS,
    SEPARATED_POSTS,
    TRAVEL_GAPS,
    SMALL_ISLANDS,
}

enum class CalibrationTestType(
    val displayName: String,
    val unit: String,
    val defaultStart: Double,
    val defaultStep: Double,
    val minimum: Double,
    val maximum: Double,
    val eventType: LayerEventType,
    val defaultLevels: Int,
    val designDescription: String,
    val modelFeatures: Set<CalibrationModelFeature>,
) {
    TEMPERATURE(
        "Temperature tower",
        "°C",
        230.0,
        -5.0,
        150.0,
        500.0,
        LayerEventType.NOZZLE_TEMPERATURE,
        8,
        "Grounded posts, repeated bridges, self-supporting stepped overhangs and a full-height thin fin compare temperature without generated supports.",
        setOf(
            CalibrationModelFeature.SUPPORT_FREE,
            CalibrationModelFeature.BRIDGES,
            CalibrationModelFeature.OVERHANGS,
            CalibrationModelFeature.FINE_DETAILS,
        ),
    ),
    FLOW(
        "Flow tower",
        "%",
        90.0,
        2.5,
        10.0,
        300.0,
        LayerEventType.FLOW_FACTOR,
        8,
        "A grounded thin-wall tube, full-height measurement ribs and short wall-to-wall bridge coupons expose over- and under-extrusion without generated supports.",
        setOf(
            CalibrationModelFeature.SUPPORT_FREE,
            CalibrationModelFeature.THIN_WALLS,
            CalibrationModelFeature.TOP_SURFACES,
            CalibrationModelFeature.DIMENSIONAL_RIBS,
        ),
    ),
    SPEED(
        "Speed-factor tower",
        "%",
        60.0,
        10.0,
        10.0,
        999.0,
        LayerEventType.SPEED_FACTOR,
        8,
        "A continuously stacked multi-point star forces acceleration, sharp cornering and direction changes while remaining support-free.",
        setOf(
            CalibrationModelFeature.SUPPORT_FREE,
            CalibrationModelFeature.SHARP_CORNERS,
            CalibrationModelFeature.DIRECTION_CHANGES,
            CalibrationModelFeature.TALL_WALLS,
        ),
    ),
    FAN(
        "Fan tower",
        "%",
        0.0,
        20.0,
        0.0,
        100.0,
        LayerEventType.FAN_SPEED,
        6,
        "Grounded posts carry long bridges while stepped 45-degree brackets test cooling and overhang quality without generated supports.",
        setOf(
            CalibrationModelFeature.SUPPORT_FREE,
            CalibrationModelFeature.BRIDGES,
            CalibrationModelFeature.CANTILEVERS,
            CalibrationModelFeature.OVERHANGS,
        ),
    ),
    RETRACTION(
        "Firmware-retraction tower",
        "mm",
        0.5,
        0.25,
        0.0,
        100.0,
        LayerEventType.RETRACTION,
        8,
        "Eight grounded isolated posts force repeated travel moves and small islands for support-free stringing comparison.",
        setOf(
            CalibrationModelFeature.SUPPORT_FREE,
            CalibrationModelFeature.SEPARATED_POSTS,
            CalibrationModelFeature.TRAVEL_GAPS,
            CalibrationModelFeature.SMALL_ISLANDS,
        ),
    ),
}

data class CalibrationTowerSpec(
    val type: CalibrationTestType = CalibrationTestType.TEMPERATURE,
    val startValue: Double = type.defaultStart,
    val stepValue: Double = type.defaultStep,
    val levels: Int = type.defaultLevels,
    val sectionHeightMm: Double = 4.0,
    val towerWidthMm: Double = 16.0,
)

data class CalibrationTowerResult(
    val mesh: StlMesh,
    val plannedEvents: List<PlannedLayerEvent>,
    val description: String,
    val requiresFirmwareRetraction: Boolean,
    val levelValues: List<Double>,
    val modelFeatures: Set<CalibrationModelFeature>,
)

internal data class Point2(val x: Double, val y: Double)
