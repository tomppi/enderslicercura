package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CuraSliceSettingsResolverTest {
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
    fun appEditsAreAppliedBeforeDependentCuraFormulas() {
        val profile = profile()
        val settings = SlicerSettings(
            layerHeightMm = 0.20,
            initialLayerHeightMm = 0.28,
            lineWidthMm = 0.40,
            wallLineCount = 4,
            topLayers = 7,
            bottomLayers = 6,
            infillDensityPercent = 10.0,
            infillPattern = "gyroid",
            printSpeedMmPerSecond = 200.0,
            wallSpeedMmPerSecond = 80.0,
            outerWallSpeedMmPerSecond = 40.0,
            innerWallSpeedMmPerSecond = 70.0,
            infillSpeedMmPerSecond = 150.0,
            topBottomSpeedMmPerSecond = 60.0,
            travelSpeedMmPerSecond = 220.0,
            initialLayerSpeedMmPerSecond = 25.0,
            nozzleTemperatureC = 210,
            initialNozzleTemperatureC = 235,
            bedTemperatureC = 60,
            fanSpeedPercent = 100.0,
            initialFanSpeedPercent = 15.0,
            fanFullAtLayer = 5,
            supportsEnabled = true,
            supportPlacement = "everywhere",
            supportStructure = "tree",
            supportAngleDegrees = 56.0,
            retractionMinimumTravelMm = 2.5,
            combingMode = "noskin",
            avoidPrintedParts = true,
            travelAvoidDistanceMm = 0.8,
            zHopHeightMm = 0.4,
            skirtLineCount = 3,
            brimWidthMm = 10.0,
            ironingEnabled = true,
            ironingFlowPercent = 12.0,
            ironingSpeedMmPerSecond = 18.0,
            overriddenSettingKeys = setOf(
                SlicerSettings.Keys.LAYER_HEIGHT,
                SlicerSettings.Keys.INITIAL_LAYER_HEIGHT,
                SlicerSettings.Keys.LINE_WIDTH,
                SlicerSettings.Keys.WALL_LINE_COUNT,
                SlicerSettings.Keys.TOP_LAYERS,
                SlicerSettings.Keys.BOTTOM_LAYERS,
                SlicerSettings.Keys.INFILL_DENSITY,
                SlicerSettings.Keys.INFILL_PATTERN,
                SlicerSettings.Keys.PRINT_SPEED,
                SlicerSettings.Keys.WALL_SPEED,
                SlicerSettings.Keys.OUTER_WALL_SPEED,
                SlicerSettings.Keys.INNER_WALL_SPEED,
                SlicerSettings.Keys.INFILL_SPEED,
                SlicerSettings.Keys.TOP_BOTTOM_SPEED,
                SlicerSettings.Keys.TRAVEL_SPEED,
                SlicerSettings.Keys.INITIAL_LAYER_SPEED,
                SlicerSettings.Keys.NOZZLE_TEMPERATURE,
                SlicerSettings.Keys.INITIAL_NOZZLE_TEMPERATURE,
                SlicerSettings.Keys.BED_TEMPERATURE,
                SlicerSettings.Keys.FAN_SPEED,
                SlicerSettings.Keys.INITIAL_FAN_SPEED,
                SlicerSettings.Keys.FAN_FULL_AT_LAYER,
                SlicerSettings.Keys.SUPPORTS_ENABLED,
                SlicerSettings.Keys.SUPPORT_PLACEMENT,
                SlicerSettings.Keys.SUPPORT_STRUCTURE,
                SlicerSettings.Keys.SUPPORT_ANGLE,
                SlicerSettings.Keys.RETRACTION_MINIMUM_TRAVEL,
                SlicerSettings.Keys.COMBING_MODE,
                SlicerSettings.Keys.AVOID_PRINTED_PARTS,
                SlicerSettings.Keys.TRAVEL_AVOID_DISTANCE,
                SlicerSettings.Keys.Z_HOP_HEIGHT,
                SlicerSettings.Keys.SKIRT_LINE_COUNT,
                SlicerSettings.Keys.BRIM_WIDTH,
                SlicerSettings.Keys.IRONING_ENABLED,
                SlicerSettings.Keys.IRONING_FLOW,
                SlicerSettings.Keys.IRONING_SPEED,
            ),
        )

        val resolved = resolve(profile, settings)

        assertNumeric(resolved.extruderValues, "material_print_temperature", 210.0)
        assertNumeric(resolved.extruderValues, "material_print_temperature_layer_0", 235.0)
        assertNumeric(resolved.extruderValues, "cool_min_temperature", 210.0)
        assertNumeric(resolved.extruderValues, "speed_print", 200.0)
        assertNumeric(resolved.extruderValues, "speed_wall", 80.0)
        assertNumeric(resolved.extruderValues, "speed_wall_0", 40.0)
        assertNumeric(resolved.extruderValues, "speed_wall_x", 70.0)
        assertNumeric(resolved.extruderValues, "speed_infill", 150.0)
        assertNumeric(resolved.extruderValues, "speed_topbottom", 60.0)
        assertNumeric(resolved.extruderValues, "speed_travel", 220.0)
        assertNumeric(resolved.extruderValues, "speed_layer_0", 25.0)
        assertNumeric(resolved.extruderValues, "top_layers", 7.0)
        assertNumeric(resolved.extruderValues, "bottom_layers", 6.0)
        assertNumeric(resolved.extruderValues, "wall_line_count", 4.0)
        assertNumeric(resolved.extruderValues, "infill_line_distance", 4.0)
        assertNumeric(resolved.extruderValues, "cool_fan_speed_0", 15.0)
        assertNumeric(resolved.extruderValues, "cool_fan_full_layer", 5.0)
        assertNumeric(resolved.extruderValues, "retraction_min_travel", 2.5)
        assertNumeric(resolved.extruderValues, "travel_avoid_distance", 0.8)
        assertNumeric(resolved.extruderValues, "retraction_hop", 0.4)
        assertNumeric(resolved.extruderValues, "skirt_line_count", 3.0)
        assertNumeric(resolved.extruderValues, "brim_width", 10.0)
        assertNumeric(resolved.extruderValues, "ironing_flow", 12.0)
        assertNumeric(resolved.extruderValues, "speed_ironing", 18.0)
        assertEquals("gyroid", resolved.extruderValues["infill_pattern"])
        assertEquals("noskin", resolved.extruderValues["retraction_combing"])
        assertEquals("true", resolved.extruderValues["travel_avoid_other_parts"]?.lowercase())
        assertEquals("true", resolved.extruderValues["ironing_enabled"]?.lowercase())
        assertEquals("G28", resolved.globalValues["machine_start_gcode"])
        assertEquals("M104 S0", resolved.globalValues["machine_end_gcode"])
        assertTrue(resolved.expressionCount > 0)
    }

    @Test
    fun importedValuesRemainActiveWithoutExplicitAppOverrides() {
        val resolved = resolve(
            profile(),
            SlicerSettings(
                printSpeedMmPerSecond = 300.0,
                nozzleTemperatureC = 245,
                initialNozzleTemperatureC = 250,
                overriddenSettingKeys = emptySet(),
            ),
        )

        assertNumeric(resolved.extruderValues, "speed_print", 120.0)
        assertNumeric(resolved.extruderValues, "material_print_temperature", 200.0)
        assertNumeric(resolved.extruderValues, "material_print_temperature_layer_0", 220.0)
        assertNumeric(resolved.extruderValues, "cool_min_temperature", 200.0)
    }

    @Test
    fun globalTreeSupportFeedsExtruderFormulasAndMeshScope() {
        val resolved = resolve(
            profile(),
            SlicerSettings(overriddenSettingKeys = emptySet()),
        )

        assertEquals("tree", resolved.extruderValues["support_structure"])
        assertEquals("everywhere", resolved.extruderValues["support_type"])
        assertEquals("true", resolved.extruderValues["support_enable"]?.lowercase())
        assertNumeric(resolved.extruderValues, "support_infill_rate", 0.0)
        assertNumeric(resolved.extruderValues, "support_interface_density", 33.333)

        assertEquals("true", resolved.modelValues["support_enable"]?.lowercase())
        assertEquals("true", resolved.modelValues["support_interface_enable"]?.lowercase())
        assertEquals("true", resolved.modelValues["support_roof_enable"]?.lowercase())
        assertNumeric(resolved.modelValues, "support_z_distance", 0.2)
        assertNumeric(resolved.modelValues, "support_xy_distance", 0.8)
        assertNumeric(resolved.modelValues, "support_angle", 56.0)
    }

    private fun resolve(
        profile: CuraEngineProfile,
        settings: SlicerSettings,
    ): CuraSliceSettingsResolver.Result = CuraSliceSettingsResolver.resolve(
        profile = profile,
        printer = printer,
        settings = settings,
        startGcode = "G28",
        endGcode = "M104 S0",
    )

    private fun profile(): CuraEngineProfile = CuraEngineProfile(
        globalValues = mapOf("layer_height" to "0.2"),
        extruderValues = mapOf("speed_print" to "120"),
        rawGlobalValues = linkedMapOf(
            "layer_height" to "0.2",
            "layer_height_0" to "0.28",
            "top_bottom_thickness" to "=layer_height_0+layer_height*3",
            "wall_thickness" to "=line_width*2",
            "support_enable" to "True",
            "support_type" to "everywhere",
            "support_structure" to "tree",
            "material_bed_temperature" to "60",
        ),
        rawExtruderValues = linkedMapOf(
            "machine_nozzle_size" to "0.4",
            "line_width" to "=machine_nozzle_size",
            "speed_print" to "120",
            "speed_infill" to "=speed_print",
            "speed_wall" to "=speed_print / 2",
            "speed_topbottom" to "=speed_print / 2",
            "infill_sparse_density" to "10",
            "infill_pattern" to "cubic",
            "infill_line_width" to "=line_width",
            "infill_line_distance" to "=0 if infill_sparse_density == 0 else (infill_line_width * 100) / infill_sparse_density * (3 if infill_pattern == 'cubic' else 1)",
            "material_print_temperature" to "200",
            "material_print_temperature_layer_0" to "220",
            "cool_min_temperature" to "=material_print_temperature",
            "cool_fan_speed" to "100",
            "cool_fan_speed_0" to "0",
            "cool_fan_full_at_height" to "=layer_height_0 + layer_height * 2",
            "cool_fan_full_layer" to "=max(1, int(math.floor((cool_fan_full_at_height - resolveOrValue('layer_height_0')) / resolveOrValue('layer_height')) + 2))",
            "top_bottom_thickness" to "=layer_height_0+layer_height*3",
            "top_thickness" to "=top_bottom_thickness",
            "bottom_thickness" to "=top_bottom_thickness",
            "top_layers" to "=0 if infill_sparse_density == 100 else math.ceil(round(top_thickness / resolveOrValue('layer_height'), 4))",
            "bottom_layers" to "=999999 if infill_sparse_density == 100 and not magic_spiralize else math.ceil(round(bottom_thickness / resolveOrValue('layer_height'), 4))",
            "initial_bottom_layers" to "=bottom_layers",
            "wall_line_width_0" to "=line_width",
            "wall_line_width_x" to "=line_width",
            "wall_line_count" to "=1 if magic_spiralize else max(1, round((wall_thickness - wall_line_width_0) / wall_line_width_x) + 1) if wall_thickness != 0 else 0",
            "wall_thickness" to "=line_width*2",
            // These mirror the flattened support expressions stored in a Cura
            // 3MF definition. The bundled source definitions keep the same
            // values under `overrides`, so they are not part of this synthetic
            // imported-project fixture unless explicitly supplied here.
            "support_infill_rate" to "=0 if support_enable and support_structure == 'tree' else 20",
            "support_interface_enable" to "=True",
            "support_roof_enable" to "=support_interface_enable",
            "support_bottom_enable" to "=support_interface_enable",
            "support_interface_density" to "=33.333",
            "support_z_distance" to "=layer_height if layer_height >= 0.16 else layer_height * 2",
            "support_xy_distance" to "=wall_line_width_0 * 2",
            "support_angle" to "56",
        ),
        definitionFiles = loadDefinitions(),
        machineDefinitionFileName = "creality_ender3.def.json",
        extruderDefinitionFileName = "creality_base_extruder_0.def.json",
    )

    private fun assertNumeric(values: Map<String, String>, key: String, expected: Double) {
        val raw = values[key] ?: error("Missing resolved setting: $key")
        val actual = raw.toDoubleOrNull() ?: error("Resolved setting is not numeric: $key=$raw")
        assertEquals("Unexpected resolved value for $key", expected, actual, 1e-7)
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
