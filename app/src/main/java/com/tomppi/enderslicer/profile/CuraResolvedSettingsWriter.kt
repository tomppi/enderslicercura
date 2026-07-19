package com.tomppi.enderslicer.profile

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object CuraResolvedSettingsWriter {
    fun write(
        destination: File,
        modelFileName: String,
        resolved: CuraSliceSettingsResolver.Result,
    ) {
        require(modelFileName.endsWith(".stl", ignoreCase = true)) {
            "Resolved Cura model must be an STL file"
        }
        val modelDirectory = destination.parentFile
            ?: error("Resolved settings destination has no parent directory")
        val modelFile = File(modelDirectory, modelFileName)
        require(modelFile.isFile && modelFile.length() > 0L) {
            "Resolved Cura STL is missing or empty: ${modelFile.absolutePath}"
        }

        // EnderSlicer stores and displays transformed vertices in normal build-
        // plate coordinates (0..machine_width, 0..machine_depth). CuraEngine's
        // MeshGroup::finalize() adds half the build volume for machines whose
        // origin is not centred. Translate the staged binary STL itself into
        // CuraEngine's centred coordinate space. Doing this in the mesh bytes is
        // independent of which settings scope CuraEngine ultimately consults.
        val centerIsZero = resolved.globalValues["machine_center_is_zero"]
            ?.trim()
            ?.equals("true", ignoreCase = true)
            ?: false
        val engineOffsetX = if (centerIsZero) 0.0 else -requiredNumber(resolved.globalValues, "machine_width") / 2.0
        val engineOffsetY = if (centerIsZero) 0.0 else -requiredNumber(resolved.globalValues, "machine_depth") / 2.0
        translateBinaryStl(modelFile, engineOffsetX, engineOffsetY)

        // CuraEngine's command-line model loader constructs the single model
        // from the extruder stack. Copy all resolved per-mesh values into that
        // stack as well as retaining the model section. This guarantees support
        // interface/roof/floor settings are inherited even on an unpatched or
        // differently scoped CuraEngine loader.
        val extruderValues = JSONObject(resolved.extruderValues)
        resolved.modelValues.forEach { (key, value) -> extruderValues.put(key, value) }
        extruderValues
            .put("center_object", false)
            .put("mesh_position_x", 0)
            .put("mesh_position_y", 0)
            .put("mesh_position_z", 0)

        val modelValues = JSONObject(resolved.modelValues)
            .put("extruder_nr", 0)
            .put("center_object", false)
            .put("mesh_position_x", 0)
            .put("mesh_position_y", 0)
            .put("mesh_position_z", 0)

        val root = JSONObject()
            .put("global", JSONObject(resolved.globalValues))
            .put("extruder.0", extruderValues)
            .put(modelFileName, modelValues)
        destination.writeText(root.toString())
        check(destination.isFile && destination.length() > 0L) {
            "Unable to write resolved Cura settings"
        }
    }

    private fun translateBinaryStl(file: File, offsetX: Double, offsetY: Double) {
        if (offsetX == 0.0 && offsetY == 0.0) return
        RandomAccessFile(file, "rw").use { random ->
            require(random.length() >= STL_HEADER_BYTES) { "Staged STL is too small" }
            random.seek(80L)
            val countBytes = ByteArray(4).also(random::readFully)
            val triangleCount = ByteBuffer.wrap(countBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
            require(triangleCount > 0) { "Staged STL contains no triangles" }
            val expectedLength = STL_HEADER_BYTES + triangleCount.toLong() * STL_TRIANGLE_BYTES
            require(random.length() == expectedLength) {
                "Staged STL is not the expected binary format"
            }

            val coordinateBytes = ByteArray(12)
            repeat(triangleCount) { triangle ->
                val triangleStart = STL_HEADER_BYTES + triangle.toLong() * STL_TRIANGLE_BYTES
                repeat(3) { vertex ->
                    val coordinateOffset = triangleStart + STL_NORMAL_BYTES + vertex * 12L
                    random.seek(coordinateOffset)
                    random.readFully(coordinateBytes)
                    val coordinates = ByteBuffer.wrap(coordinateBytes).order(ByteOrder.LITTLE_ENDIAN)
                    val x = coordinates.float + offsetX.toFloat()
                    val y = coordinates.float + offsetY.toFloat()
                    val z = coordinates.float
                    val translated = ByteBuffer.allocate(12)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putFloat(x)
                        .putFloat(y)
                        .putFloat(z)
                        .array()
                    random.seek(coordinateOffset)
                    random.write(translated)
                }
            }
        }
    }

    private fun requiredNumber(values: Map<String, String>, key: String): Double {
        val raw = values[key] ?: error("Resolved Cura setting is missing: $key")
        val value = raw.toDoubleOrNull() ?: error("Resolved Cura setting is not numeric: $key=$raw")
        require(value.isFinite() && value > 0.0) { "Resolved Cura setting is invalid: $key=$raw" }
        return value
    }

    private const val STL_HEADER_BYTES = 84L
    private const val STL_TRIANGLE_BYTES = 50L
    private const val STL_NORMAL_BYTES = 12L
}
