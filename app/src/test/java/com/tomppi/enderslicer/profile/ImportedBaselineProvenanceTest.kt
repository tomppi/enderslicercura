package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedBaselineProvenanceTest {
    @Test
    fun importedConfigRetainsOriginalBaselineWhenCurrentStateContainsOverrides() {
        val imported = SlicerSettings(wallThicknessMm = 0.8)
        val restoredCurrent = imported.copy(
            wallThicknessMm = 1.2,
            overriddenSettingKeys = setOf(SlicerSettings.Keys.WALL_THICKNESS),
        )
        val config = ImportedCuraConfig(
            name = "Imported",
            source = "test",
            rawValues = mapOf("wall_thickness" to "0.8"),
            mappedSettings = imported,
        )

        val immutableBaseline = config.mappedSettings.copy(overriddenSettingKeys = emptySet())

        assertEquals(0.8, immutableBaseline.wallThicknessMm, 0.0001)
        assertEquals(1.2, restoredCurrent.wallThicknessMm, 0.0001)
        assertTrue(immutableBaseline.overriddenSettingKeys.isEmpty())
    }
}
