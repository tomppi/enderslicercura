package com.tomppi.enderslicer.model

import android.content.res.AssetManager
import org.json.JSONObject

/** One browsable engine setting from the "all settings" catalog. */
data class ExtraSettingSpec(
    val key: String,
    val label: String,
    val description: String = "",
    val defaultValue: String? = null,
) { val display: String get() = defaultValue?.let { "$key (default: $it)" } ?: key }

/**
 * Catalogs of every engine setting that can be added into the normal settings UI.
 * Cura: the bundled machine definitions (fdmprinter + children) carry the full
 * setting catalog with labels. Prusa: a generated catalog from the console's
 * --help-fff output.
 */
object AllSettingsCatalogs {

    fun prusa(assets: AssetManager): List<ExtraSettingSpec> = runCatching {
        val json = JSONObject(assets.open("prusa/all-settings.json").bufferedReader().use { it.readText() })
        val settings = json.getJSONArray("settings")
        buildList {
            for (i in 0 until settings.length()) {
                val item = settings.getJSONObject(i)
                add(
                    ExtraSettingSpec(
                        key = item.getString("key"),
                        label = item.getString("key"),
                        description = item.optString("desc", ""),
                        defaultValue = item.optString("default", "").ifBlank { null },
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    fun cura(assets: AssetManager): List<ExtraSettingSpec> = runCatching {
        val result = linkedMapOf<String, ExtraSettingSpec>()
        val defs = listOf(
            "cura/definitions/fdmprinter.def.json",
            "cura/definitions/creality_base.def.json",
            "cura/definitions/creality_ender3.def.json",
        )
        for (path in defs) {
            val root = JSONObject(assets.open(path).bufferedReader().use { it.readText() })
            collectCuraSettings(root, result)
        }
        result.values.toList()
    }.getOrDefault(emptyList())

    private fun collectCuraSettings(node: JSONObject, out: MutableMap<String, ExtraSettingSpec>) {
        collectCuraMap(node.optJSONObject("settings"), out)
        collectCuraMap(node.optJSONObject("children"), out)
    }

    private fun collectCuraMap(map: JSONObject?, out: MutableMap<String, ExtraSettingSpec>) {
        if (map == null) return
        val keys = map.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val spec = map.optJSONObject(key) ?: continue
            val type = spec.optString("type", "")
            if (type == "category") {
                // Category containers nested inside settings/children.
                collectCuraSettings(spec, out)
                continue
            }
            if (spec.has("settings") || spec.has("children")) {
                collectCuraSettings(spec, out)
                continue
            }
            if (key !in out) {
                out[key] = ExtraSettingSpec(
                    key,
                    spec.optString("label", key).ifBlank { key },
                    spec.optString("description", ""),
                )
            }
        }
    }

    /** Cura keys the app always feeds the engine; an extra named here overrides the UI value (applied last). */
    val CURA_MANAGED_KEYS: Set<String> = setOf(
        "machine_width", "machine_depth", "machine_height", "machine_center_is_zero",
        "machine_heated_bed", "machine_nozzle_size", "machine_extruder_count",
        "gantry_height", "machine_head_with_fans_polygon", "machine_nozzle_offset_x",
        "machine_nozzle_offset_y", "machine_nozzle_tip_clearance", "machine_x_max", "machine_x_min",
        "machine_y_max", "machine_y_min", "machine_z_max", "layer_height", "layer_height_0",
    )

    /** Prusa keys the app always writes into the flat config; extras named here override them (last wins). */
    val PRUSA_MANAGED_KEYS: Set<String> = setOf(
        "layer_height", "first_layer_height", "perimeters", "top_solid_layers", "bottom_solid_layers",
        "thin_walls", "external_perimeters_first", "fill_density", "fill_pattern", "skirts",
        "skirt_height", "skirt_distance", "brim_width", "overhangs",
        "first_layer_extrusion_width", "perimeter_extrusion_width", "external_perimeter_extrusion_width",
        "infill_extrusion_width", "solid_infill_extrusion_width", "top_infill_extrusion_width",
        "support_material", "support_material_threshold_angle", "support_material_pattern",
        "support_material_interface", "support_material_interface_layers",
        "print_speed", "external_perimeter_speed", "infill_speed", "first_layer_speed", "travel_speed",
        "gcode_flavor", "start_gcode", "end_gcode", "filament_settings_id", "filament_diameter",
        "filament_type", "temperature", "first_layer_temperature", "bed_temperature",
        "first_layer_bed_temperature", "fan_speed", "extrusion_multiplier",
        "printer_settings_id", "printer_model", "bed_shape", "nozzle_diameter", "extruder_count",
        "use_firmware_retraction", "retraction_length", "retraction_speed",
        "retraction_min_travel", "retract_lift",
    )

    /** Cura keys that must never be shadowed because they define the machine envelope. */
    val CURA_BLOCKED_KEYS: Set<String> = setOf(
        "machine_width", "machine_depth", "machine_height", "gantry_height",
        "machine_head_with_fans_polygon", "machine_nozzle_offset_x", "machine_nozzle_offset_y",
        "machine_nozzle_tip_clearance", "machine_start_gcode", "machine_end_gcode",
        "machine_extruder_count", "machine_nozzle_size", "machine_heated_bed", "machine_center_is_zero",
    )

    /** Prusa keys that must never be shadowed (dedicated editors / machine envelope). */
    val PRUSA_BLOCKED_KEYS: Set<String> = setOf(
        "start_gcode", "end_gcode", "gcode_flavor", "bed_shape", "printer_settings_id",
        "filament_settings_id", "print_settings_id", "filament_diameter", "nozzle_diameter", "extruder_count",
    )

    /** Recursively collects settings from a definition root (testable without Android). */
    internal fun curaFromJson(root: JSONObject): List<ExtraSettingSpec> {
        val out = linkedMapOf<String, ExtraSettingSpec>()
        // The root exposes its categories inside "settings" itself.
        collectCuraSettings(root, out)
        return out.values.toList()
    }
}
