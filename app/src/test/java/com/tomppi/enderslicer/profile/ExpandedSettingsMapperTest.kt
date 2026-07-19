package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpandedSettingsMapperTest {
    @Test
    fun importsExpandedCategorizedSettings() {
        val mapped = CuraSettingsMapper.apply(
            SlicerSettings(),
            mapOf(
                "wall_line_count" to "4",
                "top_layers" to "7",
                "bottom_layers" to "6",
                "infill_pattern" to "gyroid",
                "speed_wall" to "80",
                "speed_wall_0" to "45",
                "speed_wall_x" to "75",
                "speed_infill" to "140",
                "speed_topbottom" to "60",
                "speed_travel" to "220",
                "speed_layer_0" to "25",
                "cool_fan_speed_0" to "15",
                "cool_fan_full_layer" to "5",
                "retraction_min_travel" to "2.5",
                "retraction_combing" to "noskin",
                "travel_avoid_other_parts" to "true",
                "travel_avoid_distance" to "0.8",
                "retraction_hop" to "0.4",
                "skirt_line_count" to "3",
                "brim_width" to "10",
                "ironing_enabled" to "true",
                "ironing_flow" to "12",
                "speed_ironing" to "18",
            ),
        )

        assertEquals(4, mapped.wallLineCount)
        assertEquals(7, mapped.topLayers)
        assertEquals(6, mapped.bottomLayers)
        assertEquals("gyroid", mapped.infillPattern)
        assertEquals(80.0, mapped.wallSpeedMmPerSecond, 0.0)
        assertEquals(45.0, mapped.outerWallSpeedMmPerSecond, 0.0)
        assertEquals(75.0, mapped.innerWallSpeedMmPerSecond, 0.0)
        assertEquals(140.0, mapped.infillSpeedMmPerSecond, 0.0)
        assertEquals(60.0, mapped.topBottomSpeedMmPerSecond, 0.0)
        assertEquals(220.0, mapped.travelSpeedMmPerSecond, 0.0)
        assertEquals(25.0, mapped.initialLayerSpeedMmPerSecond, 0.0)
        assertEquals(15.0, mapped.initialFanSpeedPercent, 0.0)
        assertEquals(5, mapped.fanFullAtLayer)
        assertEquals(2.5, mapped.retractionMinimumTravelMm, 0.0)
        assertEquals("noskin", mapped.combingMode)
        assertTrue(mapped.avoidPrintedParts)
        assertEquals(0.8, mapped.travelAvoidDistanceMm, 0.0)
        assertEquals(0.4, mapped.zHopHeightMm, 0.0)
        assertEquals(3, mapped.skirtLineCount)
        assertEquals(10.0, mapped.brimWidthMm, 0.0)
        assertTrue(mapped.ironingEnabled)
        assertEquals(12.0, mapped.ironingFlowPercent, 0.0)
        assertEquals(18.0, mapped.ironingSpeedMmPerSecond, 0.0)
        assertTrue(mapped.overriddenSettingKeys.isEmpty())
        assertFalse(mapped.zHopEnabled)
    }
}
