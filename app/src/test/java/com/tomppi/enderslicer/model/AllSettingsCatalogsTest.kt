package com.tomppi.enderslicer.model

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class AllSettingsCatalogsTest {

    @Test
    fun curaCatalogWalksCategoriesRecursively() {
        val root = JSONObject(
            """
            {
              "name": "test",
              "settings": {
                "quality": {
                  "label": "Quality",
                  "type": "category",
                  "settings": {
                    "layer_height": {"label": "Layer Height", "type": "float"},
                    "wall_line_count": {"label": "Walls", "type": "int"}
                  }
                },
                "extruder": {
                  "label": "Extruder",
                  "type": "category",
                  "children": {
                    "extruder_settings": {
                      "label": "Extruder settings",
                      "children": {
                        "infill_spacing": {"label": "Infill Spacing", "type": "float"}
                      }
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        )

        val specs = AllSettingsCatalogs.curaFromJson(root)
        val keys = specs.map { it.key }
        assertTrue(keys.contains("layer_height"))
        assertTrue(keys.contains("wall_line_count"))
        assertTrue(keys.contains("infill_spacing"))
        assertTrue(specs.any { it.label == "Layer Height" })
    }
}
