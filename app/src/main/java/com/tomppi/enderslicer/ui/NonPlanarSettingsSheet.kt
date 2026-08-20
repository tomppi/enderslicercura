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
import androidx.compose.material3.OutlinedButton
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
    var maximumLift by rememberSaveable(initial.maximumLiftMm) { mutableStateOf(initial.maximumLiftMm.toString()) }
    var nozzleAngle by rememberSaveable(initial.nozzleAngleDegrees) { mutableStateOf(initial.nozzleAngleDegrees.toString()) }
    var nozzleProtrusion by rememberSaveable(initial.nozzleProtrusionMm) {
        mutableStateOf(initial.nozzleProtrusionMm.toString())
    }
    var blockWidth by rememberSaveable(initial.heatingBlockWidthMm) {
        mutableStateOf(initial.heatingBlockWidthMm.toString())
    }
    var blockDepth by rememberSaveable(initial.heatingBlockDepthMm) {
        mutableStateOf(initial.heatingBlockDepthMm.toString())
    }
    var blockOffsetX by rememberSaveable(initial.heatingBlockOffsetXmm) {
        mutableStateOf(initial.heatingBlockOffsetXmm.toString())
    }
    var blockOffsetY by rememberSaveable(initial.heatingBlockOffsetYmm) {
        mutableStateOf(initial.heatingBlockOffsetYmm.toString())
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
    var drapeMode by rememberSaveable(initial.drapeMode) { mutableStateOf(initial.drapeMode) }
    var fadeStart by rememberSaveable(initial.fadeStartPercent) { mutableStateOf(initial.fadeStartPercent.toString()) }

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
    val parsedMaximumLift = parseDecimal(
        maximumLift,
        NonPlanarSettings.MIN_MAXIMUM_LIFT_MM,
        NonPlanarSettings.MAX_MAXIMUM_LIFT_MM,
    )
    val parsedNozzleAngle = parseDecimal(
        nozzleAngle,
        NonPlanarSettings.MIN_NOZZLE_ANGLE_DEGREES,
        NonPlanarSettings.MAX_NOZZLE_ANGLE_DEGREES,
    )
    val parsedNozzleProtrusion = parseDecimal(
        nozzleProtrusion,
        NonPlanarSettings.MIN_NOZZLE_PROTRUSION_MM,
        NonPlanarSettings.MAX_NOZZLE_PROTRUSION_MM,
    )
    val parsedBlockWidth = parseDecimal(
        blockWidth,
        NonPlanarSettings.MIN_BLOCK_SIZE_MM,
        NonPlanarSettings.MAX_BLOCK_SIZE_MM,
    )
    val parsedBlockDepth = parseDecimal(
        blockDepth,
        NonPlanarSettings.MIN_BLOCK_SIZE_MM,
        NonPlanarSettings.MAX_BLOCK_SIZE_MM,
    )
    val parsedBlockOffsetX = parseDecimal(
        blockOffsetX,
        -NonPlanarSettings.MAX_BLOCK_OFFSET_MM,
        NonPlanarSettings.MAX_BLOCK_OFFSET_MM,
    )
    val parsedBlockOffsetY = parseDecimal(
        blockOffsetY,
        -NonPlanarSettings.MAX_BLOCK_OFFSET_MM,
        NonPlanarSettings.MAX_BLOCK_OFFSET_MM,
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
    val parsedFadeStart = parseDecimal(
        fadeStart,
        NonPlanarSettings.MIN_FADE_START_PERCENT,
        NonPlanarSettings.MAX_FADE_START_PERCENT,
    )
    val draft = if (
        parsedStrength != null && parsedSmoothing != null && parsedSlope != null &&
        parsedClearanceAngle != null && parsedClearanceHeight != null && parsedMaximumLift != null &&
        parsedNozzleAngle != null && parsedNozzleProtrusion != null && parsedBlockWidth != null &&
        parsedBlockDepth != null && parsedBlockOffsetX != null && parsedBlockOffsetY != null &&
        parsedFlatBaseLayers != null && parsedFieldResolution != null && parsedSegmentLength != null &&
        parsedMaximumZSpeed != null && parsedFadeStart != null
    ) {
        NonPlanarSettings(
            enabled = enabled,
            strengthPercent = parsedStrength,
            smoothingRadiusMm = parsedSmoothing,
            maximumSlopeDegrees = parsedSlope,
            nozzleClearanceAngleDegrees = parsedClearanceAngle,
            nozzleClearanceHeightMm = parsedClearanceHeight,
            maximumLiftMm = parsedMaximumLift,
            nozzleAngleDegrees = parsedNozzleAngle,
            nozzleProtrusionMm = parsedNozzleProtrusion,
            heatingBlockWidthMm = parsedBlockWidth,
            heatingBlockDepthMm = parsedBlockDepth,
            heatingBlockOffsetXmm = parsedBlockOffsetX,
            heatingBlockOffsetYmm = parsedBlockOffsetY,
            flatBaseLayers = parsedFlatBaseLayers,
            fieldResolution = parsedFieldResolution,
            maximumSegmentLengthMm = parsedSegmentLength,
            maximumZSpeedMmPerSecond = parsedMaximumZSpeed,
            compensateExtrusion = compensateExtrusion,
            warpSmartInfillModifiers = warpSmartInfill,
            pauseAfterProbe = pauseAfterProbe,
            drapeMode = drapeMode,
            fadeStartPercent = parsedFadeStart,
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
            "Block cone height (mm)",
            clearanceHeight,
            NonPlanarSettings.MIN_CLEARANCE_HEIGHT_MM,
            NonPlanarSettings.MAX_CLEARANCE_HEIGHT_MM,
            onText = { clearanceHeight = it },
        )
        DecimalSettingField(
            "Maximum surface lift (mm)",
            maximumLift,
            NonPlanarSettings.MIN_MAXIMUM_LIFT_MM,
            NonPlanarSettings.MAX_MAXIMUM_LIFT_MM,
            onText = { maximumLift = it },
        )
        DecimalSettingField(
            "Nozzle taper angle (degrees)",
            nozzleAngle,
            NonPlanarSettings.MIN_NOZZLE_ANGLE_DEGREES,
            NonPlanarSettings.MAX_NOZZLE_ANGLE_DEGREES,
            onText = { nozzleAngle = it },
        )
        DecimalSettingField(
            "Nozzle protrusion (mm)",
            nozzleProtrusion,
            NonPlanarSettings.MIN_NOZZLE_PROTRUSION_MM,
            NonPlanarSettings.MAX_NOZZLE_PROTRUSION_MM,
            onText = { nozzleProtrusion = it },
        )
        DecimalSettingField(
            "Heating block width (mm)",
            blockWidth,
            NonPlanarSettings.MIN_BLOCK_SIZE_MM,
            NonPlanarSettings.MAX_BLOCK_SIZE_MM,
            onText = { blockWidth = it },
        )
        DecimalSettingField(
            "Heating block depth (mm)",
            blockDepth,
            NonPlanarSettings.MIN_BLOCK_SIZE_MM,
            NonPlanarSettings.MAX_BLOCK_SIZE_MM,
            onText = { blockDepth = it },
        )
        DecimalSettingField(
            "Nozzle offset from block centre X (mm)",
            blockOffsetX,
            -NonPlanarSettings.MAX_BLOCK_OFFSET_MM,
            NonPlanarSettings.MAX_BLOCK_OFFSET_MM,
            onText = { blockOffsetX = it },
        )
        DecimalSettingField(
            "Nozzle offset from block centre Y (mm)",
            blockOffsetY,
            -NonPlanarSettings.MAX_BLOCK_OFFSET_MM,
            NonPlanarSettings.MAX_BLOCK_OFFSET_MM,
            onText = { blockOffsetY = it },
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Measure your hot end", style = MaterialTheme.typography.titleSmall)
                Text(
                    "All measurements are taken once, on the printer, with the tip just touching the bed. They build the collision volume: the nozzle cone (taper angle × protrusion below the block), the heating block frustum (the block's X × Y footprint widening at the same measured clearance angle up to the holding-object height), and a flat no-go cutoff above the holding object that spans the whole build plate plus 30%. After slicing, EnderSlicer sweeps that volume along every move and warns if the printed surface pokes into it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Nozzle taper: angle of the nozzle's own cone from vertical (V6 ≈ 30°). Protrusion: how far the tip sticks out below the block (≈ 4–6 mm) - the first cone's height. Block cone height: from the top of the nozzle up to the lowest holding structure - the second cone's height. Block: the X × Y footprint of the heater block (E3D ≈ 20 × 16 mm) and the nozzle axis offset from the block centre in X and Y. Clearance angle: from vertical out to the nearest thing that could collide - the block's sides follow this same angle.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        var viewerOpen by rememberSaveable { mutableStateOf(false) }
        OutlinedButton(
            onClick = { viewerOpen = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("View hot-end model (3D)")
        }
        if (viewerOpen) {
            HotendVolumeDialog(
                nozzleAngleDegrees = parsedNozzleAngle,
                protrusionMm = parsedNozzleProtrusion,
                blockWidthMm = parsedBlockWidth,
                blockDepthMm = parsedBlockDepth,
                offsetXmm = parsedBlockOffsetX,
                offsetYmm = parsedBlockOffsetY,
                clearanceAngleDegrees = parsedClearanceAngle,
                holderHeightMm = parsedClearanceHeight?.plus(parsedNozzleProtrusion ?: 0.0),
                onDismiss = { viewerOpen = false },
            )
        }
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
        DecimalSettingField(
            "Fade start (% of height)",
            fadeStart,
            NonPlanarSettings.MIN_FADE_START_PERCENT,
            NonPlanarSettings.MAX_FADE_START_PERCENT,
            onText = { fadeStart = it },
        )
        Text(
            "0% = the curve ramps up from the flat base; higher values keep the lower part of the model flat and curve only the top. Drape mode ignores this.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingSwitch(
            title = "Drape layers (curve every layer)",
            description = "Like the original CurviSlicer: every layer above the flat base follows the surface shape, so the whole print becomes stacked curved sheets. Full strength, big Z sweeps.",
            checked = drapeMode,
            onChecked = { drapeMode = it },
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

