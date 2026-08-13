package com.tomppi.enderslicer.nonplanar

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurviSlicerRuntimeTest {
    @After
    fun resetRuntime() {
        CurviSlicerRuntime.activate(NonPlanarSettings())
    }

    @Test
    fun generationAdvancesOnlyWhenActivatedSettingsChange() {
        CurviSlicerRuntime.activate(NonPlanarSettings(enabled = true))
        val first = requireNotNull(CurviSlicerRuntime.snapshot())

        CurviSlicerRuntime.activate(NonPlanarSettings(enabled = true))
        assertEquals(first.generation, requireNotNull(CurviSlicerRuntime.snapshot()).generation)

        CurviSlicerRuntime.activate(NonPlanarSettings(enabled = true, strengthPercent = 80.0))
        val second = requireNotNull(CurviSlicerRuntime.snapshot())
        assertEquals(first.generation + 1L, second.generation)
    }

    @Test
    fun disabledSettingsAreNotExposedAsASnapshot() {
        CurviSlicerRuntime.activate(NonPlanarSettings(enabled = false))
        assertNull(CurviSlicerRuntime.snapshot())
    }

    @Test
    fun reEnablingAfterDisableAdvancesTheGeneration() {
        CurviSlicerRuntime.activate(NonPlanarSettings(enabled = true))
        val firstEnabled = requireNotNull(CurviSlicerRuntime.snapshot()).generation

        CurviSlicerRuntime.activate(NonPlanarSettings(enabled = false))
        assertNull(CurviSlicerRuntime.snapshot())
        CurviSlicerRuntime.activate(NonPlanarSettings(enabled = true))
        assertTrue(requireNotNull(CurviSlicerRuntime.snapshot()).generation > firstEnabled)
    }
}
