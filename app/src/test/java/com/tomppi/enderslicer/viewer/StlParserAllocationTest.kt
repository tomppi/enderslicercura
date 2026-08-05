package com.tomppi.enderslicer.viewer

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StlParserAllocationTest {
    @Test
    fun parsesAsciiGrammarWithMixedCaseWhitespaceAndWrapperDescription() {
        val file = Files.createTempFile("enderslicer-ascii-", ".stl").toFile()
        try {
            file.writeText(
                """
                    SoLiD exported model with description
                    FaCeT NoRmAl 0 0 1
                      OuTeR LoOp
                        VeRtEx 10 20 30
                        VeRtEx 11 20 30
                        VeRtEx 10 21 30
                      EnDlOoP
                    EnDfAcEt
                    EnDsOlId exported model with description
                """.trimIndent(),
            )

            val mesh = StlParser.parse(file, displayName = "described.stl", maxTriangles = 10)

            assertEquals("described.stl", mesh.displayName)
            assertEquals(1, mesh.triangleCount)
            assertEquals(18, mesh.interleavedVertices.size)
            assertEquals(0f, mesh.bounds.minX, 0f)
            assertEquals(0f, mesh.bounds.minY, 0f)
            assertEquals(0f, mesh.bounds.minZ, 0f)
            assertEquals(1f, mesh.bounds.maxX, 0f)
            assertEquals(1f, mesh.bounds.maxY, 0f)
            assertEquals(0f, mesh.bounds.maxZ, 0f)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsFacetThatDoesNotContainExactlyThreeVertices() {
        val file = Files.createTempFile("enderslicer-invalid-ascii-", ".stl").toFile()
        try {
            file.writeText(
                """
                    solid invalid
                    facet normal 0 0 1
                    outer loop
                    vertex 0 0 0
                    vertex 1 0 0
                    endloop
                    endfacet
                    endsolid invalid
                """.trimIndent(),
            )

            assertThrows(IllegalArgumentException::class.java) {
                StlParser.parse(file, maxTriangles = 10)
            }
        } finally {
            file.delete()
        }
    }
}
