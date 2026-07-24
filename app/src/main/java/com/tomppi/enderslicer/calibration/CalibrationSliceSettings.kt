package com.tomppi.enderslicer.calibration

import com.tomppi.enderslicer.model.SlicerSettings

/**
 * Calibration parts deliberately contain bridges and controlled overhangs.
 * Generated support material would invalidate the comparison, so these
 * overrides are applied only to the calibration slice and are not persisted.
 */
internal fun SlicerSettings.forCalibrationSlice(
    active: Boolean,
    requiresFirmwareRetraction: Boolean,
): SlicerSettings {
    if (!active) return this
    return copy(
        supportsEnabled = false,
        supportInterfaceEnabled = false,
        firmwareRetraction = firmwareRetraction || requiresFirmwareRetraction,
    )
}
