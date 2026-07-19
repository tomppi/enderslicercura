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
    fun importedFallbackProfileRemainsActiveWithoutExplicitAppOverrides() {
        val machineDefinition = "/files/definitions/creality_ender3.def.json"
        val extruderDefinition = "/files/definitions/creality_base_extruder_0.def.json"
        val profile = CuraEngineProfile(
            globalValues = linkedMapOf(
                "slicing_tolerance" to "exclusive",
                "support_enable" to "False",
                "unresolved" to "=some_formula()",
            ),
            extruderValues = linkedMapOf(
                "coasting_enable" to "True",
                "coasting_volume" to "0.256",
                "speed_print" to "120",
                "material_standby_temperature" to "180",
                "cool_min_temperature" to "0",
            ),
        )
        val command = CuraEngineCommand.build(
            executablePath = "/native/libcuraengine_exec.so",
            definitionsDirectory = "/files/definitions",
            machineDefinitionPath = machineDefinition,
            extruderDefinitionPath = extruderDefinition,
            modelPath = "/files/current model.stl",
            outputPath = "/files/current.gcode",
            printer = printer,
            settings = SlicerSettings(
                printSpeedMmPerSecond = 200.0,
                supportsEnabled = true,
                overriddenSettingKeys = emptySet(),
            ),
            startGcode = "G28\nG29 L0\nG29 A",
            endGcode = "M104 S0\nM140 S0",
            profile = profile,
        )

        assertEquals("/native/libcuraengine_exec.so", command.first())
        assertEquals("slice", command[1])
        assertEquals(2, command.count { it == "--force-read-parent" })
        assertFalse(command.contains("--force-read-nondefault"))
        assertFalse(command.any { it.startsWith("unresolved=") })
        assertTrue(command.contains("slicing_tolerance=exclusive"))
        assertTrue(command.contains("coasting_enable=true"))
        assertTrue(command.contains("coasting_volume=0.256"))
        assertTrue(command.contains("material_standby_temperature=180"))
        assertTrue(command.contains("cool_min_temperature=0"))
        assertFalse(command.contains("cool_min_temperature=210"))
        assertTrue(command.contains("speed_print=120"))
        assertFalse(command.contains("speed_print=200.0"))
        assertTrue(command.contains("support_enable=false"))
        assertFalse(command.contains("support_enable=true"))
        assertFalse(command.contains("material_initial_print_temperature=210"))
        assertFalse(command.contains("material_final_print_temperature=210"))

        assertContainsSubsequence(
            command,
            listOf("--force-read-parent", "-j", machineDefinition, "--end-force-read"),
        )
        assertContainsSubsequence(
            command,
            listOf(
                "-e0",
                "--force-read-parent",
                "-j",
                machineDefinition,
                "-j",
                extruderDefinition,
                "--end-force-read",
            ),
        )
        assertEquals("/files/current.gcode", command.last())
        assertEquals("-o", command[command.lastIndex - 1])
    }

    private fun assertContainsSubsequence(command: List<String>, expected: List<String>) {
        assertTrue(
            "Expected command to contain contiguous sequence: $expected\nActual: $command",
            command.windowed(expected.size).any { it == expected },
        )
    }
}
