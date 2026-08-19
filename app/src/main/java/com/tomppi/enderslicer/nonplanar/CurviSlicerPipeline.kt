package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.engine.GcodeCommand
import com.tomppi.enderslicer.engine.GcodeModalState
import com.tomppi.enderslicer.engine.PrinterEnvelope
import com.tomppi.enderslicer.engine.checkCancellation
import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.StlMeshWriter
import com.tomppi.enderslicer.viewer.StlParser
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Android-native CurviSlicer implementation.
 *
 * The desktop research prototype obtains a volumetric deformation from a
 * tetrahedral optimization. On Android we construct a practical monotone
 * height-field adaptation directly from the displayed triangle mesh, flatten the STL,
 * slice that flattened solid with CuraEngine, then apply the inverse field to
 * every print move. The field is slope-limited for nozzle clearance, fades in
 * above flat base layers, preserves layer ordering, and compensates extrusion
 * for the longer 3D paths.
 */
internal object CurviSlicerPipeline {
    data class Prepared(
        val field: CurviSlicerField,
        val diagnostics: Diagnostics,
        val settings: NonPlanarSettings,
    ) {
        fun warpModifier(file: File) {
            warpStl(file, file, field)
        }

        fun curveGcode(file: File, printerEnvelope: PrinterEnvelope): GcodeDiagnostics =
            CurviGcodeTransformer.transform(file, field, settings, printerEnvelope)
    }

    data class Diagnostics(
        val gridColumns: Int,
        val gridRows: Int,
        val requestedStrength: Double,
        val appliedStrength: Double,
        val maximumRawReliefMm: Double,
        val maximumAppliedDisplacementMm: Double,
        val maximumFieldSlopeDegrees: Double,
        val sourceTriangles: Int,
    )

    data class GcodeDiagnostics(
        val sourceMoves: Int,
        val emittedMoves: Int,
        val subdividedMoves: Int,
        val extrusionMoves: Int,
        val travelMoves: Int,
        val minimumZmm: Double,
        val maximumZmm: Double,
        val maximumObservedSlopeDegrees: Double,
        val maximumObservedZSpeedMmPerSecond: Double,
    )

    fun prepareAndWarp(
        modelFile: File,
        settings: NonPlanarSettings,
        layerHeightMm: Double,
        nozzleDiameterMm: Double,
    ): Prepared {
        require(modelFile.isFile && modelFile.length() > 0L) { "CurviSlicer input STL is missing" }
        val safe = settings.validated()
        require(safe.enabled) { "CurviSlicer is not enabled" }
        require(layerHeightMm.isFinite() && layerHeightMm in 0.04..1.2) { "Invalid CurviSlicer layer height" }
        require(nozzleDiameterMm.isFinite() && nozzleDiameterMm in 0.1..2.0) { "Invalid CurviSlicer nozzle diameter" }

        val mesh = parseCancellable(modelFile)
        require(mesh.bounds.height > layerHeightMm * (safe.flatBaseLayers + 2)) {
            "The model is too short for ${safe.flatBaseLayers} flat CurviSlicer base layers"
        }
        val built = CurviSlicerFieldBuilder.build(mesh, safe, layerHeightMm, nozzleDiameterMm)
        warpMesh(modelFile, mesh, built.field)
        return Prepared(built.field, built.diagnostics, safe)
    }

    private fun parseCancellable(file: File): StlMesh = try {
        StlParser.parse(file, file.name)
    } catch (closed: java.nio.channels.ClosedByInterruptException) {
        throw InterruptedException("CurviSlicer processing was cancelled")
    }

    private fun warpStl(source: File, destination: File, field: CurviSlicerField) {
        warpMesh(destination, parseCancellable(source), field)
    }

    private fun warpMesh(destination: File, mesh: StlMesh, field: CurviSlicerField) {
        val transformed = mesh.interleavedVertices
        val bounds = MutableBounds()
        var offset = 0
        repeat(mesh.triangleCount) { triangleIndex ->
            checkCancellation(triangleIndex, "CurviSlicer processing")
            val x0 = transformed[offset].toDouble()
            val y0 = transformed[offset + 1].toDouble()
            val z0 = field.flattenZ(x0, y0, transformed[offset + 2].toDouble()).toFloat()
            val x1 = transformed[offset + 6].toDouble()
            val y1 = transformed[offset + 7].toDouble()
            val z1 = field.flattenZ(x1, y1, transformed[offset + 8].toDouble()).toFloat()
            val x2 = transformed[offset + 12].toDouble()
            val y2 = transformed[offset + 13].toDouble()
            val z2 = field.flattenZ(x2, y2, transformed[offset + 14].toDouble()).toFloat()

            transformed[offset + 2] = z0
            transformed[offset + 8] = z1
            transformed[offset + 14] = z2

            val ax = x1.toFloat() - x0.toFloat()
            val ay = y1.toFloat() - y0.toFloat()
            val az = z1 - z0
            val bx = x2.toFloat() - x0.toFloat()
            val by = y2.toFloat() - y0.toFloat()
            val bz = z2 - z0
            var nx = ay * bz - az * by
            var ny = az * bx - ax * bz
            var nz = ax * by - ay * bx
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            if (length > 1e-12f) {
                nx /= length
                ny /= length
                nz /= length
            } else {
                nx = 0f
                ny = 0f
                nz = 0f
            }
            for (vertex in 0..2) {
                val base = offset + vertex * 6
                transformed[base + 3] = nx
                transformed[base + 4] = ny
                transformed[base + 5] = nz
                bounds.include(transformed[base], transformed[base + 1], transformed[base + 2])
            }
            offset += 18
        }

        val warped = StlMesh(
            displayName = mesh.displayName,
            interleavedVertices = transformed,
            triangleCount = mesh.triangleCount,
            bounds = bounds.finish(),
        )
        val temporary = File(destination.parentFile, "${destination.name}.curvislicer.tmp")
        temporary.delete()
        try {
            StlMeshWriter.writeBinary(warped, temporary)
            check(temporary.isFile && temporary.length() > 84L) { "CurviSlicer did not produce a warped STL" }
            if (destination.exists()) check(destination.delete()) { "Unable to replace the CurviSlicer STL" }
            check(temporary.renameTo(destination) || temporary.copyTo(destination, overwrite = false).let { temporary.delete(); true }) {
                "Unable to publish the CurviSlicer STL"
            }
        } finally {
            temporary.delete()
        }
    }

    private class MutableBounds {
        private var minX = Float.POSITIVE_INFINITY
        private var minY = Float.POSITIVE_INFINITY
        private var minZ = Float.POSITIVE_INFINITY
        private var maxX = Float.NEGATIVE_INFINITY
        private var maxY = Float.NEGATIVE_INFINITY
        private var maxZ = Float.NEGATIVE_INFINITY

        fun include(x: Float, y: Float, z: Float) {
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            minZ = minOf(minZ, z)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
            maxZ = maxOf(maxZ, z)
        }

        fun finish(): MeshBounds = MeshBounds(minX, minY, minZ, maxX, maxY, maxZ)
    }
}
