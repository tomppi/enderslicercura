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

        // EnderSlicer now writes a transformed STL whose vertices are already in
        // final build-plate coordinates. Do not ask CuraEngine to center or drop
        // it again: doing so discards imported 3MF translations, floating-model
        // Z offsets, manual movement and lay-flat results.
        val extruderValues = JSONObject(resolved.extruderValues)
            .put("center_object", false)
            .put("mesh_position_x", 0)
            .put("mesh_position_y", 0)
            .put("mesh_position_z", 0)
        val modelValues = JSONObject()
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
