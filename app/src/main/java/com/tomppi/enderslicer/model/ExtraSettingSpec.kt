package com.tomppi.enderslicer.model

import android.content.res.AssetManager
import org.json.JSONObject

/** One browsable engine setting from the "all settings" catalog. */
data class ExtraSettingSpec(
    val key: String,
    val label: String,
    val description: String = "",
    val defaultValue: String? = null,
    /** True when the catalogue declares a numeric (float/int) type. */
    val numeric: Boolean = false,
) { val display: String get() = defaultValue?.let { "$key (default: $it)" } ?: key }

/**
 * Catalogs of every engine setting that can be added into the normal settings UI.
 * Cura: the bundled machine definitions (fdmprinter + children) carry the full
 * setting catalog with labels. Prusa: a generated catalog from the console's
 * --help-fff output.
 */
object AllSettingsCatalogs {

    /**
     * The generated Prusa catalog carries no value types, so no Prusa key is
     * marked numeric: a default like "0" is also how PrusaSlicer spells boolean
     * options, and treating those as numbers would reject "true"/"false".
     */
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

    /** Cura definitions declare "type": "float"/"int" per setting, so those keys are numeric. */
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
                    numeric = type == "float" || type == "int",
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

    /**
     * Prusa keys the app itself writes into the generated config; the All-settings
     * sheet marks them "(managed by the app)". This is the single source of truth:
     * it used to be duplicated in PrusaConfigWriter under different names
     * (support_material_threshold_angle, print_speed, fan_speed, retraction_length,
     * nozzle_diameter, ...), which made the hint lie about keys the writer never
     * writes. PrusaConfigWriterTest pins the list to the keys the writer renders.
     */
    val PRUSA_MANAGED_KEYS: Set<String> = setOf(
        "layer_height", "first_layer_height", "perimeters", "top_solid_layers", "bottom_solid_layers",
        "thin_walls", "external_perimeters_first", "fill_density", "fill_pattern", "arc_fitting",
        "skirts", "skirt_height", "skirt_distance", "brim_width", "overhangs",
        "first_layer_extrusion_width", "perimeter_extrusion_width", "external_perimeter_extrusion_width",
        "infill_extrusion_width", "solid_infill_extrusion_width", "top_infill_extrusion_width",
        "support_material", "support_material_threshold", "support_material_pattern",
        "support_material_interface_layers",
        "perimeter_speed", "external_perimeter_speed", "infill_speed", "first_layer_speed", "travel_speed",
        "retract_length", "retract_speed", "retract_before_travel", "retract_lift",
        "gcode_flavor", "machine_limits_usage", "machine_max_acceleration_x", "machine_max_acceleration_y",
        "machine_max_acceleration_z", "machine_max_acceleration_e", "machine_max_feedrate_x",
        "machine_max_feedrate_y", "machine_max_feedrate_z", "machine_max_feedrate_e",
        "machine_max_jerk_x", "machine_max_jerk_y", "machine_max_jerk_z", "machine_max_jerk_e",
        "bed_shape", "printer_model", "use_firmware_retraction", "start_gcode", "end_gcode",
        "filament_type", "filament_diameter", "temperature", "first_layer_temperature",
        "bed_temperature", "first_layer_bed_temperature", "max_fan_speed", "min_fan_speed",
        "extrusion_multiplier", "pressure_advance",
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

/**
 * One value rule set for "all settings" entries, shared by the settings store,
 * both engine config writers and the All-settings sheet.
 *
 * An extra setting is persisted and re-sent on every later slice as
 * `-s key=value` / JSON, so a blank or malformed value only ever surfaces as a
 * generic engine failure unless it is rejected where it is entered, stored and
 * sent to the engine.
 */
object ExtraSettingValidation {
    /** Longest value the engine transports accept. */
    const val MAX_VALUE_CHARS = 500

    private val KEY_PATTERN = Regex("[a-z][a-z0-9_]*")

    fun isValidKey(key: String): Boolean = key.matches(KEY_PATTERN)

    /** Human-readable reason [value] cannot be sent for [key], or null when it can. */
    fun rejectReason(key: String, value: String, spec: ExtraSettingSpec? = null): String? = when {
        value.isBlank() -> "the value is blank"
        value.length > MAX_VALUE_CHARS -> "the value is longer than $MAX_VALUE_CHARS characters"
        value != value.trim() -> "the value has leading or trailing whitespace"
        value.any { it.isISOControl() } -> "the value contains a line break or control character"
        '=' in value -> "the value contains '='"
        spec?.numeric == true && value.toDoubleOrNull()?.isFinite() != true -> "the value must be a number"
        else -> null
    }

    /** Throws with the offending key named when [value] cannot be sent to the engine. */
    fun requireValid(key: String, value: String, spec: ExtraSettingSpec? = null) {
        val reason = rejectReason(key, value, spec)
        require(reason == null) { "Extra setting \"$key\" is invalid: $reason" }
    }

    /** The subset of [values] that can be sent to the engine, using [catalog] value types. */
    fun validOnly(
        values: Map<String, String>,
        catalog: List<ExtraSettingSpec> = emptyList(),
    ): Map<String, String> {
        if (values.isEmpty()) return emptyMap()
        val specs = catalog.associateBy(ExtraSettingSpec::key)
        return values.filter { (key, value) -> isValidKey(key) && rejectReason(key, value, specs[key]) == null }
    }
}
