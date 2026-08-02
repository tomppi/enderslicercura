package com.tomppi.enderslicer.smartinfill

import android.content.Context
import android.net.Uri
import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

private const val STL_HEADER_BYTES = 84L
private const val STL_TRIANGLE_BYTES = 50L

/** One filaSim modifier volume and the Cura sparse-infill density it applies. */
data class SmartInfillModifier(
    val densityPercent: Int,
    val file: File,
)

data class SmartInfillSummary(
    val packageId: String,
    val sourceName: String,
    val baseDensityPercent: Double,
    val modifierDensitiesPercent: List<Int>,
    val pattern: String,
    val mode: String,
    val perimeters: Int,
    val lineWidthMm: Double,
    val topBottomLayers: Int,
    val layerHeightMm: Double,
)

data class SmartInfillPackage(
    val id: String,
    val directory: File,
    val sourceName: String,
    val sourceSha256: String,
    val baseDensityPercent: Double,
    val pattern: String,
    val mode: String,
    val perimeters: Int,
    val lineWidthMm: Double,
    val topBottomLayers: Int,
    val layerHeightMm: Double,
    val upstreamCommit: String,
    val modifiers: List<SmartInfillModifier>,
) {
    val summary: SmartInfillSummary
        get() = SmartInfillSummary(
            packageId = id,
            sourceName = sourceName,
            baseDensityPercent = baseDensityPercent,
            modifierDensitiesPercent = modifiers.map(SmartInfillModifier::densityPercent),
            pattern = pattern,
            mode = mode,
            perimeters = perimeters,
            lineWidthMm = lineWidthMm,
            topBottomLayers = topBottomLayers,
            layerHeightMm = layerHeightMm,
        )

    fun requireMatchesSource(source: File) {
        require(source.isFile && source.length() >= STL_HEADER_BYTES) {
            "The displayed STL used for Smart Infill is unavailable"
        }
        val actual = sha256(source)
        require(actual == sourceSha256) {
            "The model or its placement changed after Smart Infill was generated. Run Smart Infill again."
        }
    }

    /** Copies immutable, validated modifier snapshots into a CuraEngine request workspace. */
    fun stageModifiers(destination: File): List<SmartInfillModifier> {
        require(destination.mkdirs() || destination.isDirectory) {
            "Unable to create the Smart Infill staging directory"
        }
        return modifiers.mapIndexed { index, modifier ->
            requireValidBinaryStl(modifier.file, MeshTriangleLimits.current())
            val target = File(destination, "smart-infill-${index + 1}-${modifier.densityPercent}pct.stl")
            copyStable(modifier.file, target)
            requireValidBinaryStl(target, MeshTriangleLimits.current())
            SmartInfillModifier(modifier.densityPercent, target)
        }
    }

    private fun copyStable(source: File, target: File) {
        val size = source.length()
        val modified = source.lastModified()
        source.inputStream().buffered().use { input ->
            target.outputStream().buffered().use(input::copyTo)
        }
        check(target.length() == size && source.length() == size && source.lastModified() == modified) {
            target.delete()
            "A Smart Infill modifier changed while it was being staged"
        }
    }
}

/** Atomic private storage for filaSim modifier exports. */
class SmartInfillPackageStore(private val context: Context) {
    private val root = File(context.filesDir, "smart-infill").apply { mkdirs() }
    private val packagesDirectory = File(root, "packages").apply { mkdirs() }
    private val activeFile = File(root, "active-package.txt")

    fun importPackage(zipUri: Uri, metadataJson: String, sourceSha256: String): SmartInfillPackage {
        require(sourceSha256.matches(SHA_PATTERN)) { "Smart Infill source fingerprint is invalid" }
        val metadata = parseMetadata(metadataJson)
        require(metadata.sourceSha256 == null || metadata.sourceSha256 == sourceSha256) {
            "filaSim returned a source fingerprint that does not match the analyzed STL"
        }
        val id = "filasim-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val staging = File(packagesDirectory, "$id.next")
        val destination = File(packagesDirectory, id)
        staging.deleteRecursively()
        destination.deleteRecursively()
        check(staging.mkdir()) { "Unable to create the Smart Infill package workspace" }

        try {
            val modifiers = context.contentResolver.openInputStream(zipUri)?.use { input ->
                extractModifiers(input, staging, metadata.baseDensityPercent)
            } ?: error("Unable to open the filaSim modifier archive")

            val manifest = JSONObject()
                .put("version", MANIFEST_VERSION)
                .put("id", id)
                .put("sourceName", metadata.sourceName)
                .put("sourceSha256", sourceSha256)
                .put("baseDensityPercent", metadata.baseDensityPercent)
                .put("pattern", metadata.pattern)
                .put("mode", metadata.mode)
                .put("perimeters", metadata.perimeters)
                .put("lineWidthMm", metadata.lineWidthMm)
                .put("topBottomLayers", metadata.topBottomLayers)
                .put("layerHeightMm", metadata.layerHeightMm)
                .put("upstreamCommit", metadata.upstreamCommit)
                .put(
                    "modifiers",
                    JSONArray().apply {
                        modifiers.forEach { modifier ->
                            put(
                                JSONObject()
                                    .put("densityPercent", modifier.densityPercent)
                                    .put("fileName", modifier.file.name),
                            )
                        }
                    },
                )
            writeSynced(File(staging, MANIFEST_FILE), manifest.toString())
            check(staging.renameTo(destination)) { "Unable to publish the Smart Infill package" }

            val loaded = loadPackage(destination)
            activate(loaded)
            cleanupOldPackages(loaded.id)
            return loaded
        } catch (error: Throwable) {
            staging.deleteRecursively()
            destination.deleteRecursively()
            throw error
        }
    }

    fun loadActive(): SmartInfillPackage? {
        if (!activeFile.isFile) return null
        val id = activeFile.readText().trim()
        if (!SAFE_ID.matches(id)) {
            activeFile.delete()
            return null
        }
        return runCatching { loadPackage(File(packagesDirectory, id)) }
            .onFailure { activeFile.delete() }
            .getOrNull()
    }

    fun clearActive() {
        activeFile.delete()
    }

    fun activate(packageValue: SmartInfillPackage) {
        require(packageValue.directory.parentFile?.canonicalFile == packagesDirectory.canonicalFile) {
            "Smart Infill package is outside private storage"
        }
        val next = File(root, "active-package.next")
        writeSynced(next, packageValue.id)
        activeFile.delete()
        check(next.renameTo(activeFile)) { "Unable to activate the Smart Infill package" }
    }

    private fun loadPackage(directory: File): SmartInfillPackage {
        require(directory.isDirectory && directory.parentFile?.canonicalFile == packagesDirectory.canonicalFile) {
            "Smart Infill package directory is invalid"
        }
        val manifestFile = File(directory, MANIFEST_FILE)
        require(manifestFile.isFile && manifestFile.length() in 1..MAX_MANIFEST_BYTES) {
            "Smart Infill package manifest is missing or too large"
        }
        val root = JSONObject(manifestFile.readText())
        require(root.getInt("version") == MANIFEST_VERSION) { "Unsupported Smart Infill package version" }
        val id = root.getString("id")
        require(id == directory.name && SAFE_ID.matches(id)) { "Smart Infill package identity is invalid" }
        val sourceSha256 = root.getString("sourceSha256")
        require(sourceSha256.matches(SHA_PATTERN)) { "Smart Infill source fingerprint is invalid" }
        val mode = requireSupportedMode(root.getString("mode"))
        val pattern = requireSupportedPattern(root.getString("pattern"))
        val upstreamCommit = root.getString("upstreamCommit")
        require(upstreamCommit == SmartInfillActivity.FILASIM_COMMIT) {
            "Smart Infill package was generated by an unsupported filaSim build"
        }

        val baseDensity = root.getDouble("baseDensityPercent")
        require(baseDensity.isFinite() && baseDensity in MIN_DENSITY..MAX_DENSITY) {
            "Smart Infill base density is invalid"
        }
        val modifiersJson = root.getJSONArray("modifiers")
        require(modifiersJson.length() in 1..MAX_MODIFIERS) { "Smart Infill modifier count is invalid" }
        val modifiers = List(modifiersJson.length()) { index ->
            val item = modifiersJson.getJSONObject(index)
            val density = item.getInt("densityPercent")
            val fileName = item.getString("fileName")
            require(MODIFIER_FILE.matches(fileName)) { "Smart Infill modifier name is invalid" }
            val file = File(directory, fileName)
            require(file.canonicalFile.parentFile == directory.canonicalFile) {
                "Smart Infill modifier path escapes its package"
            }
            requireValidBinaryStl(file, MeshTriangleLimits.current())
            SmartInfillModifier(density, file)
        }.sortedBy(SmartInfillModifier::densityPercent)
        validateDensities(baseDensity, modifiers.map(SmartInfillModifier::densityPercent))

        val lineWidth = root.getDouble("lineWidthMm")
        val layerHeight = root.getDouble("layerHeightMm")
        val perimeters = root.getInt("perimeters")
        val topBottom = root.getInt("topBottomLayers")
        require(lineWidth.isFinite() && lineWidth in 0.1..2.0) { "Smart Infill line width is invalid" }
        require(layerHeight.isFinite() && layerHeight in 0.02..1.5) { "Smart Infill layer height is invalid" }
        require(perimeters in 1..20) { "Smart Infill perimeter count is invalid" }
        require(topBottom in 0..50) { "Smart Infill top/bottom layer count is invalid" }

        return SmartInfillPackage(
            id = id,
            directory = directory,
            sourceName = root.getString("sourceName").take(MAX_SOURCE_NAME),
            sourceSha256 = sourceSha256,
            baseDensityPercent = baseDensity,
            pattern = pattern,
            mode = mode,
            perimeters = perimeters,
            lineWidthMm = lineWidth,
            topBottomLayers = topBottom,
            layerHeightMm = layerHeight,
            upstreamCommit = upstreamCommit,
            modifiers = modifiers,
        )
    }

    private fun extractModifiers(
        input: InputStream,
        destination: File,
        baseDensityPercent: Double,
    ): List<SmartInfillModifier> {
        val triangleLimit = MeshTriangleLimits.current()
        val maxStlBytes = STL_HEADER_BYTES + triangleLimit.toLong() * STL_TRIANGLE_BYTES
        val seen = hashSetOf<Int>()
        val modifiers = mutableListOf<SmartInfillModifier>()
        var totalBytes = 0L

        ZipInputStream(BufferedInputStream(input, BUFFER_SIZE)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                require('/' !in entry.name && '\\' !in entry.name) { "Smart Infill archive contains nested paths" }
                val match = MODIFIER_ARCHIVE.matchEntire(entry.name)
                    ?: error("Unexpected file in Smart Infill archive: ${entry.name}")
                val density = match.groupValues[1].toInt()
                require(seen.add(density)) { "Smart Infill archive contains duplicate $density% modifiers" }
                require(density in 1..100) { "Smart Infill modifier density is invalid" }
                require(modifiers.size < MAX_MODIFIERS) { "Smart Infill archive contains too many modifiers" }

                val target = File(destination, "modifier-${density}pct.stl")
                var written = 0L
                FileOutputStream(target).buffered(BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        written += count
                        totalBytes += count
                        require(written <= maxStlBytes) { "Smart Infill modifier exceeds the mesh triangle limit" }
                        require(totalBytes <= MAX_TOTAL_UNCOMPRESSED_BYTES) { "Smart Infill archive is too large" }
                        output.write(buffer, 0, count)
                    }
                }
                requireValidBinaryStl(target, triangleLimit)
                modifiers += SmartInfillModifier(density, target)
            }
        }
        require(modifiers.isNotEmpty()) { "filaSim did not export any modifier volumes" }
        val sorted = modifiers.sortedBy(SmartInfillModifier::densityPercent)
        validateDensities(baseDensityPercent, sorted.map(SmartInfillModifier::densityPercent))
        return sorted
    }

    private fun parseMetadata(raw: String): Metadata {
        require(raw.length in 2..MAX_METADATA_CHARS) { "Smart Infill metadata is missing or too large" }
        val root = JSONObject(raw)
        val baseDensity = root.getDouble("baseDensityPercent")
        val lineWidth = root.getDouble("lineWidthMm")
        val layerHeight = root.getDouble("layerHeightMm")
        val perimeters = root.getInt("perimeters")
        val topBottom = root.getInt("topBottomLayers")
        require(baseDensity.isFinite() && baseDensity in MIN_DENSITY..MAX_DENSITY) {
            "filaSim returned an invalid base infill density"
        }
        require(lineWidth.isFinite() && lineWidth in 0.1..2.0) { "filaSim returned an invalid line width" }
        require(layerHeight.isFinite() && layerHeight in 0.02..1.5) { "filaSim returned an invalid layer height" }
        require(perimeters in 1..20) { "filaSim returned an invalid perimeter count" }
        require(topBottom in 0..50) { "filaSim returned an invalid shell count" }
        val upstreamCommit = root.getString("upstreamCommit")
        require(upstreamCommit == SmartInfillActivity.FILASIM_COMMIT) {
            "filaSim export came from an unsupported source revision"
        }
        val sourceFingerprint = root.optString("sourceSha256")
            .takeIf(String::isNotBlank)
        require(sourceFingerprint == null || sourceFingerprint.matches(SHA_PATTERN)) {
            "filaSim returned an invalid source fingerprint"
        }
        return Metadata(
            sourceName = root.optString("sourceName", "model.stl").take(MAX_SOURCE_NAME),
            sourceSha256 = sourceFingerprint,
            baseDensityPercent = baseDensity,
            pattern = requireSupportedPattern(root.getString("pattern")),
            mode = requireSupportedMode(root.getString("mode")),
            perimeters = perimeters,
            lineWidthMm = lineWidth,
            topBottomLayers = topBottom,
            layerHeightMm = layerHeight,
            upstreamCommit = upstreamCommit,
        )
    }

    private fun requireSupportedMode(raw: String): String {
        val normalized = raw.trim().lowercase()
        require(normalized in SUPPORTED_MODES) {
            "filaSim modifier export must use graded or binary optimization, not '$raw'"
        }
        return normalized
    }

    private fun requireSupportedPattern(raw: String): String {
        val normalized = raw.trim().lowercase()
        require(normalized in SUPPORTED_PATTERNS) {
            "filaSim returned an infill pattern that EnderSlicerCura cannot reproduce: '$raw'"
        }
        return normalized
    }

    private fun cleanupOldPackages(activeId: String) {
        packagesDirectory.listFiles().orEmpty()
            .filter { it.isDirectory && it.name != activeId && !it.name.endsWith(".next") }
            .sortedByDescending(File::lastModified)
            .drop(MAX_RETAINED_PACKAGES - 1)
            .forEach(File::deleteRecursively)
        packagesDirectory.listFiles().orEmpty()
            .filter { it.name.endsWith(".next") }
            .forEach(File::deleteRecursively)
    }

    private fun writeSynced(file: File, text: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private data class Metadata(
        val sourceName: String,
        val sourceSha256: String?,
        val baseDensityPercent: Double,
        val pattern: String,
        val mode: String,
        val perimeters: Int,
        val lineWidthMm: Double,
        val topBottomLayers: Int,
        val layerHeightMm: Double,
        val upstreamCommit: String,
    )

    companion object {
        private const val MANIFEST_VERSION = 1
        private const val MANIFEST_FILE = "manifest.json"
        private const val MAX_MANIFEST_BYTES = 256L * 1024L
        private const val MAX_METADATA_CHARS = 64 * 1024
        private const val MAX_SOURCE_NAME = 512
        private const val MAX_MODIFIERS = 16
        private const val MAX_RETAINED_PACKAGES = 4
        private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 768L * 1024L * 1024L
        private const val BUFFER_SIZE = 128 * 1024
        private const val MIN_DENSITY = 1.0
        private const val MAX_DENSITY = 100.0
        private val SUPPORTED_MODES = setOf("graded", "binary")
        private val SUPPORTED_PATTERNS = setOf("cubic", "gyroid", "grid", "rectilinear", "zig-zag", "zigzag", "concentric")
        private val MODIFIER_ARCHIVE = Regex("modifier_(\\d{1,3})pct\\.stl", RegexOption.IGNORE_CASE)
        private val MODIFIER_FILE = Regex("modifier-\\d{1,3}pct\\.stl", RegexOption.IGNORE_CASE)
        private val SAFE_ID = Regex("filasim-[A-Za-z0-9-]+")
        private val SHA_PATTERN = Regex("[0-9a-f]{64}")
    }
}

internal fun requireValidBinaryStl(file: File, maxTriangles: Int) {
    require(file.isFile && file.length() >= STL_HEADER_BYTES) { "Smart Infill modifier STL is missing or empty" }
    RandomAccessFile(file, "r").use { input ->
        input.seek(80L)
        val countBytes = ByteArray(4)
        input.readFully(countBytes)
        val triangleCount = ByteBuffer.wrap(countBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
            .toLong() and 0xffffffffL
        require(triangleCount in 1..maxTriangles.toLong()) {
            "Smart Infill modifier triangle count is outside the configured limit"
        }
        require(file.length() == STL_HEADER_BYTES + triangleCount * STL_TRIANGLE_BYTES) {
            "Smart Infill modifier is not a structurally valid binary STL"
        }
    }
}

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun validateDensities(baseDensityPercent: Double, modifierDensities: List<Int>) {
    require(modifierDensities == modifierDensities.sorted() && modifierDensities.distinct().size == modifierDensities.size) {
        "Smart Infill modifier densities must be unique and increasing"
    }
    require(modifierDensities.all { it.toDouble() > baseDensityPercent && it <= 100 }) {
        "Every Smart Infill modifier must be denser than the base infill"
    }
}
