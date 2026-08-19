package com.tomppi.enderslicer.conical

import com.tomppi.enderslicer.engine.PrinterEnvelope
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.StlMeshWriter
import com.tomppi.enderslicer.viewer.StlParser
import java.io.File

internal const val CONICAL_CANCELLATION_INTERVAL = 1024

internal fun checkConicalCancellation(workItems: Int, interval: Int = CONICAL_CANCELLATION_INTERVAL) {
    if (workItems % interval == 0 && Thread.currentThread().isInterrupted) {
        throw InterruptedException("Conical slicing was cancelled")
    }
}

/**
 * Android-native EasyConical (conical slicing) implementation.
 *
 * The published EasyConical algorithm has three steps: warp the STL around the
 * vertical axis with a cone transform, slice the warped solid with a normal
 * slicer, then back-transform every G-code move to the original geometry. Here
 * the warp pivots on the mesh XY bounds centre (the automatic equivalent of
 * EasyConical's "centre the model on the origin" step) and the back-transform
 * restores X/Y/Z/E around the same centre before translating to the first-layer
 * height and any requested X/Y shift.
 */
internal object ConicalPipeline {
    data class Prepared(
        val centerX: Double,
        val centerY: Double,
        val settings: ConicalSettings,
        val diagnostics: Diagnostics,
    ) {
        fun backtransformGcode(file: File, printerEnvelope: PrinterEnvelope): GcodeDiagnostics =
            ConicalGcodeTransformer.transform(file, centerX, centerY, settings, printerEnvelope)

        /**
         * Warps one CuraEngine modifier volume (for example a painted support
         * enforcer/blocker prism) with the exact refinement and cone warp used
         * for the model, pivoting on the model's XY bounds centre so the
         * modifier stays aligned with the warped solid. CuraEngine then
         * generates supports against the warped geometry and the G-code
         * back-transform restores both together.
         */
        fun warpModifier(file: File) {
            val mesh = StlParser.parse(file, file.name)
            checkConicalCancellation(1)
            val refined = ConicalTransform.refine(mesh, settings.refinementIterations)
            val warped = ConicalTransform.warpAround(refined, centerX, centerY, settings)
            writeWarped(file, warped)
        }
    }

    data class Diagnostics(
        val sourceTriangles: Int,
        val refinedTriangles: Int,
        val refinementIterations: Int,
        val coneAngleDegrees: Double,
    )

    data class GcodeDiagnostics(
        val sourceMoves: Int,
        val emittedMoves: Int,
        val subdividedMoves: Int,
        val extrusionMoves: Int,
        val travelMoves: Int,
        val minimumZmm: Double,
        val maximumZmm: Double,
    )

    fun prepareAndWarp(modelFile: File, settings: ConicalSettings): Prepared {
        require(modelFile.isFile && modelFile.length() > 0L) { "Conical input STL is missing" }
        val safe = settings.validated()
        require(safe.enabled) { "Conical slicing is not enabled" }

        val mesh = StlParser.parse(modelFile, modelFile.name)
        checkConicalCancellation(1)
        val refined = ConicalTransform.refine(mesh, safe.refinementIterations)
        val warped = ConicalTransform.warp(refined, safe)
        writeWarped(modelFile, warped)

        return Prepared(
            centerX = refined.bounds.centerX.toDouble(),
            centerY = refined.bounds.centerY.toDouble(),
            settings = safe,
            diagnostics = Diagnostics(
                sourceTriangles = mesh.triangleCount,
                refinedTriangles = refined.triangleCount,
                refinementIterations = safe.refinementIterations,
                coneAngleDegrees = safe.coneAngleDegrees,
            ),
        )
    }

    private fun writeWarped(destination: File, warped: StlMesh) {
        val temporary = File(destination.parentFile, "${destination.name}.conical.tmp")
        temporary.delete()
        try {
            StlMeshWriter.writeBinary(warped, temporary)
            check(temporary.isFile && temporary.length() > 84L) { "Conical slicing did not produce a warped STL" }
            if (destination.exists()) check(destination.delete()) { "Unable to replace the conical STL" }
            check(
                temporary.renameTo(destination) ||
                    temporary.copyTo(destination, overwrite = false).let { temporary.delete(); true },
            ) { "Unable to publish the conical STL" }
        } finally {
            temporary.delete()
        }
    }
}
