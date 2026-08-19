package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.conical.ConicalRuntime
import com.tomppi.enderslicer.conical.ConicalSettings
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.nonplanar.CurviSlicerRuntime
import com.tomppi.enderslicer.nonplanar.NonPlanarSettings
import com.tomppi.enderslicer.profile.CuraEngineProfile
import com.tomppi.enderslicer.smartinfill.SmartInfillCuraContract
import com.tomppi.enderslicer.smartinfill.SmartInfillModifier
import com.tomppi.enderslicer.supportpaint.SupportPaintModifier
import com.tomppi.enderslicer.viewer.StlParser
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CuraEngineCommandTest {
    private val printer = PrinterDefinition(
        id = "modified_ender3_v2",
        name = "Modified Ender 3 V2",
        manufacturer = "Creality",
        widthMm = 230.0,
        depthMm = 230.0,
        heightMm = 250.0,
        buildPlateShape = "rectangular",
        originAtCenter = false,
        heatedBed = true,
        heatedBuildVolume = false,
        gcodeFlavor = "Marlin",
        extruders = 1,
        nozzleSizeMm = 0.4,
        filamentDiameterMm = 1.75,
        printheadXMinMm = -26.0,
        printheadYMinMm = -32.0,
        printheadXMaxMm = 32.0,
        printheadYMaxMm = 34.0,
        gantryHeightMm = 25.0,
        directDrive = true,
        dualZ = true,
        zProbe = true,
        bedLeveling = "UBL",
        ublMeshSlot = 0,
    )

    @Test
    fun resolvedCommandUsesOfficialResolvedSettingsInput() {
        val command = CuraEngineCommand.buildResolved(
            executablePath = "/native/libcuraengine_exec.so",
            definitionsDirectory = "/files/definitions",
            resolvedSettingsPath = "/files/resolved-settings.json",
            outputPath = "/files/current.gcode",
            threadCount = 4,
        )

        assertEquals(
            listOf(
                "/native/libcuraengine_exec.so",
                "slice",
                "-m4",
                "-d",
                "/files/definitions",
                "-r",
                "/files/resolved-settings.json",
                "-o",
                "/files/current.gcode",
            ),
            command,
        )
        assertFalse(command.contains("-l"))
        assertFalse(command.contains("-j"))
        assertFalse(command.contains("-s"))
    }

    @Test
    fun workerCountUsesPhysicalTopologyWhenRuntimeCpusetIsSmaller() {
        assertEquals(1, CuraEngineCommand.recommendedThreadCount(1, 1))
        assertEquals(4, CuraEngineCommand.recommendedThreadCount(4, 4))
        assertEquals(8, CuraEngineCommand.recommendedThreadCount(3, 8))
        assertEquals(8, CuraEngineCommand.recommendedThreadCount(16, 16))
    }

    @Test
    fun importedConfigurationCannotBypassDependencyResolution() {
        val error = runCatching {
            CuraEngineCommand.build(
                executablePath = "/native/libcuraengine_exec.so",
                definitionsDirectory = "/files/definitions",
                machineDefinitionPath = "/files/definitions/creality_ender3.def.json",
                extruderDefinitionPath = "/files/definitions/creality_base_extruder_0.def.json",
                modelPath = "/files/current.stl",
                outputPath = "/files/current.gcode",
                printer = printer,
                settings = SlicerSettings(),
                startGcode = "G28",
                endGcode = "M104 S0",
                profile = CuraEngineProfile(extruderValues = mapOf("speed_print" to "120")),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("dependency-resolved"))
    }

    @Test
    fun fallbackCommandAppliesRolesAndNeutralShellsAfterEachTargetMeshIsLoaded() {
        val directory = Files.createTempDirectory("cura-smart-infill-command").toFile()
        try {
            val model = File(directory, "model.stl")
            val low = File(directory, "modifier-35pct.stl")
            val high = File(directory, "modifier-70pct.stl")
            writeTriangle(model, 100f, 100f, 0.2f)
            writeTriangle(low, 101f, 101f, 0.4f)
            writeTriangle(high, 102f, 102f, 0.6f)

            val command = CuraEngineCommand.build(
                executablePath = "/native/libcuraengine_exec.so",
                definitionsDirectory = "/files/definitions",
                machineDefinitionPath = "/files/definitions/creality_ender3.def.json",
                extruderDefinitionPath = "/files/definitions/creality_base_extruder_0.def.json",
                modelPath = model.absolutePath,
                outputPath = File(directory, "current.gcode").absolutePath,
                printer = printer,
                settings = SlicerSettings(),
                startGcode = "G28",
                endGcode = "M104 S0",
                smartInfillModifiers = listOf(
                    SmartInfillModifier(70, high),
                    SmartInfillModifier(35, low),
                ),
                threadCount = 4,
            )

            val modelIndex = command.indexOf(model.absolutePath)
            val lowIndex = command.indexOf(low.absolutePath)
            val highIndex = command.indexOf(high.absolutePath)
            val outputIndex = command.indexOf("-o")
            assertTrue(modelIndex > 0)
            assertTrue(lowIndex > modelIndex)
            assertTrue(highIndex > lowIndex)
            assertTrue(outputIndex > highIndex)

            val modelSettings = command.subList(modelIndex + 1, lowIndex)
            assertTrue(modelSettings.contains("infill_mesh=false"))
            assertFalse(modelSettings.contains("infill_mesh=true"))
            assertFalse(modelSettings.contains("infill_sparse_density=35"))
            assertFalse(modelSettings.contains("wall_line_count=0"))

            val lowSettings = command.subList(lowIndex + 1, highIndex)
            assertTrue(lowSettings.contains("infill_mesh=true"))
            assertTrue(lowSettings.contains("infill_mesh_order=1"))
            assertTrue(lowSettings.contains("infill_sparse_density=35"))
            assertModifierShellNeutral(lowSettings)

            val highSettings = command.subList(highIndex + 1, outputIndex)
            assertTrue(highSettings.contains("infill_mesh=true"))
            assertTrue(highSettings.contains("infill_mesh_order=2"))
            assertTrue(highSettings.contains("infill_sparse_density=70"))
            assertModifierShellNeutral(highSettings)

            // Rotation/centering are load-time inputs and must precede each -l.
            assertEquals("-l", command[modelIndex - 1])
            assertEquals("-l", command[lowIndex - 1])
            assertEquals("-l", command[highIndex - 1])
            assertTrue(command.subList(0, modelIndex).contains("mesh_rotation_matrix=[[1,0,0],[0,1,0],[0,0,1]]"))
            assertTrue(command.subList(modelIndex, lowIndex).contains("mesh_rotation_matrix=[[1,0,0],[0,1,0],[0,0,1]]"))
            assertTrue(command.subList(lowIndex, highIndex).contains("mesh_rotation_matrix=[[1,0,0],[0,1,0],[0,0,1]]"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun curviSlicerWarpsPaintedSupportModifiersInsteadOfRejectingThem() {
        val directory = Files.createTempDirectory("curvi-paint-command").toFile()
        try {
            CurviSlicerRuntime.activate(NonPlanarSettings(enabled = true))
            val model = File(directory, "model.stl")
            val enforcer = File(directory, "support-enforcer.stl")
            writePyramid(model)
            writeFloatingPatch(enforcer, 4f, 4f, 0.8f)
            val sourceMaxZ = StlParser.parse(enforcer, enforcer.name).bounds.maxZ

            val command = CuraEngineCommand.build(
                executablePath = "/native/libcuraengine_exec.so",
                definitionsDirectory = "/files/definitions",
                machineDefinitionPath = "/files/definitions/creality_ender3.def.json",
                extruderDefinitionPath = "/files/definitions/creality_base_extruder_0.def.json",
                modelPath = model.absolutePath,
                outputPath = File(directory, "current.gcode").absolutePath,
                printer = printer,
                settings = SlicerSettings(),
                startGcode = "G28",
                endGcode = "M104 S0",
                supportPaintModifiers = listOf(SupportPaintModifier(isBlocker = false, file = enforcer)),
                threadCount = 4,
            )

            val enforcerIndex = command.indexOf(enforcer.absolutePath)
            assertTrue(enforcerIndex > 0)
            val enforcerSettings = command.subList(enforcerIndex + 1, command.indexOf("-o"))
            assertTrue(enforcerSettings.contains("support_mesh=true"))
            assertTrue(enforcerSettings.contains("anti_overhang_mesh=false"))

            val warped = StlParser.parse(enforcer, enforcer.name)
            assertTrue(
                "CurviSlicer must flatten the painted prism with the relief field",
                warped.bounds.maxZ < sourceMaxZ,
            )
        } finally {
            CurviSlicerRuntime.activate(NonPlanarSettings())
            directory.deleteRecursively()
        }
    }

    @Test
    fun conicalSlicingWarpsPaintedSupportModifiersInsteadOfRejectingThem() {
        val directory = Files.createTempDirectory("conical-paint-command").toFile()
        try {
            ConicalRuntime.activate(ConicalSettings(enabled = true))
            val model = File(directory, "model.stl")
            val enforcer = File(directory, "support-enforcer.stl")
            writeTriangle(model, 100f, 100f, 0.2f)
            writeFloatingPatch(enforcer, 100f, 100f, 1.0f)
            val source = StlParser.parse(enforcer, enforcer.name)

            val command = CuraEngineCommand.build(
                executablePath = "/native/libcuraengine_exec.so",
                definitionsDirectory = "/files/definitions",
                machineDefinitionPath = "/files/definitions/creality_ender3.def.json",
                extruderDefinitionPath = "/files/definitions/creality_base_extruder_0.def.json",
                modelPath = model.absolutePath,
                outputPath = File(directory, "current.gcode").absolutePath,
                printer = printer,
                settings = SlicerSettings(),
                startGcode = "G28",
                endGcode = "M104 S0",
                supportPaintModifiers = listOf(SupportPaintModifier(isBlocker = false, file = enforcer)),
                threadCount = 4,
            )

            val enforcerIndex = command.indexOf(enforcer.absolutePath)
            assertTrue(enforcerIndex > 0)
            val enforcerSettings = command.subList(enforcerIndex + 1, command.indexOf("-o"))
            assertTrue(enforcerSettings.contains("support_mesh=true"))

            val warped = StlParser.parse(enforcer, enforcer.name)
            assertTrue(
                "Conical slicing must lift the painted prism with the cone warp",
                warped.bounds.maxZ > source.bounds.maxZ,
            )
            assertTrue(
                "The outward cone warp must stretch the painted prism away from the model centre",
                warped.bounds.width > source.bounds.width,
            )
        } finally {
            ConicalRuntime.activate(ConicalSettings())
            directory.deleteRecursively()
        }
    }

    @Test
    fun adaptiveWallModifiersRemainRejectedWithNonPlanarSlicing() {
        val directory = Files.createTempDirectory("adaptive-wall-rejected").toFile()
        try {
            val model = File(directory, "model.stl")
            writeTriangle(model, 100f, 100f, 0.2f)
            val wallModifier = File(directory, "adaptive-wall.stl")
            writeTriangle(wallModifier, 101f, 101f, 0.4f)
            val adaptive = AdaptiveWallModifier(wallLineCount = 4, wallFlowPercent = 100.0, file = wallModifier)

            CurviSlicerRuntime.activate(NonPlanarSettings(enabled = true))
            val curviError = runCatching {
                CuraEngineCommand.build(
                    executablePath = "/native/libcuraengine_exec.so",
                    definitionsDirectory = "/files/definitions",
                    machineDefinitionPath = "/files/definitions/creality_ender3.def.json",
                    extruderDefinitionPath = "/files/definitions/creality_base_extruder_0.def.json",
                    modelPath = model.absolutePath,
                    outputPath = File(directory, "current.gcode").absolutePath,
                    printer = printer,
                    settings = SlicerSettings(),
                    startGcode = "G28",
                    endGcode = "M104 S0",
                    adaptiveWallModifiers = listOf(adaptive),
                    threadCount = 4,
                )
            }.exceptionOrNull()
            assertTrue(curviError is IllegalArgumentException)
            assertTrue(curviError?.message.orEmpty().contains("Adaptive walls"))

            CurviSlicerRuntime.activate(NonPlanarSettings())
            ConicalRuntime.activate(ConicalSettings(enabled = true))
            val conicalError = runCatching {
                CuraEngineCommand.build(
                    executablePath = "/native/libcuraengine_exec.so",
                    definitionsDirectory = "/files/definitions",
                    machineDefinitionPath = "/files/definitions/creality_ender3.def.json",
                    extruderDefinitionPath = "/files/definitions/creality_base_extruder_0.def.json",
                    modelPath = model.absolutePath,
                    outputPath = File(directory, "current.gcode").absolutePath,
                    printer = printer,
                    settings = SlicerSettings(),
                    startGcode = "G28",
                    endGcode = "M104 S0",
                    adaptiveWallModifiers = listOf(adaptive),
                    threadCount = 4,
                )
            }.exceptionOrNull()
            assertTrue(conicalError is IllegalArgumentException)
            assertTrue(conicalError?.message.orEmpty().contains("Adaptive walls"))
        } finally {
            CurviSlicerRuntime.activate(NonPlanarSettings())
            ConicalRuntime.activate(ConicalSettings())
            directory.deleteRecursively()
        }
    }

    private fun assertModifierShellNeutral(settings: List<String>) {
        SmartInfillCuraContract.modifierShellNeutralValues.forEach { (key, value) ->
            assertTrue("Missing modifier shell override $key=$value", settings.contains("$key=$value"))
        }
    }

    private fun writeTriangle(file: File, x: Float, y: Float, z: Float) {
        val bytes = ByteBuffer.allocate(84 + 50).order(ByteOrder.LITTLE_ENDIAN)
        bytes.position(80)
        bytes.putInt(1)
        bytes.putFloat(0f)
        bytes.putFloat(0f)
        bytes.putFloat(1f)
        bytes.putFloat(x)
        bytes.putFloat(y)
        bytes.putFloat(z)
        bytes.putFloat(x + 1f)
        bytes.putFloat(y)
        bytes.putFloat(z)
        bytes.putFloat(x)
        bytes.putFloat(y + 1f)
        bytes.putFloat(z)
        bytes.putShort(0)
        file.writeBytes(bytes.array())
    }

    /**
     * A 10 x 10 mm square pyramid whose apex sits at z = 1.2 over the centre:
     * tall enough for CurviSlicer's flat-base requirement and gentle enough
     * (about 13 degrees) to stay inside the default slope limit.
     */
    private fun writePyramid(file: File) {
        val apexX = 5f
        val apexY = 5f
        val apexZ = 1.2f
        val triangles = listOf(
            // Base, facing up.
            floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 10f, 10f, 0f),
            floatArrayOf(0f, 0f, 0f, 10f, 10f, 0f, 0f, 10f, 0f),
            // Sides, winding chosen so each normal has a positive Z component.
            floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, apexX, apexY, apexZ),
            floatArrayOf(10f, 0f, 0f, 10f, 10f, 0f, apexX, apexY, apexZ),
            floatArrayOf(10f, 10f, 0f, 0f, 10f, 0f, apexX, apexY, apexZ),
            floatArrayOf(0f, 10f, 0f, 0f, 0f, 0f, apexX, apexY, apexZ),
        )
        val bytes = ByteBuffer.allocate(84 + triangles.size * 50).order(ByteOrder.LITTLE_ENDIAN)
        bytes.position(80)
        bytes.putInt(triangles.size)
        for (triangle in triangles) {
            bytes.putFloat(0f).putFloat(0f).putFloat(1f)
            repeat(3) { vertex ->
                bytes.putFloat(triangle[vertex * 3])
                bytes.putFloat(triangle[vertex * 3 + 1])
                bytes.putFloat(triangle[vertex * 3 + 2])
            }
            bytes.putShort(0)
        }
        file.writeBytes(bytes.array())
    }

    /** Two triangles forming a small square patch at z within (x..x+2, y..y+2). */
    private fun writeFloatingPatch(file: File, x: Float, y: Float, z: Float) {
        val triangles = listOf(
            floatArrayOf(x, y, z, x + 2f, y, z, x, y + 2f, z),
            floatArrayOf(x + 2f, y, z, x + 2f, y + 2f, z, x, y + 2f, z),
        )
        val bytes = ByteBuffer.allocate(84 + triangles.size * 50).order(ByteOrder.LITTLE_ENDIAN)
        bytes.position(80)
        bytes.putInt(triangles.size)
        for (triangle in triangles) {
            bytes.putFloat(0f).putFloat(0f).putFloat(1f)
            repeat(3) { vertex ->
                bytes.putFloat(triangle[vertex * 3])
                bytes.putFloat(triangle[vertex * 3 + 1])
                bytes.putFloat(triangle[vertex * 3 + 2])
            }
            bytes.putShort(0)
        }
        file.writeBytes(bytes.array())
    }
}
