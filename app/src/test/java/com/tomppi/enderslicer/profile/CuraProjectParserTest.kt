package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CuraProjectParserTest {
    @Test
    fun userLayerOverridesQualityLayer() {
        val archive = zipOf(
            "Cura/version.ini" to "[versions]\ncura_version = 5.11.0-beta.1\n",
            "Cura/Test.global.cfg" to """
                [general]
                name = Test printer
                [metadata]
                setting_version = 25
                [containers]
                0 = Test_user
                1 = Test_quality
            """.trimIndent(),
            "Cura/Test_user.inst.cfg" to "[general]\nname = Test_user\n[values]\nsupport_enable = True\n",
            "Cura/Test_quality.inst.cfg" to "[general]\nname = Test_quality\n[values]\nsupport_enable = False\n",
            "Cura/E0.extruder.cfg" to """
                [general]
                name = E0
                [containers]
                0 = E0_user
                1 = E0_quality
            """.trimIndent(),
            "Cura/E0_user.inst.cfg" to "[general]\nname = E0_user\n[values]\nspeed_print = 200\n",
            "Cura/E0_quality.inst.cfg" to "[general]\nname = E0_quality\n[values]\nspeed_print = 120\n",
        )

        val result = CuraProjectParser.parse(
            ByteArrayInputStream(archive),
            "test.3mf",
            SlicerSettings(),
        )

        assertTrue(result.mappedSettings.supportsEnabled)
        assertEquals(200.0, result.mappedSettings.printSpeedMmPerSecond, 0.0)
        assertEquals("5.11.0-beta.1", result.curaVersion)
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
