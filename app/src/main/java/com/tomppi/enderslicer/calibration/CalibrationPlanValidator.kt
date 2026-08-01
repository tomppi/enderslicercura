package com.tomppi.enderslicer.calibration

import com.tomppi.enderslicer.engine.CalibrationFirmwareEncoder
import com.tomppi.enderslicer.engine.LayerEventType

internal object CalibrationPlanValidator {
    data class ValidatedPlan(
        val values: List<Double>,
        val firmware: CalibrationFirmwareEncoder,
        val eventType: LayerEventType,
    )

    fun validate(
        spec: CalibrationTowerSpec,
        gcodeFlavor: String,
        retractionSpeedMmPerSecond: Double = 25.0,
    ): ValidatedPlan {
        require(spec.stepValue != 0.0) { "Calibration step must not be zero" }
        val values = spec.values()
        require(values.zipWithNext().all { (first, second) -> first != second }) {
            "Calibration levels must be numerically distinct"
        }
        val eventType = eventType(spec.testType)
        val firmware = CalibrationFirmwareEncoder.fromFlavor(gcodeFlavor)
        firmware.requireDistinctCalibrationSequence(
            type = eventType,
            values = values,
            secondaryValue = if (eventType == LayerEventType.RETRACTION) {
                retractionSpeedMmPerSecond.coerceAtLeast(0.1)
            } else {
                null
            },
        )
        return ValidatedPlan(values, firmware, eventType)
    }

    private fun eventType(testType: CalibrationTestType): LayerEventType = when (testType) {
        CalibrationTestType.TEMPERATURE -> LayerEventType.NOZZLE_TEMPERATURE
        CalibrationTestType.FLOW -> LayerEventType.FLOW_FACTOR
        CalibrationTestType.PRINT_SPEED -> LayerEventType.SPEED_FACTOR
        CalibrationTestType.FAN -> LayerEventType.FAN_SPEED
        CalibrationTestType.RETRACTION -> LayerEventType.RETRACTION
        CalibrationTestType.PRESSURE_ADVANCE -> LayerEventType.PRESSURE_ADVANCE
        CalibrationTestType.JUNCTION_DEVIATION -> LayerEventType.JUNCTION_DEVIATION
    }
}
