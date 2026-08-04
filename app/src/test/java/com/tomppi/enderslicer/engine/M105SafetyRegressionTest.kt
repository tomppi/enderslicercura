package com.tomppi.enderslicer.engine

import org.junit.Assert.assertTrue
import org.junit.Test

class M105SafetyRegressionTest {
    @Test
    fun parameterlessTemperatureQueryIsAllowedByEverySharedSafetyConsumer() {
        val command = requireNotNull(GcodeCommand.parse("M105"))

        GcodeCommandPolicy.requireCurviSupported(command, inPrintableLayers = false)
        GcodeCommandPolicy.requireCurviSupported(command, inPrintableLayers = true)
        GcodeCommandPolicy.requirePublishedSafe(command, currentLayer = 0, lineNumber = 1)
        GcodeCommandPolicy.requirePreviewSafe(command, spatialMovesSeen = 10)
    }

    @Test
    fun temperatureQueryWithUnmodeledParametersRemainsRejected() {
        val command = requireNotNull(GcodeCommand.parse("M105 S1"))

        assertTrue(runCatching {
            GcodeCommandPolicy.requireCurviSupported(command, inPrintableLayers = false)
        }.isFailure)
        assertTrue(runCatching {
            GcodeCommandPolicy.requirePublishedSafe(command, currentLayer = null, lineNumber = 1)
        }.isFailure)
        assertTrue(runCatching {
            GcodeCommandPolicy.requirePreviewSafe(command, spatialMovesSeen = 0)
        }.isFailure)
    }
}
