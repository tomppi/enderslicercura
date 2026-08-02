package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.PrinterDefinition
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Aspect-aware camera fit that prioritizes the loaded model while retaining bed context. */
internal object SceneCameraFit {
    data class Fit(
        val centerX: Float,
        val centerY: Float,
        val centerZ: Float,
        val radius: Float,
        val distance: Float,
        val nearPlane: Float,
        val farPlane: Float,
    )

    fun calculate(
        printer: PrinterDefinition,
        meshBounds: MeshBounds?,
        aspect: Float,
        zoom: Float,
        verticalFieldOfViewDegrees: Float,
        margin: Float = 1.15f,
    ): Fit {
        require(aspect.isFinite() && aspect > 0f) { "Camera aspect must be positive" }
        require(zoom.isFinite() && zoom > 0f) { "Camera zoom must be positive" }
        require(verticalFieldOfViewDegrees in 1f..179f) { "Camera field of view is invalid" }
        require(margin.isFinite() && margin >= 1f) { "Camera fit margin must be at least one" }

        val bedMinX = if (printer.originAtCenter) -printer.widthMm / 2.0 else 0.0
        val bedMaxX = if (printer.originAtCenter) printer.widthMm / 2.0 else printer.widthMm
        val bedMinY = if (printer.originAtCenter) -printer.depthMm / 2.0 else 0.0
        val bedMaxY = if (printer.originAtCenter) printer.depthMm / 2.0 else printer.depthMm

        val focusMinX = meshBounds?.minX?.toDouble() ?: bedMinX
        val focusMaxX = meshBounds?.maxX?.toDouble() ?: bedMaxX
        val focusMinY = meshBounds?.minY?.toDouble() ?: bedMinY
        val focusMaxY = meshBounds?.maxY?.toDouble() ?: bedMaxY
        val focusMinZ = min(0.0, meshBounds?.minZ?.toDouble() ?: 0.0)
        val focusMaxZ = max(0.0, meshBounds?.maxZ?.toDouble() ?: 0.0)

        val centerX = ((focusMinX + focusMaxX) * 0.5).toFloat()
        val centerY = ((focusMinY + focusMaxY) * 0.5).toFloat()
        val centerZ = ((focusMinZ + focusMaxZ) * 0.5).toFloat()
        val halfWidth = ((focusMaxX - focusMinX) * 0.5).toFloat()
        val halfDepth = ((focusMaxY - focusMinY) * 0.5).toFloat()
        val halfHeight = ((focusMaxZ - focusMinZ) * 0.5).toFloat()
        val modelRadius = max(sqrt(halfWidth * halfWidth + halfDepth * halfDepth + halfHeight * halfHeight), 1f)

        // The previous implementation fitted the whole 230 mm bed, making a
        // Benchy-sized model occupy only a small fraction of a tall Fold view.
        // Keep enough bed for orientation, but do not let it dominate framing.
        val bedRadius = sqrt(
            (printer.widthMm * 0.5).let { it * it } +
                (printer.depthMm * 0.5).let { it * it },
        ).toFloat()
        val framingRadius = if (meshBounds == null) {
            bedRadius.coerceAtLeast(1f)
        } else {
            max(modelRadius, min(bedRadius * BED_CONTEXT_FRACTION, modelRadius * MAX_CONTEXT_SCALE))
        }

        val sceneMinX = min(bedMinX, meshBounds?.minX?.toDouble() ?: bedMinX)
        val sceneMaxX = max(bedMaxX, meshBounds?.maxX?.toDouble() ?: bedMaxX)
        val sceneMinY = min(bedMinY, meshBounds?.minY?.toDouble() ?: bedMinY)
        val sceneMaxY = max(bedMaxY, meshBounds?.maxY?.toDouble() ?: bedMaxY)
        val sceneMinZ = min(0.0, meshBounds?.minZ?.toDouble() ?: 0.0)
        val sceneMaxZ = max(0.0, meshBounds?.maxZ?.toDouble() ?: 0.0)
        val clipRadius = listOf(
            distance(centerX, centerY, centerZ, sceneMinX, sceneMinY, sceneMinZ),
            distance(centerX, centerY, centerZ, sceneMinX, sceneMaxY, sceneMinZ),
            distance(centerX, centerY, centerZ, sceneMaxX, sceneMinY, sceneMinZ),
            distance(centerX, centerY, centerZ, sceneMaxX, sceneMaxY, sceneMaxZ),
        ).maxOrNull()?.toFloat()?.coerceAtLeast(framingRadius) ?: framingRadius

        val verticalHalfFov = Math.toRadians(verticalFieldOfViewDegrees.toDouble() / 2.0).toFloat()
        val horizontalHalfFov = atan(tan(verticalHalfFov) * aspect)
        val limitingHalfFov = min(verticalHalfFov, horizontalHalfFov).coerceAtLeast(0.01f)
        val fittedDistance = framingRadius / sin(limitingHalfFov) * margin
        val distance = max(fittedDistance / zoom, framingRadius + 1f)
        val near = max(0.05f, distance - clipRadius * 1.35f)
        val far = max(near + 10f, distance + clipRadius * 2.5f + 10f)
        return Fit(centerX, centerY, centerZ, framingRadius, distance, near, far)
    }

    private fun distance(
        centerX: Float,
        centerY: Float,
        centerZ: Float,
        x: Double,
        y: Double,
        z: Double,
    ): Double {
        val dx = x - centerX
        val dy = y - centerY
        val dz = z - centerZ
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private const val BED_CONTEXT_FRACTION = 0.28f
    private const val MAX_CONTEXT_SCALE = 2.25f
}
