package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class NozzlePathExtrusionHueTest {

    @Test
    fun `layer ramp goes from blue at the bed to red at the top`() {
        assertEquals(240f, extrusionHue(0f, 0f, false), 1e-4f)
        assertEquals(120f, extrusionHue(0.5f, 0f, false), 1e-4f)
        assertEquals(0f, extrusionHue(1f, 0f, false), 1e-4f)
    }

    @Test
    fun `speed ramp goes from cyan slow to orange fast`() {
        assertEquals(200f, extrusionHue(0f, 0f, true), 1e-4f)
        assertEquals(110f, extrusionHue(0f, 0.5f, true), 1e-4f)
        assertEquals(20f, extrusionHue(0f, 1f, true), 1e-4f)
    }

    @Test
    fun `ratios are clamped`() {
        assertEquals(240f, extrusionHue(-1f, 0f, false), 1e-4f)
        assertEquals(0f, extrusionHue(2f, 0f, false), 1e-4f)
        assertEquals(200f, extrusionHue(0f, -1f, true), 1e-4f)
        assertEquals(20f, extrusionHue(0f, 2f, true), 1e-4f)
    }
}
