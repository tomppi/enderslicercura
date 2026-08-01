package com.tomppi.enderslicer.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CuraResolvedSettingsWriterWorkspaceTest {
    @Test
    fun findsDisplayedModelFromNestedIsolatedRequestWorkspace() {
        val cache = kotlin.io.path.createTempDirectory("enderslicer-cache").toFile()
        val displayed = File(cache, "model-placement/current-transformed.stl").apply {
            parentFile?.mkdirs()
            writeText("displayed")
        }
        val request = File(cache, "curaengine/requests/slice-1").apply { mkdirs() }

        assertEquals(displayed, CuraResolvedSettingsWriter.findStagedDisplayedFile(request))
    }

    @Test
    fun lookupIsBoundedAndDoesNotFindUnrelatedDistantAncestors() {
        val root = kotlin.io.path.createTempDirectory("enderslicer-cache").toFile()
        File(root, "model-placement/current-transformed.stl").apply {
            parentFile?.mkdirs()
            writeText("displayed")
        }
        var nested = root
        repeat(10) { index -> nested = File(nested, "level-$index").apply { mkdirs() } }

        assertNull(CuraResolvedSettingsWriter.findStagedDisplayedFile(nested))
    }

    @Test
    fun resolvedSourceAndTransformAreCopiedAsOneStableSnapshot() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-model-snapshot").toFile()
        val displayed = File(directory, "current-transformed.stl").apply { writeText("displayed") }
        val source = File(directory, "current-transformed.slice-source.stl").apply {
            writeBytes(ByteArray(84) { index -> index.toByte() })
        }
        File(directory, "current-transformed.slice-transform.json").writeText(transformJson())
        val destination = File(directory, "request/model.stl").apply { parentFile?.mkdirs() }

        val transform = CuraResolvedSettingsWriter.copyResolvedSourceSnapshot(displayed, destination)

        assertEquals(source.readBytes().toList(), destination.readBytes().toList())
        assertEquals(10.0, transform?.translationXmm ?: Double.NaN, 0.0)
        assertEquals(20.0, transform?.translationYmm ?: Double.NaN, 0.0)
        assertEquals(3.0, transform?.translationZmm ?: Double.NaN, 0.0)
    }

    @Test
    fun changingTransformDuringSourceCopyRejectsTheSnapshot() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-model-snapshot-race").toFile()
        val displayed = File(directory, "current-transformed.stl").apply { writeText("displayed") }
        File(directory, "current-transformed.slice-source.stl").writeBytes(ByteArray(84))
        val transformFile = File(directory, "current-transformed.slice-transform.json").apply {
            writeText(transformJson())
        }
        val destination = File(directory, "request/model.stl").apply { parentFile?.mkdirs() }

        val error = runCatching {
            CuraResolvedSettingsWriter.copyResolvedSourceSnapshot(displayed, destination) { source, target ->
                source.copyTo(target, overwrite = true)
                transformFile.appendText(" ")
            }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
    }

    private fun transformJson(): String = """
        {
          "version": 1,
          "linear": [1,0,0,0,1,0,0,0,1],
          "translationXmm": 10,
          "translationYmm": 20,
          "translationZmm": 3
        }
    """.trimIndent()
}
