package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.conical.ConicalPipeline
import com.tomppi.enderslicer.conical.ConicalRuntime
import com.tomppi.enderslicer.conical.ConicalStorage
import com.tomppi.enderslicer.nonplanar.ConformalSurfaceStorage
import com.tomppi.enderslicer.nonplanar.CurviSlicerFieldStorage
import com.tomppi.enderslicer.nonplanar.CurviSlicerPipeline
import com.tomppi.enderslicer.nonplanar.CurviSlicerRuntime
import com.tomppi.enderslicer.smartinfill.SmartInfillModifier
import com.tomppi.enderslicer.supportpaint.SupportPaintModifier
import java.io.File

/**
 * Single source of truth for the non-planar preparation shared by both engine
 * transports (the standalone command builder and the resolved-settings
 * writer). Warps the staged model, warps Smart Infill and painted-support
 * modifier volumes with the same transform, re-validates the warped
 * intermediate against the printer envelope, and persists the sidecars the
 * G-code transformers read back.
 */
internal object NonPlanarPreparation {
    data class Outcome(
        val curviPrepared: CurviSlicerPipeline.Prepared?,
        val conformalPrepared: CurviSlicerPipeline.ConformalPrepared?,
        val conicalPrepared: ConicalPipeline.Prepared?,
    )

    fun prepare(
        modelFile: File,
        workspace: File,
        printerEnvelope: PrinterEnvelope,
        layerHeightMm: Double,
        nozzleDiameterMm: Double,
        smartInfillModifiers: List<SmartInfillModifier>,
        adaptiveWallModifiers: List<AdaptiveWallModifier>,
        supportPaintModifiers: List<SupportPaintModifier>,
    ): Outcome {
        val snapshot = CurviSlicerRuntime.snapshot()
        val conformalPrepared = snapshot?.takeIf { it.settings.conformalMode }?.let { active ->
            require(adaptiveWallModifiers.isEmpty()) {
                "Adaptive walls cannot be combined with conformal surface mode"
            }
            require(smartInfillModifiers.isEmpty()) {
                "Smart Infill cannot be combined with conformal surface mode: " +
                    "the conformal shells need the full top-layer material"
            }
            require(ConicalRuntime.snapshot() == null) {
                "Conformal surface mode cannot be combined with conical slicing"
            }
            CurviSlicerPipeline.prepareConformal(
                modelFile = modelFile,
                settings = active.settings,
                layerHeightMm = layerHeightMm,
                nozzleDiameterMm = nozzleDiameterMm,
            )
        }
        val curviPrepared = snapshot?.takeIf { !it.settings.conformalMode }?.let { active ->
            require(adaptiveWallModifiers.isEmpty()) {
                "Adaptive walls cannot be combined with CurviSlicer: " +
                    "the modifier volumes are generated from the un-warped model and would misalign"
            }
            CurviSlicerPipeline.prepareAndWarp(
                modelFile = modelFile,
                settings = active.settings,
                layerHeightMm = layerHeightMm,
                nozzleDiameterMm = nozzleDiameterMm,
            )
        }
        if (curviPrepared != null) {
            if (smartInfillModifiers.isNotEmpty()) {
                require(curviPrepared.settings.warpSmartInfillModifiers) {
                    "CurviSlicer must warp Smart Infill modifiers so density regions remain aligned"
                }
                smartInfillModifiers.forEach { modifier -> curviPrepared.warpModifier(modifier.file) }
            }
            supportPaintModifiers.forEach { modifier -> curviPrepared.warpModifier(modifier.file) }
            printerEnvelope.requireBinaryStlFits(modelFile)
            smartInfillModifiers.forEach { modifier -> printerEnvelope.requireBinaryStlFits(modifier.file) }
            supportPaintModifiers.forEach { modifier ->
                printerEnvelope.requireBinaryStlFits(
                    modifier.file,
                    label = "Support-paint modifier " + modifier.file.name,
                )
            }
            CurviSlicerFieldStorage.write(workspace, curviPrepared)
        }
        if (conformalPrepared != null) {
            // The model is untouched in conformal mode, so modifier volumes
            // stay at their displayed coordinates and need no warping.
            printerEnvelope.requireBinaryStlFits(modelFile)
            smartInfillModifiers.forEach { modifier -> printerEnvelope.requireBinaryStlFits(modifier.file) }
            supportPaintModifiers.forEach { modifier ->
                printerEnvelope.requireBinaryStlFits(
                    modifier.file,
                    label = "Support-paint modifier " + modifier.file.name,
                )
            }
            ConformalSurfaceStorage.write(workspace, conformalPrepared)
        }

        val conicalPrepared = ConicalRuntime.snapshot()?.let { snapshot ->
            require(smartInfillModifiers.isEmpty()) {
                "Conical slicing cannot be combined with Smart Infill modifier volumes"
            }
            require(adaptiveWallModifiers.isEmpty()) {
                "Adaptive walls cannot be combined with conical slicing: " +
                    "the modifier volumes are generated from the un-warped model and would misalign"
            }
            ConicalPipeline.prepareAndWarp(modelFile = modelFile, settings = snapshot.settings)
        }
        if (conicalPrepared != null) {
            supportPaintModifiers.forEach { modifier -> conicalPrepared.warpModifier(modifier.file) }
            printerEnvelope.requireBinaryStlFits(modelFile)
            supportPaintModifiers.forEach { modifier ->
                printerEnvelope.requireBinaryStlFits(
                    modifier.file,
                    label = "Support-paint modifier " + modifier.file.name,
                )
            }
            ConicalStorage.write(workspace, conicalPrepared)
        }
        return Outcome(curviPrepared, conformalPrepared, conicalPrepared)
    }

    fun markMachineEndGcode(gcode: String): String =
        ConicalRuntime.markMachineEndGcode(CurviSlicerRuntime.markMachineEndGcode(gcode))
}
