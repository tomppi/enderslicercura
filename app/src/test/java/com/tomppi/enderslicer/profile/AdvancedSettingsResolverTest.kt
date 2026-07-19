package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AdvancedSettingsResolverTest {
    @Test
    fun explicitAdvancedOverridesReplaceImportedCuraValues() {
        val settings = SlicerSettings(
            slicingTolerance = "exclusive",
            zSeamType = "back",
            zSeamXmm = 15.5,
            zSeamYmm = 224.0,
            zSeamRelative = true,
            zSeamCorner = "z_seam_corner_weighted",
            coastingEnabled = true,
            coastingVolumeMm3 = 0.08,
            coastingMinimumVolumeMm3 = 1.2,
            coastingSpeedPercent = 92.0,
            overriddenSettingKeys = setOf(
                SlicerSettings.Keys.SLICING_TOLERANCE,
                SlicerSettings.Keys.Z_SEAM_TYPE,
                SlicerSettings.Keys.Z_SEAM_X,
                SlicerSettings.Keys.Z_SEAM_Y,
                SlicerSettings.Keys.Z_SEAM_RELATIVE,
                SlicerSettings.Keys.Z_SEAM_CORNER,
                SlicerSettings.Keys.COASTING_ENABLED,
                SlicerSettings.Keys.COASTING_VOLUME,
                SlicerSettings.Keys.COASTING_MINIMUM_VOLUME,
                SlicerSettings.Keys.COASTING_SPEED,
            ),
        )

        val resolved = CuraSliceSettingsResolver.resolve(
            profile = profile(),
            printer = printer(),
            settings = settings,
            startGcode = "G28",
            endGcode = "M104 S0",
        ).extruderValues

        assertEquals("exclusive", resolved["slicing_tolerance"])
        assertEquals("back", resolved["z_seam_type"])
        assertNumeric(resolved, "z_seam_x", 15.5)
        assertNumeric(resolved, "z_seam_y", 224.0)
        assertEquals("true", resolved["z_seam_relative"]?.lowercase())
        assertEquals("z_seam_corner_weighted", resolved["z_seam_corner"])
        assertEquals("true", resolved["coasting_enable"]?.lowercase())
        assertNumeric(resolved, "coasting_volume", 0.08)
        assertNumeric(resolved, "coasting_min_volume", 1.2)
        assertNumeric(resolved, "coasting_speed", 92.0)
    }

    private fun profile(): CuraEngineProfile = CuraEngineProfile(
        globalValues = emptyMap(),
        extruderValues = emptyMap(),
        rawGlobalValues = linkedMapOf(
            "layer_height" to "0.2",
            "layer_height_0" to "0.28",
            "material_bed_temperature" to "60",
        ),
        rawExtruderValues = linkedMapOf(
            "machine_nozzle_size" to "0.4",
            "material_diameter" to "1.75",
            "line_width" to "0.4",
            "slicing_tolerance" to "middle",
            "z_seam_type" to "sharpest_corner",
            "z_seam_x" to "115",
            "z_seam_y" to "230",
            "z_seam_relative" to "false",
            "z_seam_corner" to "z_seam_corner_inner",
            "coasting_enable" to "false",
            "coasting_volume" to "0.064",
            "coasting_min_volume" to "0.8",
            "coasting_speed" to "90",
            "material_print_temperature" to "210",
            "material_print_temperature_layer_0" to "235",
            "cool_min_temperature" to "210",
        ),
        definitionFiles = loadDefinitions(),
        machineDefinitionFileName = "creality_ender3.def.json",
        extruderDefinitionFileName = "creality_base_extruder_0.def.json",
    )

    private fun printer(): PrinterDefinition = PrinterDefinition(
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

    private fun assertNumeric(values: Map<String, String>, key: String, expected: Double) {
        val actual = values[key]?.toDoubleOrNull() ?: error("Missing numeric setting $key")
        assertEquals(expected, actual, 1e-7)
    }

    private fun loadDefinitions(): Map<String, String> {
        val directory = sequenceOf(
            File("app/src/main/assets/cura/definitions"),
            File("src/main/assets/cura/definitions"),
        ).firstOrNull(File::isDirectory)
            ?: error("Pinned Cura definition directory was not found")
        return listOf(
            "fdmprinter.def.json",
            "fdmextruder.def.json",
            "creality_base.def.json",
            "creality_base_extruder_0.def.json",
            "creality_ender3.def.json",
        ).associateWith { name -> File(directory, name).readText() }
    }
}
