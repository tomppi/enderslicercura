package com.tomppi.enderslicer.supportpaint

/** How a paint stroke is applied to the model surface. */
enum class SupportPaintMode {
    NONE,
    ENFORCER,
    BLOCKER,
    ERASE,
}

/**
 * Support painting state for a single displayed model.
 *
 * Painted regions are stored as triangle indices into the displayed mesh's
 * [com.tomppi.enderslicer.viewer.StlMesh.interleavedVertices]. Triangle order is
 * stable across placement transforms (they only recompute vertices, never
 * reorder triangles), so these indices remain valid until a different model is
 * imported.
 */
data class SupportPaintState(
    val enforcerTriangles: Set<Int> = emptySet(),
    val blockerTriangles: Set<Int> = emptySet(),
    val brushRadiusMm: Double = DEFAULT_BRUSH_RADIUS_MM,
) {
    init {
        require(brushRadiusMm.isFinite() && brushRadiusMm > 0.0) { "Paint brush radius must be positive" }
        require((enforcerTriangles intersect blockerTriangles).isEmpty()) {
            "A triangle cannot be both a support enforcer and a support blocker"
        }
    }

    val isEmpty: Boolean get() = enforcerTriangles.isEmpty() && blockerTriangles.isEmpty()

    fun withEnforcer(triangles: Set<Int>): SupportPaintState = copy(
        enforcerTriangles = enforcerTriangles + triangles,
        blockerTriangles = blockerTriangles - triangles,
    )

    fun withBlocker(triangles: Set<Int>): SupportPaintState = copy(
        blockerTriangles = blockerTriangles + triangles,
        enforcerTriangles = enforcerTriangles - triangles,
    )

    fun erased(triangles: Set<Int>): SupportPaintState = copy(
        enforcerTriangles = enforcerTriangles - triangles,
        blockerTriangles = blockerTriangles - triangles,
    )

    companion object {
        const val DEFAULT_BRUSH_RADIUS_MM = 2.0
        const val MIN_BRUSH_RADIUS_MM = 0.5
        const val MAX_BRUSH_RADIUS_MM = 20.0
    }
}
