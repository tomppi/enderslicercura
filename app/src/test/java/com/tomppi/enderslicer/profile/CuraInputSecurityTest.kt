package com.tomppi.enderslicer.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CuraInputSecurityTest {
    @Test
    fun rejectsAcceptedArchiveDataBeyondTotalLimit() {
        val archive = zip(
            "Cura/a.cfg" to "a".repeat(40),
            "Cura/b.cfg" to "b".repeat(40),
        )

        val error = runCatching {
            CuraArchive.readTextEntries(
                input = ByteArrayInputStream(archive),
                maximumEntryBytes = 64,
                maximumTotalBytes = 64,
                accept = { it.startsWith("Cura/") },
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("total", ignoreCase = true))
    }

    @Test
    fun rejectsTooManyAcceptedArchiveEntries() {
        val archive = zip(
            "Cura/a.cfg" to "a",
            "Cura/b.cfg" to "b",
            "Cura/c.cfg" to "c",
        )

        val error = runCatching {
            CuraArchive.readTextEntries(
                input = ByteArrayInputStream(archive),
                maximumAcceptedEntries = 2,
                accept = { it.startsWith("Cura/") },
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("entries", ignoreCase = true))
    }

    @Test
    fun secureXmlRejectsDoctypeEntities() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE material [<!ENTITY leak SYSTEM "file:///etc/passwd">]>
            <material><name>&leak;</name></material>
        """.trimIndent()

        val error = runCatching {
            SecureXml.factory().newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray()))
        }.exceptionOrNull()

        assertTrue(error != null)
    }

    @Test
    fun readsNormalAcceptedEntries() {
        val archive = zip("Cura/a.cfg" to "hello")
        val entries = CuraArchive.readTextEntries(
            ByteArrayInputStream(archive),
            accept = { it.startsWith("Cura/") },
        )
        assertEquals("hello", entries["Cura/a.cfg"])
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
