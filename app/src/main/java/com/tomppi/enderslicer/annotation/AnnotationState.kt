package com.tomppi.enderslicer.annotation

import kotlin.math.sqrt

/** A position in model space, in millimetres. */
data class Point3(val x: Float, val y: Float, val z: Float) {
    fun distanceTo(other: Point3): Float {
        val dx = other.x - x
        val dy = other.y - y
        val dz = other.z - z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}

/**
 * How a point's depth was established.
 *
 * A point placed where the ray hit the model is [SURFACE] and remembers the
 * triangle, so it stays meaningful if the intent was "on this face". A point
 * dropped where the ray missed the model is projected onto the drawing plane
 * and is [PLANE] - it has a depth, but that depth is a convention rather than
 * something the geometry confirms. Keeping the distinction explicit means the
 * receiving side never has to guess which points are trustworthy in depth.
 */
enum class AnnotationAnchor { SURFACE, PLANE }

/** What a chain is for. */
enum class AnnotationKind {
    /** Two points; the distance between them is the point of the annotation. */
    MEASURE,

    /** An open path the user traced. */
    PATH,

    /** A closed loop - a region to act on. */
    REGION,
}

data class AnnotationPoint(
    val position: Point3,
    val anchor: AnnotationAnchor,
    val faceIndex: Int? = null,
)

data class AnnotationChain(
    val id: Int,
    val kind: AnnotationKind,
    val points: List<AnnotationPoint>,
    val closed: Boolean = false,
) {
    /** Total length: the path length, or the perimeter when closed. */
    fun lengthMm(): Float {
        if (points.size < 2) return 0f
        var total = 0f
        for (i in 0 until points.size - 1) {
            total += points[i].position.distanceTo(points[i + 1].position)
        }
        if (closed) total += points.last().position.distanceTo(points.first().position)
        return total
    }

    /** True when the chain has enough points to mean anything. */
    fun isComplete(): Boolean = when (kind) {
        AnnotationKind.MEASURE -> points.size >= 2
        AnnotationKind.PATH -> points.size >= 2
        AnnotationKind.REGION -> points.size >= 3
    }
}

/**
 * Point-to-point annotation editing.
 *
 * The interaction is deliberately discrete rather than freehand: a point is
 * placed, stays adjustable, and is only committed when the user locks it. That
 * gives three things freehand cannot:
 *
 *  - precision, because a point can be nudged after it is placed;
 *  - *depth*, because the point lives in world space while it is unlocked, so
 *    orbiting the camera reveals whether it is floating or buried and the user
 *    can slide it until it sits right from every angle;
 *  - a clean contract, because only locked geometry is ever sent anywhere.
 *
 * This type is pure: it holds no camera and does no picking. The caller
 * resolves screen positions to rays and 3D points, which keeps every rule here
 * unit-testable.
 */
class AnnotationState {

    private val chainList = mutableListOf<AnnotationChain>()
    private var nextChainId = 1

    /** Locked chains, in creation order. */
    val chains: List<AnnotationChain> get() = chainList.toList()

    /** The unlocked point currently being placed or adjusted, if any. */
    var active: AnnotationPoint? = null
        private set

    /** The chain the next lock will extend, or null when starting a new one. */
    var activeChainId: Int? = null
        private set

    /** Kind applied to a chain created by the next lock. */
    var kind: AnnotationKind = AnnotationKind.PATH

    /**
     * Distance from the camera captured when an adjustment began.
     *
     * Holding this for the duration of a drag is what makes orbiting safe: the
     * point keeps its depth while the camera moves, so a lateral correction
     * never silently changes how far away the point is.
     */
    private var capturedDepth: Float? = null

    val isEmpty: Boolean get() = chainList.isEmpty() && active == null

    val activeIsModified: Boolean get() = capturedDepth != null

    /** Places or replaces the unlocked point, anchoring it as given. */
    fun setActive(position: Point3, anchor: AnnotationAnchor, faceIndex: Int? = null) {
        active = AnnotationPoint(position, anchor, faceIndex)
    }

    /**
     * Captures the active point's depth from [camera] so subsequent moves
     * preserve it. Call once at the start of an adjustment drag.
     */
    fun beginAdjustment(camera: Point3) {
        val current = active ?: return
        capturedDepth = camera.distanceTo(current.position)
    }

    /**
     * Slides the active point along a screen ray at the captured depth.
     *
     * Sliding is a lateral correction, so the point detaches from any surface
     * triangle it was on - it is no longer necessarily on that face. Use
     * [snapActive] to put it back on the model.
     *
     * Returns false when there is nothing to move or no depth was captured.
     */
    fun moveAlongRay(origin: Point3, direction: Point3): Boolean {
        val depth = capturedDepth ?: return false
        val current = active ?: return false
        val length = sqrt(
            direction.x * direction.x + direction.y * direction.y + direction.z * direction.z,
        )
        if (length <= 1e-6f) return false
        val unit = Point3(direction.x / length, direction.y / length, direction.z / length)
        active = AnnotationPoint(
            position = Point3(
                origin.x + unit.x * depth,
                origin.y + unit.y * depth,
                origin.z + unit.z * depth,
            ),
            anchor = AnnotationAnchor.PLANE,
            faceIndex = null,
        )
        return true
    }

    /** Ends an adjustment; the captured depth no longer applies. */
    fun endAdjustment() {
        capturedDepth = null
    }

    /** Re-anchors the active point onto the model surface. */
    fun snapActive(position: Point3, faceIndex: Int) {
        if (active == null) return
        active = AnnotationPoint(position, AnnotationAnchor.SURFACE, faceIndex)
        // A snap fixes the depth deliberately, so a later orbit must not undo it.
        capturedDepth = null
    }

    /** Nudges the active point by an explicit model-space delta. */
    fun nudge(dx: Float, dy: Float, dz: Float) {
        val current = active ?: return
        active = AnnotationPoint(
            position = Point3(
                current.position.x + dx,
                current.position.y + dy,
                current.position.z + dz,
            ),
            anchor = AnnotationAnchor.PLANE,
            faceIndex = null,
        )
        capturedDepth = null
    }

    /**
     * Commits the active point into a chain and clears it, ready for the next.
     *
     * The first lock of a sequence creates the chain; later locks extend it,
     * which is what makes the interaction point-to-point rather than
     * point-per-chain. Returns the affected chain, or null when there was
     * nothing to lock.
     */
    fun lock(): AnnotationChain? {
        val point = active ?: return null
        val existingIndex = chainList.indexOfFirst { it.id == activeChainId }
        val chain = if (existingIndex >= 0) {
            val existing = chainList[existingIndex]
            existing.copy(points = existing.points + point)
        } else {
            AnnotationChain(id = nextChainId++, kind = kind, points = listOf(point))
        }
        if (existingIndex >= 0) {
            chainList[existingIndex] = chain
        } else {
            chainList += chain
            activeChainId = chain.id
        }
        active = null
        capturedDepth = null
        return chain
    }

    /** Removes the newest locked point, or clears the active one when unlocked. */
    fun undo(): Boolean {
        if (active != null) {
            active = null
            capturedDepth = null
            return true
        }
        if (chainList.isEmpty()) return false
        val last = chainList.last()
        if (last.points.size <= 1) {
            chainList.removeAt(chainList.size - 1)
            activeChainId = null
        } else {
            chainList[chainList.size - 1] = last.copy(
                points = last.points.subList(0, last.points.size - 1),
            )
        }
        return true
    }

    /** Closes the active chain into a region, if it has enough points. */
    fun closeActiveChain(): Boolean {
        val index = chainList.indexOfFirst { it.id == activeChainId }
        if (index < 0) return false
        val chain = chainList[index]
        if (chain.points.size < 3) return false
        chainList[index] = chain.copy(kind = AnnotationKind.REGION, closed = true)
        activeChainId = null
        return true
    }

    /** Ends the current chain so the next lock starts a fresh one. */
    fun finishChain() {
        activeChainId = null
    }

    /**
     * Replaces all content, used when restoring a persisted document.
     *
     * The active point is never restored: an unlocked point is transient editing
     * state, not something worth persisting.
     */
    fun restore(restored: List<AnnotationChain>) {
        chainList.clear()
        chainList += restored
        active = null
        activeChainId = null
        capturedDepth = null
        nextChainId = (restored.maxOfOrNull { it.id } ?: 0) + 1
    }

    fun clear() {
        chainList.clear()
        active = null
        activeChainId = null
        capturedDepth = null
        nextChainId = 1
    }

    /**
     * Live distance from the last locked point to the active one.
     *
     * This is what the measure mode shows while a point is being placed, and it
     * is the value that makes a 2D gesture carry a real millimetre dimension.
     */
    fun pendingLengthMm(): Float? {
        val point = active ?: return null
        val index = chainList.indexOfFirst { it.id == activeChainId }
        if (index < 0) return null
        val previous = chainList[index].points.lastOrNull() ?: return null
        return previous.position.distanceTo(point.position)
    }

    /** Total length of the chain being edited, including the active point. */
    fun liveChainLengthMm(): Float? {
        val point = active ?: return null
        val index = chainList.indexOfFirst { it.id == activeChainId }
        if (index < 0) return null
        val chain = chainList[index]
        if (chain.points.isEmpty()) return null
        var total = 0f
        for (i in 0 until chain.points.size - 1) {
            total += chain.points[i].position.distanceTo(chain.points[i + 1].position)
        }
        total += chain.points.last().position.distanceTo(point.position)
        return total
    }
}
