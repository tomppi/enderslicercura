package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.viewer.StlMeshWriter
import com.tomppi.enderslicer.viewer.StlSliceTransform
import org.json.JSONObject
import java.io.File

internal object CuraResolvedSettingsWriter {
    fun write(
        destination: File,
        modelFileName: String,
        resolved: CuraSliceSettingsResolver.Result,
        modelTransform: StlSliceTransform? = null,
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

        // MainViewModel still writes the displayed transformed STL so the
        // profile-less fallback path remains unchanged. For resolved Cura
        // slicing, StlMeshWriter also stages the original geometry and affine in
        // the sibling model-placement directory. Replace only the temporary
        // resolved model copy with that source STL.
        val stagedDisplayedFile = modelDirectory.parentFile
            ?.let { cacheRoot -> File(cacheRoot, "model-placement/current-transformed.stl") }
        val stagedSource = stagedDisplayedFile
            ?.takeIf(File::isFile)
            ?.let(StlMeshWriter::resolvedSliceSource)
        if (stagedSource != null) {
            stagedSource.modelFile.copyTo(modelFile, overwrite = true)
            check(modelFile.length() == stagedSource.modelFile.length()) {
                "Unable to stage original STL geometry for direct Cura transformation"
            }
        }
        val effectiveTransform = modelTransform ?: stagedSource?.transform

        // CuraEngine applies mesh_rotation_matrix while reading each source STL
        // vertex and converts the result directly to its integer-micron geometry.
        // Translation is applied later by MeshGroup::finalize(). Supplying the
        // complete affine here avoids writing an intermediate transformed Float
        // STL, which was enough to alter threshold-sized infill islands.
        val centerIsZero = resolved.globalValues["machine_center_is_zero"]
            ?.trim()
            ?.equals("true", ignoreCase = true)
            ?: false
        val machineCenterX = if (centerIsZero) 0.0 else requiredNumber(resolved.globalValues, "machine_width") / 2.0
        val machineCenterY = if (centerIsZero) 0.0 else requiredNumber(resolved.globalValues, "machine_depth") / 2.0
        val linear = effectiveTransform?.linear ?: IDENTITY
        val enginePositionX = (effectiveTransform?.translationXmm ?: 0.0) - machineCenterX
        val enginePositionY = (effectiveTransform?.translationYmm ?: 0.0) - machineCenterY
        val enginePositionZ = effectiveTransform?.translationZmm ?: 0.0
        val rotationMatrix = matrixString(linear)

        // CuraEngine's command-line model loader constructs the single model
        // from the extruder stack. Copy all resolved per-mesh values into that
        // stack as well as retaining the model section. The native resolved-model
        // patch also copies the model section onto the actual Mesh.
        val extruderValues = JSONObject(resolved.extruderValues)
        resolved.modelValues.forEach { (key, value) -> extruderValues.put(key, value) }
        extruderValues
            .put("center_object", false)
            .put("mesh_rotation_matrix", rotationMatrix)
            .put("mesh_position_x", enginePositionX)
            .put("mesh_position_y", enginePositionY)
            .put("mesh_position_z", enginePositionZ)

        val modelValues = JSONObject(resolved.modelValues)
            .put("extruder_nr", 0)
            .put("center_object", false)
            .put("mesh_rotation_matrix", rotationMatrix)
            .put("mesh_position_x", enginePositionX)
            .put("mesh_position_y", enginePositionY)
            .put("mesh_position_z", enginePositionZ)

        val root = JSONObject()
            .put("global", JSONObject(resolved.globalValues))
            .put("extruder.0", extruderValues)
            .put(modelFileName, modelValues)
        destination.writeText(root.toString())
        check(destination.isFile && destination.length() > 0L) {
            "Unable to write resolved Cura settings"
        }
    }

    private fun matrixString(linear: List<Double>): String {
        require(linear.size == 9 && linear.all(Double::isFinite)) {
            "Resolved Cura model transform must contain nine finite values"
        }
        return linear.chunked(3).joinToString(prefix = "[", postfix = "]") { row ->
            row.joinToString(prefix = "[", postfix = "]", separator = ",")
        }
    }

    private fun requiredNumber(values: Map<String, String>, key: String): Double {
        val raw = values[key] ?: error("Resolved Cura setting is missing: $key")
        val value = raw.toDoubleOrNull() ?: error("Resolved Cura setting is not numeric: $key=$raw")
        require(value.isFinite() && value > 0.0) { "Resolved Cura setting is invalid: $key=$raw" }
        return value
    }

    private val IDENTITY = listOf(
        1.0, 0.0, 0.0,
        0.0, 1.0, 0.0,
        0.0, 0.0, 1.0,
    )
}
