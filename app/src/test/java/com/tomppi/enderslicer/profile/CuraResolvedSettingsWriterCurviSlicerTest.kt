package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.nonplanar.CurviSlicerRuntime
import com.tomppi.enderslicer.nonplanar.NonPlanarSettings
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CuraResolvedSettingsWriterCurviSlicerTest {
    @Test
    fun replacesPreStagedRequestModelBeforeStrictCopy() {
        val root = Files.createTempDirectory("enderslicer-curvi-staging").toFile()
        try {
            val displayed = File(root, "transformed.stl").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))
            }
            val requestDirectory = File(root, "request").apply { mkdirs() }
            val destination = File(requestDirectory, "model.stl").apply {
                writeBytes(byteArrayOf(9, 9, 9))
            }
            val displayedBytes = displayed.readBytes()
            CurviSlicerRuntime.activate(NonPlanarSettings(enabled = true))

            val transform = CuraResolvedSettingsWriter.copyResolvedSourceSnapshot(
                stagedDisplayedFile = displayed,
                destination = destination,
                copyFile = { source, target ->
                    check(!target.exists()) { "The destination file already exists." }
                    source.copyTo(target)
                },
            )

            assertNull(transform)
            assertArrayEquals(displayedBytes, destination.readBytes())
            assertArrayEquals(displayedBytes, displayed.readBytes())
            assertTrue(displayed.isFile)
        } finally {
            CurviSlicerRuntime.activate(NonPlanarSettings(enabled = false))
            root.deleteRecursively()
        }
    }
}
