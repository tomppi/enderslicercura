package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.calibration.CalibrationSliceState
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.model.resolveEndGcode
import com.tomppi.enderslicer.model.resolveStartGcode
import com.tomppi.enderslicer.model.withSettings
import com.tomppi.enderslicer.profile.CuraEngineProfile
import com.tomppi.enderslicer.profile.CuraSettingDelta
import com.tomppi.enderslicer.smartinfill.SmartInfillModifier
import com.tomppi.enderslicer.smartinfill.SmartInfillRuntime
import com.tomppi.enderslicer.smartinfill.requireValidBinaryStl
import java.io.File

object CuraEngineCommand {
    fun buildResolved(
        executablePath: String,
        definitionsDirectory: String,
        resolvedSettingsPath: String,
        outputPath: String,
        threadCount: Int = recommendedThreadCount(),
    ): List<String> {
        require(threadCount in 1..32) { "Invalid CuraEngine thread count: $threadCount" }
        listOf(executablePath, definitionsDirectory, resolvedSettingsPath, outputPath)
            .forEach(::requireSafeArgument)
        return listOf(
            executablePath,
            "slice",
            "-m$threadCount",
            "-d",
            definitionsDirectory,
            "-r",
            resolvedSettingsPath,
            "-o",
            outputPath,
        )
    }

    fun build(
        executablePath: String,
        definitionsDirectory: String,
        machineDefinitionPath: String,
        extruderDefinitionPath: String,
        modelPath: String,
        outputPath: String,
        printer: PrinterDefinition,
        settings: SlicerSettings,
        startGcode: String,
        endGcode: String,
        profile: CuraEngineProfile? = null,
        smartInfillModifiers: List<SmartInfillModifier> = emptyList(),
        threadCount: Int = recommendedThreadCount(),
    ): List<String> {
        require(profile == null) {
            "Imported Cura configurations must be dependency-resolved before command generation"
        }
        require(threadCount in 1..32) { "Invalid CuraEngine thread count: $threadCount" }
        listOf(
            executablePath,
            definitionsDirectory,
            machineDefinitionPath,
            extruderDefinitionPath,
            modelPath,
            outputPath,
        ).forEach(::requireSafeArgument)

        val workspace = File(outputPath).parentFile
            ?: error("CuraEngine output path has no parent workspace")
        val activeSmartInfill = SmartInfillRuntime.current()
        activeSmartInfill?.requireMatchesSource(File(modelPath))
        val effectiveSmartInfillModifiers = if (smartInfillModifiers.isNotEmpty()) {
            smartInfillModifiers
        } else {
            activeSmartInfill?.stageModifiers(workspace).orEmpty()
        }
        effectiveSmartInfillModifiers.forEach { modifier ->
            requireSafeArgument(modifier.file.absolutePath)
            requireValidBinaryStl(modifier.file, Int.MAX_VALUE)
        }

        val effectiveSettings = CalibrationSliceState.effective(settings)
        val effectivePrinter = printer.withSettings(effectiveSettings)
        val printerEnvelope = PrinterEnvelope.from(effectivePrinter)
        File(modelPath).takeIf(File::isFile)?.let(printerEnvelope::requireBinaryStlFits)
        effectiveSmartInfillModifiers.forEach { modifier -> printerEnvelope.requireBinaryStlFits(modifier.file) }
        val effectiveStartGcode = effectiveSettings.resolveStartGcode(startGcode)
        val effectiveEndGcode = effectiveSettings.resolveEndGcode(endGcode)
        val engineOffsetX = if (effectivePrinter.originAtCenter) 0.0 else -effectivePrinter.widthMm / 2.0
        val engineOffsetY = if (effectivePrinter.originAtCenter) 0.0 else -effectivePrinter.depthMm / 2.0
        requireSafeArgument(effectiveStartGcode)
        requireSafeArgument(effectiveEndGcode)

        val command = mutableListOf(
            executablePath,
            "slice",
            "-m$threadCount",
            "-d",
            definitionsDirectory,
            "--force-read-parent",
            "-j",
            machineDefinitionPath,
            "--end-force-read",
        )

        fun setting(key: String, value: Any) {
            val normalized = when (value) {
                is Boolean -> value.toString().lowercase()
                else -> value.toString()
            }
            requireSafeArgument(key)
            requireSafeArgument(normalized)
            command += "-s"
            command += "$key=$normalized"
        }

        fun applySmartInfillWidths() {
            val width = activeSmartInfill?.lineWidthMm ?: return
            SMART_INFILL_WIDTH_KEYS.forEach { key -> setting(key, width) }
        }

        fun applyStandaloneSettings() {
            CuraSettingDelta.standaloneValues(effectiveSettings).forEach { (key, value) -> setting(key, value) }
            ArcOverhangEngineSettings.values(effectiveSettings).forEach { (key, value) -> setting(key, value) }
            WaveOverhangEngineSettings.values(effectiveSettings).forEach { (key, value) -> setting(key, value) }
            CalibrationSliceState.engineOverrides().forEach { (key, value) -> setting(key, value) }
            applySmartInfillWidths()
        }

        // CuraEngine does not evaluate Cura frontend formulas for command-line
        // values. A density label without these derived values does not change
        // the actual regional toolpath spacing. Reproduce the pinned Cura 5.11
        // formulas for every fallback Smart Infill mesh.
        fun applySmartInfillDensity(densityPercent: Double) {
            val packageValue = activeSmartInfill ?: return
            require(densityPercent in 0.0..100.0) { "Invalid Smart Infill density: $densityPercent" }
            val lineWidth = packageValue.lineWidthMm
            val pattern = effectiveSettings.infillPattern.lowercase()
            val patternFactor = when (pattern) {
                "grid" -> 2.0
                "triangles", "trihexagon", "cubic", "cubicsubdiv" -> 3.0
                "tetrahedral", "quarter_cubic" -> 2.0
                "cross", "cross_3d" -> 1.0
                "lightning" -> 1.6
                else -> 1.0
            }
            val lineDistance = if (densityPercent <= 0.0) {
                0.0
            } else {
                lineWidth * 100.0 / densityPercent * patternFactor
            }
            val overlapPercent = if (densityPercent < 95.0 && pattern != "concentric") 10.0 else 0.0
            val wallLineWidth = lineWidth
            val overlapMm = if (overlapPercent > 0.0) {
                0.5 * (lineWidth + wallLineWidth) * overlapPercent / 100.0
            } else {
                0.0
            }
            val densityArgument: Number = if (densityPercent % 1.0 == 0.0) {
                densityPercent.toInt()
            } else {
                densityPercent
            }
            setting("infill_sparse_density", densityArgument)
            setting("infill_pattern", pattern)
            applySmartInfillWidths()
            setting("infill_line_distance", lineDistance)
            setting("infill_overlap", overlapPercent)
            setting("infill_overlap_mm", overlapMm)
            setting(
                "extra_infill_lines_to_support_skins",
                if (densityPercent > 50.0) "none" else "walls_and_lines",
            )
        }

        // CuraEngine consumes centering/rotation state while -l loads geometry.
        // Ordinary per-mesh position and role settings must instead follow -l,
        // because its stateful CLI parser applies -s to the mesh just loaded.
        fun prepareMeshLoad() {
            setting("center_object", false)
            setting("mesh_rotation_matrix", "[[1,0,0],[0,1,0],[0,0,1]]")
        }

        fun positionLoadedMesh() {
            setting("mesh_position_x", engineOffsetX)
            setting("mesh_position_y", engineOffsetY)
            setting("mesh_position_z", 0)
        }

        setting("machine_name", effectivePrinter.name)
        setting("machine_width", effectivePrinter.widthMm)
        setting("machine_depth", effectivePrinter.depthMm)
        setting("machine_height", effectivePrinter.heightMm)
        setting("machine_shape", effectivePrinter.buildPlateShape)
        setting("machine_center_is_zero", effectivePrinter.originAtCenter)
        setting("machine_heated_bed", effectivePrinter.heatedBed)
        setting("machine_heated_build_volume", effectivePrinter.heatedBuildVolume)
        setting("machine_extruder_count", effectivePrinter.extruders)
        setting("machine_gcode_flavor", effectivePrinter.gcodeFlavor)
        setting("machine_start_gcode", effectiveStartGcode)
        setting("machine_end_gcode", effectiveEndGcode)
        setting("gantry_height", effectivePrinter.gantryHeightMm)
        setting("machine_nozzle_size", effectivePrinter.nozzleSizeMm)
        setting("material_diameter", effectivePrinter.filamentDiameterMm)
        setting(
            "machine_head_with_fans_polygon",
            "[[${effectivePrinter.printheadXMinMm},${effectivePrinter.printheadYMaxMm}],[${effectivePrinter.printheadXMinMm},${effectivePrinter.printheadYMinMm}],[${effectivePrinter.printheadXMaxMm},${effectivePrinter.printheadYMinMm}],[${effectivePrinter.printheadXMaxMm},${effectivePrinter.printheadYMaxMm}]]",
        )
        applyStandaloneSettings()

        command += listOf(
            "-e0",
            "--force-read-parent",
            "-j",
            machineDefinitionPath,
            "-j",
            extruderDefinitionPath,
            "--end-force-read",
        )

        applyStandaloneSettings()
        setting("extruder_nr", 0)
        setting("machine_nozzle_size", effectivePrinter.nozzleSizeMm)
        setting("material_diameter", effectivePrinter.filamentDiameterMm)

        val interfaceHeight = effectiveSettings.layerHeightMm * 4.0
        val density = effectiveSettings.supportInterfaceDensityPercent.coerceIn(0.0, 100.0)
        val lineDistance = if (density <= 0.0) 0.0 else effectiveSettings.lineWidthMm * 100.0 / density * 2.0
        setting("support_interface_extruder_nr", 0)
        setting("support_roof_extruder_nr", 0)
        setting("support_bottom_extruder_nr", 0)
        setting("support_interface_height", interfaceHeight)
        setting("support_interface_pattern", "grid")
        setting("support_roof_line_width", effectiveSettings.lineWidthMm)
        setting("support_bottom_line_width", effectiveSettings.lineWidthMm)
        setting("support_roof_line_distance", lineDistance)
        setting("support_bottom_line_distance", lineDistance)

        prepareMeshLoad()
        command += listOf("-l", modelPath)
        positionLoadedMesh()
        setting("extruder_nr", 0)
        applySmartInfillDensity(activeSmartInfill?.baseDensityPercent ?: effectiveSettings.infillDensityPercent)
        setting("infill_mesh", false)
        setting("support_mesh", false)
        setting("anti_overhang_mesh", false)
        setting("cutting_mesh", false)

        effectiveSmartInfillModifiers
            .sortedBy(SmartInfillModifier::densityPercent)
            .forEachIndexed { index, modifier ->
                // Modifier STL geometry already uses the displayed model's final
                // coordinates; only the common Cura bed-origin offset is needed.
                prepareMeshLoad()
                command += listOf("-l", modifier.file.absolutePath)
                positionLoadedMesh()
                setting("extruder_nr", 0)
                setting("infill_mesh", true)
                setting("infill_mesh_order", index + 1)
                applySmartInfillDensity(modifier.densityPercent.toDouble())
                setting("support_mesh", false)
                setting("anti_overhang_mesh", false)
                setting("cutting_mesh", false)
            }

        command += listOf("-o", outputPath)
        return command
    }

    private fun recommendedThreadCount(): Int = CpuTopology.detect().recommendedThreadCount

    internal fun recommendedThreadCount(
        availableProcessors: Int,
        hardwareProcessors: Int,
    ): Int = CpuTopology.recommendedThreadCount(availableProcessors, hardwareProcessors)

    private fun requireSafeArgument(value: String) {
        require('\u0000' !in value) { "CuraEngine argument contains a NUL character" }
    }

    private val SMART_INFILL_WIDTH_KEYS = listOf(
        "line_width",
        "wall_line_width",
        "wall_line_width_0",
        "wall_line_width_x",
        "skin_line_width",
        "infill_line_width",
    )
}
