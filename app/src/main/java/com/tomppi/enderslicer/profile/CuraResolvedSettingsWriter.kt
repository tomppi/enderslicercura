package com.tomppi.enderslicer.profile

import org.json.JSONObject
import java.io.File

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

        // The staged STL already contains the final build-plate placement used by
        // the viewer. Keep those vertex bytes untouched. Reopening the binary STL
        // and subtracting half the bed size from every Float introduced a second
        // rounding step that could change very narrow infill islands. CuraEngine
        // applies these mesh offsets as part of its normal placement transform,
        // so the geometry is converted to its centred coordinate space without
        // rewriting and re-rounding the source coordinates.
        val centerIsZero = resolved.globalValues["machine_center_is_zero"]
            ?.trim()
            ?.equals("true", ignoreCase = true)
            ?: false
        val engineOffsetX = if (centerIsZero) 0.0 else -requiredNumber(resolved.globalValues, "machine_width") / 2.0
        val engineOffsetY = if (centerIsZero) 0.0 else -requiredNumber(resolved.globalValues, "machine_depth") / 2.0

        // CuraEngine's command-line model loader constructs the single model
        // from the extruder stack. Copy all resolved per-mesh values into that
        // stack as well as retaining the model section. The native resolved-model
        // patch also copies the model section onto the actual Mesh.
        val extruderValues = JSONObject(resolved.extruderValues)
        resolved.modelValues.forEach { (key, value) -> extruderValues.put(key, value) }
        extruderValues
            .put("center_object", false)
            .put("mesh_position_x", engineOffsetX)
            .put("mesh_position_y", engineOffsetY)
            .put("mesh_position_z", 0)

        val modelValues = JSONObject(resolved.modelValues)
            .put("extruder_nr", 0)
            .put("center_object", false)
            .put("mesh_position_x", engineOffsetX)
            .put("mesh_position_y", engineOffsetY)
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

    private fun requiredNumber(values: Map<String, String>, key: String): Double {
        val raw = values[key] ?: error("Resolved Cura setting is missing: $key")
        val value = raw.toDoubleOrNull() ?: error("Resolved Cura setting is not numeric: $key=$raw")
        require(value.isFinite() && value > 0.0) { "Resolved Cura setting is invalid: $key=$raw" }
        return value
    }
}
