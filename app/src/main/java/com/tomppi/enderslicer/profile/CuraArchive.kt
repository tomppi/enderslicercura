package com.tomppi.enderslicer.profile

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

internal object CuraArchive {
    fun readTextEntries(
        input: InputStream,
        maximumEntryBytes: Int = 16 * 1024 * 1024,
        maximumAcceptedEntries: Int = 512,
        maximumTotalBytes: Long = 64L * 1024L * 1024L,
        accept: (String) -> Boolean = { true },
    ): Map<String, String> {
        require(maximumEntryBytes > 0) { "Archive entry limit must be positive" }
        require(maximumAcceptedEntries > 0) { "Archive entry-count limit must be positive" }
        require(maximumTotalBytes > 0L) { "Archive total-size limit must be positive" }

        val result = linkedMapOf<String, String>()
        var acceptedEntries = 0
        var acceptedBytes = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && accept(entry.name)) {
                    require(entry.name !in result) { "Archive contains a duplicate entry: ${entry.name}" }
                    acceptedEntries++
                    require(acceptedEntries <= maximumAcceptedEntries) {
                        "Archive contains more than $maximumAcceptedEntries accepted entries"
                    }

                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var entryBytes = 0
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        entryBytes += read
                        acceptedBytes += read.toLong()
                        require(entryBytes <= maximumEntryBytes) {
                            "Archive entry ${entry.name} exceeds the ${maximumEntryBytes / 1024 / 1024} MiB safety limit"
                        }
                        require(acceptedBytes <= maximumTotalBytes) {
                            "Accepted archive data exceeds the ${maximumTotalBytes / 1024 / 1024} MiB safety limit"
                        }
                        output.write(buffer, 0, read)
                    }
                    result[entry.name] = output.toString(Charsets.UTF_8.name())
                }
                zip.closeEntry()
            }
        }
        return result
    }
}
