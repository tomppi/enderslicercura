package com.tomppi.enderslicer.data

import android.content.Context
import com.tomppi.enderslicer.model.SlicerSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

class AppStateStore(context: Context) {
    data class SavedImport(
        val kind: String,
        val displayName: String,
        val file: File,
    )

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val stateDirectory = File(appContext.filesDir, "persistent-state").apply { mkdirs() }
    private val legacyImportFile = File(stateDirectory, "current-cura-import.bin")
    private val importBundle = File(stateDirectory, "current-cura-import.bundle")
    private val materializedImport = File(stateDirectory, "current-cura-import.materialized")

    fun stageImport(input: InputStream): File {
        val temporary = File(stateDirectory, "current-cura-import.tmp")
        temporary.delete()
        try {
            input.buffered().use { source ->
                temporary.outputStream().buffered().use { destination ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_CURA_IMPORT_BYTES) {
                            "The imported Cura file exceeds the 128 MiB safety limit"
                        }
                        destination.write(buffer, 0, count)
                    }
                }
            }
            check(temporary.isFile && temporary.length() > 0L) { "The imported Cura file is empty" }
            return temporary
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    @Synchronized
    fun commitImport(staged: File, kind: String, displayName: String) {
        require(kind == KIND_PROJECT || kind == KIND_PROFILE) { "Unsupported Cura import kind: $kind" }
        require(staged.isFile && staged.length() in 1..MAX_CURA_IMPORT_BYTES) {
            "The staged Cura configuration is unavailable"
        }
        val next = File(stateDirectory, "current-cura-import.bundle.next")
        val backup = File(stateDirectory, "current-cura-import.bundle.previous")
        next.delete()
        backup.delete()
        val payloadSha = sha256(staged)
        val metadata = JSONObject()
            .put("version", IMPORT_BUNDLE_VERSION)
            .put("kind", kind)
            .put("displayName", displayName.take(MAX_IMPORT_NAME_CHARS))
            .put("payloadBytes", staged.length())
            .put("payloadSha256", payloadSha)
            .toString()
            .toByteArray(Charsets.UTF_8)
        require(metadata.size <= MAX_IMPORT_METADATA_BYTES) { "Cura import metadata is too large" }

        try {
            FileOutputStream(next).use { fileOutput ->
                val output = DataOutputStream(BufferedOutputStream(fileOutput))
                output.write(IMPORT_BUNDLE_MAGIC)
                output.writeInt(metadata.size)
                output.writeLong(staged.length())
                output.write(metadata)
                staged.inputStream().buffered().use { input -> input.copyTo(output) }
                output.flush()
                fileOutput.fd.sync()
            }
            check(next.isFile && next.length() > staged.length()) { "Unable to stage the Cura import bundle" }
            if (importBundle.exists()) {
                check(importBundle.renameTo(backup)) { "Unable to preserve the previous Cura import bundle" }
            }
            try {
                check(next.renameTo(importBundle)) { "Unable to publish the Cura import bundle" }
            } catch (error: Throwable) {
                importBundle.delete()
                if (backup.exists()) backup.renameTo(importBundle)
                throw error
            }
            backup.delete()
            materializedImport.delete()
            legacyImportFile.delete()
            staged.delete()
            preferences.edit().remove(KEY_IMPORT_KIND).remove(KEY_IMPORT_NAME).commit()
        } finally {
            next.delete()
            if (backup.exists() && importBundle.exists()) backup.delete()
        }
    }

    @Synchronized
    fun savedImport(): SavedImport? {
        recoverImportBundle()
        if (importBundle.isFile) return materializeBundle()

        // One-time compatibility with the pre-bundle format.
        val kind = preferences.getString(KEY_IMPORT_KIND, null) ?: return null
        val displayName = preferences.getString(KEY_IMPORT_NAME, null) ?: "Restored Cura configuration"
        if (!legacyImportFile.isFile || legacyImportFile.length() == 0L) return null
        return SavedImport(kind, displayName, legacyImportFile)
    }

    private fun recoverImportBundle() {
        val next = File(stateDirectory, "current-cura-import.bundle.next")
        val backup = File(stateDirectory, "current-cura-import.bundle.previous")
        if (!importBundle.exists()) {
            when {
                next.isFile -> next.renameTo(importBundle)
                backup.isFile -> backup.renameTo(importBundle)
            }
        }
        if (importBundle.isFile) {
            next.delete()
            backup.delete()
        }
    }

    private fun materializeBundle(): SavedImport {
        DataInputStream(BufferedInputStream(importBundle.inputStream())).use { input ->
            val magic = ByteArray(IMPORT_BUNDLE_MAGIC.size).also(input::readFully)
            require(magic.contentEquals(IMPORT_BUNDLE_MAGIC)) { "Saved Cura import bundle has an invalid header" }
            val metadataSize = input.readInt()
            val payloadSize = input.readLong()
            require(metadataSize in 1..MAX_IMPORT_METADATA_BYTES) { "Saved Cura import metadata is invalid" }
            require(payloadSize in 1..MAX_CURA_IMPORT_BYTES) { "Saved Cura import payload is invalid" }
            val metadata = JSONObject(ByteArray(metadataSize).also(input::readFully).toString(Charsets.UTF_8))
            require(metadata.getInt("version") == IMPORT_BUNDLE_VERSION) { "Unsupported Cura import bundle version" }
            require(metadata.getLong("payloadBytes") == payloadSize) { "Saved Cura import length metadata is inconsistent" }
            val expectedSha = metadata.getString("payloadSha256")
            require(expectedSha.matches(Regex("[0-9a-f]{64}"))) { "Saved Cura import fingerprint is invalid" }
            val kind = metadata.getString("kind")
            require(kind == KIND_PROJECT || kind == KIND_PROFILE) { "Saved Cura import kind is invalid" }
            val displayName = metadata.getString("displayName").take(MAX_IMPORT_NAME_CHARS)

            val existingMatches = materializedImport.isFile && materializedImport.length() == payloadSize &&
                runCatching { sha256(materializedImport) == expectedSha }.getOrDefault(false)
            if (!existingMatches) {
                val next = File(stateDirectory, "current-cura-import.materialized.next")
                next.delete()
                val digest = MessageDigest.getInstance("SHA-256")
                FileOutputStream(next).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var remaining = payloadSize
                    while (remaining > 0L) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        require(count > 0) { "Saved Cura import bundle ended unexpectedly" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        remaining -= count.toLong()
                    }
                    output.fd.sync()
                }
                require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == expectedSha) {
                    "Saved Cura import payload fingerprint does not match"
                }
                materializedImport.delete()
                check(next.renameTo(materializedImport)) { "Unable to materialize the saved Cura import" }
            }
            return SavedImport(kind, displayName, materializedImport)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun clearSavedSettings() {
        preferences.edit().remove(KEY_SETTINGS).apply()
    }

    fun saveSettings(settings: SlicerSettings): Boolean {
        val values = JSONObject()
            .put(SlicerSettings.Keys.PRINTER_NAME, settings.printerName)
            .put(SlicerSettings.Keys.MACHINE_WIDTH, settings.machineWidthMm)
            .put(SlicerSettings.Keys.MACHINE_DEPTH, settings.machineDepthMm)
            .put(SlicerSettings.Keys.MACHINE_HEIGHT, settings.machineHeightMm)
            .put(SlicerSettings.Keys.BUILD_PLATE_SHAPE, settings.buildPlateShape)
            .put(SlicerSettings.Keys.ORIGIN_AT_CENTER, settings.originAtCenter)
            .put(SlicerSettings.Keys.HEATED_BED, settings.heatedBed)
            .put(SlicerSettings.Keys.HEATED_BUILD_VOLUME, settings.heatedBuildVolume)
            .put(SlicerSettings.Keys.GCODE_FLAVOR, settings.gcodeFlavor)
            .put(SlicerSettings.Keys.NOZZLE_SIZE, settings.nozzleSizeMm)
            .put(SlicerSettings.Keys.FILAMENT_DIAMETER, settings.filamentDiameterMm)
            .put(SlicerSettings.Keys.PRINTHEAD_X_MIN, settings.printheadXMinMm)
            .put(SlicerSettings.Keys.PRINTHEAD_Y_MIN, settings.printheadYMinMm)
            .put(SlicerSettings.Keys.PRINTHEAD_X_MAX, settings.printheadXMaxMm)
            .put(SlicerSettings.Keys.PRINTHEAD_Y_MAX, settings.printheadYMaxMm)
            .put(SlicerSettings.Keys.GANTRY_HEIGHT, settings.gantryHeightMm)
            .put(SlicerSettings.Keys.CUSTOM_START_GCODE_ENABLED, settings.customStartGcodeEnabled)
            .put(SlicerSettings.Keys.CUSTOM_START_GCODE, settings.customStartGcode)
            .put(SlicerSettings.Keys.CUSTOM_END_GCODE_ENABLED, settings.customEndGcodeEnabled)
            .put(SlicerSettings.Keys.CUSTOM_END_GCODE, settings.customEndGcode)
            .put(SlicerSettings.Keys.LAYER_HEIGHT, settings.layerHeightMm)
            .put(SlicerSettings.Keys.INITIAL_LAYER_HEIGHT, settings.initialLayerHeightMm)
            .put(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_ENABLED, settings.adaptiveLayerHeightEnabled)
            .put(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION, settings.adaptiveLayerHeightVariationMm)
            .put(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION_STEP, settings.adaptiveLayerHeightVariationStepMm)
            .put(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_THRESHOLD, settings.adaptiveLayerHeightThreshold)
            .put(SlicerSettings.Keys.LINE_WIDTH, settings.lineWidthMm)
            .put(SlicerSettings.Keys.SLICING_TOLERANCE, settings.slicingTolerance)
            .put(SlicerSettings.Keys.WALL_LINE_COUNT, settings.wallLineCount)
            .put(SlicerSettings.Keys.WALL_THICKNESS, settings.wallThicknessMm)
            .put(SlicerSettings.Keys.TOP_LAYERS, settings.topLayers)
            .put(SlicerSettings.Keys.BOTTOM_LAYERS, settings.bottomLayers)
            .put(SlicerSettings.Keys.TOP_BOTTOM_THICKNESS, settings.topBottomThicknessMm)
            .put(SlicerSettings.Keys.INITIAL_BOTTOM_LAYERS, settings.initialBottomLayers)
            .put(SlicerSettings.Keys.HOLE_HORIZONTAL_EXPANSION, settings.holeHorizontalExpansionMm)
            .put(
                SlicerSettings.Keys.INITIAL_LAYER_HORIZONTAL_EXPANSION,
                settings.initialLayerHorizontalExpansionMm,
            )
            .put(SlicerSettings.Keys.Z_SEAM_TYPE, settings.zSeamType)
            .put(SlicerSettings.Keys.Z_SEAM_X, settings.zSeamXmm)
            .put(SlicerSettings.Keys.Z_SEAM_Y, settings.zSeamYmm)
            .put(SlicerSettings.Keys.Z_SEAM_RELATIVE, settings.zSeamRelative)
            .put(SlicerSettings.Keys.Z_SEAM_CORNER, settings.zSeamCorner)
            .put(SlicerSettings.Keys.INFILL_DENSITY, settings.infillDensityPercent)
            .put(SlicerSettings.Keys.INFILL_PATTERN, settings.infillPattern)
            .put(SlicerSettings.Keys.ZIG_ZAG_CONNECT_INFILL, settings.zigZagConnectInfill)
            .put(SlicerSettings.Keys.PRINT_SPEED, settings.printSpeedMmPerSecond)
            .put(SlicerSettings.Keys.WALL_SPEED, settings.wallSpeedMmPerSecond)
            .put(SlicerSettings.Keys.OUTER_WALL_SPEED, settings.outerWallSpeedMmPerSecond)
            .put(SlicerSettings.Keys.INNER_WALL_SPEED, settings.innerWallSpeedMmPerSecond)
            .put(SlicerSettings.Keys.INFILL_SPEED, settings.infillSpeedMmPerSecond)
            .put(SlicerSettings.Keys.TOP_BOTTOM_SPEED, settings.topBottomSpeedMmPerSecond)
            .put(SlicerSettings.Keys.TRAVEL_SPEED, settings.travelSpeedMmPerSecond)
            .put(SlicerSettings.Keys.INITIAL_LAYER_SPEED, settings.initialLayerSpeedMmPerSecond)
            .put(SlicerSettings.Keys.NOZZLE_TEMPERATURE, settings.nozzleTemperatureC)
            .put(SlicerSettings.Keys.INITIAL_NOZZLE_TEMPERATURE, settings.initialNozzleTemperatureC)
            .put(SlicerSettings.Keys.BED_TEMPERATURE, settings.bedTemperatureC)
            .put(SlicerSettings.Keys.BUILD_VOLUME_TEMPERATURE, settings.buildVolumeTemperatureC)
            .put(SlicerSettings.Keys.MATERIAL_STANDBY_TEMPERATURE, settings.materialStandbyTemperatureC)
            .put(SlicerSettings.Keys.MATERIAL_DENSITY, settings.materialDensityGPerCm3)
            .put(SlicerSettings.Keys.MATERIAL_ADHESION_TENDENCY, settings.materialAdhesionTendency)
            .put(SlicerSettings.Keys.MATERIAL_SURFACE_ENERGY, settings.materialSurfaceEnergyPercent)
            .put(SlicerSettings.Keys.MATERIAL_FLOW, settings.materialFlowPercent)
            .put(SlicerSettings.Keys.FAN_SPEED, settings.fanSpeedPercent)
            .put(SlicerSettings.Keys.INITIAL_FAN_SPEED, settings.initialFanSpeedPercent)
            .put(SlicerSettings.Keys.FAN_FULL_AT_LAYER, settings.fanFullAtLayer)
            .put(SlicerSettings.Keys.SUPPORTS_ENABLED, settings.supportsEnabled)
            .put(SlicerSettings.Keys.SUPPORT_PLACEMENT, settings.supportPlacement)
            .put(SlicerSettings.Keys.SUPPORT_STRUCTURE, settings.supportStructure)
            .put(SlicerSettings.Keys.SUPPORT_ANGLE, settings.supportAngleDegrees)
            .put(SlicerSettings.Keys.SUPPORT_DENSITY, settings.supportDensityPercent)
            .put(SlicerSettings.Keys.SUPPORT_PATTERN, settings.supportPattern)
            .put(SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED, settings.supportInterfaceEnabled)
            .put(SlicerSettings.Keys.SUPPORT_INTERFACE_DENSITY, settings.supportInterfaceDensityPercent)
            .put(SlicerSettings.Keys.SUPPORT_INTERFACE_HEIGHT, settings.supportInterfaceHeightMm)
            .put(SlicerSettings.Keys.SUPPORT_Z_DISTANCE, settings.supportZDistanceMm)
            .put(SlicerSettings.Keys.SUPPORT_XY_DISTANCE, settings.supportXyDistanceMm)
            .put(SlicerSettings.Keys.SUPPORT_SPEED, settings.supportSpeedMmPerSecond)
            .put(SlicerSettings.Keys.SUPPORT_INTERFACE_SPEED, settings.supportInterfaceSpeedMmPerSecond)
            .put(SlicerSettings.Keys.RETRACTION_DISTANCE, settings.retractionDistanceMm)
            .put(SlicerSettings.Keys.RETRACTION_SPEED, settings.retractionSpeedMmPerSecond)
            .put(SlicerSettings.Keys.RETRACTION_MINIMUM_TRAVEL, settings.retractionMinimumTravelMm)
            .put(SlicerSettings.Keys.RETRACT_AT_LAYER_CHANGE, settings.retractAtLayerChange)
            .put(SlicerSettings.Keys.COMBING_MODE, settings.combingMode)
            .put(SlicerSettings.Keys.AVOID_PRINTED_PARTS, settings.avoidPrintedParts)
            .put(SlicerSettings.Keys.TRAVEL_AVOID_DISTANCE, settings.travelAvoidDistanceMm)
            .put(SlicerSettings.Keys.Z_HOP, settings.zHopEnabled)
            .put(SlicerSettings.Keys.Z_HOP_HEIGHT, settings.zHopHeightMm)
            .put(SlicerSettings.Keys.FIRMWARE_RETRACTION, settings.firmwareRetraction)
            .put(SlicerSettings.Keys.COASTING_ENABLED, settings.coastingEnabled)
            .put(SlicerSettings.Keys.COASTING_VOLUME, settings.coastingVolumeMm3)
            .put(SlicerSettings.Keys.COASTING_MINIMUM_VOLUME, settings.coastingMinimumVolumeMm3)
            .put(SlicerSettings.Keys.COASTING_SPEED, settings.coastingSpeedPercent)
            .put(SlicerSettings.Keys.ADHESION_TYPE, settings.adhesionType)
            .put(SlicerSettings.Keys.SKIRT_LINE_COUNT, settings.skirtLineCount)
            .put(SlicerSettings.Keys.BRIM_WIDTH, settings.brimWidthMm)
            .put(SlicerSettings.Keys.RAFT_MARGIN, settings.raftMarginMm)
            .put(SlicerSettings.Keys.ARC_OVERHANG_ENABLED, settings.arcOverhangEnabled)
            .put(SlicerSettings.Keys.ARC_OVERHANG_SPEED, settings.arcOverhangSpeedMmPerSecond)
            .put(SlicerSettings.Keys.ARC_OVERHANG_FLOW, settings.arcOverhangFlowPercent)
            .put(SlicerSettings.Keys.ARC_OVERHANG_LINE_SPACING, settings.arcOverhangLineSpacingPercent)
            .put(SlicerSettings.Keys.ARC_OVERHANG_MIN_RADIUS, settings.arcOverhangMinRadiusMm)
            .put(SlicerSettings.Keys.ARC_OVERHANG_MAX_RADIUS, settings.arcOverhangMaxRadiusMm)
            .put(SlicerSettings.Keys.ARC_OVERHANG_MAX_AREA, settings.arcOverhangMaxAreaMm2)
            .put(SlicerSettings.Keys.ARC_OVERHANG_RESOLUTION, settings.arcOverhangResolutionMm)
            .put(SlicerSettings.Keys.ARC_OVERHANG_FAN_SPEED, settings.arcOverhangFanSpeedPercent)
            .put(SlicerSettings.Keys.WAVE_OVERHANG_ENABLED, settings.waveOverhangEnabled)
            .put(SlicerSettings.Keys.WAVE_OVERHANG_PATTERN, settings.waveOverhangPattern)
            .put(SlicerSettings.Keys.WAVE_OVERHANG_LINE_SPACING, settings.waveOverhangLineSpacingMm)
            .put(SlicerSettings.Keys.WAVE_OVERHANG_FLOW, settings.waveOverhangFlowMm3PerMm)
            .put(SlicerSettings.Keys.WAVE_OVERHANG_SPEED, settings.waveOverhangSpeedMmPerSecond)
            .put(SlicerSettings.Keys.WAVE_OVERHANG_FAN_SPEED, settings.waveOverhangFanSpeedPercent)
            .put(SlicerSettings.Keys.WAVE_OVERHANG_PERIMETER_OVERLAP, settings.waveOverhangPerimeterOverlapMm)
            .put(SlicerSettings.Keys.WAVE_OVERHANG_MINIMUM_WIDTH, settings.waveOverhangMinimumWidthMm)
            .put(SlicerSettings.Keys.WAVE_OVERHANG_MAX_ITERATIONS, settings.waveOverhangMaxIterations)
            .put(SlicerSettings.Keys.WAVE_OVERHANG_REVERSE_ODD_LAYERS, settings.waveOverhangReverseOddLayers)
            .put(SlicerSettings.Keys.IRONING_ENABLED, settings.ironingEnabled)
            .put(SlicerSettings.Keys.IRONING_ONLY_HIGHEST_LAYER, settings.ironingOnlyHighestLayer)
            .put(SlicerSettings.Keys.IRONING_FLOW, settings.ironingFlowPercent)
            .put(SlicerSettings.Keys.IRONING_SPEED, settings.ironingSpeedMmPerSecond)

        val overrides = JSONArray()
        settings.overriddenSettingKeys.sorted().forEach(overrides::put)
        values.put(KEY_OVERRIDES_JSON, overrides)
        // commit() is required here: the caller writes the workspace descriptor
        // (with its settings-derived fingerprint) immediately afterwards. An
        // async apply() could flush the settings after the workspace file, so a
        // process death between the two would restore stale settings with a new
        // fingerprint and clear calibration state on the next launch.
        // A failed commit is reported to the caller instead of crashing the app.
        return preferences.edit().putString(KEY_SETTINGS, values.toString()).commit()
    }

    fun restoreSettings(base: SlicerSettings): SlicerSettings {
        val encoded = preferences.getString(KEY_SETTINGS, null) ?: return base.copy(overriddenSettingKeys = emptySet())
        val values = runCatching { JSONObject(encoded) }.getOrNull() ?: return base.copy(overriddenSettingKeys = emptySet())
        val overridesArray = values.optJSONArray(KEY_OVERRIDES_JSON) ?: JSONArray()
        val overrides = buildSet {
            for (index in 0 until overridesArray.length()) {
                overridesArray.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }

        var restored = base.copy(overriddenSettingKeys = emptySet())
        overrides.forEach { key ->
            restored = when (key) {
                SlicerSettings.Keys.PRINTER_NAME -> restored.copy(printerName = values.optString(key, restored.printerName))
                SlicerSettings.Keys.MACHINE_WIDTH -> restored.copy(machineWidthMm = values.optDouble(key, restored.machineWidthMm))
                SlicerSettings.Keys.MACHINE_DEPTH -> restored.copy(machineDepthMm = values.optDouble(key, restored.machineDepthMm))
                SlicerSettings.Keys.MACHINE_HEIGHT -> restored.copy(machineHeightMm = values.optDouble(key, restored.machineHeightMm))
                SlicerSettings.Keys.BUILD_PLATE_SHAPE -> restored.copy(buildPlateShape = values.optString(key, restored.buildPlateShape))
                SlicerSettings.Keys.ORIGIN_AT_CENTER -> restored.copy(originAtCenter = values.optBoolean(key, restored.originAtCenter))
                SlicerSettings.Keys.HEATED_BED -> restored.copy(heatedBed = values.optBoolean(key, restored.heatedBed))
                SlicerSettings.Keys.HEATED_BUILD_VOLUME -> restored.copy(heatedBuildVolume = values.optBoolean(key, restored.heatedBuildVolume))
                SlicerSettings.Keys.GCODE_FLAVOR -> restored.copy(gcodeFlavor = values.optString(key, restored.gcodeFlavor))
                SlicerSettings.Keys.NOZZLE_SIZE -> restored.copy(nozzleSizeMm = values.optDouble(key, restored.nozzleSizeMm))
                SlicerSettings.Keys.FILAMENT_DIAMETER -> restored.copy(filamentDiameterMm = values.optDouble(key, restored.filamentDiameterMm))
                SlicerSettings.Keys.PRINTHEAD_X_MIN -> restored.copy(printheadXMinMm = values.optDouble(key, restored.printheadXMinMm))
                SlicerSettings.Keys.PRINTHEAD_Y_MIN -> restored.copy(printheadYMinMm = values.optDouble(key, restored.printheadYMinMm))
                SlicerSettings.Keys.PRINTHEAD_X_MAX -> restored.copy(printheadXMaxMm = values.optDouble(key, restored.printheadXMaxMm))
                SlicerSettings.Keys.PRINTHEAD_Y_MAX -> restored.copy(printheadYMaxMm = values.optDouble(key, restored.printheadYMaxMm))
                SlicerSettings.Keys.GANTRY_HEIGHT -> restored.copy(gantryHeightMm = values.optDouble(key, restored.gantryHeightMm))
                SlicerSettings.Keys.CUSTOM_START_GCODE_ENABLED -> restored.copy(customStartGcodeEnabled = values.optBoolean(key, restored.customStartGcodeEnabled))
                SlicerSettings.Keys.CUSTOM_START_GCODE -> restored.copy(customStartGcode = values.optString(key, restored.customStartGcode))
                SlicerSettings.Keys.CUSTOM_END_GCODE_ENABLED -> restored.copy(customEndGcodeEnabled = values.optBoolean(key, restored.customEndGcodeEnabled))
                SlicerSettings.Keys.CUSTOM_END_GCODE -> restored.copy(customEndGcode = values.optString(key, restored.customEndGcode))
                SlicerSettings.Keys.LAYER_HEIGHT -> restored.copy(layerHeightMm = values.optDouble(key, restored.layerHeightMm))
                SlicerSettings.Keys.INITIAL_LAYER_HEIGHT -> restored.copy(initialLayerHeightMm = values.optDouble(key, restored.initialLayerHeightMm))
                SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_ENABLED -> restored.copy(
                    adaptiveLayerHeightEnabled = values.optBoolean(key, restored.adaptiveLayerHeightEnabled),
                )
                SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION -> restored.copy(
                    adaptiveLayerHeightVariationMm = values.optDouble(key, restored.adaptiveLayerHeightVariationMm),
                )
                SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION_STEP -> restored.copy(
                    adaptiveLayerHeightVariationStepMm = values.optDouble(key, restored.adaptiveLayerHeightVariationStepMm),
                )
                SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_THRESHOLD -> restored.copy(
                    adaptiveLayerHeightThreshold = values.optDouble(key, restored.adaptiveLayerHeightThreshold),
                )
                SlicerSettings.Keys.LINE_WIDTH -> restored.copy(lineWidthMm = values.optDouble(key, restored.lineWidthMm))
                SlicerSettings.Keys.SLICING_TOLERANCE -> restored.copy(slicingTolerance = values.optString(key, restored.slicingTolerance))
                SlicerSettings.Keys.WALL_LINE_COUNT -> restored.copy(wallLineCount = values.optInt(key, restored.wallLineCount))
                SlicerSettings.Keys.WALL_THICKNESS -> restored.copy(wallThicknessMm = values.optDouble(key, restored.wallThicknessMm))
                SlicerSettings.Keys.TOP_LAYERS -> restored.copy(topLayers = values.optInt(key, restored.topLayers))
                SlicerSettings.Keys.BOTTOM_LAYERS -> restored.copy(bottomLayers = values.optInt(key, restored.bottomLayers))
                SlicerSettings.Keys.TOP_BOTTOM_THICKNESS -> restored.copy(topBottomThicknessMm = values.optDouble(key, restored.topBottomThicknessMm))
                SlicerSettings.Keys.INITIAL_BOTTOM_LAYERS -> restored.copy(initialBottomLayers = values.optInt(key, restored.initialBottomLayers))
                SlicerSettings.Keys.HOLE_HORIZONTAL_EXPANSION -> restored.copy(holeHorizontalExpansionMm = values.optDouble(key, restored.holeHorizontalExpansionMm))
                SlicerSettings.Keys.INITIAL_LAYER_HORIZONTAL_EXPANSION -> restored.copy(
                    initialLayerHorizontalExpansionMm = values.optDouble(key, restored.initialLayerHorizontalExpansionMm),
                )
                SlicerSettings.Keys.Z_SEAM_TYPE -> restored.copy(zSeamType = values.optString(key, restored.zSeamType))
                SlicerSettings.Keys.Z_SEAM_X -> restored.copy(zSeamXmm = values.optDouble(key, restored.zSeamXmm))
                SlicerSettings.Keys.Z_SEAM_Y -> restored.copy(zSeamYmm = values.optDouble(key, restored.zSeamYmm))
                SlicerSettings.Keys.Z_SEAM_RELATIVE -> restored.copy(zSeamRelative = values.optBoolean(key, restored.zSeamRelative))
                SlicerSettings.Keys.Z_SEAM_CORNER -> restored.copy(zSeamCorner = values.optString(key, restored.zSeamCorner))
                SlicerSettings.Keys.INFILL_DENSITY -> restored.copy(infillDensityPercent = values.optDouble(key, restored.infillDensityPercent))
                SlicerSettings.Keys.INFILL_PATTERN -> restored.copy(infillPattern = values.optString(key, restored.infillPattern))
                SlicerSettings.Keys.ZIG_ZAG_CONNECT_INFILL -> restored.copy(zigZagConnectInfill = values.optBoolean(key, restored.zigZagConnectInfill))
                SlicerSettings.Keys.PRINT_SPEED -> restored.copy(printSpeedMmPerSecond = values.optDouble(key, restored.printSpeedMmPerSecond))
                SlicerSettings.Keys.WALL_SPEED -> restored.copy(wallSpeedMmPerSecond = values.optDouble(key, restored.wallSpeedMmPerSecond))
                SlicerSettings.Keys.OUTER_WALL_SPEED -> restored.copy(outerWallSpeedMmPerSecond = values.optDouble(key, restored.outerWallSpeedMmPerSecond))
                SlicerSettings.Keys.INNER_WALL_SPEED -> restored.copy(innerWallSpeedMmPerSecond = values.optDouble(key, restored.innerWallSpeedMmPerSecond))
                SlicerSettings.Keys.INFILL_SPEED -> restored.copy(infillSpeedMmPerSecond = values.optDouble(key, restored.infillSpeedMmPerSecond))
                SlicerSettings.Keys.TOP_BOTTOM_SPEED -> restored.copy(topBottomSpeedMmPerSecond = values.optDouble(key, restored.topBottomSpeedMmPerSecond))
                SlicerSettings.Keys.TRAVEL_SPEED -> restored.copy(travelSpeedMmPerSecond = values.optDouble(key, restored.travelSpeedMmPerSecond))
                SlicerSettings.Keys.INITIAL_LAYER_SPEED -> restored.copy(initialLayerSpeedMmPerSecond = values.optDouble(key, restored.initialLayerSpeedMmPerSecond))
                SlicerSettings.Keys.NOZZLE_TEMPERATURE -> restored.copy(nozzleTemperatureC = values.optInt(key, restored.nozzleTemperatureC))
                SlicerSettings.Keys.INITIAL_NOZZLE_TEMPERATURE -> restored.copy(initialNozzleTemperatureC = values.optInt(key, restored.initialNozzleTemperatureC))
                SlicerSettings.Keys.BED_TEMPERATURE -> restored.copy(bedTemperatureC = values.optInt(key, restored.bedTemperatureC))
                SlicerSettings.Keys.BUILD_VOLUME_TEMPERATURE -> restored.copy(buildVolumeTemperatureC = values.optDouble(key, restored.buildVolumeTemperatureC))
                SlicerSettings.Keys.MATERIAL_STANDBY_TEMPERATURE -> restored.copy(materialStandbyTemperatureC = values.optDouble(key, restored.materialStandbyTemperatureC))
                SlicerSettings.Keys.MATERIAL_DENSITY -> restored.copy(materialDensityGPerCm3 = values.optDouble(key, restored.materialDensityGPerCm3))
                SlicerSettings.Keys.MATERIAL_ADHESION_TENDENCY -> restored.copy(materialAdhesionTendency = values.optInt(key, restored.materialAdhesionTendency))
                SlicerSettings.Keys.MATERIAL_SURFACE_ENERGY -> restored.copy(materialSurfaceEnergyPercent = values.optInt(key, restored.materialSurfaceEnergyPercent))
                SlicerSettings.Keys.MATERIAL_FLOW -> restored.copy(materialFlowPercent = values.optDouble(key, restored.materialFlowPercent))
                SlicerSettings.Keys.FAN_SPEED -> restored.copy(fanSpeedPercent = values.optDouble(key, restored.fanSpeedPercent))
                SlicerSettings.Keys.INITIAL_FAN_SPEED -> restored.copy(initialFanSpeedPercent = values.optDouble(key, restored.initialFanSpeedPercent))
                SlicerSettings.Keys.FAN_FULL_AT_LAYER -> restored.copy(fanFullAtLayer = values.optInt(key, restored.fanFullAtLayer))
                SlicerSettings.Keys.SUPPORTS_ENABLED -> restored.copy(supportsEnabled = values.optBoolean(key, restored.supportsEnabled))
                SlicerSettings.Keys.SUPPORT_PLACEMENT -> restored.copy(supportPlacement = values.optString(key, restored.supportPlacement))
                SlicerSettings.Keys.SUPPORT_STRUCTURE -> restored.copy(supportStructure = values.optString(key, restored.supportStructure))
                SlicerSettings.Keys.SUPPORT_ANGLE -> restored.copy(supportAngleDegrees = values.optDouble(key, restored.supportAngleDegrees))
                SlicerSettings.Keys.SUPPORT_DENSITY -> restored.copy(supportDensityPercent = values.optDouble(key, restored.supportDensityPercent))
                SlicerSettings.Keys.SUPPORT_PATTERN -> restored.copy(supportPattern = values.optString(key, restored.supportPattern))
                SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED -> restored.copy(supportInterfaceEnabled = values.optBoolean(key, restored.supportInterfaceEnabled))
                SlicerSettings.Keys.SUPPORT_INTERFACE_DENSITY -> restored.copy(supportInterfaceDensityPercent = values.optDouble(key, restored.supportInterfaceDensityPercent))
                SlicerSettings.Keys.SUPPORT_INTERFACE_HEIGHT -> restored.copy(supportInterfaceHeightMm = values.optDouble(key, restored.supportInterfaceHeightMm))
                SlicerSettings.Keys.SUPPORT_Z_DISTANCE -> restored.copy(supportZDistanceMm = values.optDouble(key, restored.supportZDistanceMm))
                SlicerSettings.Keys.SUPPORT_XY_DISTANCE -> restored.copy(supportXyDistanceMm = values.optDouble(key, restored.supportXyDistanceMm))
                SlicerSettings.Keys.SUPPORT_SPEED -> restored.copy(supportSpeedMmPerSecond = values.optDouble(key, restored.supportSpeedMmPerSecond))
                SlicerSettings.Keys.SUPPORT_INTERFACE_SPEED -> restored.copy(supportInterfaceSpeedMmPerSecond = values.optDouble(key, restored.supportInterfaceSpeedMmPerSecond))
                SlicerSettings.Keys.RETRACTION_DISTANCE -> restored.copy(retractionDistanceMm = values.optDouble(key, restored.retractionDistanceMm))
                SlicerSettings.Keys.RETRACTION_SPEED -> restored.copy(retractionSpeedMmPerSecond = values.optDouble(key, restored.retractionSpeedMmPerSecond))
                SlicerSettings.Keys.RETRACTION_MINIMUM_TRAVEL -> restored.copy(retractionMinimumTravelMm = values.optDouble(key, restored.retractionMinimumTravelMm))
                SlicerSettings.Keys.RETRACT_AT_LAYER_CHANGE -> restored.copy(retractAtLayerChange = values.optBoolean(key, restored.retractAtLayerChange))
                SlicerSettings.Keys.COMBING_MODE -> restored.copy(combingMode = values.optString(key, restored.combingMode))
                SlicerSettings.Keys.AVOID_PRINTED_PARTS -> restored.copy(avoidPrintedParts = values.optBoolean(key, restored.avoidPrintedParts))
                SlicerSettings.Keys.TRAVEL_AVOID_DISTANCE -> restored.copy(travelAvoidDistanceMm = values.optDouble(key, restored.travelAvoidDistanceMm))
                SlicerSettings.Keys.Z_HOP -> restored.copy(zHopEnabled = values.optBoolean(key, restored.zHopEnabled))
                SlicerSettings.Keys.Z_HOP_HEIGHT -> restored.copy(zHopHeightMm = values.optDouble(key, restored.zHopHeightMm))
                SlicerSettings.Keys.FIRMWARE_RETRACTION -> restored.copy(firmwareRetraction = values.optBoolean(key, restored.firmwareRetraction))
                SlicerSettings.Keys.COASTING_ENABLED -> restored.copy(coastingEnabled = values.optBoolean(key, restored.coastingEnabled))
                SlicerSettings.Keys.COASTING_VOLUME -> restored.copy(coastingVolumeMm3 = values.optDouble(key, restored.coastingVolumeMm3))
                SlicerSettings.Keys.COASTING_MINIMUM_VOLUME -> restored.copy(coastingMinimumVolumeMm3 = values.optDouble(key, restored.coastingMinimumVolumeMm3))
                SlicerSettings.Keys.COASTING_SPEED -> restored.copy(coastingSpeedPercent = values.optDouble(key, restored.coastingSpeedPercent))
                SlicerSettings.Keys.ADHESION_TYPE -> restored.copy(adhesionType = values.optString(key, restored.adhesionType))
                SlicerSettings.Keys.SKIRT_LINE_COUNT -> restored.copy(skirtLineCount = values.optInt(key, restored.skirtLineCount))
                SlicerSettings.Keys.BRIM_WIDTH -> restored.copy(brimWidthMm = values.optDouble(key, restored.brimWidthMm))
                SlicerSettings.Keys.RAFT_MARGIN -> restored.copy(raftMarginMm = values.optDouble(key, restored.raftMarginMm))
                SlicerSettings.Keys.ARC_OVERHANG_ENABLED -> restored.copy(arcOverhangEnabled = values.optBoolean(key, restored.arcOverhangEnabled))
                SlicerSettings.Keys.ARC_OVERHANG_SPEED -> restored.copy(arcOverhangSpeedMmPerSecond = values.optDouble(key, restored.arcOverhangSpeedMmPerSecond))
                SlicerSettings.Keys.ARC_OVERHANG_FLOW -> restored.copy(arcOverhangFlowPercent = values.optDouble(key, restored.arcOverhangFlowPercent))
                SlicerSettings.Keys.ARC_OVERHANG_LINE_SPACING -> restored.copy(arcOverhangLineSpacingPercent = values.optDouble(key, restored.arcOverhangLineSpacingPercent))
                SlicerSettings.Keys.ARC_OVERHANG_MIN_RADIUS -> restored.copy(arcOverhangMinRadiusMm = values.optDouble(key, restored.arcOverhangMinRadiusMm))
                SlicerSettings.Keys.ARC_OVERHANG_MAX_RADIUS -> restored.copy(arcOverhangMaxRadiusMm = values.optDouble(key, restored.arcOverhangMaxRadiusMm))
                SlicerSettings.Keys.ARC_OVERHANG_MAX_AREA -> restored.copy(arcOverhangMaxAreaMm2 = values.optDouble(key, restored.arcOverhangMaxAreaMm2))
                SlicerSettings.Keys.ARC_OVERHANG_RESOLUTION -> restored.copy(arcOverhangResolutionMm = values.optDouble(key, restored.arcOverhangResolutionMm))
                SlicerSettings.Keys.ARC_OVERHANG_FAN_SPEED -> restored.copy(arcOverhangFanSpeedPercent = values.optDouble(key, restored.arcOverhangFanSpeedPercent))
                SlicerSettings.Keys.WAVE_OVERHANG_ENABLED -> restored.copy(waveOverhangEnabled = values.optBoolean(key, restored.waveOverhangEnabled))
                SlicerSettings.Keys.WAVE_OVERHANG_PATTERN -> restored.copy(waveOverhangPattern = values.optString(key, restored.waveOverhangPattern))
                SlicerSettings.Keys.WAVE_OVERHANG_LINE_SPACING -> restored.copy(waveOverhangLineSpacingMm = values.optDouble(key, restored.waveOverhangLineSpacingMm))
                SlicerSettings.Keys.WAVE_OVERHANG_FLOW -> restored.copy(waveOverhangFlowMm3PerMm = values.optDouble(key, restored.waveOverhangFlowMm3PerMm))
                SlicerSettings.Keys.WAVE_OVERHANG_SPEED -> restored.copy(waveOverhangSpeedMmPerSecond = values.optDouble(key, restored.waveOverhangSpeedMmPerSecond))
                SlicerSettings.Keys.WAVE_OVERHANG_FAN_SPEED -> restored.copy(waveOverhangFanSpeedPercent = values.optDouble(key, restored.waveOverhangFanSpeedPercent))
                SlicerSettings.Keys.WAVE_OVERHANG_PERIMETER_OVERLAP -> restored.copy(waveOverhangPerimeterOverlapMm = values.optDouble(key, restored.waveOverhangPerimeterOverlapMm))
                SlicerSettings.Keys.WAVE_OVERHANG_MINIMUM_WIDTH -> restored.copy(waveOverhangMinimumWidthMm = values.optDouble(key, restored.waveOverhangMinimumWidthMm))
                SlicerSettings.Keys.WAVE_OVERHANG_MAX_ITERATIONS -> restored.copy(waveOverhangMaxIterations = values.optInt(key, restored.waveOverhangMaxIterations))
                SlicerSettings.Keys.WAVE_OVERHANG_REVERSE_ODD_LAYERS -> restored.copy(waveOverhangReverseOddLayers = values.optBoolean(key, restored.waveOverhangReverseOddLayers))
                SlicerSettings.Keys.IRONING_ENABLED -> restored.copy(ironingEnabled = values.optBoolean(key, restored.ironingEnabled))
                SlicerSettings.Keys.IRONING_ONLY_HIGHEST_LAYER -> restored.copy(ironingOnlyHighestLayer = values.optBoolean(key, restored.ironingOnlyHighestLayer))
                SlicerSettings.Keys.IRONING_FLOW -> restored.copy(ironingFlowPercent = values.optDouble(key, restored.ironingFlowPercent))
                SlicerSettings.Keys.IRONING_SPEED -> restored.copy(ironingSpeedMmPerSecond = values.optDouble(key, restored.ironingSpeedMmPerSecond))
                else -> restored
            }
        }
        return restored.copy(overriddenSettingKeys = overrides)
    }

    companion object {
        const val KIND_PROJECT = "project"
        const val KIND_PROFILE = "profile"
        private const val IMPORT_BUNDLE_VERSION = 1
        private const val MAX_IMPORT_METADATA_BYTES = 64 * 1024
        private const val MAX_IMPORT_NAME_CHARS = 512
        private val IMPORT_BUNDLE_MAGIC = "ESCIMP2\n".toByteArray(Charsets.US_ASCII)

        private const val PREFERENCES_NAME = "enderslicer-state"
        private const val KEY_IMPORT_KIND = "import-kind"
        private const val KEY_IMPORT_NAME = "import-name"
        private const val KEY_SETTINGS = "settings-json"
        private const val KEY_OVERRIDES_JSON = "overrides"
        private const val MAX_CURA_IMPORT_BYTES = 128L * 1024L * 1024L
    }
}
