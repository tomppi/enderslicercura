package com.tomppi.enderslicer.annotation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationStateTest {

    private fun point(x: Float, y: Float, z: Float) = Point3(x, y, z)

    @Test
    fun startsEmpty() {
        val state = AnnotationState()
        assertTrue(state.isEmpty)
        assertTrue(state.chains.isEmpty())
        assertNull(state.active)
        assertNull(state.activeChainId)
    }

    @Test
    fun lockingWithNothingActiveReturnsNull() {
        assertNull(AnnotationState().lock())
    }

    @Test
    fun placeAndLockCreatesAChain() {
        val state = AnnotationState()
        state.setActive(point(1f, 2f, 3f), AnnotationAnchor.SURFACE, faceIndex = 42)
        val chain = state.lock()
        assertNotNull(chain)
        assertEquals(1, state.chains.size)
        assertEquals(1, state.chains[0].points.size)
        assertEquals(42, state.chains[0].points[0].faceIndex)
        assertNull("locking consumes the active point", state.active)
        assertEquals(chain!!.id, state.activeChainId)
    }

    @Test
    fun lockingAgainExtendsTheSameChain() {
        val state = AnnotationState()
        state.kind = AnnotationKind.MEASURE
        state.setActive(point(0f, 0f, 0f), AnnotationAnchor.SURFACE, 0)
        state.lock()
        state.setActive(point(30f, 40f, 0f), AnnotationAnchor.SURFACE, 1)
        state.lock()

        assertEquals(1, state.chains.size)
        assertEquals(2, state.chains[0].points.size)
        assertEquals(50f, state.chains[0].lengthMm(), 1e-3f)
        assertTrue(state.chains[0].isComplete())
    }

    @Test
    fun finishChainStartsANewChainOnTheNextLock() {
        val state = AnnotationState()
        state.setActive(point(0f, 0f, 0f), AnnotationAnchor.PLANE)
        state.lock()
        state.setActive(point(1f, 0f, 0f), AnnotationAnchor.PLANE)
        state.lock()
        state.finishChain()

        state.setActive(point(5f, 5f, 5f), AnnotationAnchor.PLANE)
        state.lock()

        assertEquals(2, state.chains.size)
        assertEquals(1, state.chains[1].points.size)
        assertEquals(2, state.chains[1].id)
    }

    @Test
    fun undoClearsTheActivePointFirst() {
        val state = AnnotationState()
        state.setActive(point(1f, 1f, 1f), AnnotationAnchor.PLANE)
        assertTrue(state.undo())
        assertNull(state.active)
        assertTrue(state.chains.isEmpty())
    }

    @Test
    fun undoRemovesTheLastLockedPoint() {
        val state = AnnotationState()
        state.setActive(point(0f, 0f, 0f), AnnotationAnchor.PLANE)
        state.lock()
        state.setActive(point(1f, 0f, 0f), AnnotationAnchor.PLANE)
        state.lock()

        assertTrue(state.undo())
        assertEquals(1, state.chains[0].points.size)
        assertTrue(state.undo())
        assertTrue("a chain with no points is dropped", state.chains.isEmpty())
        assertFalse(state.undo())
    }

    /**
     * The property that makes orbiting during an edit safe: a lateral move
     * keeps the point at the depth it had when the drag began.
     */
    @Test
    fun depthIsPreservedWhenTheCameraOrbits() {
        val state = AnnotationState()
        val original = point(10f, 0f, 0f)
        state.setActive(original, AnnotationAnchor.SURFACE, faceIndex = 7)

        val cameraBefore = point(0f, -100f, 0f)
        val depthBefore = cameraBefore.distanceTo(original)
        state.beginAdjustment(cameraBefore)

        // The camera orbits 90 degrees; the ray now arrives from the side.
        val cameraAfter = point(-100f, 0f, 0f)
        assertTrue(state.moveAlongRay(cameraAfter, point(1f, 0.2f, 0f)))

        val moved = state.active!!
        assertEquals(
            "the point keeps the depth it had when the drag began",
            depthBefore,
            cameraAfter.distanceTo(moved.position),
            1e-2f,
        )
        assertTrue("the point should have moved laterally", moved.position.y > 1f)
    }

    @Test
    fun moveWithoutCapturedDepthDoesNothing() {
        val state = AnnotationState()
        state.setActive(point(10f, 0f, 0f), AnnotationAnchor.PLANE)
        assertFalse("no depth captured means no depth to preserve", state.moveAlongRay(point(0f, 0f, 0f), point(1f, 0f, 0f)))
        assertEquals(10f, state.active!!.position.x, 1e-4f)
    }

    @Test
    fun endAdjustmentStopsDepthPreservation() {
        val state = AnnotationState()
        state.setActive(point(10f, 0f, 0f), AnnotationAnchor.PLANE)
        state.beginAdjustment(point(0f, 0f, 0f))
        state.endAdjustment()
        assertFalse(state.moveAlongRay(point(0f, 0f, 0f), point(1f, 0f, 0f)))
    }

    @Test
    fun aZeroLengthDirectionIsRejected() {
        val state = AnnotationState()
        state.setActive(point(10f, 0f, 0f), AnnotationAnchor.PLANE)
        state.beginAdjustment(point(0f, 0f, 0f))
        assertFalse(state.moveAlongRay(point(0f, 0f, 0f), point(0f, 0f, 0f)))
    }

    @Test
    fun movingDetachesThePointFromItsFace() {
        val state = AnnotationState()
        state.setActive(point(10f, 0f, 0f), AnnotationAnchor.SURFACE, faceIndex = 7)
        state.beginAdjustment(point(0f, 0f, 0f))
        state.moveAlongRay(point(0f, 0f, 0f), point(1f, 0f, 0f))

        val moved = state.active!!
        assertEquals(AnnotationAnchor.PLANE, moved.anchor)
        assertNull("a slid point is no longer on that triangle", moved.faceIndex)
    }

    @Test
    fun snapReattachesToTheSurfaceAndClearsTheDepth() {
        val state = AnnotationState()
        state.setActive(point(10f, 0f, 0f), AnnotationAnchor.PLANE)
        state.beginAdjustment(point(0f, 0f, 0f))

        state.snapActive(point(2f, 3f, 4f), faceIndex = 99)
        val snapped = state.active!!
        assertEquals(AnnotationAnchor.SURFACE, snapped.anchor)
        assertEquals(99, snapped.faceIndex)

        // The snap fixed the depth deliberately, so a later move must not
        // silently reintroduce the old captured one.
        assertFalse(state.moveAlongRay(point(0f, 0f, 0f), point(1f, 0f, 0f)))
    }

    @Test
    fun nudgeMovesThePointAndDetachesIt() {
        val state = AnnotationState()
        state.setActive(point(1f, 1f, 1f), AnnotationAnchor.SURFACE, faceIndex = 3)
        state.nudge(0.5f, -0.5f, 2f)
        val moved = state.active!!
        assertEquals(1.5f, moved.position.x, 1e-4f)
        assertEquals(0.5f, moved.position.y, 1e-4f)
        assertEquals(3f, moved.position.z, 1e-4f)
        assertEquals(AnnotationAnchor.PLANE, moved.anchor)
    }

    @Test
    fun pendingLengthMeasuresToThePreviousLockedPoint() {
        val state = AnnotationState()
        state.kind = AnnotationKind.MEASURE
        assertNull("nothing to measure without a locked point", state.pendingLengthMm())

        state.setActive(point(0f, 0f, 0f), AnnotationAnchor.PLANE)
        state.lock()
        assertNull("no active point yet", state.pendingLengthMm())

        state.setActive(point(3f, 4f, 0f), AnnotationAnchor.PLANE)
        assertEquals(5f, state.pendingLengthMm()!!, 1e-4f)
        assertEquals(5f, state.liveChainLengthMm()!!, 1e-4f)
    }

    @Test
    fun closingAChainNeedsThreePoints() {
        val state = AnnotationState()
        state.setActive(point(0f, 0f, 0f), AnnotationAnchor.PLANE)
        state.lock()
        state.setActive(point(1f, 0f, 0f), AnnotationAnchor.PLANE)
        state.lock()
        assertFalse("two points cannot enclose a region", state.closeActiveChain())

        state.setActive(point(0f, 1f, 0f), AnnotationAnchor.PLANE)
        state.lock()
        assertTrue(state.closeActiveChain())
        assertEquals(AnnotationKind.REGION, state.chains[0].kind)
        assertTrue(state.chains[0].closed)
        // Perimeter: 1 + sqrt(2) + 1
        assertEquals(3.414f, state.chains[0].lengthMm(), 1e-2f)
        assertNull("closing finishes the chain", state.activeChainId)
    }

    @Test
    fun restoreContinuesChainIds() {
        val state = AnnotationState()
        state.restore(
            listOf(
                AnnotationChain(4, AnnotationKind.PATH, listOf(
                    AnnotationPoint(point(0f, 0f, 0f), AnnotationAnchor.PLANE),
                    AnnotationPoint(point(1f, 0f, 0f), AnnotationAnchor.PLANE),
                )),
            ),
        )
        assertEquals(1, state.chains.size)
        assertNull(state.activeChainId)
        state.setActive(point(9f, 9f, 9f), AnnotationAnchor.PLANE)
        state.lock()
        assertEquals("a restored chain's id must not be reused", 5, state.chains[1].id)
    }

    @Test
    fun clearResetsEverything() {
        val state = AnnotationState()
        state.setActive(point(0f, 0f, 0f), AnnotationAnchor.PLANE)
        state.lock()
        state.setActive(point(1f, 1f, 1f), AnnotationAnchor.PLANE)
        state.clear()
        assertTrue(state.isEmpty)
        assertTrue(state.chains.isEmpty())
        assertNull(state.active)
        state.setActive(point(2f, 2f, 2f), AnnotationAnchor.PLANE)
        state.lock()
        assertEquals(1, state.chains[0].id)
    }
}
