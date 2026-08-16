package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.engine.GcodeLayerPreview
import com.tomppi.enderslicer.engine.LayerEvent
import com.tomppi.enderslicer.engine.SliceArtifactPublisher
import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.profile.CuraComputedValue
import com.tomppi.enderslicer.profile.CuraEngineProfile
import com.tomppi.enderslicer.supportpaint.SupportPaintMode
import com.tomppi.enderslicer.supportpaint.SupportPaintState
import com.tomppi.enderslicer.viewer.StlMesh
import java.io.File

data class MainUiState(
    val printer: PrinterDefinition,
    val settings: SlicerSettings = SlicerSettings(),
    val mesh: StlMesh? = null,
    val modelPath: String? = null,
    val modelPlacement: ModelPlacement? = null,
    val supportPaint: SupportPaintState = SupportPaintState(),
    val paintMode: SupportPaintMode = SupportPaintMode.NONE,
    val importedSceneTransformAvailable: Boolean = false,
    val importedSceneModelName: String? = null,
    val sliceResultId: String? = null,
    val gcodePath: String? = null,
    val baseGcodePath: String? = null,
    val layerPreview: GcodeLayerPreview? = null,
    val layerEvents: List<LayerEvent> = emptyList(),
    val calibrationDescription: String? = null,
    val estimatedPrintSeconds: Int? = null,
    val sliceLogPath: String? = null,
    val sliceDurationMilliseconds: Long? = null,
    val profileName: String = "Built-in current Cura settings",
    val profileSource: String = "Cura 5.11 / setting version 25 reference",
    val importedRawSettingCount: Int = 0,
    val curaVersion: String? = null,
    val settingVersion: String? = "27",
    val engineProfile: CuraEngineProfile? = null,
    val computedCuraValues: List<CuraComputedValue> = emptyList(),
    val computedCuraPasses: Int? = null,
    val startGcode: String = "",
    val endGcode: String = "",
    val engineStatus: String = "",
    val engineAvailable: Boolean = false,
    val warnings: List<String> = emptyList(),
    val statusMessage: String = "Import an STL to begin",
    val isBusy: Boolean = false,
) {
    fun hasCurrentGcode(): Boolean {
        val expectedId = sliceResultId ?: return false
        val file = gcodePath?.let(::File) ?: return false
        return SliceArtifactPublisher.isCompleteGcode(file, expectedId)
    }
}
