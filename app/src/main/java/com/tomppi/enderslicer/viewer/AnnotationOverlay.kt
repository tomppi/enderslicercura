package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.annotation.AnnotationState
import com.tomppi.enderslicer.annotation.Point3

/**
 * Line geometry for the annotation overlay, in model space.
 *
 * Vertices are laid out for a single \`GL_LINES\` buffer: the chain and pending
 * segments come first, then the marker cross for the unlocked point. Both
 * halves are drawn from the same buffer with different colours, which is why
 * the counts are tracked separately.
 */
class AnnotationOverlay(
    val vertices: FloatArray,
    val lineVertexCount: Int,
    val markerVertexCount: Int,
) {
    val isEmpty: Boolean get() = lineVertexCount == 0 && markerVertexCount == 0

    val totalVertexCount: Int get() = lineVertexCount + markerVertexCount
}

/**
 * Turns annotation state into drawable geometry.
 *
 * Kept separate from the renderer so the layout can be tested without a GL
 * context.
 */
object AnnotationOverlayBuilder {

    fun build(state: AnnotationState, markerSizeMm: Float): AnnotationOverlay {
        val values = ArrayList<Float>(128)

        for (chain in state.chains) {
            val points = chain.points
            for (i in 0 until points.size - 1) {
                segment(values, points[i].position, points[i + 1].position)
            }
            if (chain.closed && points.size >= 3) {
                segment(values, points.last().position, points.first().position)
            }
        }

        val active = state.active
        if (active != null) {
            // The run from the last locked point to the one being placed is what
            // makes a measurement visible while it is being drawn.
            val previous = state.chains
                .lastOrNull { it.id == state.activeChainId }
                ?.points
                ?.lastOrNull()
            if (previous != null) {
                segment(values, previous.position, active.position)
            }
        }

        val lineVertexCount = values.size / 3

        var markerVertexCount = 0
        if (active != null) {
            val p = active.position
            val s = markerSizeMm
            // A three-axis cross reads as a point from any viewing angle, which
            // a flat screen-facing marker cannot do while the camera orbits.
            segment(values, Point3(p.x - s, p.y, p.z), Point3(p.x + s, p.y, p.z))
            segment(values, Point3(p.x, p.y - s, p.z), Point3(p.x, p.y + s, p.z))
            segment(values, Point3(p.x, p.y, p.z - s), Point3(p.x, p.y, p.z + s))
            markerVertexCount = 6
        }

        return AnnotationOverlay(
            vertices = FloatArray(values.size) { values[it] },
            lineVertexCount = lineVertexCount,
            markerVertexCount = markerVertexCount,
        )
    }

    /** Suggested marker radius: small enough to point, large enough to see. */
    fun markerSizeMm(bounds: MeshBounds?): Float {
        if (bounds == null) return 1.5f
        val diagonal = kotlin.math.sqrt(
            bounds.width * bounds.width +
                bounds.depth * bounds.depth +
                bounds.height * bounds.height,
        )
        val size = diagonal * 0.012f
        return size.coerceIn(0.4f, 4f)
    }

    private fun segment(values: MutableList<Float>, from: Point3, to: Point3) {
        values += from.x; values += from.y; values += from.z
        values += to.x; values += to.y; values += to.z
    }
}
