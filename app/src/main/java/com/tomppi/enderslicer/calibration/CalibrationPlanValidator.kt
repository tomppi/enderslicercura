package com.tomppi.enderslicer.calibration

import com.tomppi.enderslicer.engine.CalibrationFirmwareEncoder
import com.tomppi.enderslicer.engine.LayerEventType
import java.math.BigDecimal

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
        require(spec.levels in 2..20) { "Calibration tower levels must be between 2 and 20" }
        require(spec.startValue.isFinite() && spec.stepValue.isFinite()) {
            "Calibration values must be finite"
        }
        require(spec.stepValue != 0.0) { "Calibration step must not be zero" }

        val start = BigDecimal.valueOf(spec.startValue)
        val step = BigDecimal.valueOf(spec.stepValue)
        val values = List(spec.levels) { index ->
            start.add(step.multiply(BigDecimal.valueOf(index.toLong())))
                .stripTrailingZeros()
                .toDouble()
        }
        values.forEach { value ->
            require(value in spec.type.minimum..spec.type.maximum) {
                "${spec.type.displayName} value $value is outside ${spec.type.minimum}..${spec.type.maximum}"
            }
        }
        if (spec.type == CalibrationTestType.RETRACTION) {
            require(retractionSpeedMmPerSecond in 0.1..1000.0) {
                "Retraction speed is outside 0.1..1000 mm/s"
            }
        }

        val eventType = spec.type.eventType
        val firmware = CalibrationFirmwareEncoder.fromFlavor(gcodeFlavor)
        firmware.requireDistinctCalibrationSequence(
            type = eventType,
            values = values,
            secondaryValue = if (eventType == LayerEventType.RETRACTION) {
                retractionSpeedMmPerSecond
            } else {
                null
            },
        )
        return ValidatedPlan(values, firmware, eventType)
    }
}
