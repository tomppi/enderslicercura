package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CuraEngineCommandPrinterTest {
    @Test
    fun fallbackCommandUsesCustomMachineAndGcodeSettings() {
        val printer = PrinterDefinition(
            id = "base",
            name = "Base",
            manufacturer = "Test",
            widthMm = 100.0,
            depthMm = 100.0,
            heightMm = 100.0,
            buildPlateShape = "rectangular",
            originAtCenter = false,
            heatedBed = false,
            heatedBuildVolume = false,
            gcodeFlavor = "Marlin",
            extruders = 1,
            nozzleSizeMm = 0.4,
            filamentDiameterMm = 1.75,
            printheadXMinMm = -10.0,
            printheadYMinMm = -10.0,
            printheadXMaxMm = 10.0,
            printheadYMaxMm = 10.0,
            gantryHeightMm = 20.0,
            directDrive = false,
            dualZ = false,
            zProbe = false,
            bedLeveling = "none",
            ublMeshSlot = 0,
        )
        val settings = SlicerSettings(
            printerName = "Other printer",
            machineWidthMm = 320.0,
            machineDepthMm = 330.0,
            machineHeightMm = 410.0,
            buildPlateShape = "elliptic",
            originAtCenter = true,
            heatedBed = true,
            gcodeFlavor = "RepRap",
            nozzleSizeMm = 0.6,
            filamentDiameterMm = 2.85,
            customStartGcodeEnabled = true,
            customStartGcode = "G28\nM117 custom start",
            customEndGcodeEnabled = true,
            customEndGcode = "M104 S0\nM84",
        )

        val command = CuraEngineCommand.build(
            executablePath = "/tmp/CuraEngine",
            definitionsDirectory = "/tmp/definitions",
            machineDefinitionPath = "/tmp/machine.def.json",
            extruderDefinitionPath = "/tmp/extruder.def.json",
            modelPath = "/tmp/model.stl",
            outputPath = "/tmp/output.gcode",
            printer = printer,
            settings = settings,
            startGcode = "G28 ; fallback",
            endGcode = "M84 ; fallback",
        )
        val values = commandSettings(command)

        assertEquals("Other printer", values["machine_name"])
        assertEquals("320.0", values["machine_width"])
        assertEquals("330.0", values["machine_depth"])
        assertEquals("410.0", values["machine_height"])
        assertEquals("elliptic", values["machine_shape"])
        assertEquals("true", values["machine_center_is_zero"])
        assertEquals("true", values["machine_heated_bed"])
        assertEquals("RepRap", values["machine_gcode_flavor"])
        assertEquals("G28\nM117 custom start", values["machine_start_gcode"])
        assertEquals("M104 S0\nM84", values["machine_end_gcode"])
        assertEquals("0.6", values["machine_nozzle_size"])
        assertEquals("2.85", values["material_diameter"])
        assertTrue(command.contains("-l"))
    }

    private fun commandSettings(command: List<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < command.size - 1) {
            if (command[index] == "-s") {
                val setting = command[index + 1]
                val separator = setting.indexOf('=')
                if (separator > 0) result[setting.substring(0, separator)] = setting.substring(separator + 1)
                index += 2
            } else {
                index += 1
            }
        }
        return result
    }
}
