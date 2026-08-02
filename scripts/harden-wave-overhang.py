#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace(path: str, old: str, new: str, count: int = 1) -> None:
    target = ROOT / path
    text = target.read_text()
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"Expected block not found in {path}: {old[:140]!r}")
    target.write_text(text.replace(old, new, count))


def insert_after(path: str, marker: str, addition: str) -> None:
    target = ROOT / path
    text = target.read_text()
    if addition.strip() in text:
        return
    if marker not in text:
        raise SystemExit(f"Marker not found in {path}: {marker!r}")
    target.write_text(text.replace(marker, marker + addition, 1))

# Preserve supported-to-unsupported level order. Odd-layer variation may reverse
# traversal within each wavefront, but must never print the outer wave first.
header = "native/curaengine/patches/include/WaveOverhang.h"
replace(header, "    bool reverse_order{};\n", "    bool reverse_direction{};\n")

wave = "native/curaengine/patches/src/WaveOverhang.cpp"
replace(
    wave,
    '''    if (parameters.reverse_order)
    {
        std::reverse(levels.begin(), levels.end());
    }
    for (size_t level_index = 0; level_index < levels.size(); ++level_index)
    {
        auto& lines = levels[level_index].getLines();
        if (parameters.pattern == "zigzag" && level_index % 2 == 1)
        {
            std::reverse(lines.begin(), lines.end());
        }
        for (OpenPolyline& line : lines)
        {
            output.push_back(std::move(line), CheckNonEmptyParam::OnlyIfValid);
        }
    }
''',
    '''    for (size_t level_index = 0; level_index < levels.size(); ++level_index)
    {
        auto& lines = levels[level_index].getLines();
        const bool reverse_level
            = (parameters.pattern == "zigzag" && level_index % 2 == 1) != parameters.reverse_direction;
        if (reverse_level)
        {
            std::reverse(lines.begin(), lines.end());
            for (OpenPolyline& line : lines)
            {
                line.reverse();
            }
        }
        for (OpenPolyline& line : lines)
        {
            output.push_back(std::move(line), CheckNonEmptyParam::OnlyIfValid);
        }
    }
''',
)

build = "scripts/build-curaengine-android.sh"
replace(
    build,
    '        wave_parameters.reverse_order = mesh.settings.get<bool>("enderslicer_wave_overhang_reverse_odd_layers") && layer_nr % 2 != 0;\n',
    '        wave_parameters.reverse_direction = mesh.settings.get<bool>("enderslicer_wave_overhang_reverse_odd_layers") && layer_nr % 2 != 0;\n',
)

ui = "app/src/main/java/com/tomppi/enderslicer/ui/CategorizedSettingsSheet.kt"
replace(
    ui,
    'SwitchRow("Reverse front order on odd layers", settings.waveOverhangReverseOddLayers,',
    'SwitchRow("Reverse wave direction on odd layers", settings.waveOverhangReverseOddLayers,',
)

# Print presets must include every Wave control and reject an Arc+Wave conflict.
preset = "app/src/main/java/com/tomppi/enderslicer/profile/PresetSettings.kt"
insert_after(
    preset,
    "        SlicerSettings.Keys.ARC_OVERHANG_FAN_SPEED,\n",
    '''        SlicerSettings.Keys.WAVE_OVERHANG_ENABLED,
        SlicerSettings.Keys.WAVE_OVERHANG_PATTERN,
        SlicerSettings.Keys.WAVE_OVERHANG_LINE_SPACING,
        SlicerSettings.Keys.WAVE_OVERHANG_FLOW,
        SlicerSettings.Keys.WAVE_OVERHANG_SPEED,
        SlicerSettings.Keys.WAVE_OVERHANG_FAN_SPEED,
        SlicerSettings.Keys.WAVE_OVERHANG_PERIMETER_OVERLAP,
        SlicerSettings.Keys.WAVE_OVERHANG_MINIMUM_WIDTH,
        SlicerSettings.Keys.WAVE_OVERHANG_MAX_ITERATIONS,
        SlicerSettings.Keys.WAVE_OVERHANG_REVERSE_ODD_LAYERS,
''',
)
insert_after(
    preset,
    "                SlicerSettings.Keys.ARC_OVERHANG_FAN_SPEED -> changed.copy(arcOverhangFanSpeedPercent = values.optDouble(key, changed.arcOverhangFanSpeedPercent))\n",
    '''                SlicerSettings.Keys.WAVE_OVERHANG_ENABLED -> changed.copy(waveOverhangEnabled = values.optBoolean(key, changed.waveOverhangEnabled))
                SlicerSettings.Keys.WAVE_OVERHANG_PATTERN -> changed.copy(waveOverhangPattern = values.optString(key, changed.waveOverhangPattern))
                SlicerSettings.Keys.WAVE_OVERHANG_LINE_SPACING -> changed.copy(waveOverhangLineSpacingMm = values.optDouble(key, changed.waveOverhangLineSpacingMm))
                SlicerSettings.Keys.WAVE_OVERHANG_FLOW -> changed.copy(waveOverhangFlowMm3PerMm = values.optDouble(key, changed.waveOverhangFlowMm3PerMm))
                SlicerSettings.Keys.WAVE_OVERHANG_SPEED -> changed.copy(waveOverhangSpeedMmPerSecond = values.optDouble(key, changed.waveOverhangSpeedMmPerSecond))
                SlicerSettings.Keys.WAVE_OVERHANG_FAN_SPEED -> changed.copy(waveOverhangFanSpeedPercent = values.optDouble(key, changed.waveOverhangFanSpeedPercent))
                SlicerSettings.Keys.WAVE_OVERHANG_PERIMETER_OVERLAP -> changed.copy(waveOverhangPerimeterOverlapMm = values.optDouble(key, changed.waveOverhangPerimeterOverlapMm))
                SlicerSettings.Keys.WAVE_OVERHANG_MINIMUM_WIDTH -> changed.copy(waveOverhangMinimumWidthMm = values.optDouble(key, changed.waveOverhangMinimumWidthMm))
                SlicerSettings.Keys.WAVE_OVERHANG_MAX_ITERATIONS -> changed.copy(waveOverhangMaxIterations = values.optInt(key, changed.waveOverhangMaxIterations))
                SlicerSettings.Keys.WAVE_OVERHANG_REVERSE_ODD_LAYERS -> changed.copy(waveOverhangReverseOddLayers = values.optBoolean(key, changed.waveOverhangReverseOddLayers))
''',
)
insert_after(
    preset,
    '        require(appliedKeys.isNotEmpty()) { "The preset has no usable ${kind.label.lowercase()} values" }\n',
    '''        require(!(changed.arcOverhangEnabled && changed.waveOverhangEnabled)) {
            "Arc and Wave overhangs cannot both be enabled"
        }
''',
)

sanitizer = "app/src/main/java/com/tomppi/enderslicer/profile/PresetValueSanitizer.kt"
insert_after(
    sanitizer,
    '''                require(settings.arcOverhangMinRadiusMm <= settings.arcOverhangMaxRadiusMm) {
                    "Arc-overhang minimum radius must not exceed its maximum radius"
                }
''',
    '''                require(!(settings.arcOverhangEnabled && settings.waveOverhangEnabled)) {
                    "Arc and Wave overhangs cannot both be enabled"
                }
''',
)
insert_after(
    sanitizer,
    '''        val arcMaximum = number(SlicerSettings.Keys.ARC_OVERHANG_MAX_RADIUS)
        if (arcMinimum != null && arcMaximum != null) {
            require(arcMinimum <= arcMaximum) {
                "Arc-overhang minimum radius must not exceed its maximum radius"
            }
        }
''',
    '''
        val arcEnabled = values.opt(SlicerSettings.Keys.ARC_OVERHANG_ENABLED) as? Boolean
        val waveEnabled = values.opt(SlicerSettings.Keys.WAVE_OVERHANG_ENABLED) as? Boolean
        require(arcEnabled != true || waveEnabled != true) {
            "Arc and Wave overhangs cannot both be enabled"
        }
''',
)
insert_after(
    sanitizer,
    "        SlicerSettings.Keys.ARC_OVERHANG_ENABLED,\n",
    '''        SlicerSettings.Keys.WAVE_OVERHANG_ENABLED,
        SlicerSettings.Keys.WAVE_OVERHANG_REVERSE_ODD_LAYERS,
''',
)
insert_after(
    sanitizer,
    "        SlicerSettings.Keys.FAN_FULL_AT_LAYER,\n",
    "        SlicerSettings.Keys.WAVE_OVERHANG_MAX_ITERATIONS,\n",
)
insert_after(
    sanitizer,
    "        SlicerSettings.Keys.ADHESION_TYPE,\n",
    "        SlicerSettings.Keys.WAVE_OVERHANG_PATTERN,\n",
)
insert_after(
    sanitizer,
    '''        SlicerSettings.Keys.Z_SEAM_CORNER to setOf(
            "z_seam_corner_none",
            "z_seam_corner_inner",
            "z_seam_corner_outer",
            "z_seam_corner_any",
            "z_seam_corner_weighted",
        ),
''',
    '        SlicerSettings.Keys.WAVE_OVERHANG_PATTERN to setOf("smart", "monotonic", "zigzag"),\n',
)
insert_after(
    sanitizer,
    "        SlicerSettings.Keys.ARC_OVERHANG_FAN_SPEED to 0.0..100.0,\n",
    '''        SlicerSettings.Keys.WAVE_OVERHANG_LINE_SPACING to 0.1..2.0,
        SlicerSettings.Keys.WAVE_OVERHANG_FLOW to 0.02..1.5,
        SlicerSettings.Keys.WAVE_OVERHANG_SPEED to 0.5..50.0,
        SlicerSettings.Keys.WAVE_OVERHANG_FAN_SPEED to 0.0..100.0,
        SlicerSettings.Keys.WAVE_OVERHANG_PERIMETER_OVERLAP to 0.0..2.0,
        SlicerSettings.Keys.WAVE_OVERHANG_MINIMUM_WIDTH to 0.0..10.0,
        SlicerSettings.Keys.WAVE_OVERHANG_MAX_ITERATIONS to 1.0..2000.0,
''',
)

# Focused round-trip and hostile-preset regression coverage.
test_path = ROOT / "app/src/test/java/com/tomppi/enderslicer/profile/WaveOverhangPresetTest.kt"
test_path.write_text('''package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveOverhangPresetTest {
    @Test
    fun printPresetRoundTripsEveryWaveSetting() {
        val source = SlicerSettings(
            waveOverhangEnabled = true,
            waveOverhangPattern = "zigzag",
            waveOverhangLineSpacingMm = 0.42,
            waveOverhangFlowMm3PerMm = 0.19,
            waveOverhangSpeedMmPerSecond = 4.5,
            waveOverhangFanSpeedPercent = 98.0,
            waveOverhangPerimeterOverlapMm = 0.12,
            waveOverhangMinimumWidthMm = 0.84,
            waveOverhangMaxIterations = 525,
            waveOverhangReverseOddLayers = false,
        )
        val values = PresetSettings.capture(PresetKind.PRINT, source)
        val restored = PresetSettings.apply(PresetKind.PRINT, SlicerSettings(), values)

        assertTrue(restored.waveOverhangEnabled)
        assertEquals("zigzag", restored.waveOverhangPattern)
        assertEquals(0.42, restored.waveOverhangLineSpacingMm, 0.0001)
        assertEquals(0.19, restored.waveOverhangFlowMm3PerMm, 0.0001)
        assertEquals(4.5, restored.waveOverhangSpeedMmPerSecond, 0.0001)
        assertEquals(98.0, restored.waveOverhangFanSpeedPercent, 0.0001)
        assertEquals(0.12, restored.waveOverhangPerimeterOverlapMm, 0.0001)
        assertEquals(0.84, restored.waveOverhangMinimumWidthMm, 0.0001)
        assertEquals(525, restored.waveOverhangMaxIterations)
        assertFalse(restored.waveOverhangReverseOddLayers)
    }

    @Test
    fun sanitizerRejectsInvalidPatternAndArcWaveConflict() {
        val invalidPattern = JSONObject()
            .put(SlicerSettings.Keys.WAVE_OVERHANG_PATTERN, "random")
        val conflicting = JSONObject()
            .put(SlicerSettings.Keys.ARC_OVERHANG_ENABLED, true)
            .put(SlicerSettings.Keys.WAVE_OVERHANG_ENABLED, true)

        assertTrue(runCatching { PresetValueSanitizer.sanitize(PresetKind.PRINT, invalidPattern) }.isFailure)
        assertTrue(runCatching { PresetValueSanitizer.sanitize(PresetKind.PRINT, conflicting) }.isFailure)
    }
}
''')

print("Applied final Wave Overhang hardening")
