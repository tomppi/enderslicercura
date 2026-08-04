package com.tomppi.enderslicer.smartinfill

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalIntegrityReportTest {
    @Test
    fun parsesValidSteadyReportAndCanonicalizesFingerprint() {
        val report = parse(validPayload().toString())

        assertEquals("PLA", report.material.name)
        assertEquals(ThermalIntegrityReport.MODE_STEADY, report.boundary.mode)
        assertEquals(40.0, report.results.maximumTemperatureC, 0.0)
        assertTrue(report.analysisFingerprintSha256.matches(Regex("[0-9a-f]{64}")))

        val canonical = parse(report.toCanonicalJson())
        assertEquals(report.analysisFingerprintSha256, canonical.analysisFingerprintSha256)
        assertTrue(canonical.toMarkdown().contains("finite-volume"))
        assertTrue(canonical.toMarkdown().contains("not certification", ignoreCase = true))
    }

    @Test
    fun exactPoseChangesAnalysisFingerprint() {
        val first = parse(validPayload().toString())
        val changed = validPayload().apply {
            getJSONObject("pose").put(
                "transform3x4",
                JSONArray(listOf(1, 0, 0, 0, 1, 0, 0, 0, 1, 10, 0, 0)),
            )
        }
        val second = parse(changed.toString())

        assertNotEquals(first.analysisFingerprintSha256, second.analysisFingerprintSha256)
    }

    @Test
    fun heatBoundaryChangesAnalysisFingerprint() {
        val first = parse(validPayload().toString())
        val changed = validPayload().apply {
            getJSONObject("boundary").put("heatPowerW", 7.0)
            getJSONObject("results")
                .put("heatInputW", 7.0)
                .put("heatRejectedW", 7.0)
        }
        val second = parse(changed.toString())

        assertNotEquals(first.analysisFingerprintSha256, second.analysisFingerprintSha256)
    }

    @Test
    fun rejectsTamperedCanonicalInput() {
        val report = parse(validPayload().toString())
        val canonical = JSONObject(report.toCanonicalJson())
        canonical.getJSONObject("material").put("conductivityXWmK", 0.25)

        assertFailureContains(canonical, "fingerprint")
    }

    @Test
    fun rejectsSingularTransform() {
        val root = validPayload()
        root.getJSONObject("pose").put("transform3x4", JSONArray(List(12) { 0.0 }))

        assertFailureContains(root, "singular")
    }

    @Test
    fun rejectsWrongModelFingerprint() {
        val root = validPayload().put("sourceSha256", "c".repeat(64))

        assertFailureContains(root, "does not match")
    }

    @Test
    fun rejectsFormattedDomPrecisionSource() {
        val root = validPayload().put("precisionSource", "formatted-dom")

        assertFailureContains(root, "exact worker")
    }

    @Test
    fun rejectsFalsePrinterCalibrationClaim() {
        val root = validPayload()
        root.getJSONObject("confidence").put("calibratedToPrinter", true)

        assertFailureContains(root, "calibration")
    }

    @Test
    fun rejectsInconsistentTemperatureExtrema() {
        val root = validPayload()
        root.getJSONObject("results").put("meanTemperatureC", 45.0)

        assertFailureContains(root, "extrema")
    }

    @Test
    fun rejectsTemperatureMarginThatDoesNotMatchMaximum() {
        val root = validPayload()
        root.getJSONObject("results").put("temperatureMarginC", 11.0)

        assertFailureContains(root, "margin")
    }

    @Test
    fun rejectsTransientRequestAboveStepBudget() {
        val root = validPayload()
        root.getJSONObject("boundary")
            .put("mode", "transient")
            .put("durationSeconds", 2_001.0)
            .put("timeStepSeconds", 1.0)

        assertFailureContains(root, "step count")
    }

    @Test
    fun acceptsConsistentTransientMetadata() {
        val root = validPayload()
        root.getJSONObject("boundary")
            .put("mode", "transient")
            .put("durationSeconds", 10.0)
            .put("timeStepSeconds", 1.0)
        root.getJSONObject("results")
            .put("timeSteps", 10)
            .put("finalTimeSeconds", 10.0)
            .put("historyPoints", 11)
            .put("storageRateW", 1.0)
            .put("heatRejectedW", 4.0)
            .put("peakTemperatureC", 42.0)
            .put("peakTimeSeconds", 10.0)

        val report = parse(root.toString())

        assertEquals(ThermalIntegrityReport.MODE_TRANSIENT, report.boundary.mode)
        assertEquals(11, report.results.historyPoints)
    }

    @Test
    fun rejectsInvalidEmissivity() {
        val root = validPayload()
        root.getJSONObject("boundary").put("emissivity", 1.2)

        assertFailureContains(root, "emissivity")
    }

    @Test
    fun rejectsFractionalGridDimension() {
        val root = validPayload()
        root.getJSONObject("mesh").put("nx", 10.5)

        assertFailureContains(root, "integer")
    }

    @Test
    fun rejectsSameHeatedAndCooledFaceWithSurfacePower() {
        val root = validPayload()
        root.getJSONObject("boundary").put("cooledFace", "zmax")

        assertFailureContains(root, "must differ")
    }

    @Test
    fun rejectsServiceLimitAtOrBelowPropertyReference() {
        val root = validPayload()
        root.getJSONObject("material").put("serviceLimitC", 23.0)
        root.getJSONObject("results").put("temperatureMarginC", -17.0)

        assertFailureContains(root, "service limit")
    }

    private fun parse(payload: String): ThermalIntegrityReport =
        ThermalIntegrityReport.parse(
            payload = payload,
            expectedSourceSha256 = SOURCE_SHA,
            expectedUpstreamCommit = COMMIT,
            nowEpochMillis = NOW,
        )

    private fun assertFailureContains(root: JSONObject, expected: String) {
        val error = runCatching { parse(root.toString()) }.exceptionOrNull()
        assertTrue("Expected failure containing '$expected'", error != null)
        assertTrue(
            "Expected '${error?.message}' to contain '$expected'",
            error?.message.orEmpty().contains(expected, ignoreCase = true),
        )
    }

    private fun validPayload(): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("analysisKind", ThermalIntegrityReport.ANALYSIS_KIND)
        .put("solverModel", ThermalIntegrityReport.SOLVER_MODEL)
        .put("precisionSource", ThermalIntegrityReport.PRECISION_SOURCE)
        .put("sourceName", "thermal-bracket.stl")
        .put("sourceSha256", SOURCE_SHA)
        .put("upstreamCommit", COMMIT)
        .put("generatedAtEpochMillis", GENERATED_AT)
        .put(
            "pose",
            JSONObject().put(
                "transform3x4",
                JSONArray(listOf(1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0)),
            ),
        )
        .put(
            "material",
            JSONObject()
                .put("name", "PLA")
                .put("propertyBasis", ThermalIntegrityReport.PROPERTY_BASIS)
                .put("conductivityXWmK", 0.18)
                .put("conductivityYWmK", 0.18)
                .put("conductivityZWmK", 0.13)
                .put("densityKgM3", 1240.0)
                .put("specificHeatJkgK", 1800.0)
                .put("conductivityExponent", 1.0)
                .put("alphaXyPerK", 0.000096)
                .put("alphaZPerK", 0.00011)
                .put("youngsModulusMpa", 2400.0)
                .put("poissonRatio", 0.35)
                .put("referenceStrengthMpa", 45.0)
                .put("strengthDensityExponent", 1.5)
                .put("referenceTemperatureC", 23.0)
                .put("serviceLimitC", 50.0)
                .put("modulusFloorFraction", 0.05)
                .put("strengthFloorFraction", 0.05),
        )
        .put(
            "boundary",
            JSONObject()
                .put("mode", "steady")
                .put("heatedFace", "zmax")
                .put("cooledFace", "zmin")
                .put("heatPowerW", 5.0)
                .put("volumetricPowerW", 0.0)
                .put("ambientTemperatureC", 23.0)
                .put("initialTemperatureC", 23.0)
                .put("cooledTemperatureC", 23.0)
                .put("convectionWm2K", 8.0)
                .put("emissivity", 0.9)
                .put("durationSeconds", 600.0)
                .put("timeStepSeconds", 10.0)
                .put("freeExpansion", true)
                .put("densityAwareRequested", true)
                .put("infillPct", 25.0)
                .put("stiffnessExponent", 1.5)
                .put("stiffnessCoefficient", 1.0)
                .put("perimeters", 2)
                .put("lineWidthMm", 0.45)
                .put("topBottomLayers", 5)
                .put("layerHeightMm", 0.2),
        )
        .put(
            "mesh",
            JSONObject()
                .put("voxelSizeMm", 1.2)
                .put("nx", 10)
                .put("ny", 8)
                .put("nz", 6)
                .put("activeCells", 400),
        )
        .put(
            "results",
            JSONObject()
                .put("minimumTemperatureC", 25.0)
                .put("meanTemperatureC", 30.0)
                .put("maximumTemperatureC", 40.0)
                .put("hotspotMm", JSONArray(listOf(2.0, 3.0, 4.0)))
                .put("heatInputW", 5.0)
                .put("heatRejectedW", 5.0)
                .put("storageRateW", 0.0)
                .put("energyBalanceRelative", 0.0002)
                .put("thermalIterations", 84)
                .put("thermalResidual", 1.0e-8)
                .put("timeSteps", 0)
                .put("finalTimeSeconds", 0.0)
                .put("peakTemperatureC", 40.0)
                .put("peakTimeSeconds", 0.0)
                .put("heatedAreaMm2", 120.0)
                .put("cooledAreaMm2", 120.0)
                .put("maxDisplacementMm", 0.08)
                .put("maxVonMisesMpa", 4.5)
                .put("minimumModulusRetention", 0.42)
                .put("minimumStrengthRetention", 0.4)
                .put("conservativeSafetyFactor", 1.8)
                .put("temperatureMarginC", 10.0)
                .put("propertyExtrapolated", false)
                .put("densityAware", true)
                .put("structuralIterations", 18)
                .put("structuralResidual", 2.0e-6)
                .put("structuralConverged", true)
                .put("solverSeconds", 1.4)
                .put("historyPoints", 0),
        )
        .put(
            "confidence",
            JSONObject()
                .put("level", ThermalIntegrityReport.CONFIDENCE_LEVEL)
                .put("calibratedToPrinter", false),
        )

    private companion object {
        const val SOURCE_SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val COMMIT = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val GENERATED_AT = 1_700_000_000_000L
        const val NOW = 1_900_000_000_000L
    }
}
