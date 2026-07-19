package com.tomppi.enderslicer.viewer

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object StlMeshWriter {
    data class ResolvedSliceSource(
        val modelFile: File,
        val transform: StlSliceTransform,
    )

    /**
     * Writes the displayed mesh exactly as before for standalone/fallback slices.
     * When ModelPlacement retained original geometry, a second internal STL and
     * affine sidecar are staged for resolved Cura slicing. This lets CuraEngine
     * transform the original STL directly instead of slicing vertices that were
     * already rounded after placement.
     */
    fun writeBinary(mesh: StlMesh, destination: File) {
        validateVertices(mesh.interleavedVertices, mesh.triangleCount)
        writeBinaryVertices(mesh.interleavedVertices, mesh.triangleCount, destination)

        val sourceVertices = mesh.slicingSourceInterleavedVertices
        val transform = mesh.slicingTransform
        val sourceFile = sourceFileFor(destination)
        val transformFile = transformFileFor(destination)
        sourceFile.delete()
        transformFile.delete()

        if (sourceVertices != null && transform != null) {
            validateVertices(sourceVertices, mesh.triangleCount)
            writeBinaryVertices(sourceVertices, mesh.triangleCount, sourceFile)
            transformFile.writeText(
                JSONObject()
                    .put("version", 1)
                    .put("linear", JSONArray(transform.linear))
                    .put("translationXmm", transform.translationXmm)
                    .put("translationYmm", transform.translationYmm)
                    .put("translationZmm", transform.translationZmm)
                    .toString(),
            )
            check(transformFile.isFile && transformFile.length() > 0L) {
                "Unable to write the original-model slice transform"
            }
        }
    }

    fun resolvedSliceSource(stagedDisplayedFile: File): ResolvedSliceSource? {
        val sourceFile = sourceFileFor(stagedDisplayedFile)
        val transformFile = transformFileFor(stagedDisplayedFile)
        if (!sourceFile.isFile || sourceFile.length() < STL_HEADER_BYTES || !transformFile.isFile) return null

        val root = JSONObject(transformFile.readText())
        require(root.getInt("version") == 1) { "Unsupported staged STL transform version" }
        val values = root.getJSONArray("linear")
        require(values.length() == 9) { "Staged STL transform must contain nine linear values" }
        val transform = StlSliceTransform(
            linear = List(9) { index -> values.getDouble(index) },
            translationXmm = root.getDouble("translationXmm"),
            translationYmm = root.getDouble("translationYmm"),
            translationZmm = root.getDouble("translationZmm"),
        )
        return ResolvedSliceSource(sourceFile, transform)
    }

    private fun writeBinaryVertices(vertices: FloatArray, triangleCount: Int, destination: File) {
        destination.parentFile?.mkdirs()
        destination.outputStream().buffered().use { output ->
            output.write(ByteArray(80))
            output.write(intLittleEndian(triangleCount))
            var index = 0
            repeat(triangleCount) {
                val normalX = vertices[index + 3]
                val normalY = vertices[index + 4]
                val normalZ = vertices[index + 5]
                output.write(floatLittleEndian(normalX))
                output.write(floatLittleEndian(normalY))
                output.write(floatLittleEndian(normalZ))
                repeat(3) { vertex ->
                    val base = index + vertex * 6
                    output.write(floatLittleEndian(vertices[base]))
                    output.write(floatLittleEndian(vertices[base + 1]))
                    output.write(floatLittleEndian(vertices[base + 2]))
                }
                output.write(byteArrayOf(0, 0))
                index += 18
            }
        }
        check(destination.isFile && destination.length() == STL_HEADER_BYTES + triangleCount * STL_TRIANGLE_BYTES) {
            "Unable to write the staged STL"
        }
    }

    private fun validateVertices(vertices: FloatArray, triangleCount: Int) {
        require(triangleCount > 0) { "Cannot write an empty STL mesh" }
        require(vertices.size == triangleCount * 18) {
            "STL mesh vertex data does not match its triangle count"
        }
        require(vertices.all(Float::isFinite)) { "STL mesh contains a non-finite value" }
    }

    private fun sourceFileFor(destination: File): File = File(
        destination.parentFile,
        "${destination.nameWithoutExtension}.slice-source.stl",
    )

    private fun transformFileFor(destination: File): File = File(
        destination.parentFile,
        "${destination.nameWithoutExtension}.slice-transform.json",
    )

    private fun intLittleEndian(value: Int): ByteArray = ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(value)
        .array()

    private fun floatLittleEndian(value: Float): ByteArray = ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putFloat(value)
        .array()

    private const val STL_HEADER_BYTES = 84L
    private const val STL_TRIANGLE_BYTES = 50L
}
