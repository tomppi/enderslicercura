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

data class StlMesh(
    val displayName: String,
    val interleavedVertices: FloatArray,
    val triangleCount: Int,
    val bounds: MeshBounds,
)
