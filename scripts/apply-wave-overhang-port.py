#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace(path: str, old: str, new: str, count: int = 1) -> None:
    target = ROOT / path
    text = target.read_text()
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"Expected block not found in {path}: {old[:120]!r}")
    target.write_text(text.replace(old, new, count))


def insert_after(path: str, marker: str, addition: str) -> None:
    target = ROOT / path
    text = target.read_text()
    if addition.strip() in text:
        return
    if marker not in text:
        raise SystemExit(f"Marker not found in {path}: {marker!r}")
    target.write_text(text.replace(marker, marker + addition, 1))

# Settings model and keys.
settings = "app/src/main/java/com/tomppi/enderslicer/model/SlicerSettings.kt"
insert_after(
    settings,
    "    val arcOverhangFanSpeedPercent: Double = 100.0,\n",
    """    val waveOverhangEnabled: Boolean = false,
    val waveOverhangPattern: String = "smart",
    val waveOverhangLineSpacingMm: Double = 0.35,
    val waveOverhangFlowMm3PerMm: Double = 0.16,
    val waveOverhangSpeedMmPerSecond: Double = 5.0,
    val waveOverhangFanSpeedPercent: Double = 100.0,
    val waveOverhangPerimeterOverlapMm: Double = 0.10,
    val waveOverhangMinimumWidthMm: Double = 0.70,
    val waveOverhangMaxIterations: Int = 400,
    val waveOverhangReverseOddLayers: Boolean = true,
""",
)
insert_after(
    settings,
    "        const val ARC_OVERHANG_FAN_SPEED = \"arcOverhangFanSpeedPercent\"\n",
    """        const val WAVE_OVERHANG_ENABLED = "waveOverhangEnabled"
        const val WAVE_OVERHANG_PATTERN = "waveOverhangPattern"
        const val WAVE_OVERHANG_LINE_SPACING = "waveOverhangLineSpacingMm"
        const val WAVE_OVERHANG_FLOW = "waveOverhangFlowMm3PerMm"
        const val WAVE_OVERHANG_SPEED = "waveOverhangSpeedMmPerSecond"
        const val WAVE_OVERHANG_FAN_SPEED = "waveOverhangFanSpeedPercent"
        const val WAVE_OVERHANG_PERIMETER_OVERLAP = "waveOverhangPerimeterOverlapMm"
        const val WAVE_OVERHANG_MINIMUM_WIDTH = "waveOverhangMinimumWidthMm"
        const val WAVE_OVERHANG_MAX_ITERATIONS = "waveOverhangMaxIterations"
        const val WAVE_OVERHANG_REVERSE_ODD_LAYERS = "waveOverhangReverseOddLayers"
""",
)

# Dependency-resolved transport to the native patch.
resolver = "app/src/main/java/com/tomppi/enderslicer/profile/CuraSliceSettingsResolver.kt"
insert_after(
    resolver,
    "import com.tomppi.enderslicer.engine.ArcOverhangEngineSettings\n",
    "import com.tomppi.enderslicer.engine.WaveOverhangEngineSettings\n",
)
insert_after(
    resolver,
    "            putAll(ArcOverhangEngineSettings.values(effectiveSettings))\n",
    "            putAll(WaveOverhangEngineSettings.values(effectiveSettings))\n",
)

# Persistent app overrides.
store = "app/src/main/java/com/tomppi/enderslicer/data/AppStateStore.kt"
insert_after(
    store,
    "            .put(SlicerSettings.Keys.ARC_OVERHANG_FAN_SPEED, settings.arcOverhangFanSpeedPercent)\n",
    """            .put(SlicerSettings.Keys.WAVE_OVERHANG_ENABLED, settings.waveOverhangEnabled)
            .put(SlicerSettings.Keys.WAVE_OVERHANG_PATTERN, settings.waveOverhangPattern)
            .put(SlicerSettings.Keys.WAVE_OVERHANG_LINE_SPACING, settings.waveOverhangLineSpacingMm)
            .put(SlicerSettings.Keys.WAVE_OVERHANG_FLOW, settings.waveOverhangFlowMm3PerMm)
            .put(SlicerSettings.Keys.WAVE_OVERHANG_SPEED, settings.waveOverhangSpeedMmPerSecond)
            .put(SlicerSettings.Keys.WAVE_OVERHANG_FAN_SPEED, settings.waveOverhangFanSpeedPercent)
            .put(SlicerSettings.Keys.WAVE_OVERHANG_PERIMETER_OVERLAP, settings.waveOverhangPerimeterOverlapMm)
            .put(SlicerSettings.Keys.WAVE_OVERHANG_MINIMUM_WIDTH, settings.waveOverhangMinimumWidthMm)
            .put(SlicerSettings.Keys.WAVE_OVERHANG_MAX_ITERATIONS, settings.waveOverhangMaxIterations)
            .put(SlicerSettings.Keys.WAVE_OVERHANG_REVERSE_ODD_LAYERS, settings.waveOverhangReverseOddLayers)
""",
)
insert_after(
    store,
    "                SlicerSettings.Keys.ARC_OVERHANG_FAN_SPEED -> restored.copy(arcOverhangFanSpeedPercent = values.optDouble(key, restored.arcOverhangFanSpeedPercent))\n",
    """                SlicerSettings.Keys.WAVE_OVERHANG_ENABLED -> restored.copy(waveOverhangEnabled = values.optBoolean(key, restored.waveOverhangEnabled))
                SlicerSettings.Keys.WAVE_OVERHANG_PATTERN -> restored.copy(waveOverhangPattern = values.optString(key, restored.waveOverhangPattern))
                SlicerSettings.Keys.WAVE_OVERHANG_LINE_SPACING -> restored.copy(waveOverhangLineSpacingMm = values.optDouble(key, restored.waveOverhangLineSpacingMm))
                SlicerSettings.Keys.WAVE_OVERHANG_FLOW -> restored.copy(waveOverhangFlowMm3PerMm = values.optDouble(key, restored.waveOverhangFlowMm3PerMm))
                SlicerSettings.Keys.WAVE_OVERHANG_SPEED -> restored.copy(waveOverhangSpeedMmPerSecond = values.optDouble(key, restored.waveOverhangSpeedMmPerSecond))
                SlicerSettings.Keys.WAVE_OVERHANG_FAN_SPEED -> restored.copy(waveOverhangFanSpeedPercent = values.optDouble(key, restored.waveOverhangFanSpeedPercent))
                SlicerSettings.Keys.WAVE_OVERHANG_PERIMETER_OVERLAP -> restored.copy(waveOverhangPerimeterOverlapMm = values.optDouble(key, restored.waveOverhangPerimeterOverlapMm))
                SlicerSettings.Keys.WAVE_OVERHANG_MINIMUM_WIDTH -> restored.copy(waveOverhangMinimumWidthMm = values.optDouble(key, restored.waveOverhangMinimumWidthMm))
                SlicerSettings.Keys.WAVE_OVERHANG_MAX_ITERATIONS -> restored.copy(waveOverhangMaxIterations = values.optInt(key, restored.waveOverhangMaxIterations))
                SlicerSettings.Keys.WAVE_OVERHANG_REVERSE_ODD_LAYERS -> restored.copy(waveOverhangReverseOddLayers = values.optBoolean(key, restored.waveOverhangReverseOddLayers))
""",
)

# Experimental settings UI. Enabling one strategy disables the other.
ui = "app/src/main/java/com/tomppi/enderslicer/ui/CategorizedSettingsSheet.kt"
insert_after(
    ui,
    "        SettingsCategory(\"Experimental\") {\n",
    """            SwitchRow(
                "Wave overhangs (experimental)",
                settings.waveOverhangEnabled,
                source(state, SlicerSettings.Keys.WAVE_OVERHANG_ENABLED),
            ) {
                onSettings(SlicerSettings.Keys.WAVE_OVERHANG_ENABLED) { current ->
                    current.copy(
                        waveOverhangEnabled = it,
                        arcOverhangEnabled = if (it) false else current.arcOverhangEnabled,
                    )
                }
            }
            if (settings.waveOverhangEnabled) {
                Text(
                    "Expanding wavefronts grow from model-supported material into open-air bottom skin. Use maximum cooling and verify the layer preview before printing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OptionField(
                    label = "Wave traversal",
                    value = settings.waveOverhangPattern,
                    options = listOf(
                        "smart" to "Smart · supported-first",
                        "monotonic" to "Monotonic · independent fronts",
                        "zigzag" to "Zigzag · alternating fronts",
                    ),
                    source = source(state, SlicerSettings.Keys.WAVE_OVERHANG_PATTERN),
                ) {
                    onSettings(SlicerSettings.Keys.WAVE_OVERHANG_PATTERN) { current -> current.copy(waveOverhangPattern = it) }
                }
                NumberField("Wave spacing (mm)", settings.waveOverhangLineSpacingMm, source(state, SlicerSettings.Keys.WAVE_OVERHANG_LINE_SPACING), decimals = 3) {
                    onSettings(SlicerSettings.Keys.WAVE_OVERHANG_LINE_SPACING) { current -> current.copy(waveOverhangLineSpacingMm = it.coerceIn(0.1, 2.0)) }
                }
                NumberField("Wave flow (mm³/mm)", settings.waveOverhangFlowMm3PerMm, source(state, SlicerSettings.Keys.WAVE_OVERHANG_FLOW), decimals = 3) {
                    onSettings(SlicerSettings.Keys.WAVE_OVERHANG_FLOW) { current -> current.copy(waveOverhangFlowMm3PerMm = it.coerceIn(0.02, 1.5)) }
                }
                NumberField("Wave speed (mm/s)", settings.waveOverhangSpeedMmPerSecond, source(state, SlicerSettings.Keys.WAVE_OVERHANG_SPEED)) {
                    onSettings(SlicerSettings.Keys.WAVE_OVERHANG_SPEED) { current -> current.copy(waveOverhangSpeedMmPerSecond = it.coerceIn(0.5, 50.0)) }
                }
                NumberField("Wave fan speed (%)", settings.waveOverhangFanSpeedPercent, source(state, SlicerSettings.Keys.WAVE_OVERHANG_FAN_SPEED), decimals = 0) {
                    onSettings(SlicerSettings.Keys.WAVE_OVERHANG_FAN_SPEED) { current -> current.copy(waveOverhangFanSpeedPercent = it.coerceIn(0.0, 100.0)) }
                }
                NumberField("Perimeter overlap (mm)", settings.waveOverhangPerimeterOverlapMm, source(state, SlicerSettings.Keys.WAVE_OVERHANG_PERIMETER_OVERLAP), decimals = 3) {
                    onSettings(SlicerSettings.Keys.WAVE_OVERHANG_PERIMETER_OVERLAP) { current -> current.copy(waveOverhangPerimeterOverlapMm = it.coerceIn(0.0, 2.0)) }
                }
                NumberField("Minimum wave width (mm)", settings.waveOverhangMinimumWidthMm, source(state, SlicerSettings.Keys.WAVE_OVERHANG_MINIMUM_WIDTH), decimals = 3) {
                    onSettings(SlicerSettings.Keys.WAVE_OVERHANG_MINIMUM_WIDTH) { current -> current.copy(waveOverhangMinimumWidthMm = it.coerceIn(0.0, 10.0)) }
                }
                NumberField("Maximum wavefronts", settings.waveOverhangMaxIterations.toDouble(), source(state, SlicerSettings.Keys.WAVE_OVERHANG_MAX_ITERATIONS), decimals = 0) {
                    onSettings(SlicerSettings.Keys.WAVE_OVERHANG_MAX_ITERATIONS) { current -> current.copy(waveOverhangMaxIterations = it.toInt().coerceIn(1, 2000)) }
                }
                SwitchRow("Reverse front order on odd layers", settings.waveOverhangReverseOddLayers, source(state, SlicerSettings.Keys.WAVE_OVERHANG_REVERSE_ODD_LAYERS)) {
                    onSettings(SlicerSettings.Keys.WAVE_OVERHANG_REVERSE_ODD_LAYERS) { current -> current.copy(waveOverhangReverseOddLayers = it) }
                }
            }

""",
)
replace(
    ui,
    "                    current.copy(arcOverhangEnabled = it)\n",
    "                    current.copy(arcOverhangEnabled = it, waveOverhangEnabled = if (it) false else current.waveOverhangEnabled)\n",
)

# Preview classification and rare-feature retention.
preview = "app/src/main/java/com/tomppi/enderslicer/engine/GcodeLayerPreview.kt"
replace(preview, "        ARC_OVERHANG(5),\n", "        ARC_OVERHANG(5),\n        WAVE_OVERHANG(6),\n")
insert_after(preview, "    private val RARE_FEATURE_PRIORITY = listOf(\n", "        GcodeLayerPreview.Feature.WAVE_OVERHANG,\n")
insert_after(
    preview,
    "            value.contains(\"ARC-OVERHANG\") || value.contains(\"ARC_OVERHANG\") -> GcodeLayerPreview.Feature.ARC_OVERHANG\n",
    "            value.contains(\"WAVE-OVERHANG\") || value.contains(\"WAVE_OVERHANG\") -> GcodeLayerPreview.Feature.WAVE_OVERHANG\n",
)
renderer = "app/src/main/java/com/tomppi/enderslicer/viewer/LayerPreviewSurfaceView.kt"
insert_after(
    renderer,
    "            GcodeLayerPreview.Feature.ARC_OVERHANG -> {\n                red = 0.72f; green = 0.38f; blue = 1f\n            }\n",
    """            GcodeLayerPreview.Feature.WAVE_OVERHANG -> {
                red = 0.12f; green = 0.92f; blue = 0.82f
            }
""",
)

# Native CuraEngine integration.
build = "scripts/build-curaengine-android.sh"
insert_after(
    build,
    '(root / "src" / "ArcOverhang.cpp").write_text((arc_patch_root / "src" / "ArcOverhang.cpp").read_text())\n',
    '(root / "include" / "WaveOverhang.h").write_text((arc_patch_root / "include" / "WaveOverhang.h").read_text())\n(root / "src" / "WaveOverhang.cpp").write_text((arc_patch_root / "src" / "WaveOverhang.cpp").read_text())\n',
)
replace(
    build,
    '    bool is_arc_overhang{ false }; //!< EnderSlicer native Multiplex path; used only for an exact G-code preview marker\n    double fan_speed',
    '    bool is_arc_overhang{ false }; //!< EnderSlicer native Multiplex path\n    bool is_wave_overhang{ false }; //!< EnderSlicer native wavefront path\n    double fan_speed',
)
replace(
    build,
    '                                         || last_extrusion_config.value().is_arc_overhang != path.config.is_arc_overhang;',
    '                                         || last_extrusion_config.value().is_arc_overhang != path.config.is_arc_overhang\n                                         || last_extrusion_config.value().is_wave_overhang != path.config.is_wave_overhang;',
)
replace(
    build,
    '''                if (path.config.is_arc_overhang)
                {
                    // App-owned semantic marker. Firmware ignores comments, while
                    // EnderSlicer's layer preview can classify these paths exactly.
                    gcode.writeComment("TYPE:ARC-OVERHANG");
                }
                else''',
    '''                if (path.config.is_wave_overhang)
                {
                    gcode.writeComment("TYPE:WAVE-OVERHANG");
                }
                else if (path.config.is_arc_overhang)
                {
                    // App-owned semantic marker. Firmware ignores comments, while
                    // EnderSlicer's layer preview can classify these paths exactly.
                    gcode.writeComment("TYPE:ARC-OVERHANG");
                }
                else''',
)
replace(build, "        src/Application.cpp\\n        src/ArcOverhang.cpp\\n", "        src/Application.cpp\\n        src/ArcOverhang.cpp\\n        src/WaveOverhang.cpp\\n")
replace(build, '#include "Application.h"\\n#include "ArcOverhang.h"\\n', '#include "Application.h"\\n#include "ArcOverhang.h"\\n#include "WaveOverhang.h"\\n')
replace(
    build,
    '''    Shape arc_supported_skin_regions;
    const bool arc_overhang_enabled = layer_nr > 0 && mesh.settings.get<bool>("enderslicer_arc_overhang_enabled");
    if (arc_overhang_enabled)
    {
        // Ignore generated support here: Multiplex must be anchored to
        // material printed as part of the model on the previous layer.
        bridgeAngle(mesh.settings, skin_part.skin_fill, storage, layer_nr, 1, nullptr, arc_supported_skin_regions);
    }
''',
    '''    Shape arc_supported_skin_regions;
    Shape wave_supported_skin_regions;
    const bool wave_overhang_enabled = layer_nr > 0 && mesh.settings.get<bool>("enderslicer_wave_overhang_enabled");
    const bool arc_overhang_enabled = layer_nr > 0
                                   && mesh.settings.get<bool>("enderslicer_arc_overhang_enabled")
                                   && ! wave_overhang_enabled;
    if (arc_overhang_enabled || wave_overhang_enabled)
    {
        Shape& supported = wave_overhang_enabled ? wave_supported_skin_regions : arc_supported_skin_regions;
        // Ignore generated support: both strategies must start on model material.
        bridgeAngle(mesh.settings, skin_part.skin_fill, storage, layer_nr, 1, nullptr, supported);
    }
''',
)
wave_block = '''    if (wave_overhang_enabled && ! wave_supported_skin_regions.empty())
    {
        WaveOverhangParameters wave_parameters;
        wave_parameters.line_spacing = mesh.settings.get<coord_t>("enderslicer_wave_overhang_line_spacing");
        wave_parameters.perimeter_overlap = mesh.settings.get<coord_t>("enderslicer_wave_overhang_perimeter_overlap");
        wave_parameters.minimum_width = mesh.settings.get<coord_t>("enderslicer_wave_overhang_minimum_width");
        wave_parameters.max_iterations = mesh.settings.get<size_t>("enderslicer_wave_overhang_max_iterations");
        wave_parameters.pattern = mesh.settings.get<std::string>("enderslicer_wave_overhang_pattern");
        wave_parameters.reverse_order = mesh.settings.get<bool>("enderslicer_wave_overhang_reverse_odd_layers") && layer_nr % 2 != 0;

        OpenLinesSet wave_lines;
        if (WaveOverhangGenerator::generate(
                skin_part.skin_fill,
                wave_supported_skin_regions,
                wave_parameters,
                wave_lines))
        {
            GCodePathConfig wave_config = *skin_config;
            wave_config.is_wave_overhang = true;
            wave_config.speed_derivatives.speed = mesh.settings.get<Velocity>("enderslicer_wave_overhang_speed");
            const double line_width_mm = std::max(INT2MM(skin_config->getLineWidth()), 0.001);
            const double layer_height_mm = std::max(INT2MM(mesh.settings.get<coord_t>("layer_height")), 0.001);
            const double nominal_mm3_per_mm = line_width_mm * layer_height_mm;
            const double wave_flow = mesh.settings.get<double>("enderslicer_wave_overhang_flow_mm3_per_mm") / nominal_mm3_per_mm;
            const double wave_fan_speed = mesh.settings.get<double>("enderslicer_wave_overhang_fan_speed");

            added_something = true;
            gcode_layer.setIsInside(true);
            for (const OpenPolyline& wave_line : wave_lines)
            {
                if (! wave_line.isValid())
                {
                    continue;
                }
                OpenLinesSet ordered_wave;
                ordered_wave.push_back(wave_line, CheckNonEmptyParam::OnlyIfValid);
                gcode_layer.addLinesByOptimizer(
                    ordered_wave,
                    wave_config,
                    SpaceFillType::PolyLines,
                    false,
                    0,
                    wave_flow,
                    std::nullopt,
                    wave_fan_speed);
            }
            return;
        }
        // Incomplete propagation falls through to Cura's normal bridge/skin path.
    }

'''
insert_after(build, "    const bool monotonic = mesh.settings.get<bool>(\"skin_monotonic\");\n", wave_block)

# CI source and packaged marker checks.
workflow = ".github/workflows/build.yml"
insert_after(workflow, "          grep -q 'is_arc_overhang' .build/CuraEngine/include/GCodePathConfig.h\n", "          grep -q 'is_wave_overhang' .build/CuraEngine/include/GCodePathConfig.h\n          grep -q 'TYPE:WAVE-OVERHANG' .build/CuraEngine/src/LayerPlan.cpp\n          test -s .build/CuraEngine/src/WaveOverhang.cpp\n")

# Documentation and attribution.
readme = "README.md"
insert_after(
    readme,
    "- Native experimental Multiplex arc-overhang paths with bridge fallback\n",
    "- Native experimental wave-overhang wavefront paths with all-or-nothing bridge fallback\n",
)
insert_after(
    readme,
    "## Native arc overhangs\n",
    """## Native wave overhangs

Enable **Print settings → Experimental → Wave overhangs** to replace eligible open-air bottom skin with expanding, clipped wavefronts seeded on material from the previous model layer. Smart, monotonic and zigzag traversal are available. Wave paths are turquoise in the layer preview and use an absolute mm³/mm flow setting because the bead is deposited into open air.

Wave and Arc overhangs are mutually exclusive. The generator is all-or-nothing per skin island: missing anchors, incomplete propagation or iteration limits retain Cura's normal bridge/skin path. The feature is disabled by default and remains experimental; maximum cooling and a small test model are strongly recommended.

## Native arc overhangs
""",
)
notices = "THIRD_PARTY_NOTICES.md"
insert_after(
    notices,
    "# Third-party notices\n",
    """
## Wave-overhang algorithm research and reference implementation

The native EnderSlicerCura wavefront generator is an independent CuraEngine adaptation of the propagation method documented by `dennisklappe/OrcaSlicer-WaveOverhangs`, itself based on `stmcculloch/PrusaSlicer-WaveOverhangs`. Those projects and CuraEngine are distributed under the GNU AGPL. The adapted source is retained under `native/curaengine/patches/` with attribution headers.

""",
)

print("Wave-overhang integration applied")
