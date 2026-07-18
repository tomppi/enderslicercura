package com.tomppi.enderslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GcodeSanitizerTest {
    @Test
    fun rejectsNozzleShutdownBeforeFinalLayer() {
        val file = temporaryGcode(
            """
            ;TIME:6666
            ;Filament used: 0m
            ;MINX:2.14748e+06
            ;MINY:2.14748e+06
            ;MINZ:2.14748e+06
            ;MAXX:-2.14748e+06
            ;MAXY:-2.14748e+06
            ;MAXZ:-2.14748e+06
            ;LAYER_COUNT:3
            ;LAYER:0
            ;MESH:model.stl
            G1 X10 Y20 Z0.2 E1
            M104 S0
            ;LAYER:1
            G1 X11 Y21 Z0.4 E2
            ;LAYER:2
            M104 S0
            ;TIME_ELAPSED:12.4
            """.trimIndent(),
        )

        val error = runCatching { GcodeSanitizer.validateAndRepair(file) }.exceptionOrNull()
        assertTrue(error is GcodeSanitizer.UnsafeGcodeException)
        assertTrue(error?.message.orEmpty().contains("layer 0"))
    }

    @Test
    fun repairsHeaderAndAllowsShutdownOnFinalLayer() {
        val file = temporaryGcode(
            """
            ;TIME:6666
            ;Filament used: 0m
            ;MINX:2.14748e+06
            ;MINY:2.14748e+06
            ;MINZ:2.14748e+06
            ;MAXX:-2.14748e+06
            ;MAXY:-2.14748e+06
            ;MAXZ:-2.14748e+06
            M82
            G92 E0
            G1 X1 Y1 E30
            G92 E0
            ;LAYER_COUNT:2
            ;LAYER:0
            M104 S210
            ;MESH:model.stl
            G1 X100 Y110 Z0.2 E1
            G1 X120 Y130 Z0.4 E2
            ;MESH:NONMESH
            ;TIME_ELAPSED:42.2
            ;LAYER:1
            M104 S0
            """.trimIndent(),
        )

        val summary = GcodeSanitizer.validateAndRepair(file)
        val output = file.readText()

        assertEquals(2, summary.layerCount)
        assertEquals(43, summary.estimatedSeconds)
        assertEquals(32.0, summary.filamentMillimeters, 0.0001)
        assertTrue(output.contains(";TIME:43"))
        assertTrue(output.contains(";Filament used: 0.032m"))
        assertTrue(output.contains(";MINX:100"))
        assertTrue(output.contains(";MAXY:130"))
    }

    private fun temporaryGcode(content: String): File {
        return kotlin.io.path.createTempFile("enderslicer-test", ".gcode")
            .toFile()
            .apply { writeText(content) }
    }
}
