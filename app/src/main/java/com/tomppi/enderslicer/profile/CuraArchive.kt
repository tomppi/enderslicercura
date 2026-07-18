package com.tomppi.enderslicer.profile

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

internal object CuraArchive {
    fun readTextEntries(
        input: InputStream,
        maximumEntryBytes: Int = 16 * 1024 * 1024,
        accept: (String) -> Boolean = { true },
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && accept(entry.name)) {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= maximumEntryBytes) {
                            "Archive entry ${entry.name} exceeds the ${maximumEntryBytes / 1024 / 1024} MiB safety limit"
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
