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
        val modelValues = JSONObject()
            .put("extruder_nr", 0)
            // Cura desktop centers newly imported models on the build plate. A
            // raw CuraEngine -l load otherwise preserves the STL's source
            // coordinates and then adds the machine-centre offset, shifting the
            // model and changing build-plate-anchored infill patterns.
            .put("center_object", true)
            .put("mesh_position_x", 0)
            .put("mesh_position_y", 0)
            .put("mesh_position_z", 0)
        val root = JSONObject()
            .put("global", JSONObject(resolved.globalValues))
            .put("extruder.0", JSONObject(resolved.extruderValues))
            .put(modelFileName, modelValues)
        destination.writeText(root.toString())
        check(destination.isFile && destination.length() > 0L) {
            "Unable to write resolved Cura settings"
        }
    }
}
