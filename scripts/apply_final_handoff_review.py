#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = ROOT / path
    content = file.read_text()
    count = content.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    file.write_text(content.replace(old, new, 1))


def append_before(path: str, marker: str, insertion: str, label: str) -> None:
    replace_once(path, marker, insertion + marker, label)


# F02: snapshot the exact model/source/transform inside each CuraEngine request.
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/engine/CuraEngineRunner.kt",
    '''            require(isAvailable()) { status() }
            require(modelFile.isFile && modelFile.length() > 0L) { "The imported STL is no longer available" }
            copyStable(modelFile, workspace.model, "The model changed while it was being staged")
            throwIfInterrupted()

            val resolutionProfile = profile?.let(::completeDefinitionStack)
            val definitions = prepareDefinitions(workspace.directory, log, resolutionProfile)
''',
    '''            require(isAvailable()) { status() }
            require(modelFile.isFile && modelFile.length() > 0L) { "The imported STL is no longer available" }
            val resolutionProfile = profile?.let(::completeDefinitionStack)
            val modelTransform = if (resolutionProfile != null) {
                CuraResolvedSettingsWriter.copyResolvedSourceSnapshot(
                    stagedDisplayedFile = modelFile,
                    destination = workspace.model,
                    copyFile = { source, destination ->
                        copyStable(source, destination, "The original model changed while it was being staged")
                    },
                )
            } else {
                null
            }
            if (modelTransform == null) {
                copyStable(modelFile, workspace.model, "The model changed while it was being staged")
            }
            throwIfInterrupted()

            val definitions = prepareDefinitions(workspace.directory, log, resolutionProfile)
''',
    "request-local model snapshot",
)
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/engine/CuraEngineRunner.kt",
    '''                CuraResolvedSettingsWriter.write(
                    workspace.resolvedSettings,
                    workspace.model.name,
                    resolved,
                )
''',
    '''                CuraResolvedSettingsWriter.write(
                    destination = workspace.resolvedSettings,
                    modelFileName = workspace.model.name,
                    resolved = resolved,
                    modelTransform = modelTransform,
                )
''',
    "request-local model transform",
)
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/profile/CuraResolvedSettingsWriter.kt",
    '''        printerEnvelope.requireBinaryStlFits(modelFile)
        printerEnvelope.writeTo(File(modelDirectory, PrinterEnvelope.METADATA_FILE_NAME))

        val stagedDisplayedFile = findStagedDisplayedFile(modelDirectory)
        val stagedTransform = stagedDisplayedFile?.let { displayed ->
            copyResolvedSourceSnapshot(displayed, modelFile)
        }
        val effectiveTransform = modelTransform ?: stagedTransform
''',
    '''        printerEnvelope.requireBinaryStlFits(modelFile, modelTransform)
        printerEnvelope.writeTo(File(modelDirectory, PrinterEnvelope.METADATA_FILE_NAME))

        val effectiveTransform = modelTransform
''',
    "resolved writer explicit transform",
)
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/profile/CuraResolvedSettingsWriter.kt",
    '''    internal fun findStagedDisplayedFile(modelDirectory: File): File? =
        generateSequence(modelDirectory) { current -> current.parentFile }
            .take(MAX_CACHE_ANCESTORS)
            .map { ancestor -> File(ancestor, STAGED_DISPLAYED_MODEL_PATH) }
            .firstOrNull(File::isFile)

''',
    "",
    "remove ancestor model lookup",
)
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/profile/CuraResolvedSettingsWriter.kt",
    '''    private const val STAGED_DISPLAYED_MODEL_PATH = "model-placement/current-transformed.stl"
    private const val MAX_CACHE_ANCESTORS = 8

''',
    "",
    "remove shared staging constants",
)
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/profile/CuraResolvedSettingsWriter.kt",
    '''    ): StlSliceTransform? {
        val sourceFile = File(
''',
    '''    ): StlSliceTransform? {
        require(stagedDisplayedFile.isFile && stagedDisplayedFile.length() > 0L) {
            "The transformed STL is unavailable for resolved source staging"
        }
        val sourceFile = File(
''',
    "resolved source displayed validation",
)

# MainViewModel uses a unique private transformed-model directory per request.
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt",
    "import java.io.File\nimport java.util.concurrent.atomic.AtomicLong\n",
    "import java.io.File\nimport java.util.UUID\nimport java.util.concurrent.atomic.AtomicLong\n",
    "UUID import",
)
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt",
    '''                    val placement = automaticPlacement
                        ?: ModelPlacement.centeredOnBed(mesh, printer.widthMm, printer.depthMm)
''',
    '''                    val placement = automaticPlacement
                        ?: ModelPlacement.centeredOnBed(
                            mesh = mesh,
                            bedWidthMm = stateSnapshot.settings.machineWidthMm,
                            bedDepthMm = stateSnapshot.settings.machineDepthMm,
                            originAtCenter = stateSnapshot.settings.originAtCenter,
                        )
''',
    "effective imported model centering",
)
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt",
    '''    fun resetModelTransform() {
        changePlacement("Model transform reset and centered") { _, mesh ->
            ModelPlacement.centeredOnBed(mesh, printer.widthMm, printer.depthMm)
        }
    }
''',
    '''    fun resetModelTransform() {
        val settings = _uiState.value.settings
        changePlacement("Model transform reset and centered") { _, mesh ->
            ModelPlacement.centeredOnBed(
                mesh = mesh,
                bedWidthMm = settings.machineWidthMm,
                bedDepthMm = settings.machineDepthMm,
                originAtCenter = settings.originAtCenter,
            )
        }
    }
''',
    "effective reset centering",
)
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt",
    '''                withContext(Dispatchers.IO) {
                    val transformedFile = File(app.cacheDir, "model-placement/current-transformed.stl")
                    StlMeshWriter.writeBinary(transformedMesh, transformedFile)
                    if (plannedEventsSnapshot.isNotEmpty()) {
                        val transform = requireNotNull(
                            StlMeshWriter.resolvedSliceSource(transformedFile)?.transform,
                        ) { "Calibration slice transform is unavailable" }
                        CalibrationPlacementPolicy.requireAllowed(transform)
                    }
                    engine.slice(
                        modelFile = transformedFile,
                        printer = snapshot.printer,
                        settings = snapshot.settings,
                        startGcode = snapshot.startGcode,
                        endGcode = snapshot.endGcode,
                        profile = snapshot.engineProfile,
                        layerEvents = snapshot.layerEvents.filter { it.source == LayerEventSource.USER },
                        plannedLayerEvents = plannedEventsSnapshot,
                    )
                }
''',
    '''                withContext(Dispatchers.IO) {
                    val stagingRoot = File(app.cacheDir, "model-placement").apply {
                        check(mkdirs() || isDirectory) { "Unable to create the model staging directory" }
                    }
                    val stagingDirectory = File(stagingRoot, "slice-${UUID.randomUUID()}").apply {
                        check(mkdir()) { "Unable to create an isolated model staging directory" }
                    }
                    try {
                        val transformedFile = File(stagingDirectory, "transformed.stl")
                        StlMeshWriter.writeBinary(transformedMesh, transformedFile)
                        if (plannedEventsSnapshot.isNotEmpty()) {
                            val transform = requireNotNull(
                                StlMeshWriter.resolvedSliceSource(transformedFile)?.transform,
                            ) { "Calibration slice transform is unavailable" }
                            CalibrationPlacementPolicy.requireAllowed(transform)
                        }
                        engine.slice(
                            modelFile = transformedFile,
                            printer = snapshot.printer,
                            settings = snapshot.settings,
                            startGcode = snapshot.startGcode,
                            endGcode = snapshot.endGcode,
                            profile = snapshot.engineProfile,
                            layerEvents = snapshot.layerEvents.filter { it.source == LayerEventSource.USER },
                            plannedLayerEvents = plannedEventsSnapshot,
                        )
                    } finally {
                        stagingDirectory.deleteRecursively()
                    }
                }
''',
    "isolated model slice staging",
)
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt",
    '''                val placement = ModelPlacement.centeredOnBed(result.mesh, printer.widthMm, printer.depthMm)
''',
    '''                val placement = ModelPlacement.centeredOnBed(
                    mesh = result.mesh,
                    bedWidthMm = snapshot.settings.machineWidthMm,
                    bedDepthMm = snapshot.settings.machineDepthMm,
                    originAtCenter = snapshot.settings.originAtCenter,
                )
''',
    "effective calibration centering",
)

# F05: centered-origin placement and transform-aware streaming build-volume validation.
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/model/ModelPlacement.kt",
    '''        fun centeredOnBed(mesh: StlMesh, bedWidthMm: Double, bedDepthMm: Double): ModelPlacement = ModelPlacement(
            centerXmm = bedWidthMm / 2.0,
            centerYmm = bedDepthMm / 2.0,
            baseZmm = 0.0,
        )
''',
    '''        fun centeredOnBed(
            mesh: StlMesh,
            bedWidthMm: Double,
            bedDepthMm: Double,
            originAtCenter: Boolean = false,
        ): ModelPlacement {
            require(bedWidthMm.isFinite() && bedWidthMm > 0.0) { "Bed width must be positive" }
            require(bedDepthMm.isFinite() && bedDepthMm > 0.0) { "Bed depth must be positive" }
            return ModelPlacement(
                centerXmm = if (originAtCenter) 0.0 else bedWidthMm / 2.0,
                centerYmm = if (originAtCenter) 0.0 else bedDepthMm / 2.0,
                baseZmm = 0.0,
            )
        }
''',
    "centered-origin model placement",
)
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/engine/PrinterEnvelope.kt",
    "import com.tomppi.enderslicer.viewer.StlMesh\n",
    "import com.tomppi.enderslicer.viewer.StlMesh\nimport com.tomppi.enderslicer.viewer.StlSliceTransform\n",
    "slice transform import",
)
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/engine/PrinterEnvelope.kt",
    '''    fun requireBinaryStlFits(file: File) {
''',
    '''    fun requireBinaryStlFits(file: File, transform: StlSliceTransform? = null) {
''',
    "transform-aware binary preflight signature",
)
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/engine/PrinterEnvelope.kt",
    '''                    repeat(3) {
                        requirePoint(
                            x = buffer.float.toDouble(),
                            y = buffer.float.toDouble(),
                            z = buffer.float.toDouble(),
                            context = "Model vertex $vertexNumber",
                        )
                        vertexNumber++
                    }
''',
    '''                    repeat(3) {
                        val x = buffer.float.toDouble()
                        val y = buffer.float.toDouble()
                        val z = buffer.float.toDouble()
                        requirePoint(
                            x = transformedX(transform, x, y, z),
                            y = transformedY(transform, x, y, z),
                            z = transformedZ(transform, x, y, z),
                            context = "Model vertex $vertexNumber",
                        )
                        vertexNumber++
                    }
''',
    "transform-aware binary vertices",
)
append_before(
    "app/src/main/java/com/tomppi/enderslicer/engine/PrinterEnvelope.kt",
    '''    private fun requirePoint(x: Double, y: Double, z: Double, context: String) {
''',
    '''    private fun transformedX(transform: StlSliceTransform?, x: Double, y: Double, z: Double): Double =
        transform?.let { it.linear[0] * x + it.linear[1] * y + it.linear[2] * z + it.translationXmm } ?: x

    private fun transformedY(transform: StlSliceTransform?, x: Double, y: Double, z: Double): Double =
        transform?.let { it.linear[3] * x + it.linear[4] * y + it.linear[5] * z + it.translationYmm } ?: y

    private fun transformedZ(transform: StlSliceTransform?, x: Double, y: Double, z: Double): Double =
        transform?.let { it.linear[6] * x + it.linear[7] * y + it.linear[8] * z + it.translationZmm } ?: z

''',
    "binary transform helpers",
)

# F11: unknown firmware must never silently receive calibration commands.
append_before(
    "app/src/main/java/com/tomppi/enderslicer/engine/CalibrationFirmwareEncoder.kt",
    '''    fun requireDistinctCalibrationSequence(
''',
    '''    fun requireVerifiedCalibrationDialect() {
        if (dialect == FirmwareDialect.GENERIC) {
            throw UnsupportedFirmwareCommand(
                "$declaredFlavor is not a verified calibration firmware dialect",
            )
        }
    }

''',
    "verified calibration dialect",
)
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/calibration/CalibrationPlanValidator.kt",
    '''        val firmware = CalibrationFirmwareEncoder.fromFlavor(gcodeFlavor)
        firmware.requireDistinctCalibrationSequence(
''',
    '''        val firmware = CalibrationFirmwareEncoder.fromFlavor(gcodeFlavor)
        firmware.requireVerifiedCalibrationDialect()
        firmware.requireDistinctCalibrationSequence(
''',
    "reject unknown calibration firmware",
)

# F24: a sphere/corner fit uses sin(half-FOV), not tan(half-FOV).
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/viewer/SceneCameraFit.kt",
    "import kotlin.math.sqrt\nimport kotlin.math.tan\n",
    "import kotlin.math.sin\nimport kotlin.math.sqrt\nimport kotlin.math.tan\n",
    "scene fit sin import",
)
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/viewer/SceneCameraFit.kt",
    "        val fittedDistance = radius / tan(limitingHalfFov) * margin\n",
    "        val fittedDistance = radius / sin(limitingHalfFov) * margin\n",
    "conservative scene fit",
)
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/viewer/LayerPreviewSurfaceView.kt",
    "import kotlin.math.sqrt\nimport kotlin.math.tan\n",
    "import kotlin.math.sin\nimport kotlin.math.sqrt\nimport kotlin.math.tan\n",
    "preview fit sin import",
)
replace_once(
    "app/src/main/java/com/tomppi/enderslicer/viewer/LayerPreviewSurfaceView.kt",
    "        val fitted = radius / tan(limitingHalfFov) * CAMERA_MARGIN\n",
    "        val fitted = radius / sin(limitingHalfFov) * CAMERA_MARGIN\n",
    "conservative preview fit",
)

# Focused regressions.
writer_test = ROOT / "app/src/test/java/com/tomppi/enderslicer/profile/CuraResolvedSettingsWriterWorkspaceTest.kt"
writer_test.write_text('''package com.tomppi.enderslicer.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CuraResolvedSettingsWriterWorkspaceTest {
    @Test
    fun resolvedSourceAndTransformAreCopiedAsOneStableSnapshot() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-model-snapshot").toFile()
        val displayed = File(directory, "transformed.stl").apply { writeText("displayed") }
        val source = File(directory, "transformed.slice-source.stl").apply {
            writeBytes(ByteArray(84) { index -> index.toByte() })
        }
        File(directory, "transformed.slice-transform.json").writeText(transformJson())
        val destination = File(directory, "request/model.stl").apply { parentFile?.mkdirs() }

        val transform = CuraResolvedSettingsWriter.copyResolvedSourceSnapshot(displayed, destination)

        assertEquals(source.readBytes().toList(), destination.readBytes().toList())
        assertEquals(10.0, transform?.translationXmm ?: Double.NaN, 0.0)
        assertEquals(20.0, transform?.translationYmm ?: Double.NaN, 0.0)
        assertEquals(3.0, transform?.translationZmm ?: Double.NaN, 0.0)
    }

    @Test
    fun missingSidecarsReturnNullWithoutTouchingTheRequestModel() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-model-no-sidecars").toFile()
        val displayed = File(directory, "transformed.stl").apply { writeText("displayed") }
        val destination = File(directory, "request/model.stl").apply {
            parentFile?.mkdirs()
            writeText("existing-request-model")
        }

        val transform = CuraResolvedSettingsWriter.copyResolvedSourceSnapshot(displayed, destination)

        assertNull(transform)
        assertEquals("existing-request-model", destination.readText())
    }

    @Test
    fun changingTransformDuringSourceCopyRejectsTheSnapshot() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-model-snapshot-race").toFile()
        val displayed = File(directory, "transformed.stl").apply { writeText("displayed") }
        File(directory, "transformed.slice-source.stl").writeBytes(ByteArray(84))
        val transformFile = File(directory, "transformed.slice-transform.json").apply {
            writeText(transformJson())
        }
        val destination = File(directory, "request/model.stl").apply { parentFile?.mkdirs() }

        val error = runCatching {
            CuraResolvedSettingsWriter.copyResolvedSourceSnapshot(displayed, destination) { source, target ->
                source.copyTo(target, overwrite = true)
                transformFile.appendText(" ")
            }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
    }

    private fun transformJson(): String = """
        {
          "version": 1,
          "linear": [1,0,0,0,1,0,0,0,1],
          "translationXmm": 10,
          "translationYmm": 20,
          "translationZmm": 3
        }
    """.trimIndent()
}
''')

append_before(
    "app/src/test/java/com/tomppi/enderslicer/model/ModelPlacementTest.kt",
    '''    @Test
    fun imported3mfTransformUsesEmbeddedTargetBounds() {
''',
    '''    @Test
    fun centeredOriginPlacesTheModelAroundZero() {
        val mesh = triangleMesh(
            floatArrayOf(
                -4f, 8f, -2f,
                6f, 8f, -2f,
                -4f, 18f, 3f,
            ),
        )

        val transformed = ModelPlacement.centeredOnBed(
            mesh = mesh,
            bedWidthMm = 230.0,
            bedDepthMm = 230.0,
            originAtCenter = true,
        ).transformed(mesh)

        assertEquals(0.0, transformed.bounds.centerX.toDouble(), 1e-5)
        assertEquals(0.0, transformed.bounds.centerY.toDouble(), 1e-5)
        assertEquals(0.0, transformed.bounds.minZ.toDouble(), 1e-5)
    }

''',
    "centered-origin placement regression",
)
append_before(
    "app/src/test/java/com/tomppi/enderslicer/engine/PrinterEnvelopeTest.kt",
    '''    @Test
    fun sanitizerRejectsPositiveSupportAdhesionAndStartupExtrusionsOutsideThePlate() {
''',
    '''    @Test
    fun binaryStlPreflightAppliesTheRequestTransformBeforeBoundsChecking() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-envelope-transform").toFile()
        val source = File(directory, "source.stl")
        writeBinaryStl(source, listOf(
            floatArrayOf(1000f, 1000f, 10f),
            floatArrayOf(1010f, 1000f, 10f),
            floatArrayOf(1000f, 1010f, 20f),
        ))
        val transform = com.tomppi.enderslicer.viewer.StlSliceTransform(
            linear = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            translationXmm = -990.0,
            translationYmm = -990.0,
            translationZmm = -10.0,
        )

        rectangular(false).requireBinaryStlFits(source, transform)
        val error = runCatching { rectangular(false).requireBinaryStlFits(source) }.exceptionOrNull()

        assertTrue(error is PrinterEnvelope.OutsideBuildVolumeException)
    }

''',
    "transform-aware envelope regression",
)
append_before(
    "app/src/test/java/com/tomppi/enderslicer/calibration/CalibrationPlanValidatorTest.kt",
    '''    @Test
    fun acceptsDistinctKlipperPressureAdvanceLevels() {
''',
    '''    @Test
    fun rejectsUnknownFirmwareEvenForStandardCalibrationCommands() {
        val error = runCatching {
            CalibrationPlanValidator.validate(
                spec = validSpec(CalibrationTestType.TEMPERATURE),
                gcodeFlavor = "UnknownFirmware",
            )
        }.exceptionOrNull()

        assertTrue(error is CalibrationFirmwareEncoder.UnsupportedFirmwareCommand)
    }

''',
    "unknown firmware calibration regression",
)
replace_once(
    "app/src/test/java/com/tomppi/enderslicer/viewer/SceneCameraFitTest.kt",
    "import kotlin.math.atan\nimport kotlin.math.tan\n",
    "import kotlin.math.atan\nimport kotlin.math.sin\nimport kotlin.math.tan\n",
    "camera test sin import",
)
replace_once(
    "app/src/test/java/com/tomppi/enderslicer/viewer/SceneCameraFitTest.kt",
    "        assertTrue(fit.radius / fit.distance < tan(horizontalHalf))\n",
    "        assertTrue(fit.distance >= fit.radius / sin(horizontalHalf) * 1.15f)\n",
    "portrait camera regression",
)
replace_once(
    "app/src/test/java/com/tomppi/enderslicer/viewer/SceneCameraFitTest.kt",
    "            assertTrue(fit.radius / fit.distance < tan(limitingHalf))\n",
    "            assertTrue(fit.distance >= fit.radius / sin(limitingHalf) * 1.15f)\n",
    "displaced camera regression",
)

print("Applied final EnderSlicerCura handoff review fixes")
