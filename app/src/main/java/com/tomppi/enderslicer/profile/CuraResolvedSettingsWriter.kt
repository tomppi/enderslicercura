package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.calibration.CalibrationSliceState
import com.tomppi.enderslicer.engine.PrinterEnvelope
import com.tomppi.enderslicer.nonplanar.CurviSlicerFieldStorage
import com.tomppi.enderslicer.nonplanar.CurviSlicerPipeline
import com.tomppi.enderslicer.nonplanar.CurviSlicerRuntime
import com.tomppi.enderslicer.smartinfill.SmartInfillCuraContract
import com.tomppi.enderslicer.smartinfill.SmartInfillModifier
import com.tomppi.enderslicer.smartinfill.requireValidBinaryStl
import com.tomppi.enderslicer.viewer.StlMeshWriter
import com.tomppi.enderslicer.viewer.StlSliceTransform
import org.json.JSONObject
import java.io.File

internal object CuraResolvedSettingsWriter {
    fun write(
        destination: File,
        modelFileName: String,
        resolved: CuraSliceSettingsResolver.Result,
        modelTransform: StlSliceTransform? = null,
        smartInfillModifiers: List<SmartInfillModifier> = emptyList(),
    ) {
        require(modelFileName.endsWith(".stl", ignoreCase = true)) {
            "Resolved Cura model must be an STL file"
        }
        val modelDirectory = destination.parentFile
            ?: error("Resolved settings destination has no parent directory")
        val modelFile = File(modelDirectory, modelFileName)
        require(modelFile.isFile && modelFile.length() > 0L) {
            "Resolved Cura STL is missing or empty: ${modelFile.absolutePath}"
        }
        val effectiveSmartInfillModifiers = smartInfillModifiers
            .sortedBy(SmartInfillModifier::densityPercent)
        if (effectiveSmartInfillModifiers.isNotEmpty()) {
            val requestedDensities = effectiveSmartInfillModifiers
                .map(SmartInfillModifier::densityPercent)
                .toSet()
            require(resolved.smartInfillModelValues.keys.containsAll(requestedDensities)) {
                "Resolved Cura request is missing density-dependent Smart Infill settings"
            }
        }

        val machineWidth = requiredNumber(resolved.globalValues, "machine_width")
        val machineDepth = requiredNumber(resolved.globalValues, "machine_depth")
        val machineHeight = requiredNumber(resolved.globalValues, "machine_height")
        val centerIsZero = requiredBoolean(resolved.globalValues, "machine_center_is_zero")
        val printerEnvelope = PrinterEnvelope(
            widthMm = machineWidth,
            depthMm = machineDepth,
            heightMm = machineHeight,
            buildPlateShape = resolved.globalValues["machine_shape"]
                ?: error("Resolved Cura setting is missing: machine_shape"),
            originAtCenter = centerIsZero,
            gcodeFlavor = resolved.globalValues["machine_gcode_flavor"]
                ?.trim()
                ?.trim('"')
                ?.takeIf(String::isNotBlank)
                ?: PrinterEnvelope.DEFAULT_GCODE_FLAVOR,
        )
        printerEnvelope.requireBinaryStlFits(modelFile, modelTransform)
        effectiveSmartInfillModifiers.forEach { modifier ->
            require(modifier.file.parentFile?.canonicalFile == modelDirectory.canonicalFile) {
                "Smart Infill modifier was not staged inside the CuraEngine request"
            }
            requireValidBinaryStl(modifier.file, Int.MAX_VALUE)
            printerEnvelope.requireBinaryStlFits(modifier.file)
        }

        val curviPrepared = CurviSlicerRuntime.snapshot()?.let { snapshot ->
            require(modelTransform == null) {
                "CurviSlicer resolved requests must stage the displayed model before flattening"
            }
            CurviSlicerPipeline.prepareAndWarp(
                modelFile = modelFile,
                settings = snapshot.settings,
                layerHeightMm = requiredResolvedNumber(resolved, "layer_height"),
                nozzleDiameterMm = requiredResolvedNumber(resolved, "machine_nozzle_size"),
            )
        }
        if (curviPrepared != null) {
            if (effectiveSmartInfillModifiers.isNotEmpty()) {
                require(curviPrepared.settings.warpSmartInfillModifiers) {
                    "CurviSlicer must warp Smart Infill modifiers so density regions remain aligned"
                }
                effectiveSmartInfillModifiers.forEach { modifier -> curviPrepared.warpModifier(modifier.file) }
            }
            printerEnvelope.requireBinaryStlFits(modelFile)
            effectiveSmartInfillModifiers.forEach { modifier -> printerEnvelope.requireBinaryStlFits(modifier.file) }
            CurviSlicerFieldStorage.write(modelDirectory, curviPrepared)
        }
        printerEnvelope.writeTo(File(modelDirectory, PrinterEnvelope.METADATA_FILE_NAME))

        val machineCenterX = if (centerIsZero) 0.0 else machineWidth / 2.0
        val machineCenterY = if (centerIsZero) 0.0 else machineDepth / 2.0
        val enginePositionX = -machineCenterX
        val enginePositionY = -machineCenterY
        val enginePositionZ = 0.0

        val effectiveTransform = modelTransform
        val linear = effectiveTransform?.linear ?: IDENTITY
        val affineTranslationX = effectiveTransform?.translationXmm ?: 0.0
        val affineTranslationY = effectiveTransform?.translationYmm ?: 0.0
        val affineTranslationZ = effectiveTransform?.translationZmm ?: 0.0

        val calibrationOverrides = CalibrationSliceState.engineOverrides()
        val extruderValues = JSONObject(resolved.extruderValues)
        resolved.modelValues.forEach { (key, value) -> extruderValues.put(key, value) }
        calibrationOverrides.forEach { (key, value) -> extruderValues.put(key, value) }
        applyTransform(
            values = extruderValues,
            linear = linear,
            translationX = affineTranslationX,
            translationY = affineTranslationY,
            translationZ = affineTranslationZ,
            enginePositionX = enginePositionX,
            enginePositionY = enginePositionY,
            enginePositionZ = enginePositionZ,
        )

        val modelValues = JSONObject(resolved.modelValues)
        calibrationOverrides.forEach { (key, value) ->
            if (resolved.modelValues.containsKey(key)) modelValues.put(key, value)
        }
        modelValues.put("extruder_nr", 0)
        applyTransform(
            values = modelValues,
            linear = linear,
            translationX = affineTranslationX,
            translationY = affineTranslationY,
            translationZ = affineTranslationZ,
            enginePositionX = enginePositionX,
            enginePositionY = enginePositionY,
            enginePositionZ = enginePositionZ,
        )

        val root = JSONObject()
            .put("global", JSONObject(resolved.globalValues))
            .put("extruder.0", extruderValues)
            .put(modelFileName, modelValues)

        effectiveSmartInfillModifiers.forEachIndexed { index, modifier ->
            val densityResolved = resolved.smartInfillModelValues[modifier.densityPercent]
                ?: error("Resolved Cura settings are missing for ${modifier.densityPercent}% Smart Infill")
            val values = JSONObject(densityResolved)
                .put("extruder_nr", 0)
                .put("infill_mesh", true)
                .put("infill_mesh_order", index + 1)
                .put("infill_sparse_density", modifier.densityPercent)
                .put("support_mesh", false)
                .put("anti_overhang_mesh", false)
                .put("cutting_mesh", false)
            // Enforce the modifier contract at the final serialization boundary
            // as well as during dependency resolution. This prevents callers
            // constructing Result directly from reintroducing inherited shells.
            SmartInfillCuraContract.modifierShellNeutralValues.forEach { (key, value) ->
                values.put(key, value.toInt())
            }
            // filaSim receives the already transformed/displayed STL, so
            // modifier geometry is in final printer coordinates. Do not apply
            // the source model's 3MF affine a second time.
            applyTransform(
                values = values,
                linear = IDENTITY,
                translationX = 0.0,
                translationY = 0.0,
                translationZ = 0.0,
                enginePositionX = enginePositionX,
                enginePositionY = enginePositionY,
                enginePositionZ = enginePositionZ,
            )
            root.put(modifier.file.name, values)
        }

        destination.writeText(root.toString())
        check(destination.isFile && destination.length() > 0L) {
            "Unable to write resolved Cura settings"
        }
    }

    internal fun copyResolvedSourceSnapshot(
        stagedDisplayedFile: File,
        destination: File,
        copyFile: (File, File) -> Unit = { source, target -> source.copyTo(target, overwrite = true) },
    ): StlSliceTransform? {
        require(stagedDisplayedFile.isFile && stagedDisplayedFile.length() > 0L) {
            "The transformed STL is unavailable for resolved source staging"
        }
        if (CurviSlicerRuntime.snapshot() != null) {
            val displayedStamp = fileStamp(stagedDisplayedFile)
            removePreStagedRequestModel(destination)
            copyFile(stagedDisplayedFile, destination)
            check(destination.isFile && destination.length() == displayedStamp.length) {
                "Unable to stage displayed STL geometry for CurviSlicer"
            }
            check(fileStamp(stagedDisplayedFile) == displayedStamp) {
                "The displayed STL changed while it was being staged for CurviSlicer"
            }
            return null
        }

        val sourceFile = File(
            stagedDisplayedFile.parentFile,
            "${stagedDisplayedFile.nameWithoutExtension}.slice-source.stl",
        )
        val transformFile = File(
            stagedDisplayedFile.parentFile,
            "${stagedDisplayedFile.nameWithoutExtension}.slice-transform.json",
        )
        if (!sourceFile.isFile || !transformFile.isFile) return null

        val displayedStamp = fileStamp(stagedDisplayedFile)
        val sourceStamp = fileStamp(sourceFile)
        val transformStamp = fileStamp(transformFile)
        val stagedSource = StlMeshWriter.resolvedSliceSource(stagedDisplayedFile) ?: return null
        copyFile(stagedSource.modelFile, destination)
        check(destination.isFile && destination.length() == sourceStamp.length) {
            "Unable to stage original STL geometry for direct Cura transformation"
        }
        check(fileStamp(stagedDisplayedFile) == displayedStamp) {
            "The transformed STL changed while its resolved source was being staged"
        }
        check(fileStamp(sourceFile) == sourceStamp) {
            "The original STL changed while it was being staged"
        }
        check(fileStamp(transformFile) == transformStamp) {
            "The STL transform changed while it was being staged"
        }
        return stagedSource.transform
    }

    private fun removePreStagedRequestModel(destination: File) {
        if (!destination.exists()) return
        require(destination.isFile) {
            "Resolved Cura request model destination is not a file: ${destination.absolutePath}"
        }
        check(destination.delete()) {
            "Unable to replace the pre-staged Cura request model: ${destination.absolutePath}"
        }
    }

    private fun applyTransform(
        values: JSONObject,
        linear: List<Double>,
        translationX: Double,
        translationY: Double,
        translationZ: Double,
        enginePositionX: Double,
        enginePositionY: Double,
        enginePositionZ: Double,
    ) {
        values
            .put("center_object", false)
            .put("mesh_rotation_matrix", matrixString(linear))
            .put(AFFINE_TRANSLATION_X, translationX)
            .put(AFFINE_TRANSLATION_Y, translationY)
            .put(AFFINE_TRANSLATION_Z, translationZ)
            .put("mesh_position_x", enginePositionX)
            .put("mesh_position_y", enginePositionY)
            .put("mesh_position_z", enginePositionZ)
    }

    private fun fileStamp(file: File): FileStamp = FileStamp(file.length(), file.lastModified())

    private fun matrixString(linear: List<Double>): String {
        require(linear.size == 9 && linear.all(Double::isFinite)) {
            "Resolved Cura model transform must contain nine finite values"
        }
        return linear.chunked(3).joinToString(prefix = "[", postfix = "]", separator = ",") { row ->
            row.joinToString(prefix = "[", postfix = "]", separator = ",")
        }
    }

    private fun requiredNumber(values: Map<String, String>, key: String): Double {
        val raw = values[key] ?: error("Resolved Cura setting is missing: $key")
        val value = raw.toDoubleOrNull() ?: error("Resolved Cura setting is not numeric: $key=$raw")
        require(value.isFinite() && value > 0.0) { "Resolved Cura setting is invalid: $key=$raw" }
        return value
    }

    private fun requiredResolvedNumber(resolved: CuraSliceSettingsResolver.Result, key: String): Double {
        val raw = resolved.modelValues[key]
            ?: resolved.extruderValues[key]
            ?: resolved.globalValues[key]
            ?: error("Resolved Cura setting is missing: $key")
        val value = raw.toDoubleOrNull() ?: error("Resolved Cura setting is not numeric: $key=$raw")
        require(value.isFinite() && value > 0.0) { "Resolved Cura setting is invalid: $key=$raw" }
        return value
    }

    private fun requiredBoolean(values: Map<String, String>, key: String): Boolean {
        val raw = values[key] ?: error("Resolved Cura setting is missing: $key")
        return raw.toBooleanStrictOrNull()
            ?: error("Resolved Cura setting is not boolean: $key=$raw")
    }

    private data class FileStamp(val length: Long, val modified: Long)

    private const val AFFINE_TRANSLATION_X = "enderslicer_mesh_translation_x"
    private const val AFFINE_TRANSLATION_Y = "enderslicer_mesh_translation_y"
    private const val AFFINE_TRANSLATION_Z = "enderslicer_mesh_translation_z"
    private val IDENTITY = listOf(
        1.0, 0.0, 0.0,
        0.0, 1.0, 0.0,
        0.0, 0.0, 1.0,
    )
}
