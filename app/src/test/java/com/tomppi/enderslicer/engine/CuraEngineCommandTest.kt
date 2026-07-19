package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.profile.CuraEngineProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun resolvedCommandUsesOfficialResolvedSettingsInput() {
        val command = CuraEngineCommand.buildResolved(
            executablePath = "/native/libcuraengine_exec.so",
            definitionsDirectory = "/files/definitions",
            resolvedSettingsPath = "/files/resolved-settings.json",
            outputPath = "/files/current.gcode",
        )

        assertEquals(
            listOf(
                "/native/libcuraengine_exec.so",
                "slice",
                "-m4",
                "-d",
                "/files/definitions",
                "-r",
                "/files/resolved-settings.json",
                "-o",
                "/files/current.gcode",
            ),
            command,
        )
        assertFalse(command.contains("-l"))
        assertFalse(command.contains("-j"))
        assertFalse(command.contains("-s"))
    }

    @Test
    fun currentVisibleValuesWinOverConflictingImportedGlobalAndExtruderCopies() {
        val profile = CuraEngineProfile(
            globalValues = linkedMapOf(
                "layer_height" to "0.2",
                "adhesion_type" to "none",
                "support_enable" to "true",
                "hidden_tree_value" to "kept-global",
            ),
            extruderValues = linkedMapOf(
                "layer_height" to "0.1",
                "adhesion_type" to "brim",
                "material_print_temperature" to "200",
                "cool_min_temperature" to "0",
                "hidden_tree_value" to "kept-extruder",
            ),
        )
        val settings = SlicerSettings(
            layerHeightMm = 0.2,
            adhesionType = "none",
            nozzleTemperatureC = 210,
            initialNozzleTemperatureC = 235,
            supportsEnabled = true,
            supportStructure = "tree",
            supportDensityPercent = 15.0,
            overriddenSettingKeys = emptySet(),
        )

        val command = CuraEngineCommand.build(
            executablePath = "/native/libcuraengine_exec.so",
            definitionsDirectory = "/files/definitions",
            machineDefinitionPath = "/files/definitions/creality_ender3.def.json",
            extruderDefinitionPath = "/files/definitions/creality_base_extruder_0.def.json",
            modelPath = "/files/current model.stl",
            outputPath = "/files/current.gcode",
            printer = printer,
            settings = settings,
            startGcode = "G28",
            endGcode = "M104 S0",
            profile = profile,
        )
        val values = commandSettings(command)

        assertEquals("0.2", values["layer_height"])
        assertEquals("none", values["adhesion_type"])
        assertEquals("210", values["material_print_temperature"])
        assertEquals("235", values["material_print_temperature_layer_0"])
        assertEquals("210", values["cool_min_temperature"])
        assertEquals("0.0", values["support_infill_rate"])
        assertEquals("kept-extruder", values["hidden_tree_value"])
        assertEquals("-115.0", values["mesh_position_x"])
        assertEquals("-115.0", values["mesh_position_y"])
        assertTrue(command.contains("-l"))
    }

    private fun commandSettings(command: List<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < command.size - 1) {
            if (command[index] == "-s") {
                val raw = command[index + 1]
                result[raw.substringBefore('=')] = raw.substringAfter('=', "")
                index += 2
            } else {
                index++
            }
        }
        return result
    }
}
