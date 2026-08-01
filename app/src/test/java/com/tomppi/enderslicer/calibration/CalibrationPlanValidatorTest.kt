package com.tomppi.enderslicer.calibration

import com.tomppi.enderslicer.engine.CalibrationFirmwareEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationPlanValidatorTest {
    @Test
    fun rejectsZeroStepForEveryCalibrationType() {
        CalibrationTestType.entries.forEach { type ->
            val error = runCatching {
                CalibrationPlanValidator.validate(
                    spec = validSpec(type).copy(stepValue = 0.0),
                    gcodeFlavor = "Marlin",
                )
            }.exceptionOrNull()
            assertTrue("Expected zero step rejection for $type", error is IllegalArgumentException)
        }
    }

    @Test
    fun rejectsFanStepsThatCollapseToTheSamePwm() {
        val error = runCatching {
            CalibrationPlanValidator.validate(
                spec = validSpec(CalibrationTestType.FAN).copy(
                    startValue = 10.0,
                    stepValue = 0.01,
                    levels = 2,
                ),
                gcodeFlavor = "Marlin",
            )
        }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("same Marlin command"))
    }

    @Test
    fun rejectsPressureAdvanceStepsBelowEncodedPrecision() {
        val error = runCatching {
            CalibrationPlanValidator.validate(
                spec = validSpec(CalibrationTestType.PRESSURE_ADVANCE).copy(
                    startValue = 0.1,
                    stepValue = 0.000001,
                    levels = 2,
                ),
                gcodeFlavor = "Klipper",
            )
        }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("same Klipper command"))
    }

    @Test
    fun rejectsUnsupportedFirmwareCalibrationBeforeGeometryGeneration() {
        val error = runCatching {
            CalibrationPlanValidator.validate(
                spec = validSpec(CalibrationTestType.JUNCTION_DEVIATION),
                gcodeFlavor = "RepRapFirmware",
            )
        }.exceptionOrNull()

        assertTrue(error is CalibrationFirmwareEncoder.UnsupportedFirmwareCommand)
    }

    @Test
    fun acceptsDistinctKlipperPressureAdvanceLevels() {
        val plan = CalibrationPlanValidator.validate(
            spec = validSpec(CalibrationTestType.PRESSURE_ADVANCE),
            gcodeFlavor = "Klipper",
        )

        assertEquals(listOf(0.0, 0.02, 0.04), plan.values)
        assertEquals(
            CalibrationFirmwareEncoder.FirmwareDialect.KLIPPER,
            plan.firmware.dialect,
        )
    }

    private fun validSpec(type: CalibrationTestType): CalibrationTowerSpec = when (type) {
        CalibrationTestType.TEMPERATURE -> CalibrationTowerSpec(type, 200.0, 5.0, 3)
        CalibrationTestType.FLOW -> CalibrationTowerSpec(type, 90.0, 5.0, 3)
        CalibrationTestType.PRINT_SPEED -> CalibrationTowerSpec(type, 80.0, 10.0, 3)
        CalibrationTestType.FAN -> CalibrationTowerSpec(type, 20.0, 20.0, 3)
        CalibrationTestType.RETRACTION -> CalibrationTowerSpec(type, 0.5, 0.5, 3)
        CalibrationTestType.PRESSURE_ADVANCE -> CalibrationTowerSpec(type, 0.0, 0.02, 3)
        CalibrationTestType.JUNCTION_DEVIATION -> CalibrationTowerSpec(type, 0.01, 0.01, 3)
    }
}
