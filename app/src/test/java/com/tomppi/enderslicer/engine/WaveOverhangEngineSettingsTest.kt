package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveOverhangEngineSettingsTest {
    @Test
    fun defaultsAreOptInAndUseNozzleSquaredFlow() {
        val settings = SlicerSettings()
        val values = WaveOverhangEngineSettings.values(settings)

        assertEquals("false", values[WaveOverhangEngineSettings.ENABLED])
        assertEquals("smart", values[WaveOverhangEngineSettings.PATTERN])
        assertEquals("0.16", values[WaveOverhangEngineSettings.FLOW_MM3_PER_MM])
    }

    @Test
    fun arcAndWaveCannotBeEnabledTogether() {
        val error = runCatching {
            WaveOverhangEngineSettings.values(
                SlicerSettings(arcOverhangEnabled = true, waveOverhangEnabled = true),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("enable only one"))
    }

    @Test
    fun invalidPatternFailsClosed() {
        val error = runCatching {
            WaveOverhangEngineSettings.values(SlicerSettings(waveOverhangPattern = "random"))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
