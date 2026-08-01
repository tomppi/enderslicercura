package com.tomppi.enderslicer.engine

import java.io.File

/** Publishes completed slices by atomically renaming a fully prepared directory. */
internal class SliceArtifactPublisher(
    private val rootDirectory: File,
    private val copyFile: (File, File) -> Unit = { source, destination ->
        source.copyTo(destination, overwrite = false)
    },
) {
    data class PublishedArtifact(
        val id: String,
        val directory: File,
        val gcodeFile: File,
        val baseGcodeFile: File,
    )

    fun publish(
        id: String,
        gcodeSource: File,
        baseGcodeSource: File,
        printerEnvelope: PrinterEnvelope,
    ): PublishedArtifact {
        require(id.matches(ID_PATTERN)) { "Invalid slice artifact id" }
        require(gcodeSource.isFile && gcodeSource.length() > 0L) { "Validated G-code is unavailable" }
        require(baseGcodeSource.isFile && baseGcodeSource.length() > 0L) { "Base G-code is unavailable" }
        check(rootDirectory.mkdirs() || rootDirectory.isDirectory) { "Unable to create the slice result directory" }
        cleanupAbandonedPublishingDirectories()

        val finalDirectory = File(rootDirectory, id)
        check(!finalDirectory.exists()) { "Slice artifact already exists: $id" }
        val publishingDirectory = File(rootDirectory, ".$id-publishing-${System.nanoTime()}")
        check(publishingDirectory.mkdir()) { "Unable to create the slice publication directory" }

        try {
            val gcodeDestination = File(publishingDirectory, GCODE_FILE_NAME)
            val baseDestination = File(publishingDirectory, BASE_GCODE_FILE_NAME)
            copyStable(gcodeSource, gcodeDestination)
            copyStable(baseGcodeSource, baseDestination)
            val resolvedEnvelopeFile = File(gcodeSource.parentFile, PrinterEnvelope.METADATA_FILE_NAME)
            val effectiveEnvelope = if (resolvedEnvelopeFile.isFile) {
                PrinterEnvelope.readFrom(resolvedEnvelopeFile)
            } else {
                printerEnvelope
            }
            effectiveEnvelope.writeTo(File(publishingDirectory, PrinterEnvelope.METADATA_FILE_NAME))
            File(publishingDirectory, COMPLETE_MARKER_FILE_NAME).writeText(id)

            check(publishingDirectory.renameTo(finalDirectory)) {
                "Unable to atomically publish the validated slice"
            }
            return PublishedArtifact(
                id = id,
                directory = finalDirectory,
                gcodeFile = File(finalDirectory, GCODE_FILE_NAME),
                baseGcodeFile = File(finalDirectory, BASE_GCODE_FILE_NAME),
            )
        } catch (error: Throwable) {
            publishingDirectory.deleteRecursively()
            throw error
        }
    }

    private fun copyStable(source: File, destination: File) {
        val length = source.length()
        val modified = source.lastModified()
        copyFile(source, destination)
        check(
            source.isFile &&
                source.length() == length &&
                source.lastModified() == modified &&
                destination.isFile &&
                destination.length() == length,
        ) { "Slice data changed while it was being published" }
    }

    private fun cleanupAbandonedPublishingDirectories() {
        rootDirectory.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith('.') && "-publishing-" in it.name }
            .forEach(File::deleteRecursively)
    }

    companion object {
        const val GCODE_FILE_NAME = "print.gcode"
        const val BASE_GCODE_FILE_NAME = "base.gcode"
        const val COMPLETE_MARKER_FILE_NAME = ".complete"

        fun isCompleteGcode(file: File, expectedId: String? = null): Boolean {
            if (!file.isFile || file.name != GCODE_FILE_NAME || file.length() <= 0L) return false
            val id = completedArtifactId(file) ?: return false
            if (expectedId != null && id != expectedId) return false
            return runCatching {
                PrinterEnvelope.readFrom(File(file.parentFile, PrinterEnvelope.METADATA_FILE_NAME))
            }.isSuccess
        }

        fun readPrinterEnvelope(artifactFile: File): PrinterEnvelope {
            require(
                artifactFile.isFile &&
                    artifactFile.length() > 0L &&
                    artifactFile.name in ARTIFACT_FILE_NAMES,
            ) { "The published slice artifact is unavailable" }
            requireNotNull(completedArtifactId(artifactFile)) { "The slice artifact is incomplete" }
            return PrinterEnvelope.readFrom(
                File(requireNotNull(artifactFile.parentFile), PrinterEnvelope.METADATA_FILE_NAME),
            )
        }

        private fun completedArtifactId(file: File): String? {
            val directory = file.parentFile ?: return null
            val marker = File(directory, COMPLETE_MARKER_FILE_NAME)
            if (!marker.isFile) return null
            val id = runCatching { marker.readText().trim() }.getOrNull() ?: return null
            return id.takeIf { it.isNotBlank() && it == directory.name }
        }

        private val ARTIFACT_FILE_NAMES = setOf(GCODE_FILE_NAME, BASE_GCODE_FILE_NAME)
        private val ID_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
    }
}
