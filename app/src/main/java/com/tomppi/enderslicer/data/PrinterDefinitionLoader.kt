package com.tomppi.enderslicer.data

import android.content.res.AssetManager
import com.tomppi.enderslicer.model.PrinterDefinition
import org.json.JSONObject

object PrinterDefinitionLoader {
    fun loadModifiedEnder3V2(assets: AssetManager): PrinterDefinition {
        val text = assets.open("printers/modified_ender3_v2.json")
            .bufferedReader()
            .use { it.readText() }
        val json = JSONObject(text)

        return PrinterDefinition(
            id = json.getString("id"),
            name = json.getString("name"),
            manufacturer = json.getString("manufacturer"),
            widthMm = json.getDouble("machine_width"),
            depthMm = json.getDouble("machine_depth"),
            heightMm = json.getDouble("machine_height"),
            buildPlateShape = json.getString("build_plate_shape"),
            originAtCenter = json.getBoolean("origin_at_center"),
            heatedBed = json.getBoolean("heated_bed"),
            heatedBuildVolume = json.getBoolean("heated_build_volume"),
            gcodeFlavor = json.getString("gcode_flavor"),
            extruders = json.getInt("extruders"),
            nozzleSizeMm = json.getDouble("nozzle_size"),
            filamentDiameterMm = json.getDouble("filament_diameter"),
            printheadXMinMm = json.getDouble("printhead_x_min"),
            printheadYMinMm = json.getDouble("printhead_y_min"),
            printheadXMaxMm = json.getDouble("printhead_x_max"),
            printheadYMaxMm = json.getDouble("printhead_y_max"),
            gantryHeightMm = json.getDouble("gantry_height"),
            directDrive = json.getBoolean("direct_drive"),
            dualZ = json.getBoolean("dual_z"),
            zProbe = json.getBoolean("z_probe"),
            bedLeveling = json.getString("bed_leveling"),
            ublMeshSlot = json.getInt("ubl_mesh_slot"),
        )
    }
}
