package com.tomppi.enderslicer.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
