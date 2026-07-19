package com.tomppi.enderslicer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.model.SlicerSettings

@Composable
internal fun CategorizedSettingsSheet(
    state: MainUiState,
    onSettings: (String, (SlicerSettings) -> SlicerSettings) -> Unit,
    onResetOverrides: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Print settings", style = MaterialTheme.typography.headlineSmall)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.profileName, style = MaterialTheme.typography.titleMedium)
                Text(state.profileSource, style = MaterialTheme.typography.bodySmall)
                if (state.importedRawSettingCount > 0) {
                    Text(
                        "${state.importedRawSettingCount} imported values · ${settings.overriddenSettingKeys.size} app overrides",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.warnings.forEach { warning ->
                    Text("Warning: $warning", color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(
                    onClick = onResetOverrides,
                    enabled = settings.overriddenSettingKeys.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Reset all app overrides")
                }
            }
        }

        SettingsCategory("Quality", initiallyExpanded = true) {
            NumberField("Layer height (mm)", settings.layerHeightMm, source(state, SlicerSettings.Keys.LAYER_HEIGHT)) {
                onSettings(SlicerSettings.Keys.LAYER_HEIGHT) { current -> current.copy(layerHeightMm = it.coerceIn(0.01, 5.0)) }
            }
            NumberField("Initial layer height (mm)", settings.initialLayerHeightMm, source(state, SlicerSettings.Keys.INITIAL_LAYER_HEIGHT)) {
                onSettings(SlicerSettings.Keys.INITIAL_LAYER_HEIGHT) { current -> current.copy(initialLayerHeightMm = it.coerceIn(0.01, 5.0)) }
            }
            NumberField("Line width (mm)", settings.lineWidthMm, source(state, SlicerSettings.Keys.LINE_WIDTH)) {
                onSettings(SlicerSettings.Keys.LINE_WIDTH) { current -> current.copy(lineWidthMm = it.coerceIn(0.01, 5.0)) }
            }
            OptionField(
                label = "Slicing tolerance",
                value = settings.slicingTolerance,
                options = listOf(
                    "middle" to "Middle · closest to surface",
                    "exclusive" to "Exclusive · best dimensional fit",
                    "inclusive" to "Inclusive · retain small details",
                ),
                source = source(state, SlicerSettings.Keys.SLICING_TOLERANCE),
            ) {
                onSettings(SlicerSettings.Keys.SLICING_TOLERANCE) { current -> current.copy(slicingTolerance = it) }
            }
        }

        SettingsCategory("Walls and top/bottom") {
            NumberField("Wall line count", settings.wallLineCount.toDouble(), source(state, SlicerSettings.Keys.WALL_LINE_COUNT), decimals = 0) {
                onSettings(SlicerSettings.Keys.WALL_LINE_COUNT) { current -> current.copy(wallLineCount = it.toInt().coerceIn(0, 1000)) }
            }
            NumberField("Top layers", settings.topLayers.toDouble(), source(state, SlicerSettings.Keys.TOP_LAYERS), decimals = 0) {
                onSettings(SlicerSettings.Keys.TOP_LAYERS) { current -> current.copy(topLayers = it.toInt().coerceIn(0, 1000000)) }
            }
            NumberField("Bottom layers", settings.bottomLayers.toDouble(), source(state, SlicerSettings.Keys.BOTTOM_LAYERS), decimals = 0) {
                onSettings(SlicerSettings.Keys.BOTTOM_LAYERS) { current -> current.copy(bottomLayers = it.toInt().coerceIn(0, 1000000)) }
            }
            OptionField(
                label = "Z seam alignment",
                value = settings.zSeamType,
                options = listOf(
                    "back" to "User specified",
                    "shortest" to "Shortest path",
                    "random" to "Random",
                    "sharpest_corner" to "Sharpest corner",
                ),
                source = source(state, SlicerSettings.Keys.Z_SEAM_TYPE),
            ) {
                onSettings(SlicerSettings.Keys.Z_SEAM_TYPE) { current -> current.copy(zSeamType = it) }
            }
            if (settings.zSeamType == "back") {
                NumberField("Z seam X (mm)", settings.zSeamXmm, source(state, SlicerSettings.Keys.Z_SEAM_X)) {
                    onSettings(SlicerSettings.Keys.Z_SEAM_X) { current -> current.copy(zSeamXmm = it.coerceIn(-2000.0, 2000.0)) }
                }
                NumberField("Z seam Y (mm)", settings.zSeamYmm, source(state, SlicerSettings.Keys.Z_SEAM_Y)) {
                    onSettings(SlicerSettings.Keys.Z_SEAM_Y) { current -> current.copy(zSeamYmm = it.coerceIn(-2000.0, 2000.0)) }
                }
                SwitchRow("Coordinates relative to each model", settings.zSeamRelative, source(state, SlicerSettings.Keys.Z_SEAM_RELATIVE)) {
                    onSettings(SlicerSettings.Keys.Z_SEAM_RELATIVE) { current -> current.copy(zSeamRelative = it) }
                }
            }
            if (settings.zSeamType != "random") {
                OptionField(
                    label = "Seam corner preference",
                    value = settings.zSeamCorner,
                    options = listOf(
                        "z_seam_corner_none" to "None",
                        "z_seam_corner_inner" to "Hide seam",
                        "z_seam_corner_outer" to "Expose seam",
                        "z_seam_corner_any" to "Hide or expose",
                        "z_seam_corner_weighted" to "Smart hiding",
                    ),
                    source = source(state, SlicerSettings.Keys.Z_SEAM_CORNER),
                ) {
                    onSettings(SlicerSettings.Keys.Z_SEAM_CORNER) { current -> current.copy(zSeamCorner = it) }
                }
            }
        }

        SettingsCategory("Infill") {
            NumberField("Infill density (%)", settings.infillDensityPercent, source(state, SlicerSettings.Keys.INFILL_DENSITY)) {
                onSettings(SlicerSettings.Keys.INFILL_DENSITY) { current -> current.copy(infillDensityPercent = it.coerceIn(0.0, 100.0)) }
            }
            OptionField(
                label = "Infill pattern",
                value = settings.infillPattern,
                options = listOf(
                    "grid" to "Grid",
                    "lines" to "Lines",
                    "triangles" to "Triangles",
                    "trihexagon" to "Tri-hexagon",
                    "cubic" to "Cubic",
                    "cubicsubdiv" to "Cubic subdivision",
                    "octet" to "Octet",
                    "quarter_cubic" to "Quarter cubic",
                    "concentric" to "Concentric",
                    "zigzag" to "Zig zag",
                    "cross" to "Cross",
                    "cross_3d" to "Cross 3D",
                    "gyroid" to "Gyroid",
                    "lightning" to "Lightning",
                ),
                source = source(state, SlicerSettings.Keys.INFILL_PATTERN),
            ) {
                onSettings(SlicerSettings.Keys.INFILL_PATTERN) { current -> current.copy(infillPattern = it) }
            }
        }

        SettingsCategory("Speed") {
            NumberField("Print speed (mm/s)", settings.printSpeedMmPerSecond, source(state, SlicerSettings.Keys.PRINT_SPEED)) {
                onSettings(SlicerSettings.Keys.PRINT_SPEED) { current -> current.copy(printSpeedMmPerSecond = it.coerceIn(0.1, 1000.0)) }
            }
            NumberField("Wall speed (mm/s)", settings.wallSpeedMmPerSecond, source(state, SlicerSettings.Keys.WALL_SPEED)) {
                onSettings(SlicerSettings.Keys.WALL_SPEED) { current -> current.copy(wallSpeedMmPerSecond = it.coerceIn(0.1, 1000.0)) }
            }
            NumberField("Outer wall speed (mm/s)", settings.outerWallSpeedMmPerSecond, source(state, SlicerSettings.Keys.OUTER_WALL_SPEED)) {
                onSettings(SlicerSettings.Keys.OUTER_WALL_SPEED) { current -> current.copy(outerWallSpeedMmPerSecond = it.coerceIn(0.1, 1000.0)) }
            }
            NumberField("Inner wall speed (mm/s)", settings.innerWallSpeedMmPerSecond, source(state, SlicerSettings.Keys.INNER_WALL_SPEED)) {
                onSettings(SlicerSettings.Keys.INNER_WALL_SPEED) { current -> current.copy(innerWallSpeedMmPerSecond = it.coerceIn(0.1, 1000.0)) }
            }
            NumberField("Infill speed (mm/s)", settings.infillSpeedMmPerSecond, source(state, SlicerSettings.Keys.INFILL_SPEED)) {
                onSettings(SlicerSettings.Keys.INFILL_SPEED) { current -> current.copy(infillSpeedMmPerSecond = it.coerceIn(0.1, 1000.0)) }
            }
            NumberField("Top/bottom speed (mm/s)", settings.topBottomSpeedMmPerSecond, source(state, SlicerSettings.Keys.TOP_BOTTOM_SPEED)) {
                onSettings(SlicerSettings.Keys.TOP_BOTTOM_SPEED) { current -> current.copy(topBottomSpeedMmPerSecond = it.coerceIn(0.1, 1000.0)) }
            }
            NumberField("Travel speed (mm/s)", settings.travelSpeedMmPerSecond, source(state, SlicerSettings.Keys.TRAVEL_SPEED)) {
                onSettings(SlicerSettings.Keys.TRAVEL_SPEED) { current -> current.copy(travelSpeedMmPerSecond = it.coerceIn(0.1, 1000.0)) }
            }
            NumberField("Initial layer speed (mm/s)", settings.initialLayerSpeedMmPerSecond, source(state, SlicerSettings.Keys.INITIAL_LAYER_SPEED)) {
                onSettings(SlicerSettings.Keys.INITIAL_LAYER_SPEED) { current -> current.copy(initialLayerSpeedMmPerSecond = it.coerceIn(0.1, 1000.0)) }
            }
        }

        SettingsCategory("Material") {
            NumberField("Nozzle temperature (°C)", settings.nozzleTemperatureC.toDouble(), source(state, SlicerSettings.Keys.NOZZLE_TEMPERATURE), decimals = 0) {
                onSettings(SlicerSettings.Keys.NOZZLE_TEMPERATURE) { current -> current.copy(nozzleTemperatureC = it.toInt().coerceIn(150, 500)) }
            }
            NumberField("Initial nozzle temperature (°C)", settings.initialNozzleTemperatureC.toDouble(), source(state, SlicerSettings.Keys.INITIAL_NOZZLE_TEMPERATURE), decimals = 0) {
                onSettings(SlicerSettings.Keys.INITIAL_NOZZLE_TEMPERATURE) { current -> current.copy(initialNozzleTemperatureC = it.toInt().coerceIn(150, 500)) }
            }
            NumberField("Bed temperature (°C)", settings.bedTemperatureC.toDouble(), source(state, SlicerSettings.Keys.BED_TEMPERATURE), decimals = 0) {
                onSettings(SlicerSettings.Keys.BED_TEMPERATURE) { current -> current.copy(bedTemperatureC = it.toInt().coerceIn(0, 200)) }
            }
            NumberField("Material flow (%)", settings.materialFlowPercent, source(state, SlicerSettings.Keys.MATERIAL_FLOW)) {
                onSettings(SlicerSettings.Keys.MATERIAL_FLOW) { current -> current.copy(materialFlowPercent = it.coerceIn(1.0, 300.0)) }
            }
        }

        SettingsCategory("Cooling") {
            NumberField("Regular fan speed (%)", settings.fanSpeedPercent, source(state, SlicerSettings.Keys.FAN_SPEED)) {
                onSettings(SlicerSettings.Keys.FAN_SPEED) { current -> current.copy(fanSpeedPercent = it.coerceIn(0.0, 100.0)) }
            }
            NumberField("Initial fan speed (%)", settings.initialFanSpeedPercent, source(state, SlicerSettings.Keys.INITIAL_FAN_SPEED)) {
                onSettings(SlicerSettings.Keys.INITIAL_FAN_SPEED) { current -> current.copy(initialFanSpeedPercent = it.coerceIn(0.0, 100.0)) }
            }
            NumberField("Regular fan at layer", settings.fanFullAtLayer.toDouble(), source(state, SlicerSettings.Keys.FAN_FULL_AT_LAYER), decimals = 0) {
                onSettings(SlicerSettings.Keys.FAN_FULL_AT_LAYER) { current -> current.copy(fanFullAtLayer = it.toInt().coerceIn(0, 1000000)) }
            }
        }

        SettingsCategory("Supports") {
            SwitchRow("Generate supports", settings.supportsEnabled, source(state, SlicerSettings.Keys.SUPPORTS_ENABLED)) {
                onSettings(SlicerSettings.Keys.SUPPORTS_ENABLED) { current -> current.copy(supportsEnabled = it) }
            }
            if (settings.supportsEnabled) {
                OptionField(
                    label = "Structure",
                    value = settings.supportStructure,
                    options = listOf("tree" to "Tree", "normal" to "Normal"),
                    source = source(state, SlicerSettings.Keys.SUPPORT_STRUCTURE),
                ) {
                    onSettings(SlicerSettings.Keys.SUPPORT_STRUCTURE) { current -> current.copy(supportStructure = it) }
                }
                OptionField(
                    label = "Placement",
                    value = settings.supportPlacement,
                    options = listOf("everywhere" to "Everywhere", "buildplate" to "Build plate only"),
                    source = source(state, SlicerSettings.Keys.SUPPORT_PLACEMENT),
                ) {
                    onSettings(SlicerSettings.Keys.SUPPORT_PLACEMENT) { current -> current.copy(supportPlacement = it) }
                }
                NumberField("Overhang angle (°)", settings.supportAngleDegrees, source(state, SlicerSettings.Keys.SUPPORT_ANGLE)) {
                    onSettings(SlicerSettings.Keys.SUPPORT_ANGLE) { current -> current.copy(supportAngleDegrees = it.coerceIn(0.0, 90.0)) }
                }
                NumberField("Support density (%)", settings.supportDensityPercent, source(state, SlicerSettings.Keys.SUPPORT_DENSITY)) {
                    onSettings(SlicerSettings.Keys.SUPPORT_DENSITY) { current -> current.copy(supportDensityPercent = it.coerceIn(0.0, 100.0)) }
                }
                OptionField(
                    label = "Support pattern",
                    value = settings.supportPattern,
                    options = listOf(
                        "zigzag" to "Zig zag",
                        "lines" to "Lines",
                        "grid" to "Grid",
                        "triangles" to "Triangles",
                        "concentric" to "Concentric",
                        "cross" to "Cross",
                        "gyroid" to "Gyroid",
                    ),
                    source = source(state, SlicerSettings.Keys.SUPPORT_PATTERN),
                ) {
                    onSettings(SlicerSettings.Keys.SUPPORT_PATTERN) { current -> current.copy(supportPattern = it) }
                }
                SwitchRow("Support interface", settings.supportInterfaceEnabled, source(state, SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED)) {
                    onSettings(SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED) { current -> current.copy(supportInterfaceEnabled = it) }
                }
                if (settings.supportInterfaceEnabled) {
                    NumberField("Interface density (%)", settings.supportInterfaceDensityPercent, source(state, SlicerSettings.Keys.SUPPORT_INTERFACE_DENSITY)) {
                        onSettings(SlicerSettings.Keys.SUPPORT_INTERFACE_DENSITY) { current -> current.copy(supportInterfaceDensityPercent = it.coerceIn(0.0, 100.0)) }
                    }
                    NumberField("Interface speed (mm/s)", settings.supportInterfaceSpeedMmPerSecond, source(state, SlicerSettings.Keys.SUPPORT_INTERFACE_SPEED)) {
                        onSettings(SlicerSettings.Keys.SUPPORT_INTERFACE_SPEED) { current -> current.copy(supportInterfaceSpeedMmPerSecond = it.coerceIn(0.1, 1000.0)) }
                    }
                }
                NumberField("Z distance (mm)", settings.supportZDistanceMm, source(state, SlicerSettings.Keys.SUPPORT_Z_DISTANCE)) {
                    onSettings(SlicerSettings.Keys.SUPPORT_Z_DISTANCE) { current -> current.copy(supportZDistanceMm = it.coerceIn(0.0, 20.0)) }
                }
                NumberField("XY distance (mm)", settings.supportXyDistanceMm, source(state, SlicerSettings.Keys.SUPPORT_XY_DISTANCE)) {
                    onSettings(SlicerSettings.Keys.SUPPORT_XY_DISTANCE) { current -> current.copy(supportXyDistanceMm = it.coerceIn(0.0, 20.0)) }
                }
                NumberField("Support speed (mm/s)", settings.supportSpeedMmPerSecond, source(state, SlicerSettings.Keys.SUPPORT_SPEED)) {
                    onSettings(SlicerSettings.Keys.SUPPORT_SPEED) { current -> current.copy(supportSpeedMmPerSecond = it.coerceIn(0.1, 1000.0)) }
                }
            }
        }

        SettingsCategory("Travel and retraction") {
            NumberField("Retraction distance (mm)", settings.retractionDistanceMm, source(state, SlicerSettings.Keys.RETRACTION_DISTANCE)) {
                onSettings(SlicerSettings.Keys.RETRACTION_DISTANCE) { current -> current.copy(retractionDistanceMm = it.coerceIn(0.0, 100.0)) }
            }
            NumberField("Retraction speed (mm/s)", settings.retractionSpeedMmPerSecond, source(state, SlicerSettings.Keys.RETRACTION_SPEED)) {
                onSettings(SlicerSettings.Keys.RETRACTION_SPEED) { current -> current.copy(retractionSpeedMmPerSecond = it.coerceIn(0.0, 1000.0)) }
            }
            NumberField("Minimum retraction travel (mm)", settings.retractionMinimumTravelMm, source(state, SlicerSettings.Keys.RETRACTION_MINIMUM_TRAVEL)) {
                onSettings(SlicerSettings.Keys.RETRACTION_MINIMUM_TRAVEL) { current -> current.copy(retractionMinimumTravelMm = it.coerceIn(0.0, 1000.0)) }
            }
            SwitchRow("Retract at layer change", settings.retractAtLayerChange, source(state, SlicerSettings.Keys.RETRACT_AT_LAYER_CHANGE)) {
                onSettings(SlicerSettings.Keys.RETRACT_AT_LAYER_CHANGE) { current -> current.copy(retractAtLayerChange = it) }
            }
            OptionField(
                label = "Combing mode",
                value = settings.combingMode,
                options = listOf(
                    "off" to "Off",
                    "all" to "All",
                    "noskin" to "Not in skin",
                    "infill" to "Within infill",
                ),
                source = source(state, SlicerSettings.Keys.COMBING_MODE),
            ) {
                onSettings(SlicerSettings.Keys.COMBING_MODE) { current -> current.copy(combingMode = it) }
            }
            SwitchRow("Avoid printed parts when travelling", settings.avoidPrintedParts, source(state, SlicerSettings.Keys.AVOID_PRINTED_PARTS)) {
                onSettings(SlicerSettings.Keys.AVOID_PRINTED_PARTS) { current -> current.copy(avoidPrintedParts = it) }
            }
            if (settings.avoidPrintedParts) {
                NumberField("Travel avoid distance (mm)", settings.travelAvoidDistanceMm, source(state, SlicerSettings.Keys.TRAVEL_AVOID_DISTANCE)) {
                    onSettings(SlicerSettings.Keys.TRAVEL_AVOID_DISTANCE) { current -> current.copy(travelAvoidDistanceMm = it.coerceIn(0.0, 100.0)) }
                }
            }
            SwitchRow("Z hop", settings.zHopEnabled, source(state, SlicerSettings.Keys.Z_HOP)) {
                onSettings(SlicerSettings.Keys.Z_HOP) { current -> current.copy(zHopEnabled = it) }
            }
            if (settings.zHopEnabled) {
                NumberField("Z hop height (mm)", settings.zHopHeightMm, source(state, SlicerSettings.Keys.Z_HOP_HEIGHT)) {
                    onSettings(SlicerSettings.Keys.Z_HOP_HEIGHT) { current -> current.copy(zHopHeightMm = it.coerceIn(0.0, 100.0)) }
                }
            }
            SwitchRow("Firmware retraction", settings.firmwareRetraction, source(state, SlicerSettings.Keys.FIRMWARE_RETRACTION)) {
                onSettings(SlicerSettings.Keys.FIRMWARE_RETRACTION) { current -> current.copy(firmwareRetraction = it) }
            }
            SwitchRow("Enable coasting", settings.coastingEnabled, source(state, SlicerSettings.Keys.COASTING_ENABLED)) {
                onSettings(SlicerSettings.Keys.COASTING_ENABLED) { current -> current.copy(coastingEnabled = it) }
            }
            if (settings.coastingEnabled) {
                NumberField("Coasting volume (mm³)", settings.coastingVolumeMm3, source(state, SlicerSettings.Keys.COASTING_VOLUME), decimals = 3) {
                    onSettings(SlicerSettings.Keys.COASTING_VOLUME) { current -> current.copy(coastingVolumeMm3 = it.coerceIn(0.0, 1000.0)) }
                }
                NumberField("Minimum volume before coasting (mm³)", settings.coastingMinimumVolumeMm3, source(state, SlicerSettings.Keys.COASTING_MINIMUM_VOLUME), decimals = 3) {
                    onSettings(SlicerSettings.Keys.COASTING_MINIMUM_VOLUME) { current -> current.copy(coastingMinimumVolumeMm3 = it.coerceIn(0.0, 100000.0)) }
                }
                NumberField("Coasting speed (%)", settings.coastingSpeedPercent, source(state, SlicerSettings.Keys.COASTING_SPEED)) {
                    onSettings(SlicerSettings.Keys.COASTING_SPEED) { current -> current.copy(coastingSpeedPercent = it.coerceIn(0.0001, 1000.0)) }
                }
            }
        }

        SettingsCategory("Build plate adhesion") {
            OptionField(
                label = "Adhesion type",
                value = settings.adhesionType,
                options = listOf("none" to "None", "skirt" to "Skirt", "brim" to "Brim", "raft" to "Raft"),
                source = source(state, SlicerSettings.Keys.ADHESION_TYPE),
            ) {
                onSettings(SlicerSettings.Keys.ADHESION_TYPE) { current -> current.copy(adhesionType = it) }
            }
            if (settings.adhesionType == "skirt") {
                NumberField("Skirt line count", settings.skirtLineCount.toDouble(), source(state, SlicerSettings.Keys.SKIRT_LINE_COUNT), decimals = 0) {
                    onSettings(SlicerSettings.Keys.SKIRT_LINE_COUNT) { current -> current.copy(skirtLineCount = it.toInt().coerceIn(0, 1000)) }
                }
            }
            if (settings.adhesionType == "brim") {
                NumberField("Brim width (mm)", settings.brimWidthMm, source(state, SlicerSettings.Keys.BRIM_WIDTH)) {
                    onSettings(SlicerSettings.Keys.BRIM_WIDTH) { current -> current.copy(brimWidthMm = it.coerceIn(0.0, 100.0)) }
                }
            }
        }

        SettingsCategory("Experimental") {
            SwitchRow("Ironing", settings.ironingEnabled, source(state, SlicerSettings.Keys.IRONING_ENABLED)) {
                onSettings(SlicerSettings.Keys.IRONING_ENABLED) { current -> current.copy(ironingEnabled = it) }
            }
            if (settings.ironingEnabled) {
                NumberField("Ironing flow (%)", settings.ironingFlowPercent, source(state, SlicerSettings.Keys.IRONING_FLOW)) {
                    onSettings(SlicerSettings.Keys.IRONING_FLOW) { current -> current.copy(ironingFlowPercent = it.coerceIn(0.0, 100.0)) }
                }
                NumberField("Ironing speed (mm/s)", settings.ironingSpeedMmPerSecond, source(state, SlicerSettings.Keys.IRONING_SPEED)) {
                    onSettings(SlicerSettings.Keys.IRONING_SPEED) { current -> current.copy(ironingSpeedMmPerSecond = it.coerceIn(0.1, 1000.0)) }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsCategory(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable Column.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(if (expanded) "Hide" else "Show", style = MaterialTheme.typography.labelLarge)
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Double,
    source: String,
    decimals: Int = 2,
    onValue: (Double) -> Unit,
) {
    var text by remember(value) {
        mutableStateOf(if (decimals == 0) value.toInt().toString() else value.toString().trimEnd('0').trimEnd('.'))
    }
    Column {
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input
                input.replace(',', '.').toDoubleOrNull()?.let(onValue)
            },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SettingSource(source)
    }
}

@Composable
private fun OptionField(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    source: String,
    onValue: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val display = options.firstOrNull { it.first == value }?.second ?: value
    Column {
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text("$label: $display")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (optionValue, optionLabel) ->
                    DropdownMenuItem(
                        text = { Text(optionLabel) },
                        onClick = {
                            expanded = false
                            onValue(optionValue)
                        },
                    )
                }
            }
        }
        SettingSource(source)
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    source: String,
    onChecked: (Boolean) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChecked)
        }
        SettingSource(source)
    }
}

@Composable
private fun SettingSource(source: String) {
    Text(source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun source(state: MainUiState, key: String): String = when {
    state.settings.isOverridden(key) -> "App override"
    state.engineProfile != null -> "Imported Cura value"
    else -> "Built-in default"
}
