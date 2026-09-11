package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.supportpaint.SupportPaintState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Arrays

/**
 * Incremental per-triangle colour storage for the model viewer.
 *
 * The previous implementation rebuilt the entire colour buffer on every paint
 * update: a [FloatArray] of \`triangleCount * 9\` floats (10 MB on a
 * 280k-triangle model), a direct [FloatBuffer] copy of the same size, and two
 * boxed [Set]&lt;Int&gt; lookups per triangle to classify it. On a 280k model
 * that is roughly 20 MB of allocation and 560k hash lookups *per touch-move
 * sample*, which is what made support painting feel unresponsive.
 *
 * This type keeps one byte of classification per triangle plus a single
 * persistent colour buffer, and rewrites only the triangles whose
 * classification actually changed. The remaining per-update cost is two byte
 * passes over the mesh plus a handful of float stores, and the caller uploads
 * only the resulting float range to the GPU.
 *
 * Not thread-safe: the viewer owns one instance and mutates it on the GL thread.
 */
class PaintColorBuffer(
    val triangleCount: Int,
    private val baseColor: FloatArray,
    private val enforcerColor: FloatArray,
    private val blockerColor: FloatArray,
) {
    init {
        require(triangleCount >= 0) { "Triangle count must not be negative" }
        require(baseColor.size == 3 && enforcerColor.size == 3 && blockerColor.size == 3) {
            "Colours must be RGB triples"
        }
    }

    /** Classification per triangle: 0 = base, 1 = enforcer, 2 = blocker. */
    private val kind = ByteArray(triangleCount)

    /** Scratch classification for [resync], so the diff needs no allocation. */
    private val next = ByteArray(triangleCount)

    private val colors: FloatBuffer = ByteBuffer
        .allocateDirect(triangleCount * FLOATS_PER_TRIANGLE * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    /** Pending dirty range in floats; empty when nothing changed. */
    private var dirtyFrom = Int.MAX_VALUE
    private var dirtyTo = -1

    /** True when anything is painted, which decides the shader's constant vs buffer path. */
    var hasPaint: Boolean = false
        private set

    val buffer: FloatBuffer get() = colors

    init {
        var i = 0
        while (i < triangleCount) {
            writeTriangle(i, baseColor)
            i++
        }
        colors.position(0)
    }

    /**
     * Brings the buffer in line with [state], rewriting only the triangles whose
     * classification changed.
     *
     * Classification walks the painted sets (O(painted)) rather than the mesh,
     * and the diff is a byte comparison per triangle - no boxing, no hashing,
     * no per-triangle allocation.
     */
    fun resync(state: SupportPaintState) {
        Arrays.fill(next, BASE)
        for (triangle in state.enforcerTriangles) {
            if (triangle in 0 until triangleCount) next[triangle] = ENFORCER
        }
        for (triangle in state.blockerTriangles) {
            if (triangle in 0 until triangleCount) next[triangle] = BLOCKER
        }
        var painted = false
        var i = 0
        while (i < triangleCount) {
            val target = next[i]
            if (target != kind[i]) {
                kind[i] = target
                writeTriangle(i, colorFor(target))
                markDirty(i)
            }
            if (target != BASE) painted = true
            i++
        }
        hasPaint = painted
        colors.position(0)
    }

    /**
     * Applies one edit when the caller already knows which triangles it touched.
     *
     * [changed] must be exactly the set of indices the edit may have
     * reclassified - the brush's expansion. Triangles outside it keep their
     * current colour, so the cost is O(changed) with no mesh-sized pass.
     */
    fun apply(state: SupportPaintState, changed: Set<Int>) {
        for (triangle in changed) {
            if (triangle < 0 || triangle >= triangleCount) continue
            val target = when {
                triangle in state.enforcerTriangles -> ENFORCER
                triangle in state.blockerTriangles -> BLOCKER
                else -> BASE
            }
            if (target != kind[triangle]) {
                kind[triangle] = target
                writeTriangle(triangle, colorFor(target))
                markDirty(triangle)
            }
        }
        var painted = false
        for (value in kind) {
            if (value != BASE) {
                painted = true
                break
            }
        }
        hasPaint = painted
        colors.position(0)
    }

    /**
     * Returns the pending dirty float range and clears it, or \`null\` when
     * nothing changed since the last call.
     */
    fun takeDirtyRange(): IntRange? {
        if (dirtyTo < dirtyFrom) return null
        val range = dirtyFrom..dirtyTo
        dirtyFrom = Int.MAX_VALUE
        dirtyTo = -1
        return range
    }

    private fun colorFor(value: Byte): FloatArray = when (value) {
        ENFORCER -> enforcerColor
        BLOCKER -> blockerColor
        else -> baseColor
    }

    private fun markDirty(triangle: Int) {
        val from = triangle * FLOATS_PER_TRIANGLE
        val to = from + FLOATS_PER_TRIANGLE - 1
        if (from < dirtyFrom) dirtyFrom = from
        if (to > dirtyTo) dirtyTo = to
    }

    private fun writeTriangle(triangle: Int, color: FloatArray) {
        val offset = triangle * FLOATS_PER_TRIANGLE
        var vertex = 0
        while (vertex < 3) {
            val at = offset + vertex * 3
            colors.put(at, color[0])
            colors.put(at + 1, color[1])
            colors.put(at + 2, color[2])
            vertex++
        }
    }

    private companion object {
        const val BASE: Byte = 0
        const val ENFORCER: Byte = 1
        const val BLOCKER: Byte = 2
        const val FLOATS_PER_TRIANGLE = 9
    }
}
