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

        // EnderSlicer stores and displays transformed vertices in normal build-
        // plate coordinates (0..machine_width, 0..machine_depth). CuraEngine's
        // MeshGroup::finalize() adds half the build volume for machines whose
        // origin is not centred. Counter that internal conversion so the final
        // G-code keeps the same coordinates as the viewer instead of receiving
        // a second +width/2,+depth/2 translation.
        val centerIsZero = resolved.globalValues["machine_center_is_zero"]
            ?.trim()
            ?.equals("true", ignoreCase = true)
            ?: false
        val engineOffsetX = if (centerIsZero) 0.0 else -requiredNumber(resolved.globalValues, "machine_width") / 2.0
        val engineOffsetY = if (centerIsZero) 0.0 else -requiredNumber(resolved.globalValues, "machine_depth") / 2.0

        val extruderValues = JSONObject(resolved.extruderValues)
            .put("center_object", false)
            .put("mesh_position_x", engineOffsetX)
            .put("mesh_position_y", engineOffsetY)
            .put("mesh_position_z", 0)

        // CuraEngine 5.11's resolved-JSON loader historically placed model
        // values on the mesh group only. EnderSlicer's native build patches the
        // loader to also copy these values onto the actual loaded mesh, which is
        // required for support interface/roof and all other per-mesh settings.
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
