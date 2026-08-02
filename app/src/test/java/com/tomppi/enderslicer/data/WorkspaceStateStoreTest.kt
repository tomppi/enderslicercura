package com.tomppi.enderslicer.data

import com.tomppi.enderslicer.calibration.CalibrationTestType
import com.tomppi.enderslicer.engine.LayerEventType
import com.tomppi.enderslicer.engine.PlannedLayerEvent
import com.tomppi.enderslicer.model.ModelPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class WorkspaceStateStoreTest {
    @Test
    fun snapshotRoundTripsThroughAtomicDescriptor() {
        val files = createTempDirectory("enderslicer-workspace").toFile()
        val model = File(files, "models/model.stl").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val store = WorkspaceStateStore(files)
        val snapshot = WorkspaceStateStore.Snapshot(
            modelPath = model.absolutePath,
            modelDisplayName = "calibration.stl",
            placement = ModelPlacement(
                centerXmm = 115.0,
                centerYmm = 115.0,
                baseZmm = 0.0,
                source = "Test placement",
            ),
            plannedEvents = listOf(
                PlannedLayerEvent(
                    targetZMm = 0.8f,
                    type = LayerEventType.TEMPERATURE,
                    value = 200.0,
                    label = "Level 1",
                ),
            ),
            calibrationDescription = "Temperature tower",
            calibrationType = CalibrationTestType.TEMPERATURE,
            calibrationFirstValue = 200.0,
            configurationFingerprint = WorkspaceStateStore.fingerprint("profile", "settings"),
        )

        store.save(snapshot)
        val restored = requireNotNull(store.load())

        assertEquals(snapshot, restored)
        assertTrue(File(files, "persistent-state/current-workspace.json").isFile)
        assertNull(File(files, "persistent-state/current-workspace.next").takeIf(File::exists))
    }

    @Test
    fun modelOutsidePrivateDirectoryIsRejected() {
        val files = createTempDirectory("enderslicer-workspace-root").toFile()
        val external = createTempDirectory("enderslicer-external-model").resolve("model.stl").toFile().apply {
            writeBytes(byteArrayOf(1))
        }
        val store = WorkspaceStateStore(files)
        val snapshot = WorkspaceStateStore.Snapshot(
            modelPath = external.absolutePath,
            modelDisplayName = "external.stl",
            placement = ModelPlacement(centerXmm = 0.0, centerYmm = 0.0, baseZmm = 0.0),
            plannedEvents = emptyList(),
            calibrationDescription = null,
            calibrationType = null,
            calibrationFirstValue = null,
            configurationFingerprint = WorkspaceStateStore.fingerprint("settings"),
        )

        val error = runCatching { store.save(snapshot) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
