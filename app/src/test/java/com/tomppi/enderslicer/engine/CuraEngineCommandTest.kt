package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CuraEngineCommandTest {
    private val printer = PrinterDefinition(
        id = "modified_ender3_v2",
        name = "Modified Ender 3 V2",
        manufacturer = "Creality",
        widthMm = 230.0,
        depthMm = 230.0,
        heightMm = 250.0,
        buildPlateShape = "rectangular",
        originAtCenter = false,
        heatedBed = true,
        heatedBuildVolume = false,
        gcodeFlavor = "Marlin",
        extruders = 1,
        nozzleSizeMm = 0.4,
        filamentDiameterMm = 1.75,
        printheadXMinMm = -26.0,
        printheadYMinMm = -32.0,
        printheadXMaxMm = 32.0,
        printheadYMaxMm = 34.0,
        gantryHeightMm = 25.0,
        directDrive = true,
        dualZ = true,
        zProbe = true,
        bedLeveling = "UBL",
        ublMeshSlot = 0,
    )

    @Test
    fun createsArgumentSafeEnder3Command() {
        val command = CuraEngineCommand.build(
            executablePath = "/native/libcuraengine_exec.so",
            definitionsDirectory = "/files/definitions",
            modelPath = "/files/current model.stl",
            outputPath = "/files/current.gcode",
            printer = printer,
            settings = SlicerSettings(printSpeedMmPerSecond = 200.0),
            startGcode = "G28\nG29 L0\nG29 A",
            endGcode = "M104 S0\nM140 S0",
        )

        assertEquals("/native/libcuraengine_exec.so", command.first())
        assertEquals("slice", command[1])
        assertTrue(command.contains("machine_width=230.0"))
        assertTrue(command.contains("machine_start_gcode=G28\nG29 L0\nG29 A"))
        assertTrue(command.contains("speed_print=200.0"))
        assertTrue(command.contains("machine_nozzle_size=0.4"))
        assertEquals("/files/current.gcode", command.last())
        assertEquals("-o", command[command.lastIndex - 1])
    }
}
