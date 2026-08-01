package com.tomppi.enderslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TemperatureCalibrationSafetyTest {
    @Test
    fun temperatureCalibrationAlwaysEndsWithHotendOffAfterCustomEndScript() {
        val file = temporaryGcode(
            """
            ;FLAVOR:Marlin
            ;LAYER_COUNT:1
            M82
            M104 S200
            ;LAYER:0
            ;MESH:model.stl
            ;ENDERSLICER_LAYER_EVENT:calibration-0:NOZZLE_TEMPERATURE:CALIBRATION
            M104 S230
            G1 X10 Y10 Z0.2 E1
            ;TIME_ELAPSED:1
            M84 ; custom end script
            M104 S215 ; custom end script reheats
            """.trimIndent(),
        )

        GcodeSanitizer.validateAndRepair(file)
        val output = file.readText()
        val shutdown = "M104 S0 ; enderslicercura temperature calibration safety shutdown"

        assertTrue(output.indexOf("M104 S215 ; custom end script reheats") < output.indexOf(shutdown))
        assertTrue(output.trimEnd().endsWith(shutdown))
        assertEquals(1, output.lineSequence().count { shutdown in it })
    }

    @Test
    fun repeatedValidationKeepsExactlyOneFinalShutdown() {
        val file = temporaryGcode(
            """
            ;FLAVOR:Marlin
            ;LAYER_COUNT:1
            M104 S200
            ;LAYER:0
            ;MESH:model.stl
            ;ENDERSLICER_LAYER_EVENT:calibration-0:NOZZLE_TEMPERATURE:CALIBRATION
            M104 S220
            G1 X1 Y1 Z0.2 E1
            ;TIME_ELAPSED:1
            M104 S205
            """.trimIndent(),
        )

        repeat(2) { GcodeSanitizer.validateAndRepair(file) }
        val shutdown = "M104 S0 ; enderslicercura temperature calibration safety shutdown"

        assertEquals(1, file.readLines().count { shutdown in it })
        assertEquals(shutdown, file.readLines().last())
    }

    @Test
    fun manualNozzleEventDoesNotSilentlyChangeTheUsersEndScript() {
        val file = temporaryGcode(
            """
            ;FLAVOR:Marlin
            ;LAYER_COUNT:1
            M104 S200
            ;LAYER:0
            ;MESH:model.stl
            ;ENDERSLICER_LAYER_EVENT:user-temp:NOZZLE_TEMPERATURE:USER
            M104 S220
            G1 X1 Y1 Z0.2 E1
            ;TIME_ELAPSED:1
            M104 S205
            """.trimIndent(),
        )

        GcodeSanitizer.validateAndRepair(file)

        assertFalse(file.readText().contains("temperature calibration safety shutdown"))
        assertTrue(file.readText().trimEnd().endsWith("M104 S205"))
    }

    private fun temporaryGcode(content: String): File {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-temperature-safety").toFile()
        return File(directory, "output.gcode").apply { writeText(content) }
    }
}
