package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.calibration.CalibrationSliceState
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.model.resolveEndGcode
import com.tomppi.enderslicer.model.resolveStartGcode
import com.tomppi.enderslicer.model.withSettings
import com.tomppi.enderslicer.profile.CuraEngineProfile
import com.tomppi.enderslicer.profile.CuraSettingDelta
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

        val effectiveSettings = CalibrationSliceState.effective(settings)
        val effectivePrinter = printer.withSettings(effectiveSettings)
        File(modelPath).takeIf(File::isFile)?.let { stagedModel ->
            PrinterEnvelope.from(effectivePrinter).requireBinaryStlFits(stagedModel)
        }
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

        fun applyStandaloneSettings() {
            CuraSettingDelta.standaloneValues(effectiveSettings).forEach { (key, value) -> setting(key, value) }
            ArcOverhangEngineSettings.values(effectiveSettings).forEach { (key, value) -> setting(key, value) }
            CalibrationSliceState.engineOverrides().forEach { (key, value) -> setting(key, value) }
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
        setting("center_object", false)
        setting("mesh_position_x", engineOffsetX)
        setting("mesh_position_y", engineOffsetY)
        setting("mesh_position_z", 0)
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

        setting("center_object", false)
        setting("mesh_position_x", engineOffsetX)
        setting("mesh_position_y", engineOffsetY)
        setting("mesh_position_z", 0)

        command += listOf("-l", modelPath, "-o", outputPath)
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
}
