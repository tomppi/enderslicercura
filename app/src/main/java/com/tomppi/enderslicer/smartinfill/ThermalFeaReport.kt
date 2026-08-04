package com.tomppi.enderslicer.smartinfill

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Auditable result captured from filaSim's build-simulation workspace.
 *
 * This is deliberately a build-process thermo-mechanical report, not a general
 * heat-transfer result. The solver uses sequential voxel activation and an
 * inherent-strain/eigenstrain model to estimate bonded/released deformation
 * and bed reactions. It does not solve transient heat conduction, interlayer
 * welding, creep, or an absolute bed-adhesion failure threshold.
 */
internal data class ThermalFeaReport(
    val schemaVersion: Int,
    val sourceName: String,
    val sourceSha256: String,
    val upstreamCommit: String,
    val generatedAtEpochMillis: Long,
    val materialName: String,
    val shrinkXyPercent: Double,
    val shrinkZPercent: Double,
    val lockingTemperatureC: Double?,
    val bedTemperatureC: Double?,
    val chamberTemperatureC: Double?,
    val densityAware: Boolean,
    val voxelSizeMm: Double?,
    val bondedWarpMm: Double,
    val releasedWarpMm: Double,
    val peakLiftMpa: Double,
    val peakShearMpa: Double,
    val solverSeconds: Double?,
) {
    fun toCanonicalJson(): String = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("analysisKind", ANALYSIS_KIND)
        .put("solverModel", SOLVER_MODEL)
        .put("sourceName", sourceName)
        .put("sourceSha256", sourceSha256)
        .put("upstreamCommit", upstreamCommit)
        .put("generatedAtEpochMillis", generatedAtEpochMillis)
        .put(
            "material",
            JSONObject()
                .put("name", materialName)
                .put("shrinkXyPercent", shrinkXyPercent)
                .put("shrinkZPercent", shrinkZPercent)
                .putNullable("lockingTemperatureC", lockingTemperatureC),
        )
        .put(
            "process",
            JSONObject()
                .putNullable("bedTemperatureC", bedTemperatureC)
                .putNullable("chamberTemperatureC", chamberTemperatureC)
                .put("densityAware", densityAware),
        )
        .put("mesh", JSONObject().putNullable("voxelSizeMm", voxelSizeMm))
        .put(
            "results",
            JSONObject()
                .put("bondedWarpMm", bondedWarpMm)
                .put("releasedWarpMm", releasedWarpMm)
                .put("peakLiftMpa", peakLiftMpa)
                .put("peakShearMpa", peakShearMpa)
                .putNullable("solverSeconds", solverSeconds),
        )
        .put(
            "confidence",
            JSONObject()
                .put("level", CONFIDENCE_LEVEL)
                .put("calibratedToPrinter", false),
        )
        .toString(2)

    fun summaryText(): String = buildString {
        appendLine("Model: $sourceName")
        appendLine("Material: $materialName")
        appendLine()
        appendLine("On-bed warp: ${formatLength(bondedWarpMm)}")
        appendLine("Released warp: ${formatLength(releasedWarpMm)}")
        appendLine("Peak bed lift traction: ${formatStress(peakLiftMpa)}")
        appendLine("Peak bed shear: ${formatStress(peakShearMpa)}")
        appendLine()
        append("Experimental literature-seeded estimate. No absolute pass/fail threshold is applied.")
    }

    fun toMarkdown(): String = buildString {
        appendLine("# EnderSlicerCura thermal FEA report")
        appendLine()
        appendLine("> Experimental build-process thermo-mechanical estimate. This report is not a certification or an absolute failure verdict.")
        appendLine()
        appendLine("## Identity")
        appendLine()
        appendLine("| Field | Value |")
        appendLine("|---|---|")
        appendLine("| Model | ${markdown(sourceName)} |")
        appendLine("| Model SHA-256 | `$sourceSha256` |")
        appendLine("| filaSim commit | `$upstreamCommit` |")
        appendLine("| Generated | ${DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(generatedAtEpochMillis))} |")
        appendLine()
        appendLine("## Inputs")
        appendLine()
        appendLine("| Input | Value |")
        appendLine("|---|---:|")
        appendLine("| Material | ${markdown(materialName)} |")
        appendLine("| In-plane shrink | ${formatPercent(shrinkXyPercent)} |")
        appendLine("| Through-layer shrink | ${formatPercent(shrinkZPercent)} |")
        lockingTemperatureC?.let { appendLine("| Locking temperature | ${formatTemperature(it)} |") }
        bedTemperatureC?.let { appendLine("| Bed temperature | ${formatTemperature(it)} |") }
        chamberTemperatureC?.let { appendLine("| Chamber temperature | ${formatTemperature(it)} |") }
        voxelSizeMm?.let { appendLine("| Voxel size | ${formatLength(it)} |") }
        appendLine("| Stiffness field | ${if (densityAware) "As-printed density aware" else "Solid hull"} |")
        appendLine()
        appendLine("## Calculated results")
        appendLine()
        appendLine("| Result | Value |")
        appendLine("|---|---:|")
        appendLine("| Maximum deformation while bonded to bed | ${formatLength(bondedWarpMm)} |")
        appendLine("| Maximum deformation after release | ${formatLength(releasedWarpMm)} |")
        appendLine("| Peak bed lift traction | ${formatStress(peakLiftMpa)} |")
        appendLine("| Peak bed shear traction | ${formatStress(peakShearMpa)} |")
        solverSeconds?.let { appendLine("| Solver time | ${formatNumber(it)} s |") }
        appendLine()
        appendLine("## Method and interpretation")
        appendLine()
        appendLine("- Sequential voxel-layer activation with an inherent-strain/eigenstrain load.")
        appendLine("- Bonded state constrains the bed plane; released state removes the bed and suppresses rigid-body motion with a minimal 3-2-1 pin.")
        appendLine("- Bed traction and shear are failure drivers, not a bed-adhesion probability. A calibrated adhesion/process-zone threshold is not available.")
        appendLine("- The result is tied to the exact STL fingerprint above. Geometry or placement changes require a new run.")
        appendLine()
        appendLine("## Applicability limits")
        appendLine()
        appendLine("This implementation does **not** calculate transient heat flow, nozzle-path reheating, interlayer weld strength, creep, service-temperature softening, radiation, convection, or an absolute probability of bed release. Those require G-code thermal history and printer/filament/build-surface calibration.")
        appendLine()
        appendLine("## Material evidence")
        appendLine()
        appendLine(materialEvidence(materialName))
        appendLine()
        appendLine("Thermal-conductivity measurements for printed PLA/PET-G/ABS across infill, pattern, and temperature are available in DOI `10.3390/ma18173950`, but are not applied here because this solver does not yet include a heat-conduction equation.")
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val ANALYSIS_KIND = "fdm-build-thermomechanical"
        const val SOLVER_MODEL = "sequential-voxel-inherent-strain"
        const val CONFIDENCE_LEVEL = "experimental-literature-seeded"

        private val SHA_PATTERN = Regex("[0-9a-f]{64}")
        private val COMMIT_PATTERN = Regex("[0-9a-f]{40}")

        fun parse(
            payload: String,
            expectedSourceSha256: String,
            expectedUpstreamCommit: String,
            nowEpochMillis: Long = System.currentTimeMillis(),
        ): ThermalFeaReport {
            require(payload.length in 2..MAX_JSON_CHARS) { "Thermal FEA report size is invalid" }
            require(expectedSourceSha256.matches(SHA_PATTERN)) { "Expected model fingerprint is invalid" }
            require(expectedUpstreamCommit.matches(COMMIT_PATTERN)) { "Expected filaSim commit is invalid" }

            val root = JSONObject(payload)
            val schema = root.requireInt("schemaVersion", SCHEMA_VERSION..SCHEMA_VERSION)
            require(root.requireText("analysisKind", 64) == ANALYSIS_KIND) {
                "Unsupported thermal FEA analysis kind"
            }
            require(root.requireText("solverModel", 96) == SOLVER_MODEL) {
                "Unsupported thermal FEA solver model"
            }
            val sourceName = root.requireText("sourceName", 240).safeFileName()
            val sourceSha = root.requireText("sourceSha256", 64)
            require(sourceSha.matches(SHA_PATTERN) && sourceSha == expectedSourceSha256) {
                "Thermal FEA result does not match the analyzed STL"
            }
            val commit = root.requireText("upstreamCommit", 40)
            require(commit.matches(COMMIT_PATTERN) && commit == expectedUpstreamCommit) {
                "Thermal FEA result came from an unexpected filaSim build"
            }
            val generatedAt = root.requireLong("generatedAtEpochMillis")
            require(generatedAt in MIN_TIMESTAMP_MILLIS..(nowEpochMillis + MAX_FUTURE_SKEW_MILLIS)) {
                "Thermal FEA report timestamp is invalid"
            }

            val material = root.requireObject("material")
            val process = root.requireObject("process")
            val mesh = root.requireObject("mesh")
            val results = root.requireObject("results")
            val confidence = root.requireObject("confidence")
            require(confidence.requireText("level", 64) == CONFIDENCE_LEVEL) {
                "Thermal FEA confidence label is missing"
            }
            require(!confidence.optBoolean("calibratedToPrinter", true)) {
                "Unverified printer calibration must not be claimed"
            }

            return ThermalFeaReport(
                schemaVersion = schema,
                sourceName = sourceName,
                sourceSha256 = sourceSha,
                upstreamCommit = commit,
                generatedAtEpochMillis = generatedAt,
                materialName = material.requireText("name", 120),
                shrinkXyPercent = material.requireFinite("shrinkXyPercent", 0.0..20.0),
                shrinkZPercent = material.requireFinite("shrinkZPercent", 0.0..20.0),
                lockingTemperatureC = material.optionalFinite("lockingTemperatureC", -50.0..350.0),
                bedTemperatureC = process.optionalFinite("bedTemperatureC", -50.0..250.0),
                chamberTemperatureC = process.optionalFinite("chamberTemperatureC", -50.0..200.0),
                densityAware = process.getBoolean("densityAware"),
                voxelSizeMm = mesh.optionalFinite("voxelSizeMm", 0.01..100.0),
                bondedWarpMm = results.requireFinite("bondedWarpMm", 0.0..2_000.0),
                releasedWarpMm = results.requireFinite("releasedWarpMm", 0.0..2_000.0),
                peakLiftMpa = results.requireFinite("peakLiftMpa", 0.0..20_000.0),
                peakShearMpa = results.requireFinite("peakShearMpa", 0.0..20_000.0),
                solverSeconds = results.optionalFinite("solverSeconds", 0.0..604_800.0),
            )
        }
    }
}

internal data class StoredThermalFeaReport(
    val report: ThermalFeaReport,
    val jsonFile: File,
    val markdownFile: File,
)

/** Atomic, source-fingerprinted storage for the latest report of each analyzed STL. */
internal class ThermalFeaReportStore(context: Context) {
    private val root = File(context.filesDir, "thermal-fea").apply { mkdirs() }

    fun save(
        payload: String,
        expectedSourceSha256: String,
        expectedUpstreamCommit: String,
    ): StoredThermalFeaReport {
        val report = ThermalFeaReport.parse(payload, expectedSourceSha256, expectedUpstreamCommit)
        val json = File(root, "${report.sourceSha256}.json")
        val markdown = File(root, "${report.sourceSha256}.md")
        writeAtomic(json, report.toCanonicalJson())
        writeAtomic(markdown, report.toMarkdown())
        cleanup(report.sourceSha256)
        return StoredThermalFeaReport(report, json, markdown)
    }

    fun load(
        sourceSha256: String,
        expectedUpstreamCommit: String,
    ): StoredThermalFeaReport? {
        if (!sourceSha256.matches(Regex("[0-9a-f]{64}"))) return null
        val json = File(root, "$sourceSha256.json")
        val markdown = File(root, "$sourceSha256.md")
        if (!json.isFile || json.length() !in 2..MAX_JSON_BYTES) return null
        return runCatching {
            val report = ThermalFeaReport.parse(
                json.readText(),
                expectedSourceSha256 = sourceSha256,
                expectedUpstreamCommit = expectedUpstreamCommit,
            )
            if (!markdown.isFile || markdown.length() !in 2..MAX_MARKDOWN_BYTES) {
                writeAtomic(markdown, report.toMarkdown())
            }
            StoredThermalFeaReport(report, json, markdown)
        }.onFailure {
            json.delete()
            markdown.delete()
        }.getOrNull()
    }

    private fun cleanup(activeSha: String) {
        val keepBases = root.listFiles().orEmpty()
            .filter { it.isFile && (it.extension == "json" || it.extension == "md") }
            .groupBy(File::nameWithoutExtension)
            .entries
            .sortedByDescending { (_, files) -> files.maxOfOrNull(File::lastModified) ?: 0L }
            .map(Map.Entry<String, List<File>>::key)
            .filter { it != activeSha }
            .take(MAX_STORED_REPORTS - 1)
            .toSet() + activeSha
        root.listFiles().orEmpty()
            .filter(File::isFile)
            .filter { it.nameWithoutExtension !in keepBases }
            .forEach(File::delete)
    }

    private fun writeAtomic(target: File, text: String) {
        val staging = File(root, ".${target.name}.${UUID.randomUUID()}.next")
        try {
            FileOutputStream(staging).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (target.exists()) check(target.delete()) { "Unable to replace ${target.name}" }
            check(staging.renameTo(target)) { "Unable to publish ${target.name}" }
        } finally {
            staging.delete()
        }
    }
}

private fun JSONObject.putNullable(name: String, value: Double?): JSONObject = apply {
    if (value == null) put(name, JSONObject.NULL) else put(name, value)
}

private fun JSONObject.requireObject(name: String): JSONObject =
    optJSONObject(name) ?: error("Thermal FEA field '$name' is missing")

private fun JSONObject.requireText(name: String, maxLength: Int): String {
    val value = getString(name).trim()
    require(value.isNotEmpty() && value.length <= maxLength && value.none { it == '\u0000' }) {
        "Thermal FEA field '$name' is invalid"
    }
    return value
}

private fun JSONObject.requireInt(name: String, range: IntRange): Int =
    getInt(name).also { require(it in range) { "Thermal FEA field '$name' is out of range" } }

private fun JSONObject.requireLong(name: String): Long =
    getLong(name).also { require(it >= 0L) { "Thermal FEA field '$name' is invalid" } }

private fun JSONObject.requireFinite(name: String, range: ClosedFloatingPointRange<Double>): Double =
    getDouble(name).also {
        require(it.isFinite() && it in range) { "Thermal FEA field '$name' is out of range" }
    }

private fun JSONObject.optionalFinite(
    name: String,
    range: ClosedFloatingPointRange<Double>,
): Double? {
    if (!has(name) || isNull(name)) return null
    return requireFinite(name, range)
}

private fun String.safeFileName(): String =
    substringAfterLast('/').substringAfterLast('\\').trim().ifBlank { "model.stl" }

private fun markdown(value: String): String = value
    .replace("\r", " ")
    .replace("\n", " ")
    .replace("|", "\\|")
    .trim()

private fun formatNumber(value: Double): String = String.format(Locale.US, "%.4g", value)
private fun formatLength(valueMm: Double): String = "${formatNumber(valueMm)} mm"
private fun formatStress(valueMpa: Double): String = "${formatNumber(valueMpa)} MPa"
private fun formatPercent(value: Double): String = "${formatNumber(value)} %"
private fun formatTemperature(value: Double): String = "${formatNumber(value)} °C"

private fun materialEvidence(name: String): String = when {
    name.contains("PLA", ignoreCase = true) ->
        "The filaSim PLA seed uses an effective printed-part CTE near 96 × 10⁻⁶/K. A 0° printed PLA value of 96 ± 5 × 10⁻⁶/K over 25–50 °C was measured in DOI `10.3390/ma17184668`. This is a literature seed, not calibration of the user's filament or printer."

    name.contains("ABS", ignoreCase = true) ->
        "Printed ABS CTE is orientation dependent. Measurements over 20–50 °C reported 74.5–85.8 × 10⁻⁶/K across three build orientations in DOI `10.3390/nano8010049`; another FDM study reported about 86 × 10⁻⁶/K for a 0° raster in DOI `10.3390/ma17184668`."

    else ->
        "Thermal shrink and locking values come from the pinned filaSim material preset. No printer-, filament-, moisture-, or profile-specific calibration is attached to this report."
}

private const val MAX_JSON_CHARS = 64 * 1024
private const val MAX_JSON_BYTES = 64L * 1024L
private const val MAX_MARKDOWN_BYTES = 256L * 1024L
private const val MAX_STORED_REPORTS = 12
private const val MIN_TIMESTAMP_MILLIS = 1_577_836_800_000L // 2020-01-01 UTC
private const val MAX_FUTURE_SKEW_MILLIS = 24L * 60L * 60L * 1_000L
