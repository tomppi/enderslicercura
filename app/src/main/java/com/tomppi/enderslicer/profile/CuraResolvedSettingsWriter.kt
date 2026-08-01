package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.calibration.CalibrationSliceState
import com.tomppi.enderslicer.engine.PrinterEnvelope
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

        val machineWidth = requiredNumber(resolved.globalValues, "machine_width")
        val machineDepth = requiredNumber(resolved.globalValues, "machine_depth")
        val machineHeight = requiredNumber(resolved.globalValues, "machine_height")
        val centerIsZero = requiredBoolean(resolved.globalValues, "machine_center_is_zero")
        PrinterEnvelope(
            widthMm = machineWidth,
            depthMm = machineDepth,
            heightMm = machineHeight,
            buildPlateShape = resolved.globalValues["machine_shape"]
                ?: error("Resolved Cura setting is missing: machine_shape"),
            originAtCenter = centerIsZero,
        ).requireBinaryStlFits(modelFile)

        // MainViewModel writes the displayed transformed STL below cacheDir.
        // Isolated CuraEngine requests can be nested several directories deeper,
        // so walk bounded ancestors instead of assuming a direct sibling.
        val stagedDisplayedFile = findStagedDisplayedFile(modelDirectory)
        val stagedTransform = stagedDisplayedFile?.let { displayed ->
            copyResolvedSourceSnapshot(displayed, modelFile)
        }
        val effectiveTransform = modelTransform ?: stagedTransform

        // Cura's frontend applies the complete affine before converting mesh
        // vertices into integer microns. A normal CuraEngine command-line slice
        // applies mesh_position only afterwards, which computes
        // round(linear * vertex) + round(translation) instead of Cura's
        // round(linear * vertex + translation). The native resolved-loader patch
        // consumes these translation keys in Matrix4x3D before STL conversion.
        val machineCenterX = if (centerIsZero) 0.0 else machineWidth / 2.0
        val machineCenterY = if (centerIsZero) 0.0 else machineDepth / 2.0
        val linear = effectiveTransform?.linear ?: IDENTITY
        val affineTranslationX = effectiveTransform?.translationXmm ?: 0.0
        val affineTranslationY = effectiveTransform?.translationYmm ?: 0.0
        val affineTranslationZ = effectiveTransform?.translationZmm ?: 0.0
        val rotationMatrix = matrixString(linear)

        // Matrix4x3D now creates final build-plate coordinates directly. Cancel
        // only CuraEngine's automatic front-left-bed half-width/depth offset in
        // MeshGroup::finalize; no model translation belongs in mesh_position.
        val enginePositionX = -machineCenterX
        val enginePositionY = -machineCenterY
        val enginePositionZ = 0.0

        // CuraEngine's command-line model loader constructs the single model
        // from the extruder stack. Copy all resolved per-mesh values into that
        // stack as well as retaining the model section. The native resolved-model
        // patch also copies the model section onto the actual Mesh.
        val calibrationOverrides = CalibrationSliceState.engineOverrides()
        val extruderValues = JSONObject(resolved.extruderValues)
        resolved.modelValues.forEach { (key, value) -> extruderValues.put(key, value) }
        // Some calibration-sensitive values (notably bridge fan settings) are
        // settable per mesh. Re-apply temporary calibration overrides after the
        // normal model-to-extruder copy so model scope cannot undo the test.
        calibrationOverrides.forEach { (key, value) -> extruderValues.put(key, value) }
        extruderValues
            .put("center_object", false)
            .put("mesh_rotation_matrix", rotationMatrix)
            .put(AFFINE_TRANSLATION_X, affineTranslationX)
            .put(AFFINE_TRANSLATION_Y, affineTranslationY)
            .put(AFFINE_TRANSLATION_Z, affineTranslationZ)
            .put("mesh_position_x", enginePositionX)
            .put("mesh_position_y", enginePositionY)
            .put("mesh_position_z", enginePositionZ)

        val modelValues = JSONObject(resolved.modelValues)
        calibrationOverrides.forEach { (key, value) ->
            if (resolved.modelValues.containsKey(key)) modelValues.put(key, value)
        }
        modelValues
            .put("extruder_nr", 0)
            .put("center_object", false)
            .put("mesh_rotation_matrix", rotationMatrix)
            .put(AFFINE_TRANSLATION_X, affineTranslationX)
            .put(AFFINE_TRANSLATION_Y, affineTranslationY)
            .put(AFFINE_TRANSLATION_Z, affineTranslationZ)
            .put("mesh_position_x", enginePositionX)
            .put("mesh_position_y", enginePositionY)
            .put("mesh_position_z", enginePositionZ)

        val root = JSONObject()
            .put("global", JSONObject(resolved.globalValues))
            .put("extruder.0", extruderValues)
            .put(modelFileName, modelValues)
        destination.writeText(root.toString())
        check(destination.isFile && destination.length() > 0L) {
            "Unable to write resolved Cura settings"
        }
    }

    internal fun findStagedDisplayedFile(modelDirectory: File): File? =
        generateSequence(modelDirectory) { current -> current.parentFile }
            .take(MAX_CACHE_ANCESTORS)
            .map { ancestor -> File(ancestor, STAGED_DISPLAYED_MODEL_PATH) }
            .firstOrNull(File::isFile)

    internal fun copyResolvedSourceSnapshot(
        stagedDisplayedFile: File,
        destination: File,
        copyFile: (File, File) -> Unit = { source, target -> source.copyTo(target, overwrite = true) },
    ): StlSliceTransform? {
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

    private fun requiredBoolean(values: Map<String, String>, key: String): Boolean {
        val raw = values[key] ?: error("Resolved Cura setting is missing: $key")
        return raw.toBooleanStrictOrNull()
            ?: error("Resolved Cura setting is not boolean: $key=$raw")
    }

    private data class FileStamp(val length: Long, val modified: Long)

    private const val AFFINE_TRANSLATION_X = "enderslicer_mesh_translation_x"
    private const val AFFINE_TRANSLATION_Y = "enderslicer_mesh_translation_y"
    private const val AFFINE_TRANSLATION_Z = "enderslicer_mesh_translation_z"
    private const val STAGED_DISPLAYED_MODEL_PATH = "model-placement/current-transformed.stl"
    private const val MAX_CACHE_ANCESTORS = 8

    private val IDENTITY = listOf(
        1.0, 0.0, 0.0,
        0.0, 1.0, 0.0,
        0.0, 0.0, 1.0,
    )
}
