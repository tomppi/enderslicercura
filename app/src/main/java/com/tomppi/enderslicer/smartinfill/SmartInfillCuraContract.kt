package com.tomppi.enderslicer.smartinfill

/**
 * Cura settings that belong to the printable model's outer shell must never be
 * inherited by Smart Infill modifier meshes. A modifier only partitions the
 * printable model's sparse-infill volume and selects regional infill settings.
 */
internal object SmartInfillCuraContract {
    val modifierShellNeutralValues: Map<String, String> = linkedMapOf(
        "wall_line_count" to "0",
        "wall_thickness" to "0",
        "top_layers" to "0",
        "bottom_layers" to "0",
        "initial_bottom_layers" to "0",
        "top_bottom_thickness" to "0",
        "roofing_layer_count" to "0",
        "flooring_layer_count" to "0",
    )

    fun neutralizeModifierShell(values: Map<String, String>): Map<String, String> =
        LinkedHashMap(values).apply { putAll(modifierShellNeutralValues) }
}
