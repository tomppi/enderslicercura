package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.smartinfill.SmartInfillActivity
import com.tomppi.enderslicer.smartinfill.SmartInfillModifier
import com.tomppi.enderslicer.smartinfill.SmartInfillPackage
import com.tomppi.enderslicer.smartinfill.SmartInfillRuntime
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CuraSliceSettingsResolverSmartInfillTest {
    @After
    fun clearSmartInfill() {
        SmartInfillRuntime.activate(null)
    }

    @Test
    fun pinnedDefinitionsRecomputeDensityDependentValuesAndOverrideChildWidths() {
        val profile = pinnedProfile(
            rawExtruderValues = mapOf(
                "wall_line_width" to "0.60",
                "wall_line_width_0" to "0.60",
                "wall_line_width_x" to "0.60",
                "skin_line_width" to "0.60",
                "infill_line_width" to "0.60",
            ),
        )
        val packageValue = SmartInfillPackage(
            id = "resolver-test",
            directory = File("."),
            sourceName = "fixture.stl",
            sourceSha256 = "0".repeat(64),
            baseDensityPercent = 10.0,
            pattern = "cubic",
            mode = "graded",
            perimeters = 3,
            lineWidthMm = 0.40,
            topBottomLayers = 5,
            layerHeightMm = 0.20,
            upstreamCommit = SmartInfillActivity.FILASIM_COMMIT,
            modifiers = listOf(
                SmartInfillModifier(35, File("unused-35.stl")),
                SmartInfillModifier(70, File("unused-70.stl")),
            ),
        )
        SmartInfillRuntime.activate(packageValue)

        val resolved = CuraSliceSettingsResolver.resolve(
            profile = profile,
            printer = printer,
            settings = SlicerSettings(),
            startGcode = "G28",
            endGcode = "M104 S0",
        )

        listOf(
            "line_width",
            "wall_line_width",
            "wall_line_width_0",
            "wall_line_width_x",
            "skin_line_width",
            "infill_line_width",
        ).forEach { key ->
            assertEquals("$key must match the filaSim analysis", 0.40, resolved.extruderValues.getValue(key).toDouble(), 1e-7)
        }

        val baseDistance = resolved.modelValues.getValue("infill_line_distance").toDouble()
        val lowDistance = resolved.smartInfillModelValues.getValue(35).getValue("infill_line_distance").toDouble()
        val highDistance = resolved.smartInfillModelValues.getValue(70).getValue("infill_line_distance").toDouble()
        assertNotEquals(baseDistance, lowDistance, 1e-7)
        assertNotEquals(lowDistance, highDistance, 1e-7)
        assertTrue(baseDistance > lowDistance)
        assertTrue(lowDistance > highDistance)
        assertEquals(35.0, resolved.smartInfillModelValues.getValue(35).getValue("infill_sparse_density").toDouble(), 0.0)
        assertEquals(70.0, resolved.smartInfillModelValues.getValue(70).getValue("infill_sparse_density").toDouble(), 0.0)
    }

    private fun pinnedProfile(rawExtruderValues: Map<String, String>): CuraEngineProfile {
        val directory = File("src/main/assets/cura/definitions")
        val names = listOf(
            "fdmprinter.def.json",
            "fdmextruder.def.json",
            "creality_base.def.json",
            "creality_base_extruder_0.def.json",
            "creality_ender3.def.json",
        )
        val files = names.associateWith { name ->
            val file = File(directory, name)
            require(file.isFile) {
                "Pinned Cura definition is missing for the test: ${file.absolutePath}. Run scripts/fetch-cura-resources.sh."
            }
            file.readText()
        }
        return CuraEngineProfile(
            rawExtruderValues = rawExtruderValues,
            definitionFiles = files,
            machineDefinitionFileName = "creality_ender3.def.json",
            extruderDefinitionFileName = "creality_base_extruder_0.def.json",
        )
    }

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
}
