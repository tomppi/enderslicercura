package com.tomppi.enderslicer.smartinfill

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * Auditable service-temperature thermal-integrity result.
 *
 * This is deliberately separate from [ThermalFeaReport], which models the FDM
 * build/cooldown process. This report covers a user-defined service heat source,
 * heat rejection, local temperature, thermal expansion, temperature-reduced
 * stiffness/strength and the current mechanical load case.
 */
internal data class ThermalIntegrityReport(
    val schemaVersion: Int,
    val precisionSource: String,
    val sourceName: String,
    val sourceSha256: String,
    val upstreamCommit: String,
    val analysisFingerprintSha256: String,
    val generatedAtEpochMillis: Long,
    /** filaSim layout: row-major 3×3 linear matrix followed by tx, ty, tz. */
    val modelTransform3x4: List<Double>,
    val material: Material,
    val boundary: Boundary,
    val mesh: Mesh,
    val results: Results,
) {
    internal data class Material(
        val name: String,
        val propertyBasis: String,
        val conductivityXWmK: Double,
        val conductivityYWmK: Double,
        val conductivityZWmK: Double,
        val densityKgM3: Double,
        val specificHeatJkgK: Double,
        val conductivityExponent: Double,
        val alphaXyPerK: Double,
        val alphaZPerK: Double,
        val youngsModulusMpa: Double,
        val poissonRatio: Double,
        val referenceStrengthMpa: Double,
        val strengthDensityExponent: Double,
        val referenceTemperatureC: Double,
        val serviceLimitC: Double,
        val modulusFloorFraction: Double,
        val strengthFloorFraction: Double,
    )

    internal data class Boundary(
        val mode: String,
        val heatedFace: String,
        val cooledFace: String,
        val heatPowerW: Double,
        val volumetricPowerW: Double,
        val ambientTemperatureC: Double,
        val initialTemperatureC: Double,
        val cooledTemperatureC: Double,
        val convectionWm2K: Double,
        val emissivity: Double,
        val durationSeconds: Double,
        val timeStepSeconds: Double,
        val freeExpansion: Boolean,
        val densityAwareRequested: Boolean,
        val infillPct: Double,
        val stiffnessExponent: Double,
        val stiffnessCoefficient: Double,
        val perimeters: Int,
        val lineWidthMm: Double,
        val topBottomLayers: Int,
        val layerHeightMm: Double,
    )

    internal data class Mesh(
        val voxelSizeMm: Double,
        val nx: Int,
        val ny: Int,
        val nz: Int,
        val activeCells: Int,
    )

    internal data class Results(
        val minimumTemperatureC: Double,
        val meanTemperatureC: Double,
        val maximumTemperatureC: Double,
        val hotspotMm: List<Double>,
        val heatInputW: Double,
        val heatRejectedW: Double,
        val storageRateW: Double,
        val energyBalanceRelative: Double,
        val thermalIterations: Int,
        val thermalResidual: Double,
        val timeSteps: Int,
        val finalTimeSeconds: Double,
        val peakTemperatureC: Double,
        val peakTimeSeconds: Double,
        val heatedAreaMm2: Double,
        val cooledAreaMm2: Double,
        val maxDisplacementMm: Double,
        val maxVonMisesMpa: Double,
        val minimumModulusRetention: Double,
        val minimumStrengthRetention: Double,
        val conservativeSafetyFactor: Double,
        val temperatureMarginC: Double,
        val propertyExtrapolated: Boolean,
        val densityAware: Boolean,
        val structuralIterations: Int,
        val structuralResidual: Double,
        val structuralConverged: Boolean,
        val solverSeconds: Double,
        val historyPoints: Int,
    )

    fun toCanonicalJson(): String = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("analysisKind", ANALYSIS_KIND)
        .put("solverModel", SOLVER_MODEL)
        .put("precisionSource", precisionSource)
        .put("sourceName", sourceName)
        .put("sourceSha256", sourceSha256)
        .put("upstreamCommit", upstreamCommit)
        .put("analysisFingerprintSha256", analysisFingerprintSha256)
        .put("generatedAtEpochMillis", generatedAtEpochMillis)
        .put("pose", JSONObject().put("transform3x4", modelTransform3x4.tiJsonArray()))
        .put("material", material.toJson())
        .put("boundary", boundary.toJson())
        .put("mesh", mesh.toJson())
        .put("results", results.toJson())
        .put(
            "confidence",
            JSONObject()
                .put("level", CONFIDENCE_LEVEL)
                .put("calibratedToPrinter", false),
        )
        .toString(2)

    fun summaryText(): String = buildString {
        appendLine("Model: $sourceName")
        appendLine("Material: ${material.name}")
        appendLine("Analysis: ${analysisFingerprintSha256.take(12)}…")
        appendLine("Mode: ${if (boundary.mode == MODE_TRANSIENT) "Transient" else "Steady state"}")
        appendLine()
        appendLine("Maximum temperature: ${tiTemperature(results.maximumTemperatureC)}")
        appendLine("Temperature margin: ${tiTemperatureDelta(results.temperatureMarginC)}")
        appendLine("Thermal deformation: ${tiLength(results.maxDisplacementMm)}")
        appendLine("Maximum von Mises stress: ${tiStress(results.maxVonMisesMpa)}")
        appendLine("Conservative safety factor: ${tiNumber(results.conservativeSafetyFactor)}")
        appendLine("Energy imbalance: ${tiPercent(results.energyBalanceRelative * 100.0)}")
        if (results.propertyExtrapolated) {
            appendLine()
            appendLine("Warning: the solved temperature is outside the material preset range.")
        }
        appendLine()
        append("Experimental literature-seeded estimate; not certification.")
    }

    fun toMarkdown(): String = buildString {
        appendLine("# EnderSlicerCura thermal integrity report")
        appendLine()
        appendLine("> Experimental service-temperature heat-transfer and thermo-mechanical estimate. This is not certification, a warranty of service life, or a substitute for physical validation.")
        appendLine()
        appendLine("## Identity")
        appendLine()
        appendLine("| Field | Value |")
        appendLine("|---|---|")
        appendLine("| Model | ${tiMarkdown(sourceName)} |")
        appendLine("| Model SHA-256 | `$sourceSha256` |")
        appendLine("| Analysis fingerprint | `$analysisFingerprintSha256` |")
        appendLine("| filaSim commit | `$upstreamCommit` |")
        appendLine("| Numeric source | Exact final `thermalIntegrity` worker response |")
        appendLine("| Generated | ${DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(generatedAtEpochMillis))} |")
        appendLine()
        appendLine("## Solved pose")
        appendLine()
        appendLine("The source hash and cumulative filaSim transform are included in the analysis fingerprint. The transform uses nine row-major linear terms followed by tx, ty and tz:")
        appendLine()
        appendLine("```text")
        appendLine(transformRow(0, 1, 2, 9))
        appendLine(transformRow(3, 4, 5, 10))
        appendLine(transformRow(6, 7, 8, 11))
        appendLine("```")
        appendLine()
        appendLine("## Material model")
        appendLine()
        appendLine("| Input | Value |")
        appendLine("|---|---:|")
        appendLine("| Material | ${tiMarkdown(material.name)} |")
        appendLine("| Property basis | ${tiMarkdown(material.propertyBasis)} |")
        appendLine("| Conductivity X / Y / Z | ${tiNumber(material.conductivityXWmK)} / ${tiNumber(material.conductivityYWmK)} / ${tiNumber(material.conductivityZWmK)} W/(m·K) |")
        appendLine("| Density | ${tiNumber(material.densityKgM3)} kg/m³ |")
        appendLine("| Specific heat | ${tiNumber(material.specificHeatJkgK)} J/(kg·K) |")
        appendLine("| Conductivity-density exponent | ${tiNumber(material.conductivityExponent)} |")
        appendLine("| CTE XY / Z | ${tiScientific(material.alphaXyPerK)} / ${tiScientific(material.alphaZPerK)} 1/K |")
        appendLine("| Young's modulus at reference | ${tiStress(material.youngsModulusMpa)} |")
        appendLine("| Poisson ratio | ${tiNumber(material.poissonRatio)} |")
        appendLine("| Reference strength | ${tiStress(material.referenceStrengthMpa)} |")
        appendLine("| Strength-density exponent | ${tiNumber(material.strengthDensityExponent)} |")
        appendLine("| Property reference temperature | ${tiTemperature(material.referenceTemperatureC)} |")
        appendLine("| Literature-seeded service limit | ${tiTemperature(material.serviceLimitC)} |")
        appendLine("| Modulus / strength retention floor | ${tiPercent(material.modulusFloorFraction * 100.0)} / ${tiPercent(material.strengthFloorFraction * 100.0)} |")
        appendLine()
        appendLine("## Thermal and structural boundaries")
        appendLine()
        appendLine("| Input | Value |")
        appendLine("|---|---:|")
        appendLine("| Solve mode | ${if (boundary.mode == MODE_TRANSIENT) "Implicit transient" else "Steady state"} |")
        appendLine("| Heated / fixed-temperature faces | ${boundary.heatedFace} / ${boundary.cooledFace} |")
        appendLine("| Surface / volumetric heat power | ${tiPower(boundary.heatPowerW)} / ${tiPower(boundary.volumetricPowerW)} |")
        appendLine("| Ambient / initial / fixed-surface temperature | ${tiTemperature(boundary.ambientTemperatureC)} / ${tiTemperature(boundary.initialTemperatureC)} / ${tiTemperature(boundary.cooledTemperatureC)} |")
        appendLine("| Convection coefficient | ${tiNumber(boundary.convectionWm2K)} W/(m²·K) |")
        appendLine("| Emissivity | ${tiNumber(boundary.emissivity)} |")
        if (boundary.mode == MODE_TRANSIENT) {
            appendLine("| Duration / time step | ${tiNumber(boundary.durationSeconds)} s / ${tiNumber(boundary.timeStepSeconds)} s |")
        }
        appendLine("| Structural constraints | ${if (boundary.freeExpansion) "Stress-free 3-2-1 grounding; user supports removed, mechanical loads retained" else "Current filaSim supports and mechanical loads"} |")
        appendLine("| Density-aware requested | ${boundary.densityAwareRequested} |")
        appendLine("| Fallback infill | ${tiPercent(boundary.infillPct)} |")
        appendLine("| Stiffness law coefficient · density^exponent | ${tiNumber(boundary.stiffnessCoefficient)} · ρ^${tiNumber(boundary.stiffnessExponent)} |")
        appendLine("| Perimeters / line width | ${boundary.perimeters} / ${tiLength(boundary.lineWidthMm)} |")
        appendLine("| Top-bottom layers / layer height | ${boundary.topBottomLayers} / ${tiLength(boundary.layerHeightMm)} |")
        appendLine()
        appendLine("## Grid")
        appendLine()
        appendLine("| Field | Value |")
        appendLine("|---|---:|")
        appendLine("| Voxel size | ${tiLength(mesh.voxelSizeMm)} |")
        appendLine("| Grid | ${mesh.nx} × ${mesh.ny} × ${mesh.nz} |")
        appendLine("| Active material cells | ${mesh.activeCells} |")
        appendLine()
        appendLine("## Calculated results")
        appendLine()
        appendLine("| Result | Value |")
        appendLine("|---|---:|")
        appendLine("| Minimum / mean / maximum temperature | ${tiTemperature(results.minimumTemperatureC)} / ${tiTemperature(results.meanTemperatureC)} / ${tiTemperature(results.maximumTemperatureC)} |")
        appendLine("| Hotspot X / Y / Z | ${results.hotspotMm.joinToString(" / ") { tiLength(it) }} |")
        appendLine("| Temperature margin to preset limit | ${tiTemperatureDelta(results.temperatureMarginC)} |")
        appendLine("| Peak transient temperature / time | ${tiTemperature(results.peakTemperatureC)} / ${tiNumber(results.peakTimeSeconds)} s |")
        appendLine("| Heat input / rejected / stored-rate | ${tiPower(results.heatInputW)} / ${tiPower(results.heatRejectedW)} / ${tiPower(results.storageRateW)} |")
        appendLine("| Energy-balance residual | ${tiPercent(results.energyBalanceRelative * 100.0)} |")
        appendLine("| Heated / fixed-temperature area | ${tiArea(results.heatedAreaMm2)} / ${tiArea(results.cooledAreaMm2)} |")
        appendLine("| Maximum coupled deformation | ${tiLength(results.maxDisplacementMm)} |")
        appendLine("| Maximum thermal-mechanical von Mises stress | ${tiStress(results.maxVonMisesMpa)} |")
        appendLine("| Minimum modulus / strength retention | ${tiPercent(results.minimumModulusRetention * 100.0)} / ${tiPercent(results.minimumStrengthRetention * 100.0)} |")
        appendLine("| Conservative material safety factor | ${tiNumber(results.conservativeSafetyFactor)} |")
        appendLine("| Optimized density actually used | ${results.densityAware} |")
        appendLine("| Material property extrapolation | ${results.propertyExtrapolated} |")
        appendLine("| Thermal iterations / residual | ${results.thermalIterations} / ${tiScientific(results.thermalResidual)} |")
        appendLine("| Structural iterations / residual / converged | ${results.structuralIterations} / ${tiScientific(results.structuralResidual)} / ${results.structuralConverged} |")
        appendLine("| Time steps / final time / history points | ${results.timeSteps} / ${tiNumber(results.finalTimeSeconds)} s / ${results.historyPoints} |")
        appendLine("| Combined solver time | ${tiNumber(results.solverSeconds)} s |")
        appendLine()
        appendLine("## Method and interpretation")
        appendLine()
        appendLine("- Cell-centred finite-volume conduction on filaSim's voxelized printed-part material field.")
        appendLine("- Harmonic face conductivity and separate X/Y/Z conductivity inputs model print anisotropy.")
        appendLine("- Exposed faces reject heat by convection and nonlinear Stefan–Boltzmann radiation; the selected cooled face uses a fixed-temperature boundary.")
        appendLine("- Transient mode uses an unconditionally stable implicit-Euler heat-capacity step. Steady mode iterates the radiation boundary to consistency.")
        appendLine("- Local temperature creates XY/Z thermal eigenstrain and reduces local stiffness and allowable strength between the reference and service-limit temperatures.")
        appendLine("- The structural solve retains current filaSim mechanical loads. Free-expansion mode replaces supports with a minimal stress-free 3-2-1 grounding; constrained mode uses the selected supports.")
        appendLine("- The reported safety factor is the minimum temperature- and density-reduced material allowable divided by local von Mises stress. It is conservative but not a certified failure probability.")
        appendLine("- Energy imbalance compares heat input with rejected heat and transient stored-energy rate. A large residual is a numerical warning, not a physical result.")
        appendLine()
        appendLine("## Applicability limits")
        appendLine()
        appendLine("This implementation does **not** model G-code/nozzle-path reheating, interlayer weld kinetics or delamination probability, temperature-dependent creep, fatigue, moisture, aging, phase change, enclosure airflow CFD, or certified thermal contact resistance. The face-based power and fixed-temperature boundaries are engineering abstractions. Material properties vary with brand, pigment, moisture, print orientation, raster pattern and process history. Validate critical designs with instrumented temperature and load tests.")
        appendLine()
        appendLine("A negative temperature margin or a safety factor below one is a warning under the entered assumptions. A positive margin or safety factor above one is **not** proof of service life or regulatory compliance.")
        appendLine()
        appendLine("## Literature provenance")
        appendLine()
        appendLine("- Printed PLA/PET-G/ABS thermal-conductivity measurements across temperature, infill and pattern: DOI `10.3390/ma18173950`.")
        appendLine("- Printed PLA coefficient of thermal expansion measurements: DOI `10.3390/ma17184668`.")
        appendLine("- Orientation-dependent printed ABS thermal expansion: DOI `10.3390/nano8010049`.")
        appendLine()
        appendLine("These references seed the presets; they do not calibrate the user's spool, printer, profile, interfaces or environment.")
    }

    private fun transformRow(a: Int, b: Int, c: Int, t: Int): String =
        listOf(modelTransform3x4[a], modelTransform3x4[b], modelTransform3x4[c], modelTransform3x4[t])
            .joinToString(prefix = "[ ", postfix = " ]", separator = "  ") { tiNumber(it) }

    companion object {
        const val SCHEMA_VERSION = 1
        const val ANALYSIS_KIND = "fdm-service-thermal-integrity"
        const val SOLVER_MODEL = "voxel-finite-volume-implicit-thermomechanical"
        const val PRECISION_SOURCE = "raw-worker-response"
        const val CONFIDENCE_LEVEL = "experimental-literature-seeded"
        const val PROPERTY_BASIS = "literature-seeded"
        const val MODE_STEADY = "steady"
        const val MODE_TRANSIENT = "transient"

        private val SHA_PATTERN = Regex("[0-9a-f]{64}")
        private val COMMIT_PATTERN = Regex("[0-9a-f]{40}")
        private val FACES = setOf("xmin", "xmax", "ymin", "ymax", "zmin", "zmax")

        fun parse(
            payload: String,
            expectedSourceSha256: String,
            expectedUpstreamCommit: String,
            nowEpochMillis: Long = System.currentTimeMillis(),
        ): ThermalIntegrityReport {
            require(payload.length in 2..MAX_TI_JSON_CHARS) { "Thermal integrity report size is invalid" }
            require(expectedSourceSha256.matches(SHA_PATTERN)) { "Expected model fingerprint is invalid" }
            require(expectedUpstreamCommit.matches(COMMIT_PATTERN)) { "Expected filaSim commit is invalid" }

            val root = JSONObject(payload)
            val schema = root.tiInt("schemaVersion", SCHEMA_VERSION..SCHEMA_VERSION)
            require(root.tiText("analysisKind", 64) == ANALYSIS_KIND) {
                "Unsupported thermal integrity analysis kind"
            }
            require(root.tiText("solverModel", 96) == SOLVER_MODEL) {
                "Unsupported thermal integrity solver model"
            }
            val precision = root.tiText("precisionSource", 64)
            require(precision == PRECISION_SOURCE) {
                "Thermal integrity report did not originate from the exact worker response"
            }
            val sourceName = root.tiText("sourceName", 240).tiSafeFileName()
            val sourceSha = root.tiText("sourceSha256", 64)
            require(sourceSha.matches(SHA_PATTERN) && sourceSha == expectedSourceSha256) {
                "Thermal integrity result does not match the analyzed STL"
            }
            val commit = root.tiText("upstreamCommit", 40)
            require(commit.matches(COMMIT_PATTERN) && commit == expectedUpstreamCommit) {
                "Thermal integrity result came from an unexpected filaSim build"
            }
            val generatedAt = root.tiLong("generatedAtEpochMillis")
            require(generatedAt in MIN_TI_TIMESTAMP_MILLIS..(nowEpochMillis + MAX_TI_FUTURE_SKEW_MILLIS)) {
                "Thermal integrity report timestamp is invalid"
            }

            val transform = root.tiObject("pose")
                .tiFiniteArray("transform3x4", 12, -MAX_TI_TRANSFORM_COMPONENT..MAX_TI_TRANSFORM_COMPONENT)
            requireValidTransform(transform)

            val materialObject = root.tiObject("material")
            val boundaryObject = root.tiObject("boundary")
            val meshObject = root.tiObject("mesh")
            val resultObject = root.tiObject("results")
            val confidence = root.tiObject("confidence")
            require(confidence.tiText("level", 64) == CONFIDENCE_LEVEL) {
                "Thermal integrity confidence label is missing"
            }
            require(!confidence.optBoolean("calibratedToPrinter", true)) {
                "Unverified printer calibration must not be claimed"
            }

            val material = Material(
                name = materialObject.tiText("name", 120),
                propertyBasis = materialObject.tiText("propertyBasis", 64).also {
                    require(it == PROPERTY_BASIS) { "Thermal material property basis is unsupported" }
                },
                conductivityXWmK = materialObject.tiFinite("conductivityXWmK", 0.005..1_000.0),
                conductivityYWmK = materialObject.tiFinite("conductivityYWmK", 0.005..1_000.0),
                conductivityZWmK = materialObject.tiFinite("conductivityZWmK", 0.005..1_000.0),
                densityKgM3 = materialObject.tiFinite("densityKgM3", 50.0..30_000.0),
                specificHeatJkgK = materialObject.tiFinite("specificHeatJkgK", 50.0..10_000.0),
                conductivityExponent = materialObject.tiFinite("conductivityExponent", 0.25..4.0),
                alphaXyPerK = materialObject.tiFinite("alphaXyPerK", 0.0..0.01),
                alphaZPerK = materialObject.tiFinite("alphaZPerK", 0.0..0.01),
                youngsModulusMpa = materialObject.tiFinite("youngsModulusMpa", 1.0..10_000_000.0),
                poissonRatio = materialObject.tiFinite("poissonRatio", -0.49..0.49),
                referenceStrengthMpa = materialObject.tiFinite("referenceStrengthMpa", 0.1..10_000.0),
                strengthDensityExponent = materialObject.tiFinite("strengthDensityExponent", 0.5..4.0),
                referenceTemperatureC = materialObject.tiFinite("referenceTemperatureC", -200.0..1_000.0),
                serviceLimitC = materialObject.tiFinite("serviceLimitC", -100.0..1_000.0),
                modulusFloorFraction = materialObject.tiFinite("modulusFloorFraction", 0.001..1.0),
                strengthFloorFraction = materialObject.tiFinite("strengthFloorFraction", 0.001..1.0),
            )
            require(material.serviceLimitC > material.referenceTemperatureC) {
                "Thermal material service limit must exceed its reference temperature"
            }

            val mode = boundaryObject.tiText("mode", 16).also {
                require(it == MODE_STEADY || it == MODE_TRANSIENT) {
                    "Thermal integrity solve mode is invalid"
                }
            }
            val heatedFace = boundaryObject.tiText("heatedFace", 8).also {
                require(it in FACES) { "Thermal heated face is invalid" }
            }
            val cooledFace = boundaryObject.tiText("cooledFace", 8).also {
                require(it in FACES) { "Thermal cooled face is invalid" }
            }
            val boundary = Boundary(
                mode = mode,
                heatedFace = heatedFace,
                cooledFace = cooledFace,
                heatPowerW = boundaryObject.tiFinite("heatPowerW", 0.0..100_000.0),
                volumetricPowerW = boundaryObject.tiFinite("volumetricPowerW", 0.0..100_000.0),
                ambientTemperatureC = boundaryObject.tiFinite("ambientTemperatureC", -200.0..1_000.0),
                initialTemperatureC = boundaryObject.tiFinite("initialTemperatureC", -200.0..1_000.0),
                cooledTemperatureC = boundaryObject.tiFinite("cooledTemperatureC", -200.0..1_000.0),
                convectionWm2K = boundaryObject.tiFinite("convectionWm2K", 0.0..100_000.0),
                emissivity = boundaryObject.tiFinite("emissivity", 0.0..1.0),
                durationSeconds = boundaryObject.tiFinite("durationSeconds", 0.01..31_536_000.0),
                timeStepSeconds = boundaryObject.tiFinite("timeStepSeconds", 0.0001..86_400.0),
                freeExpansion = boundaryObject.getBoolean("freeExpansion"),
                densityAwareRequested = boundaryObject.getBoolean("densityAwareRequested"),
                infillPct = boundaryObject.tiFinite("infillPct", 1.0..100.0),
                stiffnessExponent = boundaryObject.tiFinite("stiffnessExponent", 1.0..3.5),
                stiffnessCoefficient = boundaryObject.tiFinite("stiffnessCoefficient", 0.05..2.0),
                perimeters = boundaryObject.tiWhole("perimeters", 0..20),
                lineWidthMm = boundaryObject.tiFinite("lineWidthMm", 0.05..5.0),
                topBottomLayers = boundaryObject.tiWhole("topBottomLayers", 0..20),
                layerHeightMm = boundaryObject.tiFinite("layerHeightMm", 0.04..0.6),
            )
            require(boundary.heatPowerW == 0.0 || boundary.heatedFace != boundary.cooledFace) {
                "Thermal heated and fixed-temperature faces must differ"
            }
            val requestedSteps = kotlin.math.ceil(boundary.durationSeconds / boundary.timeStepSeconds).toLong()
            require(mode != MODE_TRANSIENT || requestedSteps in 1..MAX_TI_TRANSIENT_STEPS.toLong()) {
                "Thermal transient request exceeds the supported step count"
            }

            val mesh = Mesh(
                voxelSizeMm = meshObject.tiFinite("voxelSizeMm", 0.01..100.0),
                nx = meshObject.tiWhole("nx", 1..100_000),
                ny = meshObject.tiWhole("ny", 1..100_000),
                nz = meshObject.tiWhole("nz", 1..100_000),
                activeCells = meshObject.tiWhole("activeCells", 1..100_000_000),
            )
            val gridCells = mesh.nx.toLong() * mesh.ny.toLong() * mesh.nz.toLong()
            require(mesh.activeCells.toLong() <= gridCells) {
                "Thermal active-cell count exceeds the grid"
            }

            val results = Results(
                minimumTemperatureC = resultObject.tiFinite("minimumTemperatureC", -272.0..2_000.0),
                meanTemperatureC = resultObject.tiFinite("meanTemperatureC", -272.0..2_000.0),
                maximumTemperatureC = resultObject.tiFinite("maximumTemperatureC", -272.0..2_000.0),
                hotspotMm = resultObject.tiFiniteArray("hotspotMm", 3, -1.0e9..1.0e9),
                heatInputW = resultObject.tiFinite("heatInputW", 0.0..200_000.0),
                heatRejectedW = resultObject.tiFinite("heatRejectedW", -200_000.0..200_000.0),
                storageRateW = resultObject.tiFinite("storageRateW", -200_000.0..200_000.0),
                energyBalanceRelative = resultObject.tiFinite("energyBalanceRelative", 0.0..10_000.0),
                thermalIterations = resultObject.tiWhole("thermalIterations", 0..100_000_000),
                thermalResidual = resultObject.tiFinite("thermalResidual", 0.0..1.0e6),
                timeSteps = resultObject.tiWhole("timeSteps", 0..MAX_TI_TRANSIENT_STEPS),
                finalTimeSeconds = resultObject.tiFinite("finalTimeSeconds", 0.0..31_536_000.0),
                peakTemperatureC = resultObject.tiFinite("peakTemperatureC", -272.0..2_000.0),
                peakTimeSeconds = resultObject.tiFinite("peakTimeSeconds", 0.0..31_536_000.0),
                heatedAreaMm2 = resultObject.tiFinite("heatedAreaMm2", 0.0..1.0e12),
                cooledAreaMm2 = resultObject.tiFinite("cooledAreaMm2", 0.0..1.0e12),
                maxDisplacementMm = resultObject.tiFinite("maxDisplacementMm", 0.0..1.0e6),
                maxVonMisesMpa = resultObject.tiFinite("maxVonMisesMpa", 0.0..1.0e7),
                minimumModulusRetention = resultObject.tiFinite("minimumModulusRetention", 0.0..1.0),
                minimumStrengthRetention = resultObject.tiFinite("minimumStrengthRetention", 0.0..1.0),
                conservativeSafetyFactor = resultObject.tiFinite("conservativeSafetyFactor", 0.0..10.0),
                temperatureMarginC = resultObject.tiFinite("temperatureMarginC", -2_000.0..2_000.0),
                propertyExtrapolated = resultObject.getBoolean("propertyExtrapolated"),
                densityAware = resultObject.getBoolean("densityAware"),
                structuralIterations = resultObject.tiWhole("structuralIterations", 0..100_000_000),
                structuralResidual = resultObject.tiFinite("structuralResidual", 0.0..1.0e6),
                structuralConverged = resultObject.getBoolean("structuralConverged"),
                solverSeconds = resultObject.tiFinite("solverSeconds", 0.0..604_800.0),
                historyPoints = resultObject.tiWhole("historyPoints", 0..(MAX_TI_TRANSIENT_STEPS + 1)),
            )
            require(results.minimumTemperatureC <= results.meanTemperatureC + NUMERIC_TOLERANCE &&
                results.meanTemperatureC <= results.maximumTemperatureC + NUMERIC_TOLERANCE
            ) {
                "Thermal temperature extrema are inconsistent"
            }
            require(results.peakTemperatureC + NUMERIC_TOLERANCE >= results.maximumTemperatureC) {
                "Thermal peak temperature is below the final maximum"
            }
            require(abs(results.temperatureMarginC - (material.serviceLimitC - results.maximumTemperatureC)) <=
                MARGIN_TOLERANCE_C
            ) {
                "Thermal temperature margin does not match the solved maximum"
            }
            require(
                if (mode == MODE_TRANSIENT) {
                    results.timeSteps in 1..MAX_TI_TRANSIENT_STEPS &&
                        results.historyPoints == results.timeSteps + 1 &&
                        abs(results.finalTimeSeconds - boundary.durationSeconds) <=
                        maxOf(1.0e-6, boundary.durationSeconds * 1.0e-6)
                } else {
                    results.timeSteps == 0 && results.historyPoints == 0 && results.finalTimeSeconds == 0.0
                },
            ) {
                "Thermal time-history metadata is inconsistent with the requested mode"
            }

            val fingerprint = fingerprint(
                schemaVersion = schema,
                precisionSource = precision,
                sourceSha256 = sourceSha,
                upstreamCommit = commit,
                transform = transform,
                material = material,
                boundary = boundary,
                mesh = mesh,
            )
            val supplied = root.optString("analysisFingerprintSha256", "").trim()
            if (supplied.isNotEmpty()) {
                require(supplied.matches(SHA_PATTERN) && supplied == fingerprint) {
                    "Thermal integrity analysis fingerprint does not match its inputs"
                }
            }

            return ThermalIntegrityReport(
                schemaVersion = schema,
                precisionSource = precision,
                sourceName = sourceName,
                sourceSha256 = sourceSha,
                upstreamCommit = commit,
                analysisFingerprintSha256 = fingerprint,
                generatedAtEpochMillis = generatedAt,
                modelTransform3x4 = transform,
                material = material,
                boundary = boundary,
                mesh = mesh,
                results = results,
            )
        }

        private fun fingerprint(
            schemaVersion: Int,
            precisionSource: String,
            sourceSha256: String,
            upstreamCommit: String,
            transform: List<Double>,
            material: Material,
            boundary: Boundary,
            mesh: Mesh,
        ): String {
            val values = buildList {
                add("enderslicercura-thermal-integrity-input-v1")
                add(schemaVersion.toString())
                add(ANALYSIS_KIND)
                add(SOLVER_MODEL)
                add(precisionSource)
                add(sourceSha256)
                add(upstreamCommit)
                transform.forEach { add(it.tiStable()) }
                with(material) {
                    add(name)
                    add(propertyBasis)
                    add(conductivityXWmK.tiStable())
                    add(conductivityYWmK.tiStable())
                    add(conductivityZWmK.tiStable())
                    add(densityKgM3.tiStable())
                    add(specificHeatJkgK.tiStable())
                    add(conductivityExponent.tiStable())
                    add(alphaXyPerK.tiStable())
                    add(alphaZPerK.tiStable())
                    add(youngsModulusMpa.tiStable())
                    add(poissonRatio.tiStable())
                    add(referenceStrengthMpa.tiStable())
                    add(strengthDensityExponent.tiStable())
                    add(referenceTemperatureC.tiStable())
                    add(serviceLimitC.tiStable())
                    add(modulusFloorFraction.tiStable())
                    add(strengthFloorFraction.tiStable())
                }
                with(boundary) {
                    add(mode)
                    add(heatedFace)
                    add(cooledFace)
                    add(heatPowerW.tiStable())
                    add(volumetricPowerW.tiStable())
                    add(ambientTemperatureC.tiStable())
                    add(initialTemperatureC.tiStable())
                    add(cooledTemperatureC.tiStable())
                    add(convectionWm2K.tiStable())
                    add(emissivity.tiStable())
                    add(durationSeconds.tiStable())
                    add(timeStepSeconds.tiStable())
                    add(freeExpansion.toString())
                    add(densityAwareRequested.toString())
                    add(infillPct.tiStable())
                    add(stiffnessExponent.tiStable())
                    add(stiffnessCoefficient.tiStable())
                    add(perimeters.toString())
                    add(lineWidthMm.tiStable())
                    add(topBottomLayers.toString())
                    add(layerHeightMm.tiStable())
                }
                with(mesh) {
                    add(voxelSizeMm.tiStable())
                    add(nx.toString())
                    add(ny.toString())
                    add(nz.toString())
                    add(activeCells.toString())
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            values.forEach { value ->
                digest.update(value.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            return digest.digest().joinToString("") { byte ->
                "%02x".format(Locale.US, byte.toInt() and 0xff)
            }
        }

        private fun requireValidTransform(transform: List<Double>) {
            val a = transform[0]
            val b = transform[1]
            val c = transform[2]
            val d = transform[3]
            val e = transform[4]
            val f = transform[5]
            val g = transform[6]
            val h = transform[7]
            val i = transform[8]
            val determinant = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
            require(determinant.isFinite() && abs(determinant) > MIN_TI_TRANSFORM_DETERMINANT) {
                "Thermal integrity model transform is singular"
            }
        }
    }
}

internal data class StoredThermalIntegrityReport(
    val report: ThermalIntegrityReport,
    val jsonFile: File,
    val markdownFile: File,
)

internal class ThermalIntegrityReportStore(context: Context) {
    private val root = File(context.filesDir, "thermal-integrity-reports").apply { mkdirs() }

    fun save(
        payload: String,
        expectedSourceSha256: String,
        expectedUpstreamCommit: String,
    ): StoredThermalIntegrityReport {
        val report = ThermalIntegrityReport.parse(payload, expectedSourceSha256, expectedUpstreamCommit)
        val base = "${report.sourceSha256}-${report.analysisFingerprintSha256}"
        val json = File(root, "$base.json")
        val markdown = File(root, "$base.md")
        writeAtomic(json, report.toCanonicalJson())
        writeAtomic(markdown, report.toMarkdown())
        cleanup(base)
        return StoredThermalIntegrityReport(report, json, markdown)
    }

    fun load(
        sourceSha256: String,
        expectedUpstreamCommit: String,
    ): StoredThermalIntegrityReport? {
        if (!sourceSha256.matches(Regex("[0-9a-f]{64}"))) return null
        val candidates = root.listFiles().orEmpty()
            .filter {
                it.isFile && it.extension == "json" &&
                    it.nameWithoutExtension.startsWith("$sourceSha256-")
            }
            .sortedByDescending(File::lastModified)
        for (json in candidates) {
            val markdown = File(root, "${json.nameWithoutExtension}.md")
            val stored = runCatching {
                require(json.length() in 2..MAX_TI_JSON_BYTES) {
                    "Stored thermal integrity JSON size is invalid"
                }
                val report = ThermalIntegrityReport.parse(
                    json.readText(),
                    expectedSourceSha256 = sourceSha256,
                    expectedUpstreamCommit = expectedUpstreamCommit,
                )
                require(json.nameWithoutExtension ==
                    "${report.sourceSha256}-${report.analysisFingerprintSha256}"
                ) {
                    "Stored thermal integrity filename does not match its analysis fingerprint"
                }
                if (!markdown.isFile || markdown.length() !in 2..MAX_TI_MARKDOWN_BYTES) {
                    writeAtomic(markdown, report.toMarkdown())
                }
                StoredThermalIntegrityReport(report, json, markdown)
            }.getOrNull()
            if (stored != null) return stored
            json.delete()
            markdown.delete()
        }
        return null
    }

    private fun cleanup(activeBase: String) {
        val validBase = Regex("[0-9a-f]{64}-[0-9a-f]{64}")
        val bases = root.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" && it.nameWithoutExtension.matches(validBase) }
            .sortedByDescending(File::lastModified)
            .map(File::nameWithoutExtension)
        val keep = (listOf(activeBase) + bases.filter { it != activeBase })
            .take(MAX_TI_STORED_REPORTS)
            .toSet()
        root.listFiles().orEmpty()
            .filter(File::isFile)
            .filter {
                it.extension == "json" || it.extension == "md" || it.name.endsWith(".next")
            }
            .filter { it.nameWithoutExtension !in keep }
            .forEach(File::delete)
    }

    private fun writeAtomic(target: File, text: String) {
        val staging = File(root, ".${target.name}.${UUID.randomUUID()}.next")
        try {
            FileOutputStream(staging).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            runCatching {
                Files.move(
                    staging.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            staging.delete()
        }
    }
}

private fun ThermalIntegrityReport.Material.toJson(): JSONObject = JSONObject()
    .put("name", name)
    .put("propertyBasis", propertyBasis)
    .put("conductivityXWmK", conductivityXWmK)
    .put("conductivityYWmK", conductivityYWmK)
    .put("conductivityZWmK", conductivityZWmK)
    .put("densityKgM3", densityKgM3)
    .put("specificHeatJkgK", specificHeatJkgK)
    .put("conductivityExponent", conductivityExponent)
    .put("alphaXyPerK", alphaXyPerK)
    .put("alphaZPerK", alphaZPerK)
    .put("youngsModulusMpa", youngsModulusMpa)
    .put("poissonRatio", poissonRatio)
    .put("referenceStrengthMpa", referenceStrengthMpa)
    .put("strengthDensityExponent", strengthDensityExponent)
    .put("referenceTemperatureC", referenceTemperatureC)
    .put("serviceLimitC", serviceLimitC)
    .put("modulusFloorFraction", modulusFloorFraction)
    .put("strengthFloorFraction", strengthFloorFraction)

private fun ThermalIntegrityReport.Boundary.toJson(): JSONObject = JSONObject()
    .put("mode", mode)
    .put("heatedFace", heatedFace)
    .put("cooledFace", cooledFace)
    .put("heatPowerW", heatPowerW)
    .put("volumetricPowerW", volumetricPowerW)
    .put("ambientTemperatureC", ambientTemperatureC)
    .put("initialTemperatureC", initialTemperatureC)
    .put("cooledTemperatureC", cooledTemperatureC)
    .put("convectionWm2K", convectionWm2K)
    .put("emissivity", emissivity)
    .put("durationSeconds", durationSeconds)
    .put("timeStepSeconds", timeStepSeconds)
    .put("freeExpansion", freeExpansion)
    .put("densityAwareRequested", densityAwareRequested)
    .put("infillPct", infillPct)
    .put("stiffnessExponent", stiffnessExponent)
    .put("stiffnessCoefficient", stiffnessCoefficient)
    .put("perimeters", perimeters)
    .put("lineWidthMm", lineWidthMm)
    .put("topBottomLayers", topBottomLayers)
    .put("layerHeightMm", layerHeightMm)

private fun ThermalIntegrityReport.Mesh.toJson(): JSONObject = JSONObject()
    .put("voxelSizeMm", voxelSizeMm)
    .put("nx", nx)
    .put("ny", ny)
    .put("nz", nz)
    .put("activeCells", activeCells)

private fun ThermalIntegrityReport.Results.toJson(): JSONObject = JSONObject()
    .put("minimumTemperatureC", minimumTemperatureC)
    .put("meanTemperatureC", meanTemperatureC)
    .put("maximumTemperatureC", maximumTemperatureC)
    .put("hotspotMm", hotspotMm.tiJsonArray())
    .put("heatInputW", heatInputW)
    .put("heatRejectedW", heatRejectedW)
    .put("storageRateW", storageRateW)
    .put("energyBalanceRelative", energyBalanceRelative)
    .put("thermalIterations", thermalIterations)
    .put("thermalResidual", thermalResidual)
    .put("timeSteps", timeSteps)
    .put("finalTimeSeconds", finalTimeSeconds)
    .put("peakTemperatureC", peakTemperatureC)
    .put("peakTimeSeconds", peakTimeSeconds)
    .put("heatedAreaMm2", heatedAreaMm2)
    .put("cooledAreaMm2", cooledAreaMm2)
    .put("maxDisplacementMm", maxDisplacementMm)
    .put("maxVonMisesMpa", maxVonMisesMpa)
    .put("minimumModulusRetention", minimumModulusRetention)
    .put("minimumStrengthRetention", minimumStrengthRetention)
    .put("conservativeSafetyFactor", conservativeSafetyFactor)
    .put("temperatureMarginC", temperatureMarginC)
    .put("propertyExtrapolated", propertyExtrapolated)
    .put("densityAware", densityAware)
    .put("structuralIterations", structuralIterations)
    .put("structuralResidual", structuralResidual)
    .put("structuralConverged", structuralConverged)
    .put("solverSeconds", solverSeconds)
    .put("historyPoints", historyPoints)

private fun List<Double>.tiJsonArray(): JSONArray = JSONArray().apply {
    this@tiJsonArray.forEach(::put)
}

private fun JSONObject.tiObject(name: String): JSONObject =
    optJSONObject(name) ?: error("Thermal integrity field '$name' is missing")

private fun JSONObject.tiText(name: String, maxLength: Int): String {
    val value = getString(name).trim()
    require(value.isNotEmpty() && value.length <= maxLength && value.none { it == '\u0000' }) {
        "Thermal integrity field '$name' is invalid"
    }
    return value
}

private fun JSONObject.tiInt(name: String, range: IntRange): Int =
    getInt(name).also {
        require(it in range) { "Thermal integrity field '$name' is out of range" }
    }

private fun JSONObject.tiLong(name: String): Long =
    getLong(name).also {
        require(it >= 0L) { "Thermal integrity field '$name' is invalid" }
    }

private fun JSONObject.tiWhole(name: String, range: IntRange): Int {
    val value = getDouble(name)
    require(value.isFinite() && value % 1.0 == 0.0 &&
        value >= Int.MIN_VALUE && value <= Int.MAX_VALUE
    ) {
        "Thermal integrity field '$name' must be an integer"
    }
    return value.toInt().also {
        require(it in range) { "Thermal integrity field '$name' is out of range" }
    }
}

private fun JSONObject.tiFinite(
    name: String,
    range: ClosedFloatingPointRange<Double>,
): Double = getDouble(name).also {
    require(it.isFinite() && it in range) {
        "Thermal integrity field '$name' is out of range"
    }
}

private fun JSONObject.tiFiniteArray(
    name: String,
    expectedSize: Int,
    range: ClosedFloatingPointRange<Double>,
): List<Double> {
    val array = optJSONArray(name) ?: error("Thermal integrity field '$name' is missing")
    require(array.length() == expectedSize) {
        "Thermal integrity field '$name' has the wrong length"
    }
    return List(expectedSize) { index ->
        array.getDouble(index).also { value ->
            require(value.isFinite() && value in range) {
                "Thermal integrity field '$name[$index]' is out of range"
            }
        }
    }
}

private fun String.tiSafeFileName(): String =
    substringAfterLast('/').substringAfterLast('\\').trim().ifBlank { "model.stl" }

private fun Double.tiStable(): String = java.lang.Double.toHexString(this)

private fun tiMarkdown(value: String): String = value
    .replace("\r", " ")
    .replace("\n", " ")
    .replace("|", "\\|")
    .trim()

private fun tiNumber(value: Double): String = String.format(Locale.US, "%.8g", value)
private fun tiScientific(value: Double): String = String.format(Locale.US, "%.5e", value)
private fun tiLength(valueMm: Double): String = "${tiNumber(valueMm)} mm"
private fun tiArea(valueMm2: Double): String = "${tiNumber(valueMm2)} mm²"
private fun tiStress(valueMpa: Double): String = "${tiNumber(valueMpa)} MPa"
private fun tiPower(valueW: Double): String = "${tiNumber(valueW)} W"
private fun tiPercent(value: Double): String = "${tiNumber(value)} %"
private fun tiTemperature(value: Double): String = "${tiNumber(value)} °C"
private fun tiTemperatureDelta(value: Double): String =
    "${if (value > 0.0) "+" else ""}${tiNumber(value)} °C"

private const val MAX_TI_JSON_CHARS = 128 * 1024
private const val MAX_TI_JSON_BYTES = 128L * 1024L
private const val MAX_TI_MARKDOWN_BYTES = 512L * 1024L
private const val MAX_TI_STORED_REPORTS = 12
private const val MAX_TI_TRANSIENT_STEPS = 2_000
private const val MIN_TI_TIMESTAMP_MILLIS = 1_577_836_800_000L
private const val MAX_TI_FUTURE_SKEW_MILLIS = 24L * 60L * 60L * 1_000L
private const val MAX_TI_TRANSFORM_COMPONENT = 1.0e9
private const val MIN_TI_TRANSFORM_DETERMINANT = 1.0e-12
private const val NUMERIC_TOLERANCE = 1.0e-8
private const val MARGIN_TOLERANCE_C = 1.0e-5
