package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.calibration.CalibrationSliceState
import com.tomppi.enderslicer.calibration.CalibrationTestType
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class CuraResolvedSettingsWriterCalibrationTest {
    @After
    fun clearCalibrationState() {
        CalibrationSliceState.clear()
    }

    @Test
    fun fanCalibrationWinsOverPerMeshBridgeFanSettings() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-resolved-calibration").toFile()
        val model = directory.resolve("current.stl").apply { writeBytes(byteArrayOf(1)) }
        val destination = directory.resolve("resolved-settings.json")
        val resolved = CuraSliceSettingsResolver.Result(
            globalValues = mapOf(
                "machine_center_is_zero" to "true",
                "machine_width" to "230",
                "machine_depth" to "230",
            ),
            extruderValues = mapOf(
                "bridge_fan_speed" to "100",
                "bridge_fan_speed_2" to "100",
                "bridge_fan_speed_3" to "100",
            ),
            modelValues = mapOf(
                "bridge_fan_speed" to "80",
                "bridge_fan_speed_2" to "70",
                "bridge_fan_speed_3" to "60",
            ),
            expressionCount = 0,
            passes = 1,
        )

        CalibrationSliceState.activate(CalibrationTestType.FAN, 0.0)
        CuraResolvedSettingsWriter.write(destination, model.name, resolved)

        val root = JSONObject(destination.readText())
        val extruder = root.getJSONObject("extruder.0")
        val mesh = root.getJSONObject("current.stl")
        assertEquals("0", extruder.getString("bridge_fan_speed"))
        assertEquals("0", extruder.getString("bridge_fan_speed_2"))
        assertEquals("0", extruder.getString("bridge_fan_speed_3"))
        assertEquals("0", mesh.getString("bridge_fan_speed"))
        assertEquals("0", mesh.getString("bridge_fan_speed_2"))
        assertEquals("0", mesh.getString("bridge_fan_speed_3"))
    }
}
