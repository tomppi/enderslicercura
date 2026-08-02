package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.PrinterDefinition
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/** Conservative aspect-aware fit for the union of the bed and displaced model. */
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
        val minX = min(bedMinX, meshBounds?.minX?.toDouble() ?: bedMinX)
        val maxX = max(bedMaxX, meshBounds?.maxX?.toDouble() ?: bedMaxX)
        val minY = min(bedMinY, meshBounds?.minY?.toDouble() ?: bedMinY)
        val maxY = max(bedMaxY, meshBounds?.maxY?.toDouble() ?: bedMaxY)
        val minZ = min(0.0, meshBounds?.minZ?.toDouble() ?: 0.0)
        val maxZ = max(0.0, meshBounds?.maxZ?.toDouble() ?: 0.0)

        val centerX = ((minX + maxX) * 0.5).toFloat()
        val centerY = ((minY + maxY) * 0.5).toFloat()
        val centerZ = ((minZ + maxZ) * 0.5).toFloat()
        val halfWidth = ((maxX - minX) * 0.5).toFloat()
        val halfDepth = ((maxY - minY) * 0.5).toFloat()
        val halfHeight = ((maxZ - minZ) * 0.5).toFloat()
        val radius = max(sqrt(halfWidth * halfWidth + halfDepth * halfDepth + halfHeight * halfHeight), 1f)

        val verticalHalfFov = Math.toRadians(verticalFieldOfViewDegrees.toDouble() / 2.0).toFloat()
        val horizontalHalfFov = atan(tan(verticalHalfFov) * aspect)
        val limitingHalfFov = min(verticalHalfFov, horizontalHalfFov).coerceAtLeast(0.01f)
        val fittedDistance = radius / tan(limitingHalfFov) * margin
        val distance = max(fittedDistance / zoom, radius + 1f)
        val near = max(0.05f, distance - radius * 1.5f)
        val far = max(near + 10f, distance + radius * 2.5f + 10f)
        return Fit(centerX, centerY, centerZ, radius, distance, near, far)
    }
}
