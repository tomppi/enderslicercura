from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {path}, found {count}: {old[:100]!r}")
    path.write_text(text.replace(old, new, 1))


integrated = Path("app/src/main/java/com/tomppi/enderslicer/ui/IntegratedEnderSlicerApp.kt")
replace_once(
    integrated,
    "import androidx.compose.foundation.layout.Arrangement\n"
    "import androidx.compose.foundation.layout.Box\n"
    "import androidx.compose.foundation.layout.Column\n",
    "import androidx.compose.foundation.layout.Box\n",
)
replace_once(
    integrated,
    "    var profilesOpen by remember { mutableStateOf(false) }\n"
    "    var octoPrintOpen by remember { mutableStateOf(false) }\n",
    "    var octoPrintOpen by remember { mutableStateOf(false) }\n",
)
replace_once(
    integrated,
    """        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 94.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            ExtendedFloatingActionButton(
                onClick = {
                    octoPrintOpen = false
                    profilesOpen = true
                },
            ) {
                Text("Profiles & filament")
            }
            ExtendedFloatingActionButton(
                onClick = {
                    profilesOpen = false
                    octoPrintOpen = true
                },
            ) {
                Text(
                    when {
                        octoPrintState.isPrinting -> "OctoPrint ${octoPrintState.job.completionPercent?.toInt() ?: 0}%"
                        octoPrintState.isPaused -> "OctoPrint paused"
                        octoPrintState.isTransitioning -> "OctoPrint busy"
                        octoPrintState.isReady -> "OctoPrint"
                        else -> "Set up OctoPrint"
                    },
                )
            }
        }
""",
    """        ExtendedFloatingActionButton(
            onClick = { octoPrintOpen = true },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp),
        ) {
            Text(
                when {
                    octoPrintState.isPrinting -> "OctoPrint ${octoPrintState.job.completionPercent?.toInt() ?: 0}%"
                    octoPrintState.isPaused -> "OctoPrint paused"
                    octoPrintState.isTransitioning -> "OctoPrint busy"
                    octoPrintState.isReady -> "OctoPrint"
                    else -> "Set up OctoPrint"
                },
            )
        }
""",
)
replace_once(
    integrated,
    """
    if (profilesOpen) {
        ModalBottomSheet(
            onDismissRequest = { profilesOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ProfileManagementSheet(
                state = slicerState,
                viewModel = slicerViewModel,
                modifier = Modifier
                    .fillMaxHeight(0.96f)
                    .navigationBarsPadding(),
            )
        }
    }

""",
    "\n",
)

app = Path("app/src/main/java/com/tomppi/enderslicer/ui/EnderSlicerApp.kt")
replace_once(
    app,
    "    var settingsOpen by remember { mutableStateOf(false) }\n",
    "    var settingsOpen by remember { mutableStateOf(false) }\n"
    "    var profilesOpen by remember { mutableStateOf(false) }\n",
)
replace_once(
    app,
    """                            DropdownMenuItem(
                                text = { Text("Print settings") },
""",
    """                            DropdownMenuItem(
                                text = { Text("Profiles & filament") },
                                onClick = {
                                    menuExpanded = false
                                    profilesOpen = true
                                },
                                enabled = !state.isBusy,
                            )
                            DropdownMenuItem(
                                text = { Text("Print settings") },
""",
)
replace_once(
    app,
    "\n    if (settingsOpen) {\n",
    """
    if (profilesOpen) {
        ModalBottomSheet(
            onDismissRequest = { profilesOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
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

    if (settingsOpen) {
""",
)

workflow = Path(".github/workflows/build.yml")
workflow_text = workflow.read_text()
workflow_text = workflow_text.replace(
    "\npermissions:\n  contents: write\n",
    "",
    1,
)
workflow_text = workflow_text.replace(
    """      - uses: actions/checkout@v4
        with:
          ref: feature/profile-filament-management

      - name: Apply control placement update
        run: python scripts/move_profile_octoprint_controls.py

""",
    """      - uses: actions/checkout@v4

""",
    1,
)
workflow_text = workflow_text.replace(
    """
      - name: Commit validated control placement
        run: |
          git config user.name "github-actions[bot]"
          git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
          git add -A
          git commit -m "Move profiles to menu and OctoPrint to top center"
          git push origin HEAD:feature/profile-filament-management
""",
    "",
    1,
)
workflow.write_text(workflow_text)

Path(__file__).unlink()
print("Moved profile management into Menu and OctoPrint to the upper center")
