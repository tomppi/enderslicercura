package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.annotation.AnnotationAnchor
import com.tomppi.enderslicer.annotation.AnnotationKind
import com.tomppi.enderslicer.annotation.AnnotationState
import com.tomppi.enderslicer.annotation.Point3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay is a GL_LINES buffer split into two ranges: the chain and pending
 * segments, then the marker cross. The split indices are what the renderer uses
 * to colour each half, so an off-by-one there draws the marker in the chain
 * colour or drops it entirely.
 */
class AnnotationOverlayBuilderTest {

    private fun build(state: AnnotationState, marker: Float = 1f) =
        AnnotationOverlayBuilder.build(state, marker)

    @Test
    fun emptyStateProducesNothingToDraw() {
        val overlay = build(AnnotationState())
        assertTrue(overlay.isEmpty)
        assertEquals(0, overlay.lineVertexCount)
        assertEquals(0, overlay.markerVertexCount)
        assertEquals(0, overlay.totalVertexCount)
    }

    @Test
    fun aTwoPointChainIsOneSegment() {
        val state = AnnotationState()
        state.setActive(Point3(0f, 0f, 0f), AnnotationAnchor.PLANE)
        state.lock()
        state.setActive(Point3(10f, 0f, 0f), AnnotationAnchor.PLANE)
        state.lock()

        val overlay = build(state)
        assertEquals("one segment is two vertices", 2, overlay.lineVertexCount)
        assertEquals("nothing is being placed, so no marker", 0, overlay.markerVertexCount)
        assertEquals(6, overlay.vertices.size)
    }

    @Test
    fun aClosedRegionAddsTheClosingSegment() {
        val state = AnnotationState()
        state.setActive(Point3(0f, 0f, 0f), AnnotationAnchor.PLANE)
        state.lock()
        state.setActive(Point3(10f, 0f, 0f), AnnotationAnchor.PLANE)
        state.lock()
        state.setActive(Point3(10f, 10f, 0f), AnnotationAnchor.PLANE)
        state.lock()
        val open = build(state)
        assertEquals(4, open.lineVertexCount)

        assertTrue(state.closeActiveChain())
        val closed = build(state)
        assertEquals("closing adds one more segment", 6, closed.lineVertexCount)
    }

    @Test
    fun theActivePointAddsAPendingSegmentAndAMarker() {
        val state = AnnotationState()
        state.setActive(Point3(0f, 0f, 0f), AnnotationAnchor.PLANE)
        state.lock()
        state.setActive(Point3(10f, 0f, 0f), AnnotationAnchor.SURFACE, faceIndex = 3)

        val overlay = build(state, marker = 2f)
        // Segment from the locked point to the active one, plus the cross.
        assertEquals(
            "the marker must come last so the ranges do not overlap",
            8,
            overlay.totalVertexCount,
        )
        assertEquals(8, overlay.lineVertexCount + overlay.markerVertexCount)
        assertEquals(6, overlay.markerVertexCount)
        assertEquals(2, overlay.lineVertexCount)
    }

    @Test
    fun anActivePointWithNoPreviousPointStillGetsAMarker() {
        val state = AnnotationState()
        state.setActive(Point3(1f, 2f, 3f), AnnotationAnchor.SURFACE, faceIndex = 9)
        val overlay = build(state)
        assertEquals(0, overlay.lineVertexCount)
        assertEquals(6, overlay.markerVertexCount)
        assertTrue(!overlay.isEmpty)
    }

    @Test
    fun theMarkerCrossIsCentredOnTheActivePoint() {
        val state = AnnotationState()
        state.setActive(Point3(5f, 6f, 7f), AnnotationAnchor.PLANE)
        val overlay = build(state, marker = 1f)
        val values = overlay.vertices
        // First marker segment runs from (4,6,7) to (6,6,7).
        assertEquals(4f, values[0], 1e-4f)
        assertEquals(6f, values[1], 1e-4f)
        assertEquals(7f, values[2], 1e-4f)
        assertEquals(6f, values[3], 1e-4f)
        assertEquals(6f, values[4], 1e-4f)
        assertEquals(7f, values[5], 1e-4f)
    }

    @Test
    fun markerSizeScalesWithTheModelAndIsClamped() {
        val small = MeshBounds(0f, 0f, 0f, 1f, 1f, 1f)
        val large = MeshBounds(0f, 0f, 0f, 300f, 300f, 300f)
        assertTrue(
            "a bigger model gets a bigger marker",
            AnnotationOverlayBuilder.markerSizeMm(large) > AnnotationOverlayBuilder.markerSizeMm(small),
        )
        assertTrue(AnnotationOverlayBuilder.markerSizeMm(null) > 0f)
        assertTrue("clamped at the upper bound", AnnotationOverlayBuilder.markerSizeMm(large) <= 4f)
        assertTrue("clamped at the lower bound", AnnotationOverlayBuilder.markerSizeMm(small) >= 0.4f)
    }
}
