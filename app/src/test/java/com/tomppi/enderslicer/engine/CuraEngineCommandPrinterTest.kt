package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.profile.CuraEngineProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CuraEngineCommandPrinterTest {
    @Test
    fun fallbackCommandUsesCustomMachineGcodeAndAdvancedSettings() {
        val printer = printer(width = 100.0, depth = 100.0, originAtCenter = false)
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
            slicingTolerance = "inclusive",
            zSeamType = "back",
            zSeamXmm = 12.5,
            zSeamYmm = 300.0,
            zSeamRelative = true,
            zSeamCorner = "z_seam_corner_weighted",
            coastingEnabled = true,
            coastingVolumeMm3 = 0.09,
            coastingMinimumVolumeMm3 = 1.4,
            coastingSpeedPercent = 95.0,
        )

        val values = commandSettings(build(printer, settings))

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
        assertEquals("inclusive", values["slicing_tolerance"])
        assertEquals("back", values["z_seam_type"])
        assertEquals("12.5", values["z_seam_x"])
        assertEquals("300.0", values["z_seam_y"])
        assertEquals("true", values["z_seam_relative"])
        assertEquals("z_seam_corner_weighted", values["z_seam_corner"])
        assertEquals("true", values["coasting_enable"])
        assertEquals("0.09", values["coasting_volume"])
        assertEquals("1.4", values["coasting_min_volume"])
        assertEquals("95.0", values["coasting_speed"])
        assertEquals("false", values["center_object"])
        assertEquals("0.0", values["mesh_position_x"])
        assertTrue(build(printer, settings).contains("-l"))
    }

    @Test
    fun standaloneFallbackUsesTreeDensityAndGenericInterfaceWithoutForcingRoofOrBottom() {
        val settings = SlicerSettings(
            machineWidthMm = 230.0,
            machineDepthMm = 230.0,
            originAtCenter = false,
            supportsEnabled = true,
            supportStructure = "tree",
            supportPlacement = "everywhere",
            supportDensityPercent = 20.0,
            supportPattern = "grid",
            supportInterfaceEnabled = true,
            supportInterfaceDensityPercent = 33.333,
            supportInterfaceSpeedMmPerSecond = 30.0,
            layerHeightMm = 0.2,
            lineWidthMm = 0.4,
        )

        val values = commandSettings(build(printer(width = 230.0, depth = 230.0, originAtCenter = false), settings))

        assertEquals("-115.0", values["mesh_position_x"])
        assertEquals("-115.0", values["mesh_position_y"])
        assertEquals("20.0", values["support_infill_rate"])
        assertEquals("true", values["support_interface_enable"])
        assertEquals("33.333", values["support_interface_density"])
        assertEquals("0.8", values["support_interface_height"])
        assertEquals("grid", values["support_interface_pattern"])
        assertEquals("2.4000240002400024", values["support_roof_line_distance"])
        assertEquals("30.0", values["speed_support_interface"])
        assertFalse(values.containsKey("support_roof_enable"))
        assertFalse(values.containsKey("support_bottom_enable"))
        assertFalse(values.containsKey("support_roof_density"))
        assertFalse(values.containsKey("support_bottom_density"))
    }

    @Test
    fun importedFallbackValuesSurviveUntilTheirAndroidFieldsAreOverridden() {
        val profile = importedSupportProfile()
        val settings = SlicerSettings(
            supportsEnabled = true,
            supportStructure = "tree",
            supportInterfaceEnabled = true,
            outerWallSpeedMmPerSecond = 50.0,
            innerWallSpeedMmPerSecond = 100.0,
            supportInterfaceDensityPercent = 100.0,
            supportXyDistanceMm = 0.8,
            overriddenSettingKeys = emptySet(),
        )

        val values = commandSettings(
            build(
                printer = printer(width = 230.0, depth = 230.0, originAtCenter = false),
                settings = settings,
                profile = profile,
            ),
        )

        assertEquals("30", values["speed_wall_0"])
        assertEquals("60", values["speed_wall_x"])
        assertEquals("60", values["speed_infill"])
        assertEquals("30", values["speed_topbottom"])
        assertEquals("120", values["speed_travel"])
        assertEquals("0.0", values["support_infill_rate"])
        assertEquals("true", values["support_interface_enable"])
        assertEquals("33.333", values["support_interface_density"])
        assertEquals("0.8", values["support_interface_height"])
        assertEquals("grid", values["support_interface_pattern"])
        assertEquals("false", values["support_roof_enable"])
        assertEquals("false", values["support_bottom_enable"])
        assertEquals("0.7", values["support_xy_distance"])
        assertEquals("5", values["support_tree_branch_diameter"])
        assertEquals("0.4", values["support_tree_tip_diameter"])
        assertEquals("60", values["support_tree_angle"])
    }

    @Test
    fun explicitAndroidOverridesStillWinOverImportedFallbackValues() {
        val settings = SlicerSettings(
            supportsEnabled = true,
            supportStructure = "tree",
            outerWallSpeedMmPerSecond = 42.0,
            supportInterfaceDensityPercent = 55.0,
            overriddenSettingKeys = setOf(
                SlicerSettings.Keys.OUTER_WALL_SPEED,
                SlicerSettings.Keys.SUPPORT_INTERFACE_DENSITY,
            ),
        )

        val values = commandSettings(
            build(
                printer = printer(width = 230.0, depth = 230.0, originAtCenter = false),
                settings = settings,
                profile = importedSupportProfile(),
            ),
        )

        assertEquals("42.0", values["speed_wall_0"])
        assertEquals("55.0", values["support_interface_density"])
        assertEquals("1.4545454545454546", values["support_roof_line_distance"])
        assertEquals("false", values["support_roof_enable"])
        assertEquals("false", values["support_bottom_enable"])
        assertEquals("5", values["support_tree_branch_diameter"])
    }

    private fun importedSupportProfile() = CuraEngineProfile(
        globalValues = linkedMapOf(
            "support_enable" to "true",
            "support_structure" to "tree",
            "support_type" to "everywhere",
            "support_roof_enable" to "false",
            "support_bottom_enable" to "false",
            "support_tree_branch_diameter" to "5",
            "support_tree_tip_diameter" to "0.4",
            "support_tree_angle" to "60",
            "support_tree_angle_slow" to "50",
            "support_tree_branch_diameter_angle" to "7",
            "support_tree_max_diameter" to "25",
        ),
        extruderValues = linkedMapOf(
            "speed_wall_0" to "30",
            "speed_wall_x" to "60",
            "speed_infill" to "60",
            "speed_topbottom" to "30",
            "speed_travel" to "120",
            "support_infill_rate" to "=0 if support_enable and support_structure == 'tree' else 20",
            "support_interface_enable" to "true",
            "support_interface_density" to "33.333",
            "support_interface_height" to "0.8",
            "support_interface_pattern" to "grid",
            "support_xy_distance" to "0.7",
            "speed_support" to "30",
            "speed_support_interface" to "30",
        ),
    )

    private fun build(
        printer: PrinterDefinition,
        settings: SlicerSettings,
        profile: CuraEngineProfile? = null,
    ): List<String> = CuraEngineCommand.build(
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
        profile = profile,
    )

    private fun printer(width: Double, depth: Double, originAtCenter: Boolean) = PrinterDefinition(
        id = "base",
        name = "Base",
        manufacturer = "Test",
        widthMm = width,
        depthMm = depth,
        heightMm = 100.0,
        buildPlateShape = "rectangular",
        originAtCenter = originAtCenter,
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
