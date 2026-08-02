#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/java/com/tomppi/enderslicer/ui"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    content = path.read_text()
    count = content.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    path.write_text(content.replace(old, new, 1))


def add_saveable_import(path: Path) -> None:
    content = path.read_text()
    if "import androidx.compose.runtime.saveable.rememberSaveable" in content:
        return
    marker = "import androidx.compose.runtime.remember\n"
    count = content.count(marker)
    if count != 1:
        raise RuntimeError(f"{path.name}: expected one remember import, found {count}")
    path.write_text(
        content.replace(
            marker,
            marker + "import androidx.compose.runtime.saveable.rememberSaveable\n",
            1,
        ),
    )


model_tools = UI / "ModelToolsSheet.kt"
add_saveable_import(model_tools)
replace_once(
    model_tools,
    """    var xText by remember { mutableStateOf(placement?.centerXmm?.formatPosition().orEmpty()) }
    var yText by remember { mutableStateOf(placement?.centerYmm?.formatPosition().orEmpty()) }
    var zText by remember { mutableStateOf(placement?.baseZmm?.formatPosition().orEmpty()) }
""",
    """    var xText by rememberSaveable(placement) { mutableStateOf(placement?.centerXmm?.formatPosition().orEmpty()) }
    var yText by rememberSaveable(placement) { mutableStateOf(placement?.centerYmm?.formatPosition().orEmpty()) }
    var zText by rememberSaveable(placement) { mutableStateOf(placement?.baseZmm?.formatPosition().orEmpty()) }
""",
    "model placement drafts",
)
replace_once(
    model_tools,
    """    LaunchedEffect(placement) {
        xText = placement?.centerXmm?.formatPosition().orEmpty()
        yText = placement?.centerYmm?.formatPosition().orEmpty()
        zText = placement?.baseZmm?.formatPosition().orEmpty()
    }

""",
    "",
    "model placement draft recreation guard",
)

layer_events = UI / "LayerEventsSheet.kt"
add_saveable_import(layer_events)
replace_once(
    layer_events,
    """    var type by remember { mutableStateOf(LayerEventType.PAUSE) }
    var typeMenu by remember { mutableStateOf(false) }
    var value by remember(type) { mutableStateOf(defaultValue(type, settings)) }
    var secondary by remember(type) { mutableStateOf(defaultSecondary(type, settings)) }
    var text by remember(type) { mutableStateOf("") }
""",
    """    var type by rememberSaveable(layer.number) { mutableStateOf(LayerEventType.PAUSE) }
    var typeMenu by rememberSaveable(layer.number) { mutableStateOf(false) }
    var value by rememberSaveable(layer.number, type) { mutableStateOf(defaultValue(type, settings)) }
    var secondary by rememberSaveable(layer.number, type) { mutableStateOf(defaultSecondary(type, settings)) }
    var text by rememberSaveable(layer.number, type) { mutableStateOf("") }
""",
    "layer event drafts",
)

mesh_limit = UI / "MeshTriangleLimitSheet.kt"
add_saveable_import(mesh_limit)
replace_once(
    mesh_limit,
    """    var valueText by remember(currentLimit) { mutableStateOf(currentLimit.toString()) }
    var presetMenu by remember { mutableStateOf(false) }
""",
    """    var valueText by rememberSaveable(currentLimit) { mutableStateOf(currentLimit.toString()) }
    var presetMenu by rememberSaveable { mutableStateOf(false) }
""",
    "mesh limit draft",
)

calibration = UI / "CalibrationGeneratorSheet.kt"
add_saveable_import(calibration)
replace_once(
    calibration,
    """    var type by remember { mutableStateOf(CalibrationTestType.TEMPERATURE) }
    var start by remember(type) { mutableStateOf(format(type.defaultStart)) }
    var step by remember(type) { mutableStateOf(format(type.defaultStep)) }
    var levels by remember(type) { mutableStateOf(type.defaultLevels.toString()) }
    var sectionHeight by remember { mutableStateOf("4") }
    var width by remember { mutableStateOf("16") }
    var typeMenu by remember { mutableStateOf(false) }
""",
    """    var type by rememberSaveable { mutableStateOf(CalibrationTestType.TEMPERATURE) }
    var start by rememberSaveable(type) { mutableStateOf(format(type.defaultStart)) }
    var step by rememberSaveable(type) { mutableStateOf(format(type.defaultStep)) }
    var levels by rememberSaveable(type) { mutableStateOf(type.defaultLevels.toString()) }
    var sectionHeight by rememberSaveable { mutableStateOf("4") }
    var width by rememberSaveable { mutableStateOf("16") }
    var typeMenu by rememberSaveable { mutableStateOf(false) }
""",
    "calibration drafts",
)

machine = UI / "MachineSettingsSheet.kt"
add_saveable_import(machine)
replace_once(
    machine,
    "    var text by remember(value) { mutableStateOf(value.toString().trimEnd('0').trimEnd('.')) }\n",
    "    var text by rememberSaveable(value) { mutableStateOf(value.toString().trimEnd('0').trimEnd('.')) }\n",
    "machine numeric draft",
)

octoprint = UI / "HardenedOctoPrintSheet.kt"
add_saveable_import(octoprint)
replacements = [
    (
        """    var page by remember {
        mutableStateOf(if (state.isReady) HardenedOctoPrintPage.STATUS else HardenedOctoPrintPage.SETUP)
    }
    var pendingUploadPrintDirectory by remember { mutableStateOf<String?>(null) }
""",
        """    var page by rememberSaveable {
        mutableStateOf(if (state.isReady) HardenedOctoPrintPage.STATUS else HardenedOctoPrintPage.SETUP)
    }
    var pendingUploadPrintDirectory by rememberSaveable { mutableStateOf<String?>(null) }
""",
        "OctoPrint page and upload workflow",
    ),
    (
        "    var remoteDirectory by remember { mutableStateOf(\"\") }\n",
        "    var remoteDirectory by rememberSaveable { mutableStateOf(\"\") }\n",
        "OctoPrint upload directory draft",
    ),
    (
        """    var filter by remember { mutableStateOf("") }
    var parentPath by remember { mutableStateOf("") }
    var folderName by remember { mutableStateOf("") }
""",
        """    var filter by rememberSaveable { mutableStateOf("") }
    var parentPath by rememberSaveable { mutableStateOf("") }
    var folderName by rememberSaveable { mutableStateOf("") }
""",
        "OctoPrint file browser drafts",
    ),
    (
        "    var destination by remember { mutableStateOf(\"\") }\n",
        "    var destination by rememberSaveable { mutableStateOf(\"\") }\n",
        "OctoPrint destination draft",
    ),
    (
        """    var port by remember { mutableStateOf("") }
    var baudrate by remember { mutableStateOf("") }
    var profile by remember { mutableStateOf("") }
    var saveConnection by remember { mutableStateOf(false) }
    var autoConnect by remember { mutableStateOf(false) }
    var autoConnectEdited by remember { mutableStateOf(false) }
    var jogStep by remember { mutableStateOf(1.0) }
    var toolTarget by remember { mutableStateOf("200") }
    var bedTarget by remember { mutableStateOf("60") }
    var extrusion by remember { mutableStateOf("5") }
    var feedRate by remember { mutableStateOf("100") }
    var flowRate by remember { mutableStateOf("100") }
    var command by remember { mutableStateOf("") }
""",
        """    var port by rememberSaveable { mutableStateOf("") }
    var baudrate by rememberSaveable { mutableStateOf("") }
    var profile by rememberSaveable { mutableStateOf("") }
    var saveConnection by rememberSaveable { mutableStateOf(false) }
    var autoConnect by rememberSaveable { mutableStateOf(false) }
    var autoConnectEdited by rememberSaveable { mutableStateOf(false) }
    var jogStep by rememberSaveable { mutableStateOf(1.0) }
    var toolTarget by rememberSaveable { mutableStateOf("200") }
    var bedTarget by rememberSaveable { mutableStateOf("60") }
    var extrusion by rememberSaveable { mutableStateOf("5") }
    var feedRate by rememberSaveable { mutableStateOf("100") }
    var flowRate by rememberSaveable { mutableStateOf("100") }
    var command by rememberSaveable { mutableStateOf("") }
""",
        "OctoPrint control drafts",
    ),
    (
        """    var baseUrl by remember { mutableStateOf(state.config.baseUrl) }
    var username by remember { mutableStateOf(state.config.username) }
    var apiKey by remember { mutableStateOf("") }
    var snapshotUrl by remember { mutableStateOf(state.config.snapshotUrlOverride) }
    var pollSeconds by remember { mutableStateOf(state.config.pollIntervalSeconds.toString()) }
""",
        """    var baseUrl by rememberSaveable(state.config.baseUrl) { mutableStateOf(state.config.baseUrl) }
    var username by rememberSaveable(state.config.username) { mutableStateOf(state.config.username) }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var snapshotUrl by rememberSaveable(state.config.snapshotUrlOverride) { mutableStateOf(state.config.snapshotUrlOverride) }
    var pollSeconds by rememberSaveable(state.config.pollIntervalSeconds) { mutableStateOf(state.config.pollIntervalSeconds.toString()) }
""",
        "OctoPrint setup drafts",
    ),
]
for old, new, label in replacements:
    replace_once(octoprint, old, new, label)

print("Finalized saveable UI draft state")
