#!/usr/bin/env python3
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text()


def write(path: str, content: str) -> None:
    (ROOT / path).write_text(content)


def replace_once(content: str, old: str, new: str, label: str) -> str:
    count = content.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return content.replace(old, new, 1)


def regex_once(content: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, content, count=1, flags=re.DOTALL)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one regex match, found {count}")
    return updated


def patch_main_view_model() -> None:
    path = "app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt"
    content = read(path)

    content = replace_once(
        content,
        """import com.tomppi.enderslicer.calibration.CalibrationSliceState
import com.tomppi.enderslicer.calibration.CalibrationTowerGenerator
import com.tomppi.enderslicer.calibration.CalibrationTowerSpec
import com.tomppi.enderslicer.data.AppStateStore
import com.tomppi.enderslicer.data.PrinterDefinitionLoader
""",
        """import com.tomppi.enderslicer.calibration.CalibrationPlacementPolicy
import com.tomppi.enderslicer.calibration.CalibrationPlanValidator
import com.tomppi.enderslicer.calibration.CalibrationSliceState
import com.tomppi.enderslicer.calibration.CalibrationTestType
import com.tomppi.enderslicer.calibration.CalibrationTowerGenerator
import com.tomppi.enderslicer.calibration.CalibrationTowerSpec
import com.tomppi.enderslicer.data.AppStateStore
import com.tomppi.enderslicer.data.BuiltInGcode
import com.tomppi.enderslicer.data.PrinterDefinitionLoader
import com.tomppi.enderslicer.data.WorkspaceStateStore
""",
        "MainViewModel imports",
    )

    content = replace_once(
        content,
        """    private data class RestoredImport(
        val config: ImportedCuraConfig?,
        val settings: SlicerSettings,
        val scene: CuraProjectScene?,
    )

""",
        """    private data class RestoredImport(
        val config: ImportedCuraConfig?,
        val settings: SlicerSettings,
        val scene: CuraProjectScene?,
        val workspace: RestoredWorkspace?,
    )

    private data class RestoredWorkspace(
        val snapshot: WorkspaceStateStore.Snapshot,
        val source: StlMesh,
        val transformed: StlMesh,
        val plannedEvents: List<PlannedLayerEvent>,
        val fingerprintMatches: Boolean,
    )

""",
        "MainViewModel restored workspace models",
    )

    content = replace_once(
        content,
        """    private val engine = CuraEngineRunner(app)
    private val stateStore = AppStateStore(app)
    private val initialStartGcode = readAsset("gcode/start.gcode")
    private val initialEndGcode = readAsset("gcode/end.gcode")
""",
        """    private val engine = CuraEngineRunner(app)
    private val stateStore = AppStateStore(app)
    private val workspaceStore = WorkspaceStateStore(app)
    private val initialStartGcode = BuiltInGcode.START
    private val initialEndGcode = BuiltInGcode.END
""",
        "MainViewModel nonblocking defaults",
    )

    content = replace_once(
        content,
        """        retainReadPermission(uri)
        val sceneSnapshot = importedScene
        val previousModelPath = _uiState.value.modelPath
""",
        """        val sceneSnapshot = importedScene
        val stateSnapshot = _uiState.value
        val previousModelPath = stateSnapshot.modelPath
""",
        "STL import main-thread provider call",
    )
    content = replace_once(
        content,
        """                val (mesh, modelFile) = withContext(Dispatchers.IO) {
                    val triangleLimit = MeshTriangleLimits.current()
""",
        """                val (mesh, modelFile) = withContext(Dispatchers.IO) {
                    retainReadPermission(uri)
                    val triangleLimit = MeshTriangleLimits.current()
""",
        "STL import provider call in IO",
    )
    content = replace_once(
        content,
        """                withContext(Dispatchers.Default) {
                    val automaticPlacement = sceneSnapshot
""",
        """                val prepared = withContext(Dispatchers.Default) {
                    val automaticPlacement = sceneSnapshot
""",
        "STL prepared result",
    )
    content = replace_once(
        content,
        """                    PreparedModelImport(
                        source = mesh,
                        transformed = transformed,
                        modelFile = modelFile,
                        placement = placement,
                        automaticImportedPlacement = automaticPlacement != null,
                        mismatchWarning = mismatchWarning,
                    )
                }
            }.onSuccess { prepared ->
""",
        """                    PreparedModelImport(
                        source = mesh,
                        transformed = transformed,
                        modelFile = modelFile,
                        placement = placement,
                        automaticImportedPlacement = automaticPlacement != null,
                        mismatchWarning = mismatchWarning,
                    )
                }
                withContext(Dispatchers.IO) {
                    workspaceStore.save(
                        workspaceSnapshot(
                            modelFile = prepared.modelFile,
                            displayName = prepared.source.displayName,
                            placement = prepared.placement,
                            plannedEvents = emptyList(),
                            calibrationDescription = null,
                            state = stateSnapshot,
                        ),
                    )
                }
                prepared
            }.onSuccess { prepared ->
""",
        "STL workspace commit",
    )

    content = replace_once(
        content,
        """        retainReadPermission(uri)
        val sourceName = displayName(uri)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    stageAndParseImport(uri, AppStateStore.KIND_PROFILE, sourceName) { file ->
""",
        """        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    retainReadPermission(uri)
                    val sourceName = displayName(uri)
                    stageAndParseImport(uri, AppStateStore.KIND_PROFILE, sourceName) { file ->
""",
        "Profile provider work in IO",
    )
    content = replace_once(
        content,
        """        retainReadPermission(uri)
        val sourceName = displayName(uri)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    stageAndParseImport(
""",
        """        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    retainReadPermission(uri)
                    val sourceName = displayName(uri)
                    stageAndParseImport(
""",
        "Project provider work in IO",
    )

    content = content.replace("            stateStore.saveSettings(changed)\n", "            persistSettings(changed)\n")
    content = content.replace("        stateStore.saveSettings(restored)\n", "        persistSettings(restored)\n")

    content = replace_once(
        content,
        """                    StlMeshWriter.writeBinary(transformedMesh, transformedFile)
                    engine.slice(
""",
        """                    StlMeshWriter.writeBinary(transformedMesh, transformedFile)
                    if (plannedEventsSnapshot.isNotEmpty()) {
                        val transform = requireNotNull(
                            StlMeshWriter.resolvedSliceSource(transformedFile)?.transform,
                        ) { "Calibration slice transform is unavailable" }
                        CalibrationPlacementPolicy.requireAllowed(transform)
                    }
                    engine.slice(
""",
        "Calibration placement preflight",
    )
    content = replace_once(
        content,
        """                    current.copy(
                        gcodePath = result.gcodeFile.absolutePath,
""",
        """                    current.copy(
                        sliceResultId = result.artifactId,
                        gcodePath = result.gcodeFile.absolutePath,
""",
        "Slice result identity",
    )

    content = replace_once(
        content,
        """                val result = withContext(Dispatchers.Default) {
                    CalibrationTowerGenerator.generate(spec, snapshot.settings.retractionSpeedMmPerSecond)
                }
""",
        """                val result = withContext(Dispatchers.Default) {
                    CalibrationPlanValidator.validate(
                        spec = spec,
                        gcodeFlavor = snapshot.settings.gcodeFlavor,
                        retractionSpeedMmPerSecond = snapshot.settings.retractionSpeedMmPerSecond,
                    )
                    CalibrationTowerGenerator.generate(spec, snapshot.settings.retractionSpeedMmPerSecond)
                }
""",
        "Calibration effective command validation",
    )
    content = replace_once(
        content,
        """                PreparedCalibration(result, transformed, modelFile, placement)
            }.onSuccess { prepared ->
""",
        """                val prepared = PreparedCalibration(result, transformed, modelFile, placement)
                withContext(Dispatchers.IO) {
                    workspaceStore.save(
                        workspaceSnapshot(
                            modelFile = prepared.modelFile,
                            displayName = prepared.result.mesh.displayName,
                            placement = prepared.placement,
                            plannedEvents = prepared.result.plannedEvents,
                            calibrationDescription = prepared.result.description,
                            state = snapshot,
                        ),
                    )
                }
                prepared
            }.onSuccess { prepared ->
""",
        "Calibration workspace commit",
    )

    content = replace_once(
        content,
        """                    current.copy(
                        gcodePath = result.gcodeFile.absolutePath,
                        layerPreview = result.layerPreview,
""",
        """                    current.copy(
                        sliceResultId = result.artifactId,
                        gcodePath = result.gcodeFile.absolutePath,
                        baseGcodePath = result.baseGcodeFile.absolutePath,
                        layerPreview = result.layerPreview,
""",
        "Layer event result identity",
    )

    content = replace_once(
        content,
        """        val original = sourceMesh
        val current = _uiState.value.modelPlacement
""",
        """        val original = sourceMesh
        val stateSnapshot = _uiState.value
        val current = stateSnapshot.modelPlacement
        val modelPath = stateSnapshot.modelPath
""",
        "Placement snapshot",
    )
    content = replace_once(
        content,
        """            runCatching {
                withContext(Dispatchers.Default) {
                    val changed = transform(current, original)
                    changed to changed.transformed(original)
                }
            }.onSuccess { (changed, transformed) ->
""",
        """            runCatching {
                val prepared = withContext(Dispatchers.Default) {
                    val changed = transform(current, original)
                    val transformed = changed.transformed(original)
                    if (plannedCalibrationEvents.isNotEmpty()) {
                        CalibrationPlacementPolicy.requireAllowed(
                            requireNotNull(transformed.slicingTransform) {
                                "Calibration placement transform is unavailable"
                            },
                        )
                    }
                    changed to transformed
                }
                val durableModel = requireNotNull(modelPath)?.let(::File)
                    ?: error("The active model path is unavailable")
                withContext(Dispatchers.IO) {
                    workspaceStore.save(
                        workspaceSnapshot(
                            modelFile = durableModel,
                            displayName = original.displayName,
                            placement = prepared.first,
                            plannedEvents = plannedCalibrationEvents,
                            calibrationDescription = stateSnapshot.calibrationDescription,
                            state = stateSnapshot,
                        ),
                    )
                }
                prepared
            }.onSuccess { (changed, transformed) ->
""",
        "Placement policy and persistence",
    )

    restore_pattern = r"""                    val saved = stateStore\.savedImport\(\)
                    if \(saved == null\) \{
                        RestoredImport\(null, stateStore\.restoreSettings\(SlicerSettings\(\)\), null\)
                    \} else \{
                        val config = saved\.file\.inputStream\(\)\.use \{ input ->
                            when \(saved\.kind\) \{
                                AppStateStore\.KIND_PROJECT -> CuraProjectParser\.parse\(input, saved\.displayName, SlicerSettings\(\)\)
                                AppStateStore\.KIND_PROFILE -> CuraProfileParser\.parse\(input, saved\.displayName, SlicerSettings\(\)\)
                                else -> error\("Unknown persisted Cura import kind: \$\{saved\.kind\}"\)
                            \}
                        \}
                        val scene = if \(saved\.kind == AppStateStore\.KIND_PROJECT\) \{
                            saved\.file\.inputStream\(\)\.use\(CuraProjectSceneParser::parse\)
                        \} else \{
                            null
                        \}
                        RestoredImport\(config, stateStore\.restoreSettings\(config\.mappedSettings\), scene\)
                    \}"""
    restore_replacement = """                    val saved = stateStore.savedImport()
                    val config = saved?.let { persisted ->
                        persisted.file.inputStream().use { input ->
                            when (persisted.kind) {
                                AppStateStore.KIND_PROJECT -> CuraProjectParser.parse(input, persisted.displayName, SlicerSettings())
                                AppStateStore.KIND_PROFILE -> CuraProfileParser.parse(input, persisted.displayName, SlicerSettings())
                                else -> error("Unknown persisted Cura import kind: ${persisted.kind}")
                            }
                        }
                    }
                    val scene = saved?.takeIf { it.kind == AppStateStore.KIND_PROJECT }
                        ?.file
                        ?.inputStream()
                        ?.use(CuraProjectSceneParser::parse)
                    val settings = stateStore.restoreSettings(config?.mappedSettings ?: SlicerSettings())
                    val fingerprint = workspaceFingerprint(config, settings)
                    val workspace = runCatching {
                        workspaceStore.load()?.let { snapshot ->
                            val modelFile = File(snapshot.modelPath)
                            val source = StlParser.parse(
                                file = modelFile,
                                displayName = snapshot.modelDisplayName,
                                maxTriangles = MeshTriangleLimits.current(),
                            )
                            val transformed = snapshot.placement.transformed(source)
                            val fingerprintMatches = snapshot.configurationFingerprint == fingerprint
                            RestoredWorkspace(
                                snapshot = snapshot,
                                source = source,
                                transformed = transformed,
                                plannedEvents = if (fingerprintMatches) snapshot.plannedEvents else emptyList(),
                                fingerprintMatches = fingerprintMatches,
                            )
                        }
                    }.getOrNull()
                    RestoredImport(config, settings, scene, workspace)"""
    content = regex_once(content, restore_pattern, restore_replacement, "Workspace restore IO")

    content = replace_once(
        content,
        """            result.onSuccess { restored ->
                importedScene = restored.scene
                if (restored.config == null) {
""",
        """            result.onSuccess { restored ->
                importedScene = restored.scene
                sourceMesh = restored.workspace?.source
                plannedCalibrationEvents = restored.workspace?.plannedEvents.orEmpty()
                val restoredCalibration = restored.workspace?.snapshot
                    ?.takeIf { restored.workspace.fingerprintMatches }
                    ?.takeIf { it.calibrationType != null && it.calibrationFirstValue != null }
                if (restoredCalibration != null) {
                    CalibrationSliceState.activate(
                        requireNotNull(restoredCalibration.calibrationType),
                        requireNotNull(restoredCalibration.calibrationFirstValue),
                    )
                } else {
                    CalibrationSliceState.clear()
                }
                if (restored.config == null) {
""",
        "Workspace restore activation",
    )
    content = replace_once(
        content,
        """                    )
                }
            }.onFailure { error ->
""",
        """                    )
                }
                restoreWorkspace(restored.workspace)
            }.onFailure { error ->
""",
        "Workspace restore UI",
    )

    helpers = """
    private fun restoreWorkspace(workspace: RestoredWorkspace?) {
        if (workspace == null) return
        val snapshot = workspace.snapshot
        _uiState.update { current ->
            current.copy(
                mesh = workspace.transformed,
                modelPath = snapshot.modelPath,
                modelPlacement = snapshot.placement,
                sliceResultId = null,
                gcodePath = null,
                baseGcodePath = null,
                layerPreview = null,
                layerEvents = emptyList(),
                calibrationDescription = if (workspace.fingerprintMatches) snapshot.calibrationDescription else null,
                estimatedPrintSeconds = null,
                sliceLogPath = null,
                sliceDurationMilliseconds = null,
                isBusy = false,
                statusMessage = buildString {
                    append("Restored ${snapshot.modelDisplayName} workspace; slice again to create validated G-code")
                    if (!workspace.fingerprintMatches) {
                        append(" · configuration changed, so calibration events were cleared")
                    }
                },
            )
        }
    }

    private fun workspaceSnapshot(
        modelFile: File,
        displayName: String,
        placement: ModelPlacement,
        plannedEvents: List<PlannedLayerEvent>,
        calibrationDescription: String?,
        state: MainUiState,
    ): WorkspaceStateStore.Snapshot {
        val calibrationType = plannedEvents.firstOrNull()?.type?.let { eventType ->
            CalibrationTestType.entries.firstOrNull { it.eventType == eventType }
        }
        return WorkspaceStateStore.Snapshot(
            modelPath = modelFile.absolutePath,
            modelDisplayName = displayName,
            placement = placement,
            plannedEvents = plannedEvents,
            calibrationDescription = calibrationDescription,
            calibrationType = calibrationType,
            calibrationFirstValue = if (calibrationType == null) null else plannedEvents.firstOrNull()?.value,
            configurationFingerprint = workspaceFingerprint(state),
        )
    }

    private fun workspaceFingerprint(state: MainUiState): String = WorkspaceStateStore.fingerprint(
        state.profileName,
        state.profileSource,
        state.curaVersion,
        state.settingVersion,
        state.settings,
        state.startGcode,
        state.endGcode,
    )

    private fun workspaceFingerprint(
        config: ImportedCuraConfig?,
        settings: SlicerSettings,
    ): String = WorkspaceStateStore.fingerprint(
        config?.name ?: "Built-in current Cura settings",
        config?.source ?: "Cura 5.11 / setting version 25 reference",
        config?.curaVersion,
        config?.settingVersion ?: "25",
        settings,
        config?.startGcode ?: initialStartGcode,
        config?.endGcode ?: initialEndGcode,
    )

    private fun persistSettings(settings: SlicerSettings) {
        viewModelScope.launch(Dispatchers.IO) {
            stateStore.saveSettings(settings)
            persistCurrentWorkspace(_uiState.value.copy(settings = settings))
        }
    }

    private fun persistCurrentWorkspace(state: MainUiState) {
        val modelPath = state.modelPath ?: return
        val placement = state.modelPlacement ?: return
        val source = sourceMesh ?: return
        val modelFile = File(modelPath)
        if (!modelFile.isFile) return
        workspaceStore.save(
            workspaceSnapshot(
                modelFile = modelFile,
                displayName = source.displayName,
                placement = placement,
                plannedEvents = plannedCalibrationEvents,
                calibrationDescription = state.calibrationDescription,
                state = state,
            ),
        )
    }

"""
    content = replace_once(
        content,
        """    private fun stageAndParseImport(
""",
        helpers + """    private fun stageAndParseImport(
""",
        "Workspace helper insertion",
    )

    content = replace_once(
        content,
        """        runCatching {
            stateStore.commitImport(pending.stagedFile, pending.kind, pending.displayName)
            stateStore.clearSavedSettings()
        }.onFailure {
""",
        """        runCatching {
            withContext(Dispatchers.IO) {
                stateStore.commitImport(pending.stagedFile, pending.kind, pending.displayName)
                stateStore.clearSavedSettings()
            }
        }.onFailure {
""",
        "Imported config transaction off main",
    )
    content = replace_once(
        content,
        """        stateStore.saveSettings(baseline)
        applyImportedConfig(
""",
        """        withContext(Dispatchers.IO) { stateStore.saveSettings(baseline) }
        applyImportedConfig(
""",
        "Imported baseline off main",
    )

    content = replace_once(
        content,
        """        _uiState.update { current ->
            val concreteCount = config.engineProfile?.concreteSettingCount ?: config.rawValues.size
""",
        """        _uiState.update { current ->
            val concreteCount = config.engineProfile?.concreteSettingCount ?: config.rawValues.size
""",
        "Imported config state marker",
    )
    content = replace_once(
        content,
        """            )
        }
    }

    private fun materializeModel(uri: Uri, maxTriangles: Int): File {
""",
        """            )
        }
        withContext(Dispatchers.IO) { persistCurrentWorkspace(_uiState.value) }
    }

    private fun materializeModel(uri: Uri, maxTriangles: Int): File {
""",
        "Persist workspace after configuration changes",
    )

    content = content.replace(
        "    private fun readAsset(path: String): String = app.assets.open(path).bufferedReader().use { it.readText() }\n\n",
        "",
    )

    def add_result_clear(match: re.Match[str]) -> str:
        indent = match.group("indent")
        return f"{indent}sliceResultId = null,\n{indent}gcodePath = null,\n{indent}baseGcodePath = null,"

    content = re.sub(
        r"(?P<indent>[ \t]+)gcodePath = null,\n(?P=indent)baseGcodePath = null,",
        add_result_clear,
        content,
    )

    write(path, content)


def patch_compose_state() -> None:
    path = "app/src/main/java/com/tomppi/enderslicer/ui/EnderSlicerApp.kt"
    content = read(path)
    content = replace_once(
        content,
        "import androidx.compose.runtime.remember\n",
        "import androidx.compose.runtime.remember\nimport androidx.compose.runtime.saveable.rememberSaveable\n",
        "EnderSlicer rememberSaveable import",
    )
    old_states = """    var menuExpanded by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var profilesOpen by remember { mutableStateOf(false) }
    var machineSettingsOpen by remember { mutableStateOf(false) }
    var modelToolsOpen by remember { mutableStateOf(false) }
    var calibrationOpen by remember { mutableStateOf(false) }
    var layerEventsOpen by remember { mutableStateOf(false) }
    var meshLimitOpen by remember { mutableStateOf(false) }
    var viewerMode by remember { mutableStateOf(ViewerMode.MODEL) }
    var selectedLayerIndex by remember { mutableStateOf(0) }

    LaunchedEffect(state.layerPreview) {
        val preview = state.layerPreview
        if (preview == null) {
            viewerMode = ViewerMode.MODEL
            selectedLayerIndex = 0
        } else {
            val firstSupport = preview.layers.indexOfFirst {
                it.supportSegmentCount > 0 || it.supportInterfaceSegmentCount > 0
            }
            selectedLayerIndex = if (firstSupport >= 0) firstSupport else 0
            viewerMode = ViewerMode.LAYERS
        }
    }
"""
    new_states = """    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var profilesOpen by rememberSaveable { mutableStateOf(false) }
    var machineSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var modelToolsOpen by rememberSaveable { mutableStateOf(false) }
    var calibrationOpen by rememberSaveable { mutableStateOf(false) }
    var layerEventsOpen by rememberSaveable { mutableStateOf(false) }
    var meshLimitOpen by rememberSaveable { mutableStateOf(false) }
    var viewerMode by rememberSaveable { mutableStateOf(ViewerMode.MODEL) }
    var selectedLayerIndex by rememberSaveable { mutableStateOf(0) }
    var lastAutoSelectedResultId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.sliceResultId, state.layerPreview) {
        val preview = state.layerPreview
        val resultId = state.sliceResultId
        if (preview == null) {
            viewerMode = ViewerMode.MODEL
            selectedLayerIndex = 0
            if (resultId == null) lastAutoSelectedResultId = null
        } else if (resultId != null && lastAutoSelectedResultId != resultId) {
            val firstSupport = preview.layers.indexOfFirst {
                it.supportSegmentCount > 0 || it.supportInterfaceSegmentCount > 0
            }
            selectedLayerIndex = if (firstSupport >= 0) firstSupport else 0
            viewerMode = ViewerMode.LAYERS
            lastAutoSelectedResultId = resultId
        }
    }
"""
    content = replace_once(content, old_states, new_states, "EnderSlicer saved workflow state")
    write(path, content)

    path = "app/src/main/java/com/tomppi/enderslicer/ui/IntegratedEnderSlicerApp.kt"
    content = read(path)
    content = replace_once(
        content,
        "import androidx.compose.runtime.remember\n",
        "import androidx.compose.runtime.remember\nimport androidx.compose.runtime.saveable.rememberSaveable\n",
        "Integrated rememberSaveable import",
    )
    content = replace_once(
        content,
        "    var octoPrintOpen by remember { mutableStateOf(false) }\n",
        "    var octoPrintOpen by rememberSaveable { mutableStateOf(false) }\n",
        "OctoPrint sheet state",
    )
    write(path, content)

    path = "app/src/main/java/com/tomppi/enderslicer/ui/LayerPreviewView.kt"
    content = read(path)
    content = replace_once(
        content,
        "import androidx.compose.runtime.remember\n",
        "import androidx.compose.runtime.remember\nimport androidx.compose.runtime.saveable.rememberSaveable\n",
        "Layer preview rememberSaveable import",
    )
    content = replace_once(
        content,
        "    var style by remember { mutableStateOf(LayerPreviewStyle.CURRENT_LAYER) }\n",
        "    var style by rememberSaveable { mutableStateOf(LayerPreviewStyle.CURRENT_LAYER) }\n",
        "Layer preview style state",
    )
    write(path, content)


def patch_camera_fit() -> None:
    path = "app/src/main/java/com/tomppi/enderslicer/viewer/ModelSurfaceView.kt"
    content = read(path)
    content = replace_once(
        content,
        """        val distance = cameraDistance()
        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val bedMax = max(printer.widthMm, printer.depthMm).toFloat()
        val meshHeight = mesh?.bounds?.height ?: 0f
        val nearPlane = max(0.5f, distance * 0.015f)
        val farPlane = max(nearPlane + 100f, distance * 4.0f + bedMax + meshHeight * 2f)

        Matrix.perspectiveM(projection, 0, FIELD_OF_VIEW_DEGREES, aspect, nearPlane, farPlane)
""",
        """        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val fit = sceneFit(aspect)
        val distance = fit.distance

        Matrix.perspectiveM(projection, 0, FIELD_OF_VIEW_DEGREES, aspect, fit.nearPlane, fit.farPlane)
""",
        "Model camera projection fit",
    )
    content = replace_once(
        content,
        """        Matrix.translateM(
            scene,
            0,
            (-printer.widthMm / 2.0).toFloat(),
            (-printer.depthMm / 2.0).toFloat(),
            0f,
        )
""",
        """        Matrix.translateM(scene, 0, -fit.centerX, -fit.centerY, -fit.centerZ)
""",
        "Model camera union center",
    )
    content = regex_once(
        content,
        r"""    private fun cameraDistance\(\): Float \{
        val bedMax = max\(printer\.widthMm, printer\.depthMm\)\.toFloat\(\)
        val bounds = mesh\?\.bounds
        val meshRadius = if \(bounds == null\) \{
            0f
        \} else \{
            val diagonal = sqrt\(bounds\.width \* bounds\.width \+ bounds\.depth \* bounds\.depth \+ bounds\.height \* bounds\.height\)
            diagonal \* 0\.5f
        \}
        val requested = \(bedMax \* 1\.55f \+ \(bounds\?\.height \?: 0f\) \* 0\.35f\) / zoom
        return max\(requested, meshRadius \* 1\.12f \+ 4f\)
    \}""",
        """    private fun cameraDistance(): Float = sceneFit(
        viewportWidth.toFloat() / max(viewportHeight, 1).toFloat(),
    ).distance

    private fun sceneFit(aspect: Float): SceneCameraFit.Fit = SceneCameraFit.calculate(
        printer = printer,
        meshBounds = mesh?.bounds,
        aspect = aspect.coerceAtLeast(0.01f),
        zoom = zoom,
        verticalFieldOfViewDegrees = FIELD_OF_VIEW_DEGREES,
    )""",
        "Model camera distance helper",
    )
    write(path, content)


def patch_engine_and_small_compile_fixes() -> None:
    path = "app/src/main/java/com/tomppi/enderslicer/engine/CuraEngineRunner.kt"
    content = read(path)
    content = replace_once(
        content,
        """                GcodeLayerEventProcessor.materialize(workspace.base, workspace.output, validEvents)
""",
        """                GcodeLayerEventProcessor.materialize(
                    workspace.base,
                    workspace.output,
                    validEvents,
                    CalibrationFirmwareEncoder.fromFlavor(printerEnvelope.gcodeFlavor),
                )
""",
        "Firmware-aware user event rematerialization",
    )
    write(path, content)

    path = "app/src/main/java/com/tomppi/enderslicer/data/WorkspaceStateStore.kt"
    content = read(path)
    content = replace_once(content, "                digest.update(0)\n", "                digest.update(0.toByte())\n", "Digest separator byte")
    write(path, content)

    path = "app/src/main/java/com/tomppi/enderslicer/model/PlanarPatchSelector.kt"
    content = read(path)
    content = replace_once(
        content,
        "            if (rank[firstRoot] == rank[secondRoot]) rank[firstRoot]++\n",
        "            if (rank[firstRoot] == rank[secondRoot]) rank[firstRoot] = (rank[firstRoot] + 1).toByte()\n",
        "Union-find byte rank",
    )
    write(path, content)


if __name__ == "__main__":
    patch_main_view_model()
    patch_compose_state()
    patch_camera_fit()
    patch_engine_and_small_compile_fixes()
    print("Applied EnderSlicerCura handoff lifecycle patch")
