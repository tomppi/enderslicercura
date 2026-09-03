package com.tomppi.enderslicer.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tomppi.enderslicer.conical.ConicalSettingsStore
import com.tomppi.enderslicer.engine.GcodeDialect
import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import com.tomppi.enderslicer.model.AllSettingsCatalogs
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.model.withSettings
import com.tomppi.enderslicer.nonplanar.NonPlanarSettingsStore
import com.tomppi.enderslicer.supportpaint.SupportPaintMode
import com.tomppi.enderslicer.texturizer.BumpMeshActivity
import com.tomppi.enderslicer.viewer.MeshPicker
import com.tomppi.enderslicer.viewer.ModelSurfaceView
import com.tomppi.enderslicer.viewer.StlMeshWriter
import com.tomppi.enderslicer.viewer.ViewerOrientation
import com.tomppi.enderslicer.viewer.ViewerOrientationMath
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ViewerMode { MODEL, LAYERS, NOZZLE_PATH }

private val ViewerOrientationSaver = listSaver<ViewerOrientation?, Float>(
    save = { orientation ->
        if (orientation == null) emptyList() else listOf(orientation.yawDegrees, orientation.pitchDegrees)
    },
    restore = { saved ->
        if (saved.size < 2) null else ViewerOrientation(saved[0], saved[1])
    },
)

/** The four persistent destinations. See docs/ux-redesign/DESIGN_PROPOSAL.md. */
private enum class AppTab(val label: String, val subtitleFor: (MainUiState) -> String) {
    PLATE("Plate", { state -> state.mesh?.displayName ?: "No model yet" }),
    SETTINGS("Print settings", { "Apply immediately" }),
    PRINT("Print", { "OctoPrint session" }),
    MORE("More", { "Everything outside the plate" }),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnderSlicerApp(
    viewModel: MainViewModel = viewModel(),
    engine: SlicerEngine = SlicerEngine.CURA,
    onEngineChange: (SlicerEngine) -> Unit = {},
    sliceBlockedReason: String? = null,
    plateOverflowItems: @Composable (() -> Unit) -> Unit = { _ -> },
    moreExtraItems: @Composable () -> Unit = {},
    printTabContent: @Composable () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.PLATE) }
    var importMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var plateOverflowExpanded by rememberSaveable { mutableStateOf(false) }
    var profilesOpen by rememberSaveable { mutableStateOf(false) }
    var printerScreenOpen by rememberSaveable { mutableStateOf(false) }
    val printerChecklistStore = remember(context) { PrinterChecklistStore(context.applicationContext) }
    var printerChecklistDone by remember(printerChecklistStore) { mutableStateOf(printerChecklistStore.load()) }
    var modelToolsOpen by rememberSaveable { mutableStateOf(false) }
    var supportPaintUiOpen by rememberSaveable { mutableStateOf(false) }
    var layerEventsOpen by rememberSaveable { mutableStateOf(false) }
    var meshLimitOpen by rememberSaveable { mutableStateOf(false) }
    var nonPlanarOpen by rememberSaveable { mutableStateOf(false) }
    var allSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var modelUiCollapsed by rememberSaveable { mutableStateOf(false) }
    var conicalOpen by rememberSaveable { mutableStateOf(false) }

    // Android-style back navigation: back closes any open layer instead of exiting.
    // The menu-unfold handler is composed FIRST so it has the LOWEST priority (the
    // last registered enabled BackHandler wins in Compose), letting any open sheet
    // or dialog consume the back event before the menu is expanded again.
    BackHandler(enabled = modelUiCollapsed && selectedTab == AppTab.PLATE) { modelUiCollapsed = false }
    BackHandler(enabled = allSettingsOpen) { allSettingsOpen = false }
    BackHandler(enabled = printerScreenOpen) { printerScreenOpen = false }
    BackHandler(enabled = modelToolsOpen) { modelToolsOpen = false }
    BackHandler(enabled = nonPlanarOpen) { nonPlanarOpen = false }
    BackHandler(enabled = conicalOpen) { conicalOpen = false }
    BackHandler(enabled = meshLimitOpen) { meshLimitOpen = false }
    BackHandler(enabled = profilesOpen) { profilesOpen = false }
    BackHandler(enabled = layerEventsOpen) { layerEventsOpen = false }
    var viewerMode by rememberSaveable { mutableStateOf(ViewerMode.MODEL) }
    var selectedLayerIndex by rememberSaveable { mutableStateOf(0) }
    var modelOrientation by rememberSaveable(stateSaver = ViewerOrientationSaver) {
        mutableStateOf<ViewerOrientation?>(null)
    }
    var lastAutoSelectedResultId by rememberSaveable { mutableStateOf<String?>(null) }
    val nonPlanarStore = remember(context) { NonPlanarSettingsStore(context.applicationContext) }
    var nonPlanarSettings by remember(nonPlanarStore) { mutableStateOf(nonPlanarStore.load()) }
    val conicalStore = remember(context) { ConicalSettingsStore(context.applicationContext) }
    var conicalSettings by remember(conicalStore) { mutableStateOf(conicalStore.load()) }

    LaunchedEffect(state.sliceResultId, state.layerPreview, nonPlanarSettings, conicalSettings) {
        val gcodeAvailable = state.hasCurrentGcode()
        val preview = state.layerPreview.takeIf { gcodeAvailable }
        val resultId = state.sliceResultId.takeIf { gcodeAvailable }
        if (preview == null) {
            viewerMode = ViewerMode.MODEL
            selectedLayerIndex = 0
            if (resultId == null) lastAutoSelectedResultId = null
        } else if (resultId != null && lastAutoSelectedResultId != resultId) {
            val firstSupport = preview.layers.indexOfFirst {
                it.supportSegmentCount > 0 || it.supportInterfaceSegmentCount > 0
            }
            selectedLayerIndex = if (firstSupport >= 0) firstSupport else 0
            viewerMode = if ((nonPlanarSettings.enabled || conicalSettings.enabled) && gcodeAvailable) {
                ViewerMode.NOZZLE_PATH
            } else {
                ViewerMode.LAYERS
            }
            lastAutoSelectedResultId = resultId
        }
    }

    val stlPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importStl)
    }
    val profilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importCuraProfile)
    }
    val projectPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importCuraProject)
    }
    val textureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let(viewModel::importStl)
        }
    }
    val configExportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        uri?.let(viewModel::exportConfiguration)
    }
    val configImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importConfiguration)
    }
    val prusaConfigImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importPrusaConfig)
    }
    val gcodeExportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/x-gcode"),
    ) { uri: Uri? ->
        uri?.let(viewModel::exportGcode)
    }

    val effectiveSliceBlockedReason = sliceBlockedReason
        ?: if (nonPlanarSettings.enabled && conicalSettings.enabled) {
            "Non-planar and conical slicing are mutually exclusive; disable one before slicing"
        } else {
            null
        }

    fun launchBumpMesh() {
        val mesh = state.mesh
        if (mesh == null || state.isBusy) {
            Toast.makeText(context, "Import a model first", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val source = File(
                        context.cacheDir,
                        "bumpmesh-source/current-displayed.stl",
                    )
                    StlMeshWriter.writeBinary(mesh, source)
                    source
                }
            }.onSuccess { source ->
                textureLauncher.launch(
                    Intent(context, BumpMeshActivity::class.java)
                        .putExtra(BumpMeshActivity.EXTRA_MODEL_PATH, source.absolutePath)
                        .putExtra(BumpMeshActivity.EXTRA_MODEL_NAME, mesh.displayName),
                )
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    error.message ?: "Unable to prepare the model for BumpMesh",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val expandedLayout = maxWidth >= 600.dp
        Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (printerScreenOpen) {
                        androidx.compose.material3.IconButton(onClick = { printerScreenOpen = false }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to More",
                            )
                        }
                    }
                },
                title = {
                    Column {
                        Text(
                            if (printerScreenOpen) "Printer" else selectedTab.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            if (printerScreenOpen) "Machine profile & safety" else selectedTab.subtitleFor(state),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (selectedTab == AppTab.PLATE) {
                        Box {
                            TopBarTextAction(
                                label = "Import",
                                onClick = { importMenuExpanded = true },
                            )
                            DropdownMenu(
                                expanded = importMenuExpanded,
                                onDismissRequest = { importMenuExpanded = false },
                                modifier = Modifier.widthIn(min = 280.dp, max = 340.dp),
                            ) {
                                MenuSectionLabel("Files")
                                DropdownMenuItem(
                                    text = { Text("Import STL") },
                                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                                    onClick = {
                                        importMenuExpanded = false
                                        stlPicker.launch(arrayOf("*/*"))
                                    },
                                    enabled = !state.isBusy,
                                )
                                DropdownMenuItem(
                                    text = { Text("Import Cura project (.3mf)") },
                                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                                    onClick = {
                                        importMenuExpanded = false
                                        projectPicker.launch(
                                            arrayOf(
                                                "model/3mf",
                                                "application/vnd.ms-package.3dmanufacturing-3dmodel+xml",
                                                "*/*",
                                            ),
                                        )
                                    },
                                    enabled = !state.isBusy,
                                )
                                DropdownMenuItem(
                                    text = { Text("Import Cura profile") },
                                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                                    onClick = {
                                        importMenuExpanded = false
                                        profilePicker.launch(arrayOf("*/*"))
                                    },
                                    enabled = !state.isBusy,
                                )
                            }
                        }
                        Box {
                            TopBarTextAction(
                                label = "Plate",
                                onClick = { plateOverflowExpanded = true },
                            )
                            DropdownMenu(
                                expanded = plateOverflowExpanded,
                                onDismissRequest = { plateOverflowExpanded = false },
                                modifier = Modifier.widthIn(min = 280.dp, max = 340.dp),
                            ) {
                                MenuSectionLabel("Model")
                                DropdownMenuItem(
                                    text = { Text("Position & rotation") },
                                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                    onClick = {
                                        plateOverflowExpanded = false
                                        modelToolsOpen = true
                                    },
                                    enabled = state.mesh != null && !state.isBusy,
                                )
                                DropdownMenuItem(
                                    text = { Text("Mesh triangle limit") },
                                    leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                                    onClick = {
                                        plateOverflowExpanded = false
                                        meshLimitOpen = true
                                    },
                                    enabled = !state.isBusy,
                                )
                                HorizontalDivider()
                                plateOverflowItems { plateOverflowExpanded = false }
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                // The session pane owns Slice/Export on the expanded layout;
                // the bottom action bar is for phone-sized windows only.
                if (selectedTab == AppTab.PLATE && !expandedLayout) {
                    ActionBar(
                        state = state,
                        nonPlanarEnabled = nonPlanarSettings.enabled,
                        conicalEnabled = conicalSettings.enabled,
                        sliceBlockedReason = effectiveSliceBlockedReason,
                        onSlice = viewModel::sliceModel,
                        onExportGcode = { gcodeExportPicker.launch(GcodeExportName.suggest()) },
                        onTools = { modelToolsOpen = true },
                    )
                }
                AppTabBar(
                    selected = selectedTab,
                    onSelect = { selectedTab = it },
                )
            }
        },
    ) { padding ->
        when (selectedTab) {
            AppTab.PLATE -> BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                val expandedLayout = maxWidth >= 600.dp
                if (expandedLayout) {
                    // Full-screen model view with the original session panel as a
                    // transparent overlay on the right; the panel can be hidden.
                    Box(modifier = Modifier.fillMaxSize()) {
                        ViewerPanel(
                            state = state,
                            viewerMode = viewerMode,
                            selectedLayerIndex = selectedLayerIndex,
                            modelOrientation = modelOrientation,
                            onOrientationChanged = { modelOrientation = it },
                            nonPlanarEnabled = nonPlanarSettings.enabled,
                            conicalEnabled = conicalSettings.enabled,
                            supportPaintUiOpen = supportPaintUiOpen,
                            onViewerMode = { viewerMode = it },
                            onLayerSelected = { selectedLayerIndex = it },
                            onEditLayerEvents = { layerEventsOpen = true },
                            onPaintHit = viewModel::paintAt,
                            onPaintMode = viewModel::setPaintMode,
                            onCloseSupportPaintUi = {
                                viewModel.setPaintMode(SupportPaintMode.NONE)
                                supportPaintUiOpen = false
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (!modelUiCollapsed) {
                            SessionPanel(
                                state = state,
                                sliceBlockedReason = effectiveSliceBlockedReason,
                                onOpenSettings = { selectedTab = AppTab.SETTINGS },
                                onSettings = viewModel::updateSettings,
                                onSlice = viewModel::sliceModel,
                                onExportGcode = { gcodeExportPicker.launch(GcodeExportName.suggest()) },
                                onTools = { modelToolsOpen = true },
                                onCollapse = { modelUiCollapsed = true },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .padding(top = 56.dp)
                                    .widthIn(min = 230.dp, max = 300.dp),
                            )
                        } else {
                            OutlinedButton(
                                onClick = { modelUiCollapsed = false },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 56.dp, end = 12.dp),
                            ) {
                                Text("Menu")
                            }
                        }
                    }
                } else {
                    ViewerPanel(
                        state = state,
                        viewerMode = viewerMode,
                        selectedLayerIndex = selectedLayerIndex,
                        modelOrientation = modelOrientation,
                        onOrientationChanged = { modelOrientation = it },
                        nonPlanarEnabled = nonPlanarSettings.enabled,
                        conicalEnabled = conicalSettings.enabled,
                        supportPaintUiOpen = supportPaintUiOpen,
                        onViewerMode = { viewerMode = it },
                        onLayerSelected = { selectedLayerIndex = it },
                        onEditLayerEvents = { layerEventsOpen = true },
                        onPaintHit = viewModel::paintAt,
                        onPaintMode = viewModel::setPaintMode,
                        onCloseSupportPaintUi = {
                            viewModel.setPaintMode(SupportPaintMode.NONE)
                            supportPaintUiOpen = false
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            AppTab.SETTINGS -> if (allSettingsOpen) {
                val catalogSpecs = remember(engine) {
                    if (engine == SlicerEngine.PRUSA) {
                        AllSettingsCatalogs.prusa(context.assets)
                    } else {
                        AllSettingsCatalogs.cura(context.assets)
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    AllSettingsSheet(
                        engineLabel = engine.label,
                        specs = catalogSpecs,
                        added = if (engine == SlicerEngine.PRUSA) state.extraPrusaSettings else state.extraCuraSettings,
                        managedKeys = if (engine == SlicerEngine.PRUSA) {
                            AllSettingsCatalogs.PRUSA_MANAGED_KEYS
                        } else {
                            AllSettingsCatalogs.CURA_MANAGED_KEYS
                        },
                        blockedKeys = if (engine == SlicerEngine.PRUSA) {
                            AllSettingsCatalogs.PRUSA_BLOCKED_KEYS
                        } else {
                            AllSettingsCatalogs.CURA_BLOCKED_KEYS
                        },
                        onAdd = { key, value -> viewModel.setExtraSetting(engine, key, value) },
                        onRemove = { key -> viewModel.removeExtraSetting(engine, key) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                    OutlinedButton(
                        onClick = { allSettingsOpen = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Back to settings")
                    }
                }
            } else Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                EngineSelectorCard(
                    engine = engine,
                    onEngineChange = onEngineChange,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (engine == SlicerEngine.PRUSA) {
                    PrusaSettingsSheet(
                        state = state,
                        onSettings = viewModel::updatePrusaSettings,
                        onImportConfig = { prusaConfigImportPicker.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) },
                        onOpenAllSettings = { allSettingsOpen = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                } else {
                    CategorizedSettingsSheet(
                        state = state,
                        onSettings = viewModel::updateSettings,
                        onResetOverrides = viewModel::resetAllSettingOverrides,
                        onOpenAllSettings = { allSettingsOpen = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                }
            }
            AppTab.PRINT -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                printTabContent()
            }
            AppTab.MORE -> if (printerScreenOpen) {
                PrinterScreen(
                    state = state,
                    checklistDone = printerChecklistDone,
                    onChecklistToggle = { id, checked ->
                        val updated = if (checked) printerChecklistDone + id else printerChecklistDone - id
                        printerChecklistDone = updated
                        printerChecklistStore.save(updated)
                    },
                    onSettings = viewModel::updateSettings,
                    onResetOverrides = viewModel::resetAllSettingOverrides,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            } else MoreScreen(
                state = state,
                nonPlanarEnabled = nonPlanarSettings.enabled,
                conicalEnabled = conicalSettings.enabled,
                onProfiles = { profilesOpen = true },
                onMachineSettings = { printerScreenOpen = true },
                onExportConfig = { configExportPicker.launch("printer-config.json") },
                onImportConfig = { configImportPicker.launch(arrayOf("application/json", "*/*")) },
                onBumpMesh = ::launchBumpMesh,
                onNonPlanar = { nonPlanarOpen = true },
                onConical = { conicalOpen = true },
                onMeshLimit = { meshLimitOpen = true },
                extraItems = moreExtraItems,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
    }
    }

    if (profilesOpen) {
        AppBottomSheet(
            onDismissRequest = { profilesOpen = false },
        ) {
            ProfileManagementSheet(
                state = state,
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxHeight(0.94f)
                    .navigationBarsPadding(),
            )
        }
    }

    if (meshLimitOpen) {
        AppBottomSheet(
            onDismissRequest = { meshLimitOpen = false },
        ) {
            MeshTriangleLimitSheet(
                currentLimit = MeshTriangleLimits.current(),
                currentModelTriangles = state.mesh?.triangleCount,
                onSave = { limit ->
                    val saved = MeshTriangleLimits.save(context, limit)
                    meshLimitOpen = false
                    Toast.makeText(
                        context,
                        "Mesh triangle limit set to ${MeshTriangleLimits.formatCount(saved)}",
                        Toast.LENGTH_LONG,
                    ).show()
                },
                modifier = Modifier
                    .fillMaxHeight(0.94f)
                    .navigationBarsPadding(),
            )
        }
    }

    if (nonPlanarOpen) {
        AppBottomSheet(
            onDismissRequest = { nonPlanarOpen = false },
        ) {
            val effectivePrinter = state.printer.withSettings(state.settings)
            NonPlanarSettingsSheet(
                initial = nonPlanarSettings,
                layerHeightMm = state.settings.layerHeightMm,
                nozzleDiameterMm = effectivePrinter.nozzleSizeMm,
                onSave = { value ->
                    val safe = value.validated()
                    val changed = safe != nonPlanarSettings
                    nonPlanarStore.save(safe)
                    nonPlanarSettings = safe
                    nonPlanarOpen = false
                    if (changed) {
                        viewerMode = ViewerMode.MODEL
                        selectedLayerIndex = 0
                        lastAutoSelectedResultId = null
                    }
                    Toast.makeText(
                        context,
                        if (changed) {
                            if (safe.enabled) {
                                "Non-planar settings saved; slice again before export"
                            } else {
                                "Non-planar printing disabled; slice again before export"
                            }
                        } else if (safe.enabled) {
                            "Non-planar settings unchanged"
                        } else {
                            "Non-planar printing remains disabled"
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                modifier = Modifier
                    .fillMaxHeight(0.94f)
                    .navigationBarsPadding(),
            )
        }
    }

    if (conicalOpen) {
        AppBottomSheet(
            onDismissRequest = { conicalOpen = false },
        ) {
            ConicalSettingsSheet(
                initial = conicalSettings,
                onSave = { value ->
                    val safe = value.validated()
                    val changed = safe != conicalSettings
                    conicalStore.save(safe)
                    conicalSettings = safe
                    conicalOpen = false
                    if (changed) {
                        viewerMode = ViewerMode.MODEL
                        selectedLayerIndex = 0
                        lastAutoSelectedResultId = null
                    }
                    Toast.makeText(
                        context,
                        if (changed) {
                            if (safe.enabled) {
                                "Conical slicing settings saved; slice again before export"
                            } else {
                                "Conical slicing disabled; slice again before export"
                            }
                        } else if (safe.enabled) {
                            "Conical slicing settings unchanged"
                        } else {
                            "Conical slicing remains disabled"
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                modifier = Modifier
                    .fillMaxHeight(0.94f)
                    .navigationBarsPadding(),
            )
        }
    }

    if (layerEventsOpen && state.layerPreview != null && state.hasCurrentGcode()) {
        val preview = requireNotNull(state.layerPreview)
        val layer = preview.layers[selectedLayerIndex.coerceIn(preview.layers.indices)]
        AppBottomSheet(
            onDismissRequest = { layerEventsOpen = false },
        ) {
            LayerEventsSheet(
                layer = layer,
                events = state.layerEvents,
                settings = state.settings,
                isBusy = state.isBusy,
                onAdd = { type, value, secondary, text ->
                    viewModel.addLayerEvent(layer.number, layer.z, type, value, secondary, text)
                },
                onRemove = viewModel::removeLayerEvent,
                onClearUserEvents = viewModel::clearLayerEvents,
                modifier = Modifier
                    .fillMaxHeight(0.94f)
                    .navigationBarsPadding(),
            )
        }
    }

    if (modelToolsOpen) {
        AppBottomSheet(
            onDismissRequest = { modelToolsOpen = false },
        ) {
            ModelToolsSheet(
                state = state,
                onMove = viewModel::moveModel,
                onRotate = viewModel::rotateModel,
                onScale = viewModel::scaleModel,
                onDropToBed = viewModel::dropModelToBed,
                onLayFlat = viewModel::layModelFlat,
                onReset = viewModel::resetModelTransform,
                onApplyImportedTransform = viewModel::applyImportedSceneTransform,
                onOpenSupportPaintUi = {
                    modelToolsOpen = false
                    supportPaintUiOpen = true
                },
                onBrushRadius = viewModel::setBrushRadius,
                onClearPaint = viewModel::clearSupportPaint,
                modifier = Modifier
                    .fillMaxHeight(0.5f)
                    .navigationBarsPadding(),
            )
        }
    }
}

/**
 * Prominent engine switcher: Cura (blue) or PrusaSlicer (orange). Each
 * engine is a whole Product mode - theme accent, profile formats, G-code
 * dialect and engine binary; profiles are never merged across engines.
 */
@Composable
internal fun EngineSelectorCard(
    engine: SlicerEngine,
    onEngineChange: (SlicerEngine) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Slicing engine", style = MaterialTheme.typography.titleMedium)
            Text(
                "Profiles stay separate: pick the slicer you want, the app becomes that product.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EngineOption(
                    label = "Cura",
                    tagline = "Blue",
                    accent = EngineAccent.CURA,
                    selected = engine == SlicerEngine.CURA,
                    onClick = { onEngineChange(SlicerEngine.CURA) },
                    modifier = Modifier.weight(1f),
                )
                EngineOption(
                    label = "PrusaSlicer",
                    tagline = "Orange",
                    accent = EngineAccent.PRUSA,
                    selected = engine == SlicerEngine.PRUSA,
                    onClick = { onEngineChange(SlicerEngine.PRUSA) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private enum class EngineAccent { CURA, PRUSA }

@Composable
private fun EngineOption(
    label: String,
    tagline: String,
    accent: EngineAccent,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accentColor = when (accent) {
        EngineAccent.CURA -> Color(0xFF3B99FF)
        EngineAccent.PRUSA -> Color(0xFFFF8A2A)
    }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        border = if (selected) {
            BorderStroke(2.dp, accentColor)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        modifier = modifier.height(76.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(accentColor),
                )
                Text(label, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                tagline,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Persistent bottom navigation: Plate · Settings · Print · More. */
@Composable
private fun AppTabBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        NavigationBarItem(
            selected = selected == AppTab.PLATE,
            onClick = { onSelect(AppTab.PLATE) },
            icon = { Icon(AppIcons.Plate, contentDescription = null) },
            label = { Text("Plate") },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
        NavigationBarItem(
            selected = selected == AppTab.SETTINGS,
            onClick = { onSelect(AppTab.SETTINGS) },
            icon = { Icon(AppIcons.Settings, contentDescription = null) },
            label = { Text("Settings") },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
        NavigationBarItem(
            selected = selected == AppTab.PRINT,
            onClick = { onSelect(AppTab.PRINT) },
            icon = { Icon(AppIcons.Print, contentDescription = null) },
            label = { Text("Print") },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
        NavigationBarItem(
            selected = selected == AppTab.MORE,
            onClick = { onSelect(AppTab.MORE) },
            icon = { Icon(AppIcons.More, contentDescription = null) },
            label = { Text("More") },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
    }
}

/** More hub: grouped navigation to everything outside the plate. */
@Composable
private fun MoreScreen(
    state: MainUiState,
    nonPlanarEnabled: Boolean,
    conicalEnabled: Boolean,
    onProfiles: () -> Unit,
    onMachineSettings: () -> Unit,
    onExportConfig: () -> Unit,
    onImportConfig: () -> Unit,
    onBumpMesh: () -> Unit,
    onNonPlanar: () -> Unit,
    onConical: () -> Unit,
    onMeshLimit: () -> Unit,
    extraItems: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
    ) {
        MoreSectionLabel("Configuration")
        Card(modifier = Modifier.fillMaxWidth()) {
            MoreRow(
                icon = AppIcons.Star,
                title = "Profiles & filament",
                subtitle = "Manage profiles, materials and filaments",
                enabled = !state.isBusy,
                onClick = onProfiles,
            )
            MoreDivider()
            MoreRow(
                icon = AppIcons.Machine,
                title = "Printer & G-code",
                subtitle = "Machine profile, safety and start/end G-code",
                enabled = !state.isBusy,
                onClick = onMachineSettings,
            )
            MoreDivider()
            MoreRow(
                icon = AppIcons.Swap,
                title = "Export configuration snapshot",
                subtitle = "Save the full setup to a JSON file",
                enabled = !state.isBusy,
                onClick = onExportConfig,
            )
            MoreDivider()
            MoreRow(
                icon = AppIcons.Swap,
                title = "Import configuration snapshot",
                subtitle = "Restore a saved setup",
                enabled = !state.isBusy,
                onClick = onImportConfig,
            )
        }

        MoreSectionLabel("Experimental")
        Card(modifier = Modifier.fillMaxWidth()) {
            MoreRow(
                icon = AppIcons.Camera,
                title = "BumpMesh texturizer",
                subtitle = "Offline displacement texturing of the model",
                enabled = state.mesh != null && !state.isBusy,
                badge = "EXP",
                onClick = onBumpMesh,
            )
            MoreDivider()
            extraItems()
            MoreRow(
                icon = AppIcons.Layers,
                title = "Non-planar slicing",
                subtitle = if (nonPlanarEnabled) "CurviSlicer relief-field · enabled" else "CurviSlicer relief-field print",
                enabled = !state.isBusy,
                badge = if (nonPlanarEnabled) "ON" else "OFF",
                onClick = onNonPlanar,
            )
            MoreDivider()
            MoreRow(
                icon = AppIcons.Bolt,
                title = "Conical slicing",
                subtitle = if (conicalEnabled) "Cone-warped geometry · enabled" else "Cone-warped geometry modifier",
                enabled = !state.isBusy,
                badge = if (conicalEnabled) "ON" else "OFF",
                onClick = onConical,
            )
            MoreDivider()
            MoreRow(
                icon = AppIcons.Filter,
                title = "Mesh triangle limit",
                subtitle = "Max triangles for viewer and texturizer",
                enabled = !state.isBusy,
                onClick = onMeshLimit,
            )
        }

        MoreSectionLabel("About")
        Card(modifier = Modifier.fillMaxWidth()) {
            MoreRow(
                icon = AppIcons.Info,
                title = "EnderSlicerCura",
                subtitle = "Version 1.0.0 · AGPL-3.0-or-later",
                onClick = {},
            )
            MoreDivider()
            MoreRow(
                icon = AppIcons.Shield,
                title = "Safety notes",
                subtitle = "Inspect every model, setting and generated G-code before printing",
                onClick = {},
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun MoreSectionLabel(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 7.dp),
    )
}

@Composable
private fun MoreDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 14.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** Standard row for the More hub. Also used by integrations (Smart Infill). */
@Composable
internal fun MoreRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    badge: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(38.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (badge != null) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.extraSmall,
                modifier = Modifier.height(20.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 6.dp),
                ) {
                    Text(badge, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun TopBarTextAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .height(48.dp)
            .widthIn(min = 80.dp, max = 156.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        content()
    }
}

@Composable
private fun MenuSectionLabel(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 7.dp),
    )
}

@Composable
private fun ViewerPanel(
    state: MainUiState,
    viewerMode: ViewerMode,
    selectedLayerIndex: Int,
    modelOrientation: ViewerOrientation?,
    onOrientationChanged: (ViewerOrientation) -> Unit,
    nonPlanarEnabled: Boolean,
    conicalEnabled: Boolean,
    supportPaintUiOpen: Boolean,
    onViewerMode: (ViewerMode) -> Unit,
    onLayerSelected: (Int) -> Unit,
    onEditLayerEvents: () -> Unit,
    onPaintHit: (MeshPicker.Hit) -> Unit,
    onPaintMode: (SupportPaintMode) -> Unit,
    onCloseSupportPaintUi: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val effectivePrinter = state.printer.withSettings(state.settings)
    val gcodeAvailable = state.hasCurrentGcode()
    var modelHintsDismissed by rememberSaveable { mutableStateOf(false) }
    Box(modifier = modifier) {
        val preview = state.layerPreview.takeIf { gcodeAvailable }
        when {
            viewerMode == ViewerMode.LAYERS && preview != null -> LayerPreviewView(
                preview = preview,
                selectedLayerIndex = selectedLayerIndex,
                events = state.layerEvents,
                onLayerSelected = onLayerSelected,
                onEditEvents = onEditLayerEvents,
                modifier = Modifier.fillMaxSize(),
            )
            viewerMode == ViewerMode.NOZZLE_PATH && gcodeAvailable -> NozzlePathView(
                gcodePath = requireNotNull(state.gcodePath),
                dialect = if (state.sliceEngine == SlicerEngine.PRUSA) GcodeDialect.PRUSA else GcodeDialect.CURA,
                beadHeightMm = if (state.sliceEngine == SlicerEngine.PRUSA) {
                    state.prusaSettings.layerHeightMm
                } else {
                    state.settings.layerHeightMm
                },
                beadLineWidthMm = if (state.sliceEngine == SlicerEngine.PRUSA) {
                    state.prusaSettings.perimeterExtrusionWidthMm
                        ?: state.prusaSettings.firstLayerExtrusionWidthMm
                        ?: state.settings.lineWidthMm
                } else {
                    state.settings.lineWidthMm
                },
                filamentDiameterMm = if (state.sliceEngine == SlicerEngine.PRUSA) {
                    state.settings.filamentDiameterMm
                } else {
                    state.settings.filamentDiameterMm
                },
                modifier = Modifier.fillMaxSize(),
            )
            else -> key(effectivePrinter) {
                var modelView by remember(effectivePrinter) { mutableStateOf<ModelSurfaceView?>(null) }
                LaunchedEffect(modelView) {
                    modelView?.let { view ->
                        // A recreated surface view starts at the default camera;
                        // restore the last orbit when returning to the Plate tab.
                        modelOrientation?.let { view.restoreOrientation(it) }
                        onOrientationChanged(view.currentOrientation())
                    }
                }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, modelView) {
                    val view = modelView
                    if (view == null) {
                        onDispose { }
                    } else {
                        val observer = LifecycleEventObserver { _, event ->
                            when (event) {
                                Lifecycle.Event.ON_RESUME -> view.onResume()
                                Lifecycle.Event.ON_PAUSE,
                                Lifecycle.Event.ON_STOP,
                                Lifecycle.Event.ON_DESTROY,
                                -> view.onPause()
                                else -> Unit
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            view.onResume()
                        }
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                            view.onPause()
                        }
                    }
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        ModelSurfaceView(context, effectivePrinter).also { modelView = it }
                    },
                    update = { view ->
                        view.setMesh(state.mesh)
                        view.paintMode = state.paintMode
                        view.setPaintState(state.supportPaint)
                        view.onPaintHit = onPaintHit
                        view.onOrientationChanged = onOrientationChanged
                    },
                )
            }
        }

        if (supportPaintUiOpen && viewerMode == ViewerMode.MODEL) {
            SupportPaintOverlay(
                activeMode = state.paintMode,
                onPaintMode = onPaintMode,
                onClose = onCloseSupportPaintUi,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
        ) {
            Card(
                modifier = Modifier.widthIn(max = 290.dp),
            ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(effectivePrinter.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "%.0f × %.0f × %.0f mm · %.2f mm nozzle".format(
                        effectivePrinter.widthMm,
                        effectivePrinter.depthMm,
                        effectivePrinter.heightMm,
                        effectivePrinter.nozzleSizeMm,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (nonPlanarEnabled || conicalEnabled) {
                    HorizontalDivider()
                }
                if (nonPlanarEnabled) {
                    Text(
                        "Non-planar printing enabled",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (conicalEnabled) {
                    Text(
                        "Conical slicing enabled",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                val mesh = state.mesh
                if (mesh == null) {
                    Text("Import an STL from the Import button", style = MaterialTheme.typography.bodySmall)
                } else {
                    HorizontalDivider()
                    SummaryRow("Model", mesh.displayName)
                    SummaryRow("Triangles", "${mesh.triangleCount}")
                    SummaryRow(
                        "Size",
                        "%.1f × %.1f × %.1f mm".format(
                            mesh.bounds.width,
                            mesh.bounds.depth,
                            mesh.bounds.height,
                        ),
                    )
                    state.modelPlacement?.let { placement ->
                        SummaryRow(
                            "Center",
                            "%.2f, %.2f · Z %.2f mm".format(
                                placement.centerXmm,
                                placement.centerYmm,
                                placement.baseZmm,
                            ),
                        )
                        SummaryRow("Placement", placement.source)
                    }
                }
                state.estimatedPrintSeconds?.takeIf { gcodeAvailable }?.let { seconds ->
                    HorizontalDivider()
                    SummaryRow("Estimated print", formatPrintTime(seconds))
                }
                if (state.warnings.isNotEmpty()) {
                    Text(
                        "Cura compatibility warnings: ${state.warnings.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            }
            if (viewerMode == ViewerMode.MODEL) {
                modelOrientation?.let { orientation ->
                    Spacer(modifier = Modifier.height(8.dp))
                    OrientationGizmo(
                        yawDegrees = orientation.yawDegrees,
                        pitchDegrees = orientation.pitchDegrees,
                        cameraElevation = ViewerOrientationMath.MODEL_VIEW_ELEVATION,
                        modifier = Modifier.align(Alignment.Start),
                    )
                }
            }
        }

        if (preview != null || gcodeAvailable) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ViewerModeButton("Model", ViewerMode.MODEL, viewerMode, true, onViewerMode)
                    ViewerModeButton("Layers", ViewerMode.LAYERS, viewerMode, preview != null, onViewerMode)
                    ViewerModeButton("Path", ViewerMode.NOZZLE_PATH, viewerMode, gcodeAvailable, onViewerMode)
                }
            }
        }

        if (viewerMode == ViewerMode.MODEL) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .widthIn(max = 380.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(state.statusMessage, style = MaterialTheme.typography.bodySmall)
                    if (!modelHintsDismissed) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Drag orbit · Pinch zoom · Two-finger pan · Double-tap reset",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "Dismiss",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .clickable { modelHintsDismissed = true },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Right-hand session pane for the expanded (unfolded foldable / large
 * screen) Plate layout: print summary, quick settings and actions next to
 * the viewer instead of below it. See docs/ux-redesign/mockups/08-foldable.png.
 */
@Composable
private fun SessionPanel(
    state: MainUiState,
    sliceBlockedReason: String?,
    onOpenSettings: () -> Unit,
    onSettings: (String, (SlicerSettings) -> SlicerSettings) -> Unit,
    onSlice: () -> Unit,
    onExportGcode: () -> Unit,
    onTools: () -> Unit,
    onCollapse: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val gcodeAvailable = state.hasCurrentGcode()
    val settings = state.settings
    // Content-sized overlay: touches outside the cards reach the model
    // underneath; if the content outgrows the window it scrolls.
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Print session", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (gcodeAvailable) {
                            SessionChip("Ready", MaterialTheme.colorScheme.primary)
                        } else {
                            SessionChip("Not sliced", MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = onCollapse) { Text("Hide") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.layerPreview?.let { preview ->
                        SessionChip(preview.layers.size.toString() + " layers")
                    }
                    state.estimatedPrintSeconds?.takeIf { gcodeAvailable }?.let { seconds ->
                        SessionChip(formatPrintTime(seconds))
                    }
                    state.warnings.takeIf { it.isNotEmpty() }?.let { warnings ->
                        SessionChip(warnings.size.toString() + " warnings", MaterialTheme.colorScheme.error)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SessionStat("Layer", "%.2f".format(settings.layerHeightMm), "mm", Modifier.weight(1f))
                    SessionStat("Infill", "%.0f%%".format(settings.infillDensityPercent), "grid", Modifier.weight(1f))
                    SessionStat(
                        "Supports",
                        if (settings.supportsEnabled) "ON" else "OFF",
                        settings.supportPlacement,
                        Modifier.weight(1f),
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Quick settings", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Tap a value to edit it in the Settings tab",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                QuickSettingRow("Layer height", "%.2f".format(settings.layerHeightMm), "mm", onOpenSettings)
                QuickSettingRow("Infill density", "%.0f%%".format(settings.infillDensityPercent), "grid", onOpenSettings)
                QuickSettingRow("Adhesion", settings.adhesionType, "", onOpenSettings)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Supports", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = settings.supportsEnabled,
                        onCheckedChange = { checked ->
                            onSettings(SlicerSettings.Keys.SUPPORTS_ENABLED) { current ->
                                current.copy(supportsEnabled = checked)
                            }
                        },
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Actions", style = MaterialTheme.typography.titleSmall)
                sliceBlockedReason?.let { reason ->
                    Text(
                        reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = onSlice,
                    enabled = state.engineAvailable && state.modelPath != null && !state.isBusy && sliceBlockedReason == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (gcodeAvailable) "Slice again" else "Slice")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = onExportGcode,
                        enabled = gcodeAvailable && !state.isBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Export")
                    }
                    OutlinedButton(
                        onClick = onTools,
                        enabled = state.mesh != null && !state.isBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Model tools")
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionChip(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = color,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SessionStat(label: String, value: String, sub: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelLarge)
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun QuickSettingRow(label: String, value: String, unit: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (unit.isNotEmpty()) {
                Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun ViewerModeButton(
    label: String,
    mode: ViewerMode,
    selected: ViewerMode,
    enabled: Boolean,
    onSelected: (ViewerMode) -> Unit,
) {
    val content = @Composable { Text(label, style = MaterialTheme.typography.labelMedium) }
    if (selected == mode) {
        Button(
            onClick = { onSelected(mode) },
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp),
        ) { content() }
    } else {
        OutlinedButton(
            onClick = { onSelected(mode) },
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp),
        ) { content() }
    }
}

@Composable
private fun ActionBar(
    state: MainUiState,
    nonPlanarEnabled: Boolean,
    conicalEnabled: Boolean,
    sliceBlockedReason: String?,
    onSlice: () -> Unit,
    onExportGcode: () -> Unit,
    onTools: () -> Unit,
) {
    val gcodeAvailable = state.hasCurrentGcode()
    Surface(tonalElevation = 4.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            sliceBlockedReason?.let { reason ->
                Text(reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
            state.estimatedPrintSeconds?.takeIf { gcodeAvailable }?.let { seconds ->
                Text(
                    "Estimated print time: ${formatPrintTime(seconds)}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isBusy) CircularProgressIndicator(modifier = Modifier.height(28.dp))
                Button(
                    onClick = onSlice,
                    enabled = state.engineAvailable && state.modelPath != null && !state.isBusy && sliceBlockedReason == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        when {
                            state.isBusy -> "Working…"
                            nonPlanarEnabled -> "Slice non-planar"
                            conicalEnabled -> "Slice conical"
                            else -> "Slice"
                        },
                    )
                }
                OutlinedButton(
                    onClick = onExportGcode,
                    enabled = gcodeAvailable && !state.isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Export G-code")
                }
            }
            OutlinedButton(
                onClick = onTools,
                enabled = state.mesh != null && !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Model tools · move, rotate, scale, paint")
            }
            if (!gcodeAvailable) {
                Text(
                    "Slice a model first to export validated G-code",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

@Composable
private fun SupportPaintOverlay(
    activeMode: SupportPaintMode,
    onPaintMode: (SupportPaintMode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(Unit) {
        onDispose { onPaintMode(SupportPaintMode.NONE) }
    }
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Support painting", style = MaterialTheme.typography.titleSmall)
            Text(
                "Tap Draw, Block or Erase, then drag on the model. Use two fingers to rotate and zoom.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaintModeButton("Draw", SupportPaintMode.ENFORCER, activeMode, onPaintMode, Modifier.weight(1f))
                PaintModeButton("Block", SupportPaintMode.BLOCKER, activeMode, onPaintMode, Modifier.weight(1f))
                PaintModeButton("Erase", SupportPaintMode.ERASE, activeMode, onPaintMode, Modifier.weight(1f))
            }
            OutlinedButton(onClick = onClose) { Text("Stop painting") }
        }
    }
}

@Composable
private fun PaintModeButton(
    label: String,
    mode: SupportPaintMode,
    activeMode: SupportPaintMode,
    onPaintMode: (SupportPaintMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (activeMode == mode) {
        Button(onClick = { onPaintMode(SupportPaintMode.NONE) }, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = { onPaintMode(mode) }, modifier = modifier) { Text(label) }
    }
}
