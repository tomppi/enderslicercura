package com.tomppi.enderslicer.calibration

import com.tomppi.enderslicer.viewer.StlSliceTransform
import kotlin.math.abs

/** Geometry operations that preserve scalar calibration transition heights. */
internal object CalibrationPlacementPolicy {
    fun requireAllowed(transform: StlSliceTransform) {
        val matrix = transform.linear
        require(matrix.size == 9 && matrix.all(Double::isFinite)) {
            "Calibration model transform is invalid"
        }
        require(abs(transform.translationZmm) <= Z_TOLERANCE_MM) {
            "Calibration models must remain on the build plate; reset model Z to 0 mm"
        }
        val preservesBuildAxis =
            abs(matrix[2]) <= MATRIX_TOLERANCE &&
                abs(matrix[5]) <= MATRIX_TOLERANCE &&
                abs(matrix[6]) <= MATRIX_TOLERANCE &&
                abs(matrix[7]) <= MATRIX_TOLERANCE &&
                abs(matrix[8] - 1.0) <= MATRIX_TOLERANCE
        require(preservesBuildAxis) {
            "Calibration models cannot be rotated around X/Y or laid flat; only Z rotation and XY movement are supported"
        }
    }

    fun requireNoRaft(gcodeFile: java.io.File) {
        gcodeFile.bufferedReader().useLines { lines ->
            require(lines.none { it.trimStart().startsWith(";TYPE:RAFT") }) {
                "Raft adhesion changes calibration transition heights and is not supported for calibration models"
            }
        }
    }

    private const val MATRIX_TOLERANCE = 1e-8
    private const val Z_TOLERANCE_MM = 0.01
}
