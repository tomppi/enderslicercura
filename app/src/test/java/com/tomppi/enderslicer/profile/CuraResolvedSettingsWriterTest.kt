package com.tomppi.enderslicer.profile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CuraResolvedSettingsWriterTest {
    @Test
    fun centersModelAndKeepsResolvedScopesSeparate() {
        val destination = kotlin.io.path.createTempFile("enderslicer-resolved", ".json").toFile()
        val resolved = CuraSliceSettingsResolver.Result(
            globalValues = mapOf("machine_width" to "230"),
            extruderValues = mapOf("material_print_temperature" to "210"),
            expressionCount = 400,
            passes = 5,
        )

        CuraResolvedSettingsWriter.write(
            destination = destination,
            modelFileName = "current.stl",
            resolved = resolved,
        )

        val root = JSONObject(destination.readText())
        assertEquals("230", root.getJSONObject("global").getString("machine_width"))
        assertEquals("210", root.getJSONObject("extruder.0").getString("material_print_temperature"))
        val model = root.getJSONObject("current.stl")
        assertEquals(0, model.getInt("extruder_nr"))
        assertTrue(model.getBoolean("center_object"))
        assertEquals(0, model.getInt("mesh_position_x"))
        assertEquals(0, model.getInt("mesh_position_y"))
        assertEquals(0, model.getInt("mesh_position_z"))
    }
}
