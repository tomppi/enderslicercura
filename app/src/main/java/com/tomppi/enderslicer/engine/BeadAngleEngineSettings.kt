package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.SlicerSettings

/**
 * App-owned settings consumed by the enderslicercura CuraEngine bead-angle
 * overhang patch. Like the brick-wall keys they intentionally do not belong to
 * Cura's upstream definition tree and are appended to the resolved slice
 * snapshot after Cura evaluates its dependency graph.
 */
internal object BeadAngleEngineSettings {
    const val ENABLED = "enderslicer_bead_angle_enabled"
    const val WAVELENGTH = "enderslicer_bead_angle_wavelength"
    const val SPEED = "enderslicer_bead_angle_speed"
    const val FLOW = "enderslicer_bead_angle_flow"
    const val FAN_SPEED = "enderslicer_bead_angle_fan_speed"
    const val MAX_ITERATIONS = "enderslicer_bead_angle_max_iterations"

    fun values(settings: SlicerSettings): LinkedHashMap<String, String> {
        validate(settings)
        val bead = settings.beadAngleOverhang
        return linkedMapOf(
            ENABLED to bead.enabled.toString(),
            WAVELENGTH to bead.wavelengthMm.toString(),
            SPEED to bead.speedMmPerSecond.toString(),
            FLOW to bead.flowPercent.toString(),
            FAN_SPEED to bead.fanSpeedPercent.toString(),
            MAX_ITERATIONS to bead.maxIterations.toString(),
        )
    }

    fun validate(settings: SlicerSettings) {
        val bead = settings.beadAngleOverhang
        require(bead.wavelengthMm in 1.0..10.0) {
            "Bead press wavelength must be between 1 and 10 mm"
        }
        require(bead.speedMmPerSecond in 0.5..100.0) {
            "Bead-angle speed must be between 0.5 and 100 mm/s"
        }
        require(bead.flowPercent in 50.0..200.0) {
            "Bead-angle flow must be between 50% and 200%"
        }
        require(bead.fanSpeedPercent in 0.0..100.0) {
            "Bead-angle fan speed must be between 0% and 100%"
        }
        require(bead.maxIterations in 1..10) {
            "Bead-angle extra-wall limit must be between 1 and 10"
        }
    }
}
