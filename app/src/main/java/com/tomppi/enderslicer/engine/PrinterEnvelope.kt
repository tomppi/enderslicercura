package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.viewer.StlMesh
import java.io.File
import java.util.Locale
import org.json.JSONObject

/** Immutable build-volume policy used by model preflight and final G-code validation. */
internal data class PrinterEnvelope(
    val widthMm: Double,
    val depthMm: Double,
    val heightMm: Double,
    val buildPlateShape: String,
    val originAtCenter: Boolean,
) {
    init {
        require(widthMm.isFinite() && widthMm > 0.0) { "Machine width must be positive and finite" }
        require(depthMm.isFinite() && depthMm > 0.0) { "Machine depth must be positive and finite" }
        require(heightMm.isFinite() && heightMm > 0.0) { "Machine height must be positive and finite" }
        require(normalizedShape(buildPlateShape) in SUPPORTED_SHAPES) {
            "Unsupported build plate shape: $buildPlateShape"
        }
    }

    fun requireModelFits(mesh: StlMesh) {
        require(mesh.triangleCount > 0 && mesh.interleavedVertices.size == mesh.triangleCount * 18) {
            "Model geometry is incomplete"
        }
        var offset = 0
        var vertex = 1
        while (offset < mesh.interleavedVertices.size) {
            requirePoint(
                x = mesh.interleavedVertices[offset].toDouble(),
                y = mesh.interleavedVertices[offset + 1].toDouble(),
                z = mesh.interleavedVertices[offset + 2].toDouble(),
                context = "Model vertex $vertex",
            )
            offset += 6
            vertex++
        }
    }

    fun requireExtrusionMove(
        startX: Double,
        startY: Double,
        startZ: Double,
        endX: Double,
        endY: Double,
        endZ: Double,
        lineNumber: Int,
        layerNumber: Int?,
    ) {
        val location = layerNumber?.let { "line $lineNumber, layer $it" } ?: "line $lineNumber, startup"
        requirePoint(startX, startY, startZ, "Extrusion start at $location")
        requirePoint(endX, endY, endZ, "Extrusion end at $location")
    }

    fun contains(x: Double, y: Double, z: Double, toleranceMm: Double = DEFAULT_TOLERANCE_MM): Boolean {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return false
        if (z < -toleranceMm || z > heightMm + toleranceMm) return false

        val centerX = if (originAtCenter) 0.0 else widthMm / 2.0
        val centerY = if (originAtCenter) 0.0 else depthMm / 2.0
        return when (normalizedShape(buildPlateShape)) {
            RECTANGULAR -> {
                val halfWidth = widthMm / 2.0 + toleranceMm
                val halfDepth = depthMm / 2.0 + toleranceMm
                x in (centerX - halfWidth)..(centerX + halfWidth) &&
                    y in (centerY - halfDepth)..(centerY + halfDepth)
            }
            ELLIPTIC -> {
                val radiusX = widthMm / 2.0 + toleranceMm
                val radiusY = depthMm / 2.0 + toleranceMm
                val normalizedX = (x - centerX) / radiusX
                val normalizedY = (y - centerY) / radiusY
                normalizedX * normalizedX + normalizedY * normalizedY <= 1.0
            }
            else -> false
        }
    }

    fun writeTo(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject()
                .put("version", FORMAT_VERSION)
                .put("widthMm", widthMm)
                .put("depthMm", depthMm)
                .put("heightMm", heightMm)
                .put("buildPlateShape", normalizedShape(buildPlateShape))
                .put("originAtCenter", originAtCenter)
                .toString(),
        )
        check(file.isFile && file.length() > 0L) { "Unable to write the printer envelope" }
    }

    private fun requirePoint(x: Double, y: Double, z: Double, context: String) {
        if (contains(x, y, z)) return
        val origin = if (originAtCenter) "centered" else "front-left"
        throw OutsideBuildVolumeException(
            "$context is outside the ${format(widthMm)} x ${format(depthMm)} x ${format(heightMm)} mm " +
                "${normalizedShape(buildPlateShape)} build volume ($origin origin): " +
                "X=${format(x)}, Y=${format(y)}, Z=${format(z)}",
        )
    }

    class OutsideBuildVolumeException(message: String) : IllegalArgumentException(message)

    companion object {
        const val METADATA_FILE_NAME = "printer-envelope.json"
        const val DEFAULT_TOLERANCE_MM = 0.05

        fun from(printer: PrinterDefinition): PrinterEnvelope = PrinterEnvelope(
            widthMm = printer.widthMm,
            depthMm = printer.depthMm,
            heightMm = printer.heightMm,
            buildPlateShape = normalizedShape(printer.buildPlateShape),
            originAtCenter = printer.originAtCenter,
        )

        fun readFrom(file: File): PrinterEnvelope {
            require(file.isFile && file.length() > 0L) { "Published slice has no printer envelope" }
            val root = JSONObject(file.readText())
            require(root.getInt("version") == FORMAT_VERSION) { "Unsupported printer envelope version" }
            return PrinterEnvelope(
                widthMm = root.getDouble("widthMm"),
                depthMm = root.getDouble("depthMm"),
                heightMm = root.getDouble("heightMm"),
                buildPlateShape = root.getString("buildPlateShape"),
                originAtCenter = root.getBoolean("originAtCenter"),
            )
        }

        private fun normalizedShape(value: String): String = when (value.trim().lowercase(Locale.US)) {
            "rectangular", "rectangle" -> RECTANGULAR
            "elliptic", "ellipse", "circular", "circle" -> ELLIPTIC
            else -> value.trim().lowercase(Locale.US)
        }

        private fun format(value: Double): String =
            String.format(Locale.US, "%.5f", value).trimEnd('0').trimEnd('.')

        private const val FORMAT_VERSION = 1
        private const val RECTANGULAR = "rectangular"
        private const val ELLIPTIC = "elliptic"
        private val SUPPORTED_SHAPES = setOf(RECTANGULAR, ELLIPTIC)
    }
}
