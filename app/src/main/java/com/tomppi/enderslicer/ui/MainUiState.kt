package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.annotation.AnnotationAnchor
import com.tomppi.enderslicer.engine.GcodeLayerPreview
import com.tomppi.enderslicer.engine.LayerEvent
import com.tomppi.enderslicer.engine.SliceArtifactPublisher
import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.PrusaSliceSettings
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.profile.CuraEngineProfile
import com.tomppi.enderslicer.supportpaint.SupportPaintMode
import com.tomppi.enderslicer.supportpaint.SupportPaintState
import com.tomppi.enderslicer.viewer.AnnotationOverlay
import com.tomppi.enderslicer.viewer.StlMesh
import java.io.File

data class MainUiState(
    val printer: PrinterDefinition,
    val settings: SlicerSettings = SlicerSettings(),
    val prusaSettings: PrusaSliceSettings = PrusaSliceSettings(),
    val extraCuraSettings: Map<String, String> = emptyMap(),
    val extraPrusaSettings: Map<String, String> = emptyMap(),
    /** Prusa-imported start/end gcode; the Cura path never sees them. */
    val prusaStartGcode: String = "",
    val prusaEndGcode: String = "",
    val mesh: StlMesh? = null,
    val modelPath: String? = null,
    val modelPlacement: ModelPlacement? = null,
    val supportPaint: SupportPaintState = SupportPaintState(),
    val paintMode: SupportPaintMode = SupportPaintMode.NONE,
    /** True while the annotation tool owns single-finger gestures. */
    val annotationActive: Boolean = false,
    /** Line geometry for the annotation overlay, or null when there is none. */
    val annotationOverlay: AnnotationOverlay? = null,
    /** True when the segment being placed has both ends and can be committed. */
    val annotationCanLockSegment: Boolean = false,
    /** True when the series so far has enough points to commit. */
    val annotationCanLockSeries: Boolean = false,
    /** True when a point has been placed but the segment still needs its other end. */
    val annotationAwaitingSecondPoint: Boolean = false,
    val annotationAnchor: AnnotationAnchor? = null,
    /** Length of the segment being placed. */
    val annotationMeasureMm: Float? = null,
    /** Total length of the series being drawn, including the segment in progress. */
    val annotationChainMm: Float? = null,
    /** Locked series. */
    val annotationChainCount: Int = 0,
    /** Points already committed to the series in progress. */
    val annotationSeriesPoints: Int = 0,
    /** Line width in screen pixels. */
    val annotationThicknessPx: Float = 6f,
    /** Height of the plane points are placed on, in model millimetres. */
    val annotationWorkPlaneZ: Float = 0f,
    /** True while a handle is having its height adjusted on its own. */
    val annotationZAdjusting: Boolean = false,
    val annotationZMin: Float = 0f,
    val annotationZMax: Float = 100f,
    val annotationSavedPath: String? = null,
    val importedSceneTransformAvailable: Boolean = false,
    val importedSceneModelName: String? = null,
    val sliceResultId: String? = null,
    val gcodePath: String? = null,
    val baseGcodePath: String? = null,
    /** Engine that produced the current slice result; gates engine-specific features. */
    val sliceEngine: SlicerEngine? = null,
    val layerPreview: GcodeLayerPreview? = null,
    val layerEvents: List<LayerEvent> = emptyList(),
    val estimatedPrintSeconds: Int? = null,
    val sliceLogPath: String? = null,
    val sliceDurationMilliseconds: Long? = null,
    val profileName: String = "Built-in current Cura settings",
    val profileSource: String = "Cura 5.14.0-alpha.0 / setting version 27 reference",
    val importedRawSettingCount: Int = 0,
    val curaVersion: String? = null,
    val settingVersion: String? = "27",
    val engineProfile: CuraEngineProfile? = null,
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
