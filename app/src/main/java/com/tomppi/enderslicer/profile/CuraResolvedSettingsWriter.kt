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

        // EnderSlicer writes a transformed STL whose vertices are already in
        // final build-plate coordinates. Do not center or drop it again.
        val extruderValues = JSONObject(resolved.extruderValues)
            .put("center_object", false)
            .put("mesh_position_x", 0)
            .put("mesh_position_y", 0)
            .put("mesh_position_z", 0)

        // CuraEngine reads settings such as support enable, interface/roof
        // enable, support angle and support distances from the mesh settings
        // stack. Preserve every value marked settable_per_mesh in the imported
        // definitions instead of leaving the model at definition defaults.
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
}
