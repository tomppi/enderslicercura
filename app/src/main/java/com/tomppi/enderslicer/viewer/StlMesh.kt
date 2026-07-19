package com.tomppi.enderslicer.viewer

data class MeshBounds(
    val minX: Float,
    val minY: Float,
    val minZ: Float,
    val maxX: Float,
    val maxY: Float,
    val maxZ: Float,
) {
    val width: Float get() = maxX - minX
    val depth: Float get() = maxY - minY
    val height: Float get() = maxZ - minZ
    val centerX: Float get() = (minX + maxX) * 0.5f
    val centerY: Float get() = (minY + maxY) * 0.5f
    val centerZ: Float get() = (minZ + maxZ) * 0.5f
}

/**
 * Linear model transform plus its final translation in normal build-plate
 * coordinates. CuraEngine can apply the linear part while loading the original
 * STL and the translation during MeshGroup finalization, avoiding an
 * intermediate transformed-Float STL.
 */
data class StlSliceTransform(
    val linear: List<Double>,
    val translationXmm: Double,
    val translationYmm: Double,
    val translationZmm: Double,
) {
    init {
        require(linear.size == 9) { "Slice transform must contain nine linear values" }
        require(linear.all(Double::isFinite)) { "Slice transform contains a non-finite linear value" }
        require(listOf(translationXmm, translationYmm, translationZmm).all(Double::isFinite)) {
            "Slice transform contains a non-finite translation"
        }
    }
}

data class StlMesh(
    val displayName: String,
    val interleavedVertices: FloatArray,
    val triangleCount: Int,
    val bounds: MeshBounds,
    /** Original untransformed STL vertices retained for precision slicing. */
    val slicingSourceInterleavedVertices: FloatArray? = null,
    /** Transform that maps the original vertices to the displayed placement. */
    val slicingTransform: StlSliceTransform? = null,
)
