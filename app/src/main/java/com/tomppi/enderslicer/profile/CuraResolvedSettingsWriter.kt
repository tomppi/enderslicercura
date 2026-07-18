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

        // CuraEngine 5.11 beta loads the STL with extruder.0 as the mesh's
        // settings parent. MeshGroup::finalize() reads center_object and the
        // mesh-position values from that mesh settings stack, not from the
        // per-model JSON section. Put the placement settings in both scopes:
        // extruder.0 is effective for this single-extruder app, while the model
        // section remains correct metadata for CuraEngine's resolved format.
        val extruderValues = JSONObject(resolved.extruderValues)
            .put("center_object", true)
            .put("mesh_position_x", 0)
            .put("mesh_position_y", 0)
            .put("mesh_position_z", 0)
        val modelValues = JSONObject()
            .put("extruder_nr", 0)
            .put("center_object", true)
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
}
