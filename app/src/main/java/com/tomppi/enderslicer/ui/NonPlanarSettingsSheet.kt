package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.nonplanar.NonPlanarSettings
import com.tomppi.enderslicer.nonplanar.NonPlanarSettingsStore

@Composable
internal fun NonPlanarSettingsSheet(
    initial: NonPlanarSettings,
    layerHeightMm: Double,
    nozzleDiameterMm: Double,
    onSave: (NonPlanarSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var enabled by rememberSaveable(initial.enabled) { mutableStateOf(initial.enabled) }
    var strength by rememberSaveable(initial.strengthPercent) { mutableStateOf(initial.strengthPercent.toString()) }
    var smoothing by rememberSaveable(initial.smoothingRadiusMm) { mutableStateOf(initial.smoothingRadiusMm.toString()) }
    var slope by rememberSaveable(initial.maximumSlopeDegrees) { mutableStateOf(initial.maximumSlopeDegrees.toString()) }
    var clearanceAngle by rememberSaveable(initial.nozzleClearanceAngleDegrees) {
        mutableStateOf(initial.nozzleClearanceAngleDegrees.toString())
    }
    var clearanceHeight by rememberSaveable(initial.nozzleClearanceHeightMm) {
        mutableStateOf(initial.nozzleClearanceHeightMm.toString())
    }
    var flatBaseLayers by rememberSaveable(initial.flatBaseLayers) { mutableStateOf(initial.flatBaseLayers.toString()) }
    var fieldResolution by rememberSaveable(initial.fieldResolution) { mutableStateOf(initial.fieldResolution.toString()) }
    var segmentLength by rememberSaveable(initial.maximumSegmentLengthMm) {
        mutableStateOf(initial.maximumSegmentLengthMm.toString())
    }
    var maximumZSpeed by rememberSaveable(initial.maximumZSpeedMmPerSecond) {
        mutableStateOf(initial.maximumZSpeedMmPerSecond.toString())
    }
    var compensateExtrusion by rememberSaveable(initial.compensateExtrusion) {
        mutableStateOf(initial.compensateExtrusion)
    }
    var warpSmartInfill by rememberSaveable(initial.warpSmartInfillModifiers) {
        mutableStateOf(initial.warpSmartInfillModifiers)
    }
    var pauseAfterProbe by rememberSaveable(initial.pauseAfterProbe) { mutableStateOf(initial.pauseAfterProbe) }

    val parsedStrength = parseDecimal(strength, NonPlanarSettings.MIN_STRENGTH_PERCENT, NonPlanarSettings.MAX_STRENGTH_PERCENT)
    val parsedSmoothing = parseDecimal(
        smoothing,
        NonPlanarSettings.MIN_SMOOTHING_RADIUS_MM,
        NonPlanarSettings.MAX_SMOOTHING_RADIUS_MM,
    )
    val parsedSlope = parseDecimal(slope, NonPlanarSettings.MIN_SLOPE_DEGREES, NonPlanarSettings.MAX_SLOPE_DEGREES)
    val parsedClearanceAngle = parseDecimal(
        clearanceAngle,
        NonPlanarSettings.MIN_CLEARANCE_ANGLE_DEGREES,
        NonPlanarSettings.MAX_CLEARANCE_ANGLE_DEGREES,
    )
    val parsedClearanceHeight = parseDecimal(
        clearanceHeight,
        NonPlanarSettings.MIN_CLEARANCE_HEIGHT_MM,
        NonPlanarSettings.MAX_CLEARANCE_HEIGHT_MM,
    )
    val parsedFlatBaseLayers = parseInteger(
        flatBaseLayers,
        NonPlanarSettings.MIN_FLAT_BASE_LAYERS,
        NonPlanarSettings.MAX_FLAT_BASE_LAYERS,
    )
    val parsedFieldResolution = parseInteger(
        fieldResolution,
        NonPlanarSettings.MIN_FIELD_RESOLUTION,
        NonPlanarSettings.MAX_FIELD_RESOLUTION,
    )
    val parsedSegmentLength = parseDecimal(
        segmentLength,
        NonPlanarSettings.MIN_SEGMENT_LENGTH_MM,
        NonPlanarSettings.MAX_SEGMENT_LENGTH_MM,
    )
    val parsedMaximumZSpeed = parseDecimal(
        maximumZSpeed,
        NonPlanarSettings.MIN_Z_SPEED_MM_PER_SECOND,
        NonPlanarSettings.MAX_Z_SPEED_MM_PER_SECOND,
    )
    val draft = if (
        parsedStrength != null && parsedSmoothing != null && parsedSlope != null &&
        parsedClearanceAngle != null && parsedClearanceHeight != null && parsedFlatBaseLayers != null &&
        parsedFieldResolution != null && parsedSegmentLength != null && parsedMaximumZSpeed != null
    ) {
        NonPlanarSettings(
            enabled = enabled,
            strengthPercent = parsedStrength,
            smoothingRadiusMm = parsedSmoothing,
            maximumSlopeDegrees = parsedSlope,
            nozzleClearanceAngleDegrees = parsedClearanceAngle,
            nozzleClearanceHeightMm = parsedClearanceHeight,
            flatBaseLayers = parsedFlatBaseLayers,
            fieldResolution = parsedFieldResolution,
            maximumSegmentLengthMm = parsedSegmentLength,
            maximumZSpeedMmPerSecond = parsedMaximumZSpeed,
            compensateExtrusion = compensateExtrusion,
            warpSmartInfillModifiers = warpSmartInfill,
            pauseAfterProbe = pauseAfterProbe,
        )
    } else {
        null
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Non Planar", style = MaterialTheme.typography.headlineSmall)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(NonPlanarSettingsStore.BACKEND_NAME, style = MaterialTheme.typography.titleMedium)
                Text("Ready · fully offline · Android ARM64", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "EnderSlicer flattens the displayed STL with a monotone, slope-limited CurviSlicer field, slices the flattened solid with CuraEngine, then restores continuously varying Z paths. Extrusion and Z speed are compensated before validation.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Active Cura layer height: %.3f mm · nozzle: %.2f mm".format(layerHeightMm, nozzleDiameterMm),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        SettingSwitch(
            title = "Enable CurviSlicer",
            description = "Generate curved layers for the next slice",
            checked = enabled,
            onChecked = { enabled = it },
        )
        DecimalSettingField(
            "Curvature strength (%)",
            strength,
            NonPlanarSettings.MIN_STRENGTH_PERCENT,
            NonPlanarSettings.MAX_STRENGTH_PERCENT,
            onText = { strength = it },
        )
        DecimalSettingField(
            "Surface smoothing radius (mm)",
            smoothing,
            NonPlanarSettings.MIN_SMOOTHING_RADIUS_MM,
            NonPlanarSettings.MAX_SMOOTHING_RADIUS_MM,
            onText = { smoothing = it },
        )
        DecimalSettingField(
            "Maximum path slope (degrees)",
            slope,
            NonPlanarSettings.MIN_SLOPE_DEGREES,
            NonPlanarSettings.MAX_SLOPE_DEGREES,
            onText = { slope = it },
        )
        DecimalSettingField(
            "Nozzle clearance angle (degrees)",
            clearanceAngle,
            NonPlanarSettings.MIN_CLEARANCE_ANGLE_DEGREES,
            NonPlanarSettings.MAX_CLEARANCE_ANGLE_DEGREES,
            onText = { clearanceAngle = it },
        )
        DecimalSettingField(
            "Nozzle clearance height (mm)",
            clearanceHeight,
            NonPlanarSettings.MIN_CLEARANCE_HEIGHT_MM,
            NonPlanarSettings.MAX_CLEARANCE_HEIGHT_MM,
            onText = { clearanceHeight = it },
        )
        IntegerSettingField(
            "Flat base layers",
            flatBaseLayers,
            NonPlanarSettings.MIN_FLAT_BASE_LAYERS,
            NonPlanarSettings.MAX_FLAT_BASE_LAYERS,
            onText = { flatBaseLayers = it },
        )
        IntegerSettingField(
            "Curvature field resolution",
            fieldResolution,
            NonPlanarSettings.MIN_FIELD_RESOLUTION,
            NonPlanarSettings.MAX_FIELD_RESOLUTION,
            onText = { fieldResolution = it },
        )
        DecimalSettingField(
            "Maximum generated move length (mm)",
            segmentLength,
            NonPlanarSettings.MIN_SEGMENT_LENGTH_MM,
            NonPlanarSettings.MAX_SEGMENT_LENGTH_MM,
            onText = { segmentLength = it },
        )
        DecimalSettingField(
            "Maximum Z speed (mm/s)",
            maximumZSpeed,
            NonPlanarSettings.MIN_Z_SPEED_MM_PER_SECOND,
            NonPlanarSettings.MAX_Z_SPEED_MM_PER_SECOND,
            onText = { maximumZSpeed = it },
        )
        SettingSwitch(
            title = "Extrusion length compensation",
            description = "Scale positive extrusion for the true curved 3D path length",
            checked = compensateExtrusion,
            onChecked = { compensateExtrusion = it },
        )
        SettingSwitch(
            title = "Warp Smart Infill modifiers",
            description = "Keep filaSim density regions aligned with the flattened model",
            checked = warpSmartInfill,
            onChecked = { warpSmartInfill = it },
        )
        SettingSwitch(
            title = "Pause after bed probing",
            description = "Insert M117 + M0 after the last G29 so you can raise a deployable probe before printing",
            checked = pauseAfterProbe,
            onChecked = { pauseAfterProbe = it },
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Printer safety", style = MaterialTheme.typography.titleSmall)
                Text(
                    "The applied strength is automatically reduced against the inverse path slope. Final G-code is rejected if any move leaves the configured machine envelope, exceeds the clearance slope, or uses unsupported arcs.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Preview the complete Path view before printing. Physical fan ducts, probes and heater blocks can require a lower clearance angle than the bare nozzle.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Button(
            onClick = { onSave(requireNotNull(draft)) },
            enabled = draft != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save non-planar options")
        }
    }
}

