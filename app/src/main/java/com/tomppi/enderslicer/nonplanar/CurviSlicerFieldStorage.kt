package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.engine.PrinterEnvelope
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Isolated request-side handoff between STL flattening and G-code restoration. */
internal object CurviSlicerFieldStorage {
    private const val FILE_NAME = "curvislicer-field.bin"
    private const val MAGIC = 0x43555256
    private const val VERSION = 1

    fun write(workspace: File, prepared: CurviSlicerPipeline.Prepared) {
        require(workspace.isDirectory) { "CurviSlicer workspace is unavailable" }
        val destination = File(workspace, FILE_NAME)
        val temporary = File(workspace, "$FILE_NAME.tmp")
        temporary.delete()
        try {
            DataOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                with(prepared.field) {
                    output.writeDouble(minX)
                    output.writeDouble(minY)
                    output.writeDouble(minZ)
                    output.writeDouble(maxX)
                    output.writeDouble(maxY)
                    output.writeDouble(maxZ)
                    output.writeInt(columns)
                    output.writeInt(rows)
                    output.writeDouble(strength)
                    output.writeDouble(flatBaseHeightMm)
                    output.writeInt(relief.size)
                    relief.forEach(output::writeFloat)
                }
                with(prepared.settings) {
                    output.writeDouble(strengthPercent)
                    output.writeDouble(smoothingRadiusMm)
                    output.writeDouble(maximumSlopeDegrees)
                    output.writeDouble(nozzleClearanceAngleDegrees)
                    output.writeDouble(nozzleClearanceHeightMm)
                    output.writeInt(flatBaseLayers)
                    output.writeInt(fieldResolution)
                    output.writeDouble(maximumSegmentLengthMm)
                    output.writeDouble(maximumZSpeedMmPerSecond)
                    output.writeBoolean(compensateExtrusion)
                    output.writeBoolean(warpSmartInfillModifiers)
                }
                with(prepared.diagnostics) {
                    output.writeDouble(requestedStrength)
                    output.writeDouble(appliedStrength)
                    output.writeDouble(maximumRawReliefMm)
                    output.writeDouble(maximumAppliedDisplacementMm)
                    output.writeDouble(maximumFieldSlopeDegrees)
                    output.writeInt(sourceTriangles)
                }
            }
            check(temporary.isFile && temporary.length() > 64L) { "Unable to persist the CurviSlicer field" }
            if (destination.exists()) check(destination.delete()) { "Unable to replace the CurviSlicer field" }
            check(
                temporary.renameTo(destination) ||
                    temporary.copyTo(destination, overwrite = false).let { temporary.delete(); true },
            ) { "Unable to publish the CurviSlicer field" }
        } finally {
            temporary.delete()
        }
    }

    fun curveStagedGcode(file: File, printerEnvelope: PrinterEnvelope): CurviSlicerPipeline.GcodeDiagnostics? {
        val sidecar = File(file.parentFile, FILE_NAME)
        if (!sidecar.isFile) return null
        return read(sidecar).curveGcode(file, printerEnvelope)
    }

    fun isPrepared(workspace: File): Boolean = File(workspace, FILE_NAME).isFile

    private fun read(file: File): CurviSlicerPipeline.Prepared =
        DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == MAGIC) { "Invalid CurviSlicer field marker" }
            require(input.readInt() == VERSION) { "Unsupported CurviSlicer field version" }
            val minX = input.readDouble()
            val minY = input.readDouble()
            val minZ = input.readDouble()
            val maxX = input.readDouble()
            val maxY = input.readDouble()
            val maxZ = input.readDouble()
            val columns = input.readInt()
            val rows = input.readInt()
            val strength = input.readDouble()
            val flatBaseHeightMm = input.readDouble()
            val reliefSize = input.readInt()
            require(columns in 2..192 && rows in 2..192 && reliefSize == columns * rows) {
                "Invalid CurviSlicer field dimensions"
            }
            val relief = FloatArray(reliefSize) { input.readFloat() }
            require(relief.all(Float::isFinite)) { "CurviSlicer field contains a non-finite value" }
            val settings = NonPlanarSettings(
                enabled = true,
                strengthPercent = input.readDouble(),
                smoothingRadiusMm = input.readDouble(),
                maximumSlopeDegrees = input.readDouble(),
                nozzleClearanceAngleDegrees = input.readDouble(),
                nozzleClearanceHeightMm = input.readDouble(),
                flatBaseLayers = input.readInt(),
                fieldResolution = input.readInt(),
                maximumSegmentLengthMm = input.readDouble(),
                maximumZSpeedMmPerSecond = input.readDouble(),
                compensateExtrusion = input.readBoolean(),
                warpSmartInfillModifiers = input.readBoolean(),
            ).validated()
            val diagnostics = CurviSlicerPipeline.Diagnostics(
                gridColumns = columns,
                gridRows = rows,
                requestedStrength = input.readDouble(),
                appliedStrength = input.readDouble(),
                maximumRawReliefMm = input.readDouble(),
                maximumAppliedDisplacementMm = input.readDouble(),
                maximumFieldSlopeDegrees = input.readDouble(),
                sourceTriangles = input.readInt(),
            )
            val field = CurviSlicerField(
                minX = minX,
                minY = minY,
                minZ = minZ,
                maxX = maxX,
                maxY = maxY,
                maxZ = maxZ,
                columns = columns,
                rows = rows,
                relief = relief,
                strength = strength,
                flatBaseHeightMm = flatBaseHeightMm,
            )
            CurviSlicerPipeline.Prepared(field, diagnostics, settings)
        }
}
