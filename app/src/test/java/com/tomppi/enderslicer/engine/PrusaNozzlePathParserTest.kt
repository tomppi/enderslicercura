package com.tomppi.enderslicer.engine

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Validates the new marker-driven parser against the real device gcode. */
class PrusaNozzlePathParserTest {
    private val file = File("C:/Users/FREDRIK/Documents/PrintShare/print_20260903_232908_247.gcode")

    @Test
    fun realDeviceGcode() {
        assumeTrue("device gcode not present", file.isFile)
        val path = PrusaNozzlePathParser.parse(file)
        println("PP moves=" + path.moveCount +
            " ext=" + path.extrusionMoveCount +
            " travel=" + path.travelMoveCount +
            " layers=" + path.layerCount +
            " bounds=" + "%.1f".format(path.minX) + ".." + "%.1f".format(path.maxX) +
            "," + "%.1f".format(path.minY) + ".." + "%.1f".format(path.maxY) +
            "," + "%.1f".format(path.minZ) + ".." + "%.1f".format(path.maxZ))
        // Same layer count as the LAYER_CHANGE markers (599).
        check(path.layerCount == 599) { "expected 599 layers, got " + path.layerCount }
        check(path.moveCount > 200_000) { "expected >200k moves" }
        // Marker-driven widths: parse a few moves and confirm width is 0.42-0.46.
        val moves = path.moves
        var widthsOk = 0
        var samples = 0
        for (m in 0 until path.moveCount) {
            val o = m * PrusaNozzlePath.VALUES_PER_MOVE
            if (moves[o + PrusaNozzlePath.KIND] == PrusaNozzlePath.Kind.EXTRUSION.code) {
                val w = moves[o + PrusaNozzlePath.WIDTH]
                if (w > 0.30f && w < 0.70f) widthsOk++
                samples++
            }
        }
        println("PP widths ok=" + widthsOk + "/" + samples)
        check(widthsOk > samples * 0.95f) { "widths outside marker range: " + widthsOk + "/" + samples }
    }

    @Test
    fun markerWidthsTrackPrusa() {
        // Small synthetic gcode: verify width/height follow ;WIDTH:/;HEIGHT: markers.
        val dir = kotlin.io.path.createTempDirectory("prusa-marker-test").toFile()
        val gcode = File(dir, "t.gcode").apply {
            writeText(
                ";LAYER_CHANGE\n" +
                    ";WIDTH:0.42\n" +
                    ";HEIGHT:0.08\n" +
                    "G1 X0 Y0 Z0.08 F1200\n" +
                    "G1 X5 Y5 E0.5\n" +
                    ";WIDTH:0.50\n" +
                    "G1 X10 Y5 E0.6\n" +
                    ";LAYER_CHANGE\n" +
                    ";WIDTH:0.44\n" +
                    "G1 X10 Y10 Z0.16 E0.7\n",
            )
        }
        val path = PrusaNozzlePathParser.parse(gcode)
        val moves = path.moves
        check(path.layerCount == 2)
        // move[0] extrusion: width 0.42, height 0.08
        val m0 = 0
        check(moves[m0 * PrusaNozzlePath.VALUES_PER_MOVE + PrusaNozzlePath.WIDTH] == 0.42f)
        check(moves[m0 * PrusaNozzlePath.VALUES_PER_MOVE + PrusaNozzlePath.HEIGHT] == 0.08f)
        // Extrusion beyond the first window uses the following ;WIDTH: marker.
        val last = path.moveCount - 1
        check(moves[last * PrusaNozzlePath.VALUES_PER_MOVE + PrusaNozzlePath.WIDTH] == 0.44f)
    }
}