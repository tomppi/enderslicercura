package com.tomppi.enderslicer.model

import android.content.res.AssetManager
import org.json.JSONObject

/** One browsable engine setting from the "all settings" catalog. */
data class ExtraSettingSpec(
    val key: String,
    val label: String,
    val description: String = "",
)

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
        val settings = node.optJSONObject("settings") ?: return
        val keys = settings.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val spec = settings.optJSONObject(key) ?: continue
            val type = spec.optString("type", "")
            if (type == "category") continue
            val label = spec.optString("label", key).ifBlank { key }
            if (key !in out) {
                out[key] = ExtraSettingSpec(key, label, spec.optString("description", ""))
            }
        }
        val children = node.optJSONObject("children") ?: return
        val childKeys = children.keys()
        while (childKeys.hasNext()) {
            val child = children.optJSONObject(childKeys.next()) ?: continue
            collectCuraSettings(child, out)
        }
    }
}
