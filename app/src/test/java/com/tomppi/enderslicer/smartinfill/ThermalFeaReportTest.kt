package com.tomppi.enderslicer.smartinfill

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalFeaReportTest {
    @Test
    fun parsesAndCanonicalizesSupportedBuildSimulationReport() {
        val report = ThermalFeaReport.parse(
            payload = payload(),
            expectedSourceSha256 = SOURCE_SHA,
            expectedUpstreamCommit = COMMIT,
            nowEpochMillis = NOW,
        )

        assertEquals("PLA", report.materialName)
        assertEquals(ThermalFeaReport.PRECISION_SOURCE, report.precisionSource)
        assertEquals(0.42, report.releasedWarpMm, 1e-9)
        assertEquals(48, report.gridNx)
        assertEquals(73.25, report.meanIterationsPerLayer, 1e-9)
        assertTrue(report.densityAware)
        assertTrue(report.toMarkdown().contains("No absolute pass/fail threshold", ignoreCase = true))
        assertTrue(report.toMarkdown().contains("10.3390/ma17184668"))

        val canonical = JSONObject(report.toCanonicalJson())
        assertEquals(ThermalFeaReport.ANALYSIS_KIND, canonical.getString("analysisKind"))
        assertEquals(
            ThermalFeaReport.PRECISION_SOURCE,
            canonical.getString("precisionSource"),
        )
        assertFalse(canonical.getJSONObject("confidence").getBoolean("calibratedToPrinter"))
    }

    @Test
    fun rejectsReportForDifferentModelFingerprint() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ThermalFeaReport.parse(
                payload = payload(),
                expectedSourceSha256 = "b".repeat(64),
                expectedUpstreamCommit = COMMIT,
                nowEpochMillis = NOW,
            )
        }
        assertTrue(error.message.orEmpty().contains("does not match"))
    }

    @Test
    fun rejectsDisplayRoundedPrecisionSource() {
        val json = JSONObject(payload()).put("precisionSource", "formatted-dom")

        val error = assertThrows(IllegalArgumentException::class.java) {
            ThermalFeaReport.parse(
                payload = json.toString(),
                expectedSourceSha256 = SOURCE_SHA,
                expectedUpstreamCommit = COMMIT,
                nowEpochMillis = NOW,
            )
        }
        assertTrue(error.message.orEmpty().contains("exact worker response"))
    }

    @Test
    fun rejectsUnverifiedPrinterCalibrationClaim() {
        val json = JSONObject(payload())
        json.getJSONObject("confidence").put("calibratedToPrinter", true)

        val error = assertThrows(IllegalArgumentException::class.java) {
            ThermalFeaReport.parse(
                payload = json.toString(),
                expectedSourceSha256 = SOURCE_SHA,
                expectedUpstreamCommit = COMMIT,
                nowEpochMillis = NOW,
            )
        }
        assertTrue(error.message.orEmpty().contains("must not be claimed"))
    }

    @Test
    fun rejectsNegativeWarpMagnitude() {
        val json = JSONObject(payload())
        json.getJSONObject("results").put("releasedWarpMm", -0.01)

        assertThrows(IllegalArgumentException::class.java) {
            ThermalFeaReport.parse(
                payload = json.toString(),
                expectedSourceSha256 = SOURCE_SHA,
                expectedUpstreamCommit = COMMIT,
                nowEpochMillis = NOW,
            )
        }
    }

    @Test
    fun rejectsFractionalGridDimension() {
        val json = JSONObject(payload())
        json.getJSONObject("mesh").put("nx", 48.5)

        assertThrows(IllegalArgumentException::class.java) {
            ThermalFeaReport.parse(
                payload = json.toString(),
                expectedSourceSha256 = SOURCE_SHA,
                expectedUpstreamCommit = COMMIT,
                nowEpochMillis = NOW,
            )
        }
    }

    private fun payload(): String = JSONObject()
        .put("schemaVersion", ThermalFeaReport.SCHEMA_VERSION)
        .put("analysisKind", ThermalFeaReport.ANALYSIS_KIND)
        .put("solverModel", ThermalFeaReport.SOLVER_MODEL)
        .put("precisionSource", ThermalFeaReport.PRECISION_SOURCE)
        .put("sourceName", "bracket.stl")
        .put("sourceSha256", SOURCE_SHA)
        .put("upstreamCommit", COMMIT)
        .put("generatedAtEpochMillis", NOW)
        .put(
            "material",
            JSONObject()
                .put("name", "PLA")
                .put("shrinkXyPercent", 0.384)
                .put("shrinkZPercent", 0.192)
                .put("lockingTemperatureC", 60.0),
        )
        .put(
            "process",
            JSONObject()
                .put("bedTemperatureC", 60.0)
                .put("chamberTemperatureC", 25.0)
                .put("finalTemperatureC", 20.0)
                .put("densityAware", true),
        )
        .put(
            "mesh",
            JSONObject()
                .put("voxelSizeMm", 1.0)
                .put("nx", 48)
                .put("ny", 24)
                .put("nz", 32)
                .put("activeCells", 18_432)
                .put("buildLayers", 32),
        )
        .put(
            "results",
            JSONObject()
                .put("bondedWarpMm", 0.18)
                .put("releasedWarpMm", 0.42)
                .put("peakLiftMpa", 0.11)
                .put("peakShearMpa", 0.06)
                .put("solverSeconds", 14.5)
                .put("meanIterationsPerLayer", 73.25)
                .put("maxIterationsPerLayer", 132),
        )
        .put(
            "confidence",
            JSONObject()
                .put("level", ThermalFeaReport.CONFIDENCE_LEVEL)
                .put("calibratedToPrinter", false),
        )
        .toString()

    companion object {
        private const val NOW = 1_780_000_000_000L
        private const val SOURCE_SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val COMMIT = "e7485ec22d4ebe8baca04190404fbb877c90e031"
    }
}
