#!/usr/bin/env python3
from __future__ import annotations

import re
from pathlib import Path


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    Path(path).write_text(text, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one exact replacement, found {count}")
    write(path, text.replace(old, new, 1))


def regex_once(path: str, pattern: str, replacement: str) -> None:
    text = read(path)
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.DOTALL)
    if count != 1:
        raise RuntimeError(f"{path}: expected one regex replacement, found {count}: {pattern}")
    write(path, updated)


package_path = "app/src/main/java/com/tomppi/enderslicer/smartinfill/SmartInfillPackage.kt"
replace_once(
    package_path,
    "private const val STL_HEADER_BYTES = 84L\nprivate const val STL_TRIANGLE_BYTES = 50L\n",
    """private const val STL_HEADER_BYTES = 84L
private const val STL_TRIANGLE_BYTES = 50L

internal data class BinaryStlBounds(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
) {
    val centerX: Double get() = (minX + maxX) * 0.5
    val centerY: Double get() = (minY + maxY) * 0.5
}
""",
)

regex_once(
    package_path,
    r"    /\*\* Copies immutable, validated modifier snapshots into a CuraEngine request workspace\. \*/\n"
    r"    fun stageModifiers\(destination: File\): List<SmartInfillModifier> \{.*?\n"
    r"    private fun copyStable\(source: File, target: File\) \{.*?\n"
    r"    \}\n(?=\})",
    """    /**
     * Stages filaSim modifier volumes in the displayed model's printer coordinates.
     * filaSim centers imported geometry around local X/Y zero and grounds it at
     * local Z zero. The analyzed STL is already placed on the build plate, so its
     * center/base translation must be restored before CuraEngine sees the volume.
     */
    fun stageModifiers(destination: File, analyzedSource: File): List<SmartInfillModifier> {
        require(destination.mkdirs() || destination.isDirectory) {
            "Unable to create the Smart Infill staging directory"
        }
        requireMatchesSource(analyzedSource)
        val triangleLimit = MeshTriangleLimits.current()
        val sourceBounds = binaryStlBounds(analyzedSource, triangleLimit)
        return modifiers.mapIndexed { index, modifier ->
            requireValidBinaryStl(modifier.file, triangleLimit)
            val target = File(destination, "smart-infill-${index + 1}-${modifier.densityPercent}pct.stl")
            translateStable(
                source = modifier.file,
                target = target,
                translationX = sourceBounds.centerX,
                translationY = sourceBounds.centerY,
                translationZ = sourceBounds.minZ,
            )
            requireValidBinaryStl(target, triangleLimit)
            SmartInfillModifier(modifier.densityPercent, target)
        }
    }

    private fun translateStable(
        source: File,
        target: File,
        translationX: Double,
        translationY: Double,
        translationZ: Double,
    ) {
        require(listOf(translationX, translationY, translationZ).all(Double::isFinite)) {
            "Smart Infill source placement is not finite"
        }
        val size = source.length()
        val modified = source.lastModified()
        RandomAccessFile(source, "r").use { input ->
            FileOutputStream(target).use { output ->
                val header = ByteArray(STL_HEADER_BYTES.toInt())
                input.readFully(header)
                output.write(header)
                val triangleCount = ByteBuffer.wrap(header, 80, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .int
                val triangle = ByteArray(STL_TRIANGLE_BYTES.toInt())
                repeat(triangleCount) {
                    input.readFully(triangle)
                    val buffer = ByteBuffer.wrap(triangle).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(3) { vertex ->
                        val offset = 12 + vertex * 12
                        val x = buffer.getFloat(offset).toDouble() + translationX
                        val y = buffer.getFloat(offset + 4).toDouble() + translationY
                        val z = buffer.getFloat(offset + 8).toDouble() + translationZ
                        require(listOf(x, y, z).all(Double::isFinite)) {
                            "Smart Infill modifier translation produced a non-finite vertex"
                        }
                        buffer.putFloat(offset, x.toFloat())
                        buffer.putFloat(offset + 4, y.toFloat())
                        buffer.putFloat(offset + 8, z.toFloat())
                    }
                    output.write(triangle)
                }
                output.fd.sync()
            }
        }
        check(target.length() == size && source.length() == size && source.lastModified() == modified) {
            target.delete()
            "A Smart Infill modifier changed while it was being staged"
        }
    }
""",
)

replace_once(
    package_path,
    """    fun clearActive() {
        activeFile.delete()
    }
""",
    """    fun clearActive() {
        activeFile.delete()
    }

    fun clearAll() {
        clearActive()
        packagesDirectory.listFiles().orEmpty().forEach { it.deleteRecursively() }
    }
""",
)

replace_once(
    package_path,
    "internal fun requireValidBinaryStl(file: File, maxTriangles: Int) {\n",
    """internal fun binaryStlBounds(file: File, maxTriangles: Int): BinaryStlBounds {
    requireValidBinaryStl(file, maxTriangles)
    var minX = Double.POSITIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var minZ = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    var maxZ = Double.NEGATIVE_INFINITY
    RandomAccessFile(file, "r").use { input ->
        input.seek(STL_HEADER_BYTES)
        val triangle = ByteArray(STL_TRIANGLE_BYTES.toInt())
        val triangleCount = ((file.length() - STL_HEADER_BYTES) / STL_TRIANGLE_BYTES).toInt()
        repeat(triangleCount) {
            input.readFully(triangle)
            val buffer = ByteBuffer.wrap(triangle).order(ByteOrder.LITTLE_ENDIAN)
            repeat(3) { vertex ->
                val offset = 12 + vertex * 12
                val x = buffer.getFloat(offset).toDouble()
                val y = buffer.getFloat(offset + 4).toDouble()
                val z = buffer.getFloat(offset + 8).toDouble()
                require(listOf(x, y, z).all(Double::isFinite)) {
                    "Smart Infill STL contains a non-finite vertex"
                }
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                minZ = minOf(minZ, z)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
                maxZ = maxOf(maxZ, z)
            }
        }
    }
    return BinaryStlBounds(minX, minY, minZ, maxX, maxY, maxZ)
}

internal fun requireValidBinaryStl(file: File, maxTriangles: Int) {
""",
)

runtime_path = "app/src/main/java/com/tomppi/enderslicer/smartinfill/SmartInfillRuntime.kt"
replace_once(
    runtime_path,
    """    fun stageModifiers(destination: File): List<SmartInfillModifier> =
        packageValue.stageModifiers(destination)
""",
    """    fun stageModifiers(destination: File, analyzedSource: File): List<SmartInfillModifier> =
        packageValue.stageModifiers(destination, analyzedSource)
""",
)

runner_path = "app/src/main/java/com/tomppi/enderslicer/engine/CuraEngineRunner.kt"
replace_once(
    runner_path,
    """            val smartInfillModifiers = smartInfillSnapshot
                ?.stageModifiers(workspace.directory)
                .orEmpty()
""",
    """            val smartInfillModifiers = smartInfillSnapshot
                ?.stageModifiers(workspace.directory, modelFile)
                .orEmpty()
""",
)

view_model_path = "app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt"
replace_once(
    view_model_path,
    "    fun moveModel(centerXmm: Double, centerYmm: Double, baseZmm: Double) {\n",
    """    fun clearBuildPlate() {
        val snapshot = _uiState.value
        if (!beginOperation("Clearing build plate…")) return
        val pendingSettingsWrite = settingsPersistenceJob
        val artifactId = snapshot.gcodePath?.let(::File)?.parentFile?.name
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    pendingSettingsWrite?.join()
                    workspaceStore.clear()
                    File(app.filesDir, "models").listFiles().orEmpty().forEach { it.delete() }
                    artifactId?.let(engine::releaseArtifact)
                }
            }.onSuccess {
                CalibrationSliceState.clear()
                sourceMesh = null
                importedScene = null
                plannedCalibrationEvents = emptyList()
                _uiState.update { current ->
                    current.copy(
                        mesh = null,
                        modelPath = null,
                        modelPlacement = null,
                        importedSceneTransformAvailable = false,
                        importedSceneModelName = null,
                        sliceResultId = null,
                        gcodePath = null,
                        baseGcodePath = null,
                        layerPreview = null,
                        layerEvents = emptyList(),
                        calibrationDescription = null,
                        estimatedPrintSeconds = null,
                        sliceLogPath = null,
                        sliceDurationMilliseconds = null,
                        warnings = current.warnings.filterNot {
                            it.startsWith("Imported Cura transform is for")
                        },
                        isBusy = false,
                        statusMessage = "Build plate cleared; import an STL to begin",
                    )
                }
            }.onFailure(::showOperationFailure)
        }
    }

    fun moveModel(centerXmm: Double, centerYmm: Double, baseZmm: Double) {
""",
)

integrated_path = "app/src/main/java/com/tomppi/enderslicer/ui/IntegratedEnderSlicerApp.kt"
replace_once(
    integrated_path,
    "    fun launchSmartInfill() {\n",
    """    fun clearBuildPlate() {
        if (slicerState.isBusy) {
            Toast.makeText(context, "Finish the current operation first", Toast.LENGTH_SHORT).show()
            return
        }
        smartInfillStore.clearActive()
        SmartInfillRuntime.activate(null)
        smartInfillPackage = null
        smartInfillOpen = false
        scope.launch(Dispatchers.IO) { smartInfillStore.clearAll() }
        slicerViewModel.clearBuildPlate()
    }

    fun launchSmartInfill() {
""",
)
replace_once(
    integrated_path,
    """    Box(modifier = Modifier.fillMaxSize()) {
        EnderSlicerApp(slicerViewModel)
        ExtendedFloatingActionButton(
            onClick = { octoPrintOpen = true },
""",
    """    Box(modifier = Modifier.fillMaxSize()) {
        EnderSlicerApp(slicerViewModel)
        ExtendedFloatingActionButton(
            onClick = ::clearBuildPlate,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 72.dp, start = 12.dp),
        ) {
            Text("Clear plate")
        }
        ExtendedFloatingActionButton(
            onClick = { octoPrintOpen = true },
""",
)

prepare_path = "scripts/prepare-filasim-assets.py"
replace_once(prepare_path, "ASSET_FORMAT = 4\n", "ASSET_FORMAT = 5\n")
replace_once(
    prepare_path,
    "def inject_bridge(index_file: pathlib.Path) -> None:\n",
    '''def patch_android_startup(app_file: pathlib.Path) -> None:
    text = app_file.read_text(encoding="utf-8")
    old = '    if (!s.sampleSkipped) void s.loadSampleModel();'
    marker = "EnderSlicer Android host supplies the exact displayed model"
    if marker not in text:
        if old not in text:
            raise RuntimeError("Unable to locate filaSim sample startup for Android patching")
        new = (
            f"    // {marker}.\\n"
            '    if (!new URLSearchParams(window.location.search).has("android") && !s.sampleSkipped) {\\n'
            "      void s.loadSampleModel();\\n"
            "    }"
        )
        text = text.replace(old, new, 1)
    app_file.write_text(text, encoding="utf-8")


def inject_bridge(index_file: pathlib.Path) -> None:
''',
)
replace_once(
    prepare_path,
    '''    web_root = source_root / "web"
    store_file = web_root / "src/store.ts"
    if not store_file.is_file():
        raise RuntimeError("Pinned filaSim source did not contain web/src/store.ts")
    patch_android_export(store_file)
''',
    '''    web_root = source_root / "web"
    store_file = web_root / "src/store.ts"
    app_file = web_root / "src/App.tsx"
    if not store_file.is_file() or not app_file.is_file():
        raise RuntimeError("Pinned filaSim source did not contain its Android patch targets")
    patch_android_export(store_file)
    patch_android_startup(app_file)
''',
)

replace_once("build.gradle.kts", "val filaSimFormat = 4\n", "val filaSimFormat = 5\n")

workflow_path = ".github/workflows/build.yml"
text = read(workflow_path)
text = text.replace("permissions:\n  contents: write\n\n", "", 1)
text, removed = re.subn(
    r"\n      - name: Apply verified Smart Infill follow-up patch\n.*?(?=\n      - uses: actions/setup-java@v4)",
    "",
    text,
    count=1,
    flags=re.DOTALL,
)
if removed != 1:
    raise RuntimeError("build workflow: temporary patch step was not found")
text = text.replace("grep -q 'format=4'", "grep -q 'format=5'", 1)
marker_line = "          grep -R -q 'state.optMode === \"binary\" ? state.solidPattern : state.pattern' .build/filasim-android/e7485ec22d4ebe8baca04190404fbb877c90e031/web/src/store.ts\n"
if text.count(marker_line) != 1:
    raise RuntimeError("build workflow: filaSim metadata marker was not found")
text = text.replace(
    marker_line,
    marker_line + "          grep -R -q 'EnderSlicer Android host supplies the exact displayed model' .build/filasim-android/e7485ec22d4ebe8baca04190404fbb877c90e031/web/src/App.tsx\n",
    1,
)
write(workflow_path, text)

test_path = Path("app/src/test/java/com/tomppi/enderslicer/smartinfill/SmartInfillModifierPlacementTest.kt")
test_path.parent.mkdir(parents=True, exist_ok=True)
test_path.write_text(
    '''package com.tomppi.enderslicer.smartinfill

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartInfillModifierPlacementTest {
    @Test
    fun localFilaSimModifierIsTranslatedBackToAnalyzedPrinterCoordinates() {
        val root = Files.createTempDirectory("smart-infill-placement").toFile()
        try {
            val source = File(root, "source.stl")
            val modifier = File(root, "modifier-40pct.stl")
            writeTriangle(
                source,
                listOf(
                    floatArrayOf(100f, 105f, 5f),
                    floatArrayOf(130f, 105f, 5f),
                    floatArrayOf(100f, 125f, 30f),
                ),
            )
            writeTriangle(
                modifier,
                listOf(
                    floatArrayOf(-10f, -5f, 1f),
                    floatArrayOf(10f, -5f, 1f),
                    floatArrayOf(-10f, 5f, 20f),
                ),
            )
            val packageValue = SmartInfillPackage(
                id = "filasim-test",
                directory = root,
                sourceName = "source.stl",
                sourceSha256 = sha256(source),
                baseDensityPercent = 10.0,
                pattern = "cubic",
                mode = "graded",
                perimeters = 2,
                lineWidthMm = 0.45,
                topBottomLayers = 5,
                layerHeightMm = 0.2,
                upstreamCommit = "e7485ec22d4ebe8baca04190404fbb877c90e031",
                modifiers = listOf(SmartInfillModifier(40, modifier)),
            )

            val staged = packageValue.stageModifiers(File(root, "request"), source).single().file
            val bounds = binaryStlBounds(staged, 10)
            assertEquals(105.0, bounds.minX, 0.0001)
            assertEquals(125.0, bounds.maxX, 0.0001)
            assertEquals(110.0, bounds.minY, 0.0001)
            assertEquals(120.0, bounds.maxY, 0.0001)
            assertEquals(6.0, bounds.minZ, 0.0001)
            assertEquals(25.0, bounds.maxZ, 0.0001)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeTriangle(file: File, vertices: List<FloatArray>) {
        val bytes = ByteBuffer.allocate(84 + 50).order(ByteOrder.LITTLE_ENDIAN)
        bytes.position(80)
        bytes.putInt(1)
        bytes.putFloat(0f)
        bytes.putFloat(0f)
        bytes.putFloat(1f)
        vertices.forEach { point -> point.forEach { value -> bytes.putFloat(value) } }
        bytes.putShort(0)
        file.writeBytes(bytes.array())
    }
}
''',
    encoding="utf-8",
)

print("Smart Infill follow-up edits applied")
