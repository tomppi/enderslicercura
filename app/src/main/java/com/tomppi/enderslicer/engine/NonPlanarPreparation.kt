package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.conical.ConicalPipeline
import com.tomppi.enderslicer.conical.ConicalRuntime
import com.tomppi.enderslicer.conical.ConicalStorage
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
        val curviPrepared = CurviSlicerRuntime.snapshot()?.let { snapshot ->
            require(adaptiveWallModifiers.isEmpty()) {
                "Adaptive walls cannot be combined with CurviSlicer: " +
                    "the modifier volumes are generated from the un-warped model and would misalign"
            }
            CurviSlicerPipeline.prepareAndWarp(
                modelFile = modelFile,
                settings = snapshot.settings,
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
        return Outcome(curviPrepared, conicalPrepared)
    }

    fun markMachineEndGcode(gcode: String): String =
        ConicalRuntime.markMachineEndGcode(CurviSlicerRuntime.markMachineEndGcode(gcode))
}
