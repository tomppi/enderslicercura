package com.tomppi.enderslicer.viewer

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.tomppi.enderslicer.engine.GcodeNozzlePath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Hue for an extrusion move: the layer-height ramp (blue at the bed, red at
 * the top) or the print-speed ramp (cyan slow, orange fast) when
 * [colorBySpeed] is set.
 */
internal fun extrusionHue(zRatio: Float, speedRatio: Float, colorBySpeed: Boolean): Float {
    val clampedZ = zRatio.coerceIn(0f, 1f)
    val clampedSpeed = speedRatio.coerceIn(0f, 1f)
    return if (colorBySpeed) 200f - 180f * clampedSpeed else 240f - 240f * clampedZ
}

/** Micro-segments (sub-0.05 mm) emit with zero width. */
internal const val RIBBON_MIN_SEGMENT_MM = 0.05f

/**
 * Resolves the physical bead width for one extrusion move, shared by the
 * renderer (geometry) and the UI (inspector readout) so they can never
 * disagree: width = deltaE x filament area / length / layer height, clamped
 * to [lineWidth] x [0.4, 4.0]; sub-0.05 mm segments collapse to zero width
 * so micro-segments do not render as dark specks.
 */
internal fun resolveBeadWidthMm(
    lengthMm: Float,
    deltaE: Float,
    parsedLayerHeight: Float,
    layerHeightFallback: Float,
    lineWidth: Float,
    filamentArea: Float,
): Float {
    val height = if (parsedLayerHeight > 0.02f && parsedLayerHeight <= 2.0f) {
        parsedLayerHeight
    } else {
        layerHeightFallback
    }
    val rawWidth = if (lengthMm > 1e-4f && deltaE > 0f) {
        val crossArea = deltaE * filamentArea / lengthMm
        crossArea / height
    } else {
        lineWidth
    }
    return if (lengthMm < RIBBON_MIN_SEGMENT_MM) 0f else {
        rawWidth.coerceIn(lineWidth * 0.4f, lineWidth * 4f)
    }
}

/**
 * EGL config chooser that prefers multisampled buffers (4x, then 2x) and
 * falls back to the plain config, so bead silhouettes stay crisp instead of
 * aliased without breaking devices that have no MSAA support.
 */
private class SampleConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        // Preference ladder, most demanding first. On each failure the next
        // entry is tried; the final step asks the driver for its default
        // config (null attributes), which is what the device always has.
        // Never throw based on a strict attribute list alone: a missing
        // 8-bit-alpha or sample-buffer config is NOT a reason to take the
        // whole app down (that was the crash: model view has an internal
        // ladder, the previous nozzle-path chooser did not).
        val rungs = listOf(
            intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_STENCIL_SIZE, 0,
                EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4,
                EGL10.EGL_NONE,
            ),
            intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_STENCIL_SIZE, 0,
                EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 2,
                EGL10.EGL_NONE,
            ),
            intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_STENCIL_SIZE, 0,
                EGL10.EGL_NONE,
            ),
            intArrayOf(
                EGL10.EGL_RED_SIZE, 5, EGL10.EGL_GREEN_SIZE, 6,
                EGL10.EGL_BLUE_SIZE, 5, EGL10.EGL_ALPHA_SIZE, 0,
                EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_STENCIL_SIZE, 0,
                EGL10.EGL_NONE,
            ),
        )
        for (attrib in rungs) {
            choose(egl, display, attrib)?.let { return it }
        }
        // Last resort: whatever the driver gives us by default.
        choose(egl, display, null)?.let { return it }
        Log.e("NozzlePathView", "EGL offered no usable config at all")
        error("No usable EGL config for the nozzle-path renderer")
    }

    private fun choose(egl: EGL10, display: EGLDisplay, attrib: IntArray?): EGLConfig? {
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val ok = if (attrib == null) {
            egl.eglChooseConfig(display, null, configs, 1, count)
        } else {
            egl.eglChooseConfig(display, attrib, configs, 1, count)
        }
        return if (ok && count[0] >= 1) configs[0] else null
    }
}

class NozzlePathSurfaceView(context: Context) : GLSurfaceView(context) {
    private val pathRenderer = NozzlePathRenderer()
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())
    private var previousX = 0f
    private var previousY = 0f
    private var previousFocusX = 0f
    private var previousFocusY = 0f
    private var panning = false

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(SampleConfigChooser())
        preserveEGLContextOnPause = true
        setRenderer(pathRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        isClickable = true
    }

    fun setPath(
        path: GcodeNozzlePath,
        selectedMoveIndex: Int,
        beadHeightMm: Float,
        beadLineWidthMm: Float,
        filamentDiameterMm: Float,
    ) {
        queueEvent {
            pathRenderer.setPath(path, beadHeightMm, beadLineWidthMm, filamentDiameterMm)
            pathRenderer.setSelectedMove(selectedMoveIndex)
        }
        // setPath resets the camera on the GL thread.
        queueEvent { notifyOrientation() }
        requestRender()
    }

    /** When false, gray travel moves are not drawn. */
    var showTravels: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            queueEvent { pathRenderer.setShowTravels(value) }
            requestRender()
        }

    /** When true, extrusion colours follow print speed (cyan slow, orange fast) instead of layer height. */
    var colorBySpeed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            queueEvent { pathRenderer.setColorBySpeed(value) }
            requestRender()
        }

    /**
     * Orthographic (true-width) camera for measuring the path; perspective is
     * the default for context. The orbit pivot, pan and fit are identical.
     */
    var orthographic: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            queueEvent { pathRenderer.orthographic = value }
            requestRender()
        }

    /** Invoked on the main thread whenever the turntable yaw/pitch changes. */
    var onOrientationChanged: ((ViewerOrientation) -> Unit)? = null

    /** Invoked on the main thread with the current zoom multiplier. */
    var onZoomChanged: ((Float) -> Unit)? = null

    /** Invoked on the main thread when the user taps a move to inspect it. */
    var onMovePicked: ((Int) -> Unit)? = null

    fun currentOrientation(): ViewerOrientation = pathRenderer.orientation

    fun currentZoom(): Float = pathRenderer.zoomLevel

    private fun notifyOrientation() {
        val listener = onOrientationChanged ?: return
        post { listener(pathRenderer.orientation) }
    }

    private fun notifyZoom() {
        val listener = onZoomChanged ?: return
        post { listener(pathRenderer.zoomLevel) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previousX = event.x
                previousY = event.y
                panning = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    previousFocusX = pointerFocusX(event)
                    previousFocusY = pointerFocusY(event)
                    panning = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val focusX = pointerFocusX(event)
                    val focusY = pointerFocusY(event)
                    if (panning) {
                        pathRenderer.panPixels(focusX - previousFocusX, focusY - previousFocusY)
                    }
                    previousFocusX = focusX
                    previousFocusY = focusY
                    panning = true
                    requestRender()
                } else if (!scaleDetector.isInProgress) {
                    pathRenderer.rotate((event.x - previousX) * 0.35f, (event.y - previousY) * 0.35f)
                    previousX = event.x
                    previousY = event.y
                    notifyOrientation()
                    requestRender()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                panning = false
                if (event.pointerCount - 1 == 1) {
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    previousX = event.getX(remaining)
                    previousY = event.getY(remaining)
                }
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                panning = false
            }
            MotionEvent.ACTION_CANCEL -> panning = false
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun pointerFocusX(event: MotionEvent): Float =
        (0 until event.pointerCount).sumOf { event.getX(it).toDouble() }.toFloat() / event.pointerCount

    private fun pointerFocusY(event: MotionEvent): Float =
        (0 until event.pointerCount).sumOf { event.getY(it).toDouble() }.toFloat() / event.pointerCount

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pathRenderer.zoom(detector.scaleFactor)
            notifyZoom()
            requestRender()
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            queueEvent {
                val index = pathRenderer.pickNearestMove(event.x, event.y)
                if (index >= 0) post { onMovePicked?.invoke(index) }
            }
            return true
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            pathRenderer.resetCamera()
            notifyOrientation()
            notifyZoom()
            requestRender()
            return true
        }
    }

    /** Resets yaw, pitch, zoom, pan and the orbit pivot back to the model fit. */
    fun resetView() {
        queueEvent { pathRenderer.resetCamera() }
        queueEvent { notifyOrientation() }
        queueEvent { notifyZoom() }
        requestRender()
    }
}

private class NozzlePathRenderer : GLSurfaceView.Renderer {
    private var path: GcodeNozzlePath? = null
    private var selectedMoveIndex = 0
    private var ribbonPositions: FloatBuffer? = null
    private var ribbonNormals: FloatBuffer? = null
    private var ribbonColors: FloatBuffer? = null
    private var ribbonAmbient: FloatBuffer? = null
    private var travelPositions: FloatBuffer? = null
    private var travelColors: FloatBuffer? = null
    // Physical bead parameters from the current slice settings (fallbacks
    // when the parsed per-move flow cannot be trusted).
    private var beadHeight = 0.20f
    private var beadLineWidth = 0.40f
    private var filamentArea = Math.PI.toFloat() * 0.875f * 0.875f
    // extensionPrefix[m] = extrusion moves stored with index < m (same for travel).
    private var extrusionPrefix = IntArray(1)
    private var travelPrefix = IntArray(1)
    @Volatile private var showTravels = true
    @Volatile private var colorBySpeed = false
    @Volatile var orthographic = false
    private var gridPositions: FloatBuffer? = null
    private var gridVertexCount = 0
    private var markerPositions: FloatBuffer? = null
    private var markerVertexCount = 0
    private var markerGlowPositions: FloatBuffer? = null
    private var markerGlowVertexCount = 0

    private var litProgram = 0
    private var colorProgram = 0
    private var solidProgram = 0
    private var maxLineWidth = 1f
    private var viewportWidth = 1
    private var viewportHeight = 1
    // Camera pivot and fit use the EXTRUSION bounds (the printed model), not
    // the whole path: travel moves to the prime line or skirt sit far from the
    // part and would anchor the orbit to the plate instead of the model.
    private var modelMinX = 0f
    private var modelMinY = 0f
    private var modelMinZ = 0f
    private var modelMaxX = 0f
    private var modelMaxY = 0f
    private var modelMaxZ = 0f
    @Volatile private var yaw = DEFAULT_YAW
    @Volatile private var pitch = DEFAULT_PITCH
    @Volatile private var zoom = DEFAULT_ZOOM
    @Volatile private var panX = 0f
    @Volatile private var panY = 0f

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val scene = FloatArray(16)
    private val modelView = FloatArray(16)
    private val mvp = FloatArray(16)
    // Scratch for projection during picking.
    private val pickIn = FloatArray(4)
    private val pickOut = FloatArray(4)

    fun setPath(
        value: GcodeNozzlePath,
        beadHeightMm: Float,
        beadLineWidthMm: Float,
        filamentDiameterMm: Float,
    ) {
        if (path === value && beadHeight == beadHeightMm) return
        path = value
        beadHeight = beadHeightMm.coerceIn(0.02f, 2.0f)
        beadLineWidth = beadLineWidthMm.coerceIn(0.10f, 2.0f)
        filamentArea = (Math.PI.toFloat() * (filamentDiameterMm.coerceIn(0.5f, 4.0f) / 2f).let { it * it })
        selectedMoveIndex = if (value.moveCount <= 0) 0 else selectedMoveIndex.coerceIn(0, value.moveCount - 1)
        buildPathBuffers(value)
        buildGrid(value)
        buildMarker()
        resetCamera()
    }

    fun setSelectedMove(value: Int) {
        val current = path ?: return
        if (current.moveCount <= 0) return
        val safe = value.coerceIn(0, current.moveCount - 1)
        if (safe == selectedMoveIndex) return
        selectedMoveIndex = safe
        buildMarker()
    }

    fun setShowTravels(value: Boolean) {
        showTravels = value
    }

    fun setColorBySpeed(value: Boolean) {
        if (colorBySpeed == value) return
        colorBySpeed = value
        val current = path ?: return
        // Colours are baked into the buffers, so rebuild them on the toggle.
        buildPathBuffers(current)
    }

    val orientation: ViewerOrientation
        get() = ViewerOrientation(yaw, pitch)

    val zoomLevel: Float
        get() = zoom

    fun rotate(deltaYaw: Float, deltaPitch: Float) {
        yaw = wrapDegrees(yaw + deltaYaw)
        pitch = wrapDegrees(pitch + deltaPitch)
    }

    fun zoom(scaleFactor: Float) {
        if (!scaleFactor.isFinite() || scaleFactor <= 0f) return
        zoom = (zoom * scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    fun panPixels(deltaX: Float, deltaY: Float) {
        if (!deltaX.isFinite() || !deltaY.isFinite()) return
        val visibleHeight = 2f * cameraDistance() * CAMERA_EYE_DISTANCE_SCALE * tan(Math.toRadians(FIELD_OF_VIEW / 2.0)).toFloat()
        val worldPerPixel = visibleHeight / max(viewportHeight, 1)
        panX += deltaX * worldPerPixel
        panY -= deltaY * worldPerPixel
    }

    fun resetCamera() {
        yaw = DEFAULT_YAW
        pitch = DEFAULT_PITCH
        zoom = DEFAULT_ZOOM
        panX = 0f
        panY = 0f
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.055f, 0.065f, 0.08f, 1f)
        GLES20.glClearDepthf(1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glEnable(GL_MULTISAMPLE)
        litProgram = createProgram(LIT_VERTEX_SHADER, LIT_FRAGMENT_SHADER)
        colorProgram = createProgram(COLOR_VERTEX_SHADER, COLOR_FRAGMENT_SHADER)
        solidProgram = createProgram(SOLID_VERTEX_SHADER, SOLID_FRAGMENT_SHADER)
        maxLineWidth = queryMaxLineWidth()
    }

    private fun queryMaxLineWidth(): Float {
        val range = FloatArray(2)
        GLES20.glGetFloatv(GLES20.GL_ALIASED_LINE_WIDTH_RANGE, range, 0)
        return range[1].takeIf { it.isFinite() && it > 0f } ?: 1f
    }

    private fun lineWidth(width: Float) {
        GLES20.glLineWidth(width.coerceAtMost(maxLineWidth))
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = max(width, 1)
        viewportHeight = max(height, 1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    private fun computeCamera(aspect: Float): Float {
        val distance = cameraDistance()
        val radius = sceneRadius(requireNotNull(path))
        val nearPlane = max(0.05f, distance - radius * 1.6f)
        val farPlane = max(nearPlane + 100f, distance + radius * 2.8f + 100f)
        if (orthographic) {
            // True-width measurement mode: the projection is orthographic so
            // bead widths on screen match the physical path regardless of
            // perspective foreshortening.
            val halfHeight = distance * tan(Math.toRadians((FIELD_OF_VIEW / 2.0f).toDouble())).toFloat()
            val halfWidth = halfHeight * aspect
            Matrix.orthoM(projection, 0, -halfWidth, halfWidth, -halfHeight, halfHeight, nearPlane, farPlane)
        } else {
            Matrix.perspectiveM(projection, 0, FIELD_OF_VIEW, aspect, nearPlane, farPlane)
        }
        Matrix.setLookAtM(view, 0, 0f, -distance, distance * 0.62f, 0f, 0f, 0f, 0f, 0f, 1f)
        Matrix.translateM(view, 0, panX, panY, 0f)
        // Turntable around the printed-part centre, exactly like the model
        // viewer: rotate first, then bring the part centre to the origin.
        Matrix.setIdentityM(scene, 0)
        Matrix.rotateM(scene, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(scene, 0, yaw, 0f, 0f, 1f)
        Matrix.translateM(
            scene, 0,
            -(modelMinX + modelMaxX) * 0.5f,
            -(modelMinY + modelMaxY) * 0.5f,
            -(modelMinZ + modelMaxZ) * 0.5f,
        )
        Matrix.multiplyMM(modelView, 0, view, 0, scene, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
        return distance
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val current = path ?: return
        val aspect = viewportWidth.toFloat() / viewportHeight
        computeCamera(aspect)
        val upTo = (selectedMoveIndex + 1).coerceAtMost(extrusionPrefix.size - 1)
        val ribbonMoveCount = extrusionPrefix[upTo]

        drawSolidLines(gridPositions, gridVertexCount, 1f, 0.24f, 0.30f, 0.40f, 0.48f)
        // Physical beads in one lit pass: per-face analytic normals with a
        // fixed 3-light rig (key + fill + rim) so the bead facets read as a
        // solid printed part from every orbit angle without the zoomed-out
        // moire that interpolated normals caused.
        drawLitTriangles(
            ribbonPositions,
            ribbonNormals,
            ribbonColors,
            ribbonAmbient,
            ribbonMoveCount * RIBBON_VERTICES_PER_MOVE,
        )
        if (showTravels) {
            drawColoredLines(travelPositions, travelColors, travelPrefix[upTo] * 2, TRAVEL_WIDTH)
        }
        drawSolidLines(markerGlowPositions, markerGlowVertexCount, 8f, 0.85f, 0.55f, 0.30f, 0.20f)
        drawSolidLines(markerPositions, markerVertexCount, 4.5f, 1f, 1f, 1f, 1f)
    }

        private fun buildPathBuffers(value: GcodeNozzlePath) {
        val source = value.moves
        val stride = max(1, (value.extrusionMoveCount + RIBBON_MAX_MOVES - 1) / RIBBON_MAX_MOVES)
        val ribbonVertex = ArrayList<Float>((value.moveCount / stride) * 54)
        val ribbonNormal = ArrayList<Float>((value.moveCount / stride) * 54)
        val ribbonColor = ArrayList<Float>((value.moveCount / stride) * 72)
        val ribbonAmbientValues = ArrayList<Float>((value.moveCount / stride) * 18)
        val travelVertex = ArrayList<Float>((value.moveCount / stride) * 8)
        val travelColor = ArrayList<Float>((value.moveCount / stride) * 8)
        // Full-range extrusion points for the camera fit (percentile-trimmed
        // later), independent of the ribbon stride sampling.
        val boundsVertex = ArrayList<Float>(value.moveCount * 6)
        var minSpeed = Float.POSITIVE_INFINITY
        var maxSpeed = Float.NEGATIVE_INFINITY
        if (colorBySpeed) {
            for (moveIndex in 0 until value.moveCount) {
                val moveOffset = moveIndex * GcodeNozzlePath.VALUES_PER_MOVE
                if (source[moveOffset + GcodeNozzlePath.KIND] == GcodeNozzlePath.Kind.EXTRUSION.code) {
                    val speed = source[moveOffset + GcodeNozzlePath.SPEED]
                    minSpeed = min(minSpeed, speed)
                    maxSpeed = max(maxSpeed, speed)
                }
            }
        }
        val speedSpan = maxSpeed - minSpeed
        var offset = 0
        var extrusionMoves = 0
        var travelMoves = 0
        extrusionPrefix = IntArray(value.moveCount + 1)
        travelPrefix = IntArray(value.moveCount + 1)
        for (moveIndex in 0 until value.moveCount) {
            val extrusion = source[offset + GcodeNozzlePath.KIND] == GcodeNozzlePath.Kind.EXTRUSION.code
            val sx = source[offset + GcodeNozzlePath.X1]
            val sy = source[offset + GcodeNozzlePath.Y1]
            val sz = source[offset + GcodeNozzlePath.Z1]
            val ex = source[offset + GcodeNozzlePath.X2]
            val ey = source[offset + GcodeNozzlePath.Y2]
            val ez = source[offset + GcodeNozzlePath.Z2]
            if (extrusion) {
                boundsVertex += sx; boundsVertex += sy; boundsVertex += sz
                boundsVertex += ex; boundsVertex += ey; boundsVertex += ez
            }
            extrusionPrefix[moveIndex + 1] = extrusionMoves
            travelPrefix[moveIndex + 1] = travelMoves
            val sampled = moveIndex % stride == 0
            if (sampled) {
                val speedRatio = if (extrusion && speedSpan > 0f) {
                    ((source[offset + GcodeNozzlePath.SPEED] - minSpeed) / speedSpan).coerceIn(0f, 1f)
                } else 0f
                val zRatio = if (value.maxZ > value.minZ) {
                    ((ez - value.minZ) / (value.maxZ - value.minZ)).coerceIn(0f, 1f)
                } else 0f
                val color = if (extrusion) {
                    hsv(extrusionHue(zRatio, speedRatio, colorBySpeed), RIBBON_SATURATION, RIBBON_VALUE, 1f)
                } else floatArrayOf(0.50f, 0.54f, 0.64f, 0.20f)
                if (extrusion) {
                    addRibbonMove(
                        ribbonVertex, ribbonNormal, ribbonColor, ribbonAmbientValues,
                        sx, sy, sz, ex, ey, ez,
                        source[offset + GcodeNozzlePath.DELTA_E],
                        source[offset + GcodeNozzlePath.LAYER_HEIGHT],
                        color,
                    )
                    extrusionMoves++
                } else {
                    travelVertex += sx; travelVertex += sy; travelVertex += sz
                    travelVertex += ex; travelVertex += ey; travelVertex += ez
                    repeat(2) { color.forEach(travelColor::add) }
                    travelMoves++
                }
            }
            offset += GcodeNozzlePath.VALUES_PER_MOVE
        }
        ribbonPositions = bufferOf(ribbonVertex)
        ribbonNormals = bufferOf(ribbonNormal)
        ribbonColors = bufferOf(ribbonColor)
        ribbonAmbient = bufferOf(ribbonAmbientValues)
        travelPositions = bufferOf(travelVertex)
        travelColors = bufferOf(travelColor)

        // Camera bounds from the printed part only. Stray extrusion moves
        // (purge line at the plate corner, distant skirt loops) are trimmed
        // with per-axis percentiles so the orbit pivots on the model, not the
        // plate; the fallback keeps the view valid for travel-only paths.
        val bounds = NozzlePathBounds.printedBounds(boundsVertex.toFloatArray())
        if (bounds != null) {
            modelMinX = bounds[0]; modelMinY = bounds[1]; modelMinZ = bounds[2]
            modelMaxX = bounds[3]; modelMaxY = bounds[4]; modelMaxZ = bounds[5]
        } else {
            modelMinX = value.minX; modelMinY = value.minY; modelMinZ = value.minZ
            modelMaxX = value.maxX; modelMaxY = value.maxY; modelMaxZ = value.maxZ
        }
    }

    /**
     * Emits the physical bead for one extrusion move: a colored top face at
     * z + layer height plus shaded left/right side faces from z to z + height.
     * The width comes from the actual flow (deltaE * filament area / length /
     * layer height) bounded by the settings line width, so the geometry follows
     * the sliced settings (layer height, line width, flow).
     *
     * Lighting: each face carries ONE analytic normal (top = +Z, sides = the
     * perpendicular of the move direction). Constant normals per face mean a
     * constant luminance per facet - no interpolated gradients, so the render
     * stays crisp at every zoom and cannot produce corduroy moire. The
     * [ambient] channel carries the occlusion term: side faces get darker
     * toward the base (contact shadow) and odd layers keep the parity tint.
     */
    private fun addRibbonMove(
        vertex: ArrayList<Float>,
        normals: ArrayList<Float>,
        colors: ArrayList<Float>,
        ambient: ArrayList<Float>,
        sx: Float, sy: Float, sz: Float,
        ex: Float, ey: Float, ez: Float,
        deltaE: Float,
        parsedLayerHeight: Float,
        color: FloatArray,
    ) {
        val dx = ex - sx
        val dy = ey - sy
        val length = sqrt(dx * dx + dy * dy)
        val width = resolveBeadWidthMm(
            lengthMm = length,
            deltaE = deltaE,
            parsedLayerHeight = parsedLayerHeight,
            layerHeightFallback = beadHeight,
            lineWidth = beadLineWidth,
            filamentArea = filamentArea,
        )
        val height = if (parsedLayerHeight > 0.02f && parsedLayerHeight <= 2.0f) parsedLayerHeight else beadHeight
        val half = width * 0.5f
        val px = if (length > 1e-4f) -dy / length * half else 0f
        val py = if (length > 1e-4f) dx / length * half else 0f
        // Unit perpendicular of the move direction (outward right face).
        val ux = if (length > 1e-4f) -dy / length else 1f
        val uy = if (length > 1e-4f) dx / length else 0f
        // Subtle per-layer tint: odd layers are a touch darker so stacked
        // beads read as separate layers (the hue still follows speed/z).
        val level = if (height > 0f) (ez / height).roundToInt() else 0
        val parity = if (level and 1 == 0) 1f else RIBBON_LAYER_TINT
        // Quad corners: a/b on the start segment, c/d on the end segment,
        // left face = a->d, right face = b->c (top face at + height).
        val ax = sx - px; val ay = sy - py
        val bx = sx + px; val by = sy + py
        val cx = ex + px; val cy = ey + py
        val dxd = ex - px; val dyd = ey - py
        fun emit(amb: Float) {
            ambient += amb
        }

        // --- Top face (z + height), two triangles; normal +Z.
        vertex += ax; vertex += ay; vertex += sz + height
        vertex += bx; vertex += by; vertex += sz + height
        vertex += cx; vertex += cy; vertex += ez + height
        vertex += ax; vertex += ay; vertex += sz + height
        vertex += cx; vertex += cy; vertex += ez + height
        vertex += dxd; vertex += dyd; vertex += ez + height
        repeat(6) { normals += 0f; normals += 0f; normals += 1f }
        repeat(6) { color.forEach(colors::add) }
        repeat(6) { emit(parity * TOP_AMBIENT) }

        // --- Left side face (normal -u): base corners get contact occlusion.
        val leftBottomAmbient = parity * SIDE_BASE_AMBIENT
        val leftTopAmbient = parity * SIDE_TOP_AMBIENT
        vertex += ax; vertex += ay; vertex += sz
        vertex += dxd; vertex += dyd; vertex += ez
        vertex += dxd; vertex += dyd; vertex += ez + height
        repeat(3) { normals += -ux; normals += -uy; normals += 0f }
        repeat(3) { color.forEach(colors::add) }
        ambient += leftBottomAmbient; ambient += leftBottomAmbient; ambient += leftTopAmbient
        vertex += ax; vertex += ay; vertex += sz
        vertex += dxd; vertex += dyd; vertex += ez + height
        vertex += ax; vertex += ay; vertex += sz + height
        repeat(3) { normals += -ux; normals += -uy; normals += 0f }
        repeat(3) { color.forEach(colors::add) }
        ambient += leftBottomAmbient; ambient += leftTopAmbient; ambient += leftTopAmbient

        // --- Right side face (normal +u).
        vertex += bx; vertex += by; vertex += sz
        vertex += cx; vertex += cy; vertex += ez
        vertex += cx; vertex += cy; vertex += ez + height
        repeat(3) { normals += ux; normals += uy; normals += 0f }
        repeat(3) { color.forEach(colors::add) }
        ambient += leftBottomAmbient; ambient += leftBottomAmbient; ambient += leftTopAmbient
        vertex += bx; vertex += by; vertex += sz
        vertex += cx; vertex += cy; vertex += ez + height
        vertex += bx; vertex += by; vertex += sz + height
        repeat(3) { normals += ux; normals += uy; normals += 0f }
        repeat(3) { color.forEach(colors::add) }
        ambient += leftBottomAmbient; ambient += leftTopAmbient; ambient += leftTopAmbient
    }

    private fun bufferOf(values: List<Float>): FloatBuffer? {
        if (values.isEmpty()) return null
        val array = FloatArray(values.size) { values[it] }
        val buffer = allocate(array.size)
        buffer.put(array)
        buffer.position(0)
        return buffer
    }

    private fun buildGrid(value: GcodeNozzlePath) {
        val width = max(modelMaxX - modelMinX, 1f)
        val depth = max(modelMaxY - modelMinY, 1f)
        val step = gridStep(max(width, depth))
        val minX = floor(modelMinX / step) * step
        val maxX = kotlin.math.ceil(modelMaxX / step) * step
        val minY = floor(modelMinY / step) * step
        val maxY = kotlin.math.ceil(modelMaxY / step) * step
        val xLines = ((maxX - minX) / step).toInt() + 1
        val yLines = ((maxY - minY) / step).toInt() + 1
        val buffer = allocate((xLines + yLines) * 2 * 3)
        val z = modelMinZ
        for (line in 0 until xLines) {
            val x = minX + line * step
            buffer.put(x); buffer.put(minY); buffer.put(z)
            buffer.put(x); buffer.put(maxY); buffer.put(z)
        }
        for (line in 0 until yLines) {
            val y = minY + line * step
            buffer.put(minX); buffer.put(y); buffer.put(z)
            buffer.put(maxX); buffer.put(y); buffer.put(z)
        }
        buffer.position(0)
        gridPositions = buffer
        gridVertexCount = (xLines + yLines) * 2
    }

    private fun buildMarker() {
        val current = path ?: return
        val offset = selectedMoveIndex.coerceIn(0, current.moveCount - 1) * GcodeNozzlePath.VALUES_PER_MOVE
        val x = current.moves[offset + GcodeNozzlePath.X2]
        val y = current.moves[offset + GcodeNozzlePath.Y2]
        val z = current.moves[offset + GcodeNozzlePath.Z2]
        val size = max(sceneRadius(current) * 0.022f, 0.9f)
        // Crosshair: three axis-aligned line pairs centred on the nozzle tip.
        val buffer = allocate(18)
        buffer.put(x - size); buffer.put(y); buffer.put(z)
        buffer.put(x + size); buffer.put(y); buffer.put(z)
        buffer.put(x); buffer.put(y - size); buffer.put(z)
        buffer.put(x); buffer.put(y + size); buffer.put(z)
        buffer.put(x); buffer.put(y); buffer.put(z - size)
        buffer.put(x); buffer.put(y); buffer.put(z + size)
        buffer.position(0)
        markerPositions = buffer
        markerVertexCount = 6
        // Soft amber halo behind the crosshair so the nozzle reads clearly
        // against the blue-to-red path colours.
        val glow = allocate(18)
        val glowSize = size * 1.7f
        glow.put(x - glowSize); glow.put(y); glow.put(z)
        glow.put(x + glowSize); glow.put(y); glow.put(z)
        glow.put(x); glow.put(y - glowSize); glow.put(z)
        glow.put(x); glow.put(y + glowSize); glow.put(z)
        glow.put(x); glow.put(y); glow.put(z - glowSize)
        glow.put(x); glow.put(y); glow.put(z + glowSize)
        glow.position(0)
        markerGlowPositions = glow
        markerGlowVertexCount = 6
    }

    /** Returns the full-path move index nearest to a tap (screen px), or -1. */
    fun pickNearestMove(screenX: Float, screenY: Float): Int {
        val current = path ?: return -1
        if (current.moveCount <= 0) return -1
        val aspect = viewportWidth.toFloat() / max(viewportHeight, 1)
        computeCamera(aspect)
        val source = current.moves
        var bestDistanceSq = Float.POSITIVE_INFINITY
        var bestIndex = -1
        var offset = 0
        for (moveIndex in 0 until current.moveCount) {
            project(source, offset + GcodeNozzlePath.X1, offset + GcodeNozzlePath.Y1, offset + GcodeNozzlePath.Z1)
            val ax = pickOut[0] / pickOut[3]
            val ay = pickOut[1] / pickOut[3]
            project(source, offset + GcodeNozzlePath.X2, offset + GcodeNozzlePath.Y2, offset + GcodeNozzlePath.Z2)
            val bx = pickOut[0] / pickOut[3]
            val by = pickOut[1] / pickOut[3]
            // NDC (y up) to screen pixels (y down).
            val sx1 = (ax + 1f) * 0.5f * viewportWidth
            val sy1 = (1f - ay) * 0.5f * viewportHeight
            val sx2 = (bx + 1f) * 0.5f * viewportWidth
            val sy2 = (1f - by) * 0.5f * viewportHeight
            val distanceSq = segmentDistanceSq(screenX, screenY, sx1, sy1, sx2, sy2)
            if (distanceSq < bestDistanceSq) {
                // Ignore points behind the camera.
                val behind = pickOut[3] <= 0f
                if (!behind) {
                    bestDistanceSq = distanceSq
                    bestIndex = moveIndex
                }
            }
            offset += GcodeNozzlePath.VALUES_PER_MOVE
        }
        return bestIndex
    }

    private fun project(source: FloatArray, xOffset: Int, yOffset: Int, zOffset: Int) {
        pickIn[0] = source[xOffset]
        pickIn[1] = source[yOffset]
        pickIn[2] = source[zOffset]
        pickIn[3] = 1f
        Matrix.multiplyMV(pickOut, 0, mvp, 0, pickIn, 0)
    }

    private fun segmentDistanceSq(
        px: Float, py: Float,
        ax: Float, ay: Float,
        bx: Float, by: Float,
    ): Float {
        val vx = bx - ax
        val vy = by - ay
        val denominator = vx * vx + vy * vy
        if (denominator <= 1e-8f) {
            val dx = px - ax
            val dy = py - ay
            return dx * dx + dy * dy
        }
        val t = ((px - ax) * vx + (py - ay) * vy) / denominator
        val clamped = t.coerceIn(0f, 1f)
        val cx = ax + vx * clamped
        val cy = ay + vy * clamped
        val dx = px - cx
        val dy = py - cy
        return dx * dx + dy * dy
    }

    private fun drawLitTriangles(
        positions: FloatBuffer?,
        normals: FloatBuffer?,
        colors: FloatBuffer?,
        ambient: FloatBuffer?,
        vertexCount: Int,
    ) {
        if (positions == null || normals == null || colors == null || ambient == null || vertexCount <= 0) return
        GLES20.glUseProgram(litProgram)
        val position = GLES20.glGetAttribLocation(litProgram, "aPosition")
        val normal = GLES20.glGetAttribLocation(litProgram, "aNormal")
        val color = GLES20.glGetAttribLocation(litProgram, "aColor")
        val ambientLoc = GLES20.glGetAttribLocation(litProgram, "aAmbient")
        val matrix = GLES20.glGetUniformLocation(litProgram, "uMvpMatrix")
        val sceneMatrix = GLES20.glGetUniformLocation(litProgram, "uSceneMatrix")
        val keyDir = GLES20.glGetUniformLocation(litProgram, "uKeyDir")
        val fillDir = GLES20.glGetUniformLocation(litProgram, "uFillDir")
        val viewDir = GLES20.glGetUniformLocation(litProgram, "uViewDir")
        positions.position(0)
        normals.position(0)
        colors.position(0)
        ambient.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(normal)
        GLES20.glEnableVertexAttribArray(color)
        GLES20.glEnableVertexAttribArray(ambientLoc)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 12, positions)
        GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 12, normals)
        GLES20.glVertexAttribPointer(color, 4, GLES20.GL_FLOAT, false, 16, colors)
        GLES20.glVertexAttribPointer(ambientLoc, 1, GLES20.GL_FLOAT, false, 4, ambient)
        GLES20.glUniformMatrix4fv(matrix, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(sceneMatrix, 1, false, scene, 0)
        GLES20.glUniform3f(keyDir, KEY_LIGHT[0], KEY_LIGHT[1], KEY_LIGHT[2])
        GLES20.glUniform3f(fillDir, FILL_LIGHT[0], FILL_LIGHT[1], FILL_LIGHT[2])
        GLES20.glUniform3f(viewDir, 0f, -VIEW_EYE_Y, VIEW_EYE_Z)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(normal)
        GLES20.glDisableVertexAttribArray(color)
        GLES20.glDisableVertexAttribArray(ambientLoc)
    }

    private fun drawColoredLines(positions: FloatBuffer?, colors: FloatBuffer?, vertexCount: Int, width: Float) {
        if (positions == null || colors == null || vertexCount <= 0) return
        GLES20.glUseProgram(colorProgram)
        val position = GLES20.glGetAttribLocation(colorProgram, "aPosition")
        val color = GLES20.glGetAttribLocation(colorProgram, "aColor")
        val matrix = GLES20.glGetUniformLocation(colorProgram, "uMvpMatrix")
        positions.position(0)
        colors.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(color)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 12, positions)
        GLES20.glVertexAttribPointer(color, 4, GLES20.GL_FLOAT, false, 16, colors)
        GLES20.glUniformMatrix4fv(matrix, 1, false, mvp, 0)
        lineWidth(width)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, vertexCount)
        GLES20.glLineWidth(1f)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(color)
    }

    private fun drawSolidLines(
        buffer: FloatBuffer?,
        vertexCount: Int,
        width: Float,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
    ) {
        if (buffer == null || vertexCount <= 0) return
        GLES20.glUseProgram(solidProgram)
        val position = GLES20.glGetAttribLocation(solidProgram, "aPosition")
        val matrix = GLES20.glGetUniformLocation(solidProgram, "uMvpMatrix")
        val color = GLES20.glGetUniformLocation(solidProgram, "uColor")
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 12, buffer)
        GLES20.glUniformMatrix4fv(matrix, 1, false, mvp, 0)
        GLES20.glUniform4f(color, red, green, blue, alpha)
        lineWidth(width)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, vertexCount)
        GLES20.glLineWidth(1f)
        GLES20.glDisableVertexAttribArray(position)
    }

    /** Moves the orbit pivot to the pending touch point (ray-plane intersection). */
    private fun cameraDistance(): Float {
        val current = path ?: return 300f
        return max(sceneRadius(current) * 3.4f / zoom, 2f)
    }

    private fun sceneRadius(value: GcodeNozzlePath): Float {
        val dx = max(modelMaxX - modelMinX, 1f)
        val dy = max(modelMaxY - modelMinY, 1f)
        val dz = max(modelMaxZ - modelMinZ, 1f)
        return sqrt(dx * dx + dy * dy + dz * dz) * 0.5f
    }

    private fun gridStep(span: Float): Float = when {
        span <= 40f -> 5f
        span <= 100f -> 10f
        span <= 250f -> 20f
        else -> 50f
    }

    private fun allocate(floatCount: Int): FloatBuffer = ByteBuffer
        .allocateDirect(floatCount * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private fun hsv(hue: Float, saturation: Float, value: Float, alpha: Float): FloatArray {
        val h = ((hue % 360f) + 360f) % 360f / 60f
        val sector = floor(h).toInt()
        val fraction = h - sector
        val p = value * (1f - saturation)
        val q = value * (1f - saturation * fraction)
        val t = value * (1f - saturation * (1f - fraction))
        val rgb = when (sector) {
            0 -> floatArrayOf(value, t, p)
            1 -> floatArrayOf(q, value, p)
            2 -> floatArrayOf(p, value, t)
            3 -> floatArrayOf(p, q, value)
            4 -> floatArrayOf(t, p, value)
            else -> floatArrayOf(value, p, q)
        }
        return floatArrayOf(rgb[0], rgb[1], rgb[2], alpha)
    }

    private fun wrapDegrees(value: Float): Float {
        var result = value % 360f
        if (result < -180f) result += 360f
        if (result > 180f) result -= 360f
        return result
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES20.glCreateProgram().also { program ->
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(program) }
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
        }
    }

    private fun compileShader(type: Int, source: String): Int = GLES20.glCreateShader(type).also { shader ->
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
    }

    companion object {
        private const val FIELD_OF_VIEW = 42f
        private const val DEFAULT_YAW = -32f
        private const val DEFAULT_PITCH = 58f
        private const val DEFAULT_ZOOM = 1f
        private const val MIN_ZOOM = 0.25f
        private const val MAX_ZOOM = 60f
        private const val PATH_WIDTH = 6f
        private const val TRAVEL_WIDTH = 1.5f
        private const val RIBBON_MAX_MOVES = 160_000
        private const val RIBBON_VERTICES_PER_MOVE = 18
        private const val RIBBON_SATURATION = 0.62f
        private const val RIBBON_VALUE = 0.92f
        // Fixed WORLD-space studio rig: the bead path rotates under the lights
        // while orbiting, so faces shade consistently from every angle. These
        // are directions TOWARD the light.
        private val KEY_LIGHT = normalize3(0.52f, -0.58f, 0.63f)
        private val FILL_LIGHT = normalize3(-0.62f, 0.30f, 0.55f)
        // Eye sits at (0, -distance, 0.62*distance) in view space.
        private const val VIEW_EYE_Y = -0.85f
        private const val VIEW_EYE_Z = 0.53f
        // Ambient terms: the top face catches the key light head-on; side faces
        // darken toward the base so beads look seated on the layer below, and
        // odd layers keep a subtle tint so stacked layers separate.
        private const val TOP_AMBIENT = 0.97f
        private const val SIDE_TOP_AMBIENT = 0.92f
        private const val SIDE_BASE_AMBIENT = 0.74f
        // Odd layers render a touch darker so layers separate visually.
        private const val RIBBON_LAYER_TINT = 0.96f
        // Eye sits at (0, -distance, 0.58*distance); true eye distance is distance * sqrt(1 + 0.58^2).
        private const val CAMERA_EYE_DISTANCE_SCALE = 1.1561f
        private const val GL_MULTISAMPLE = 0x80D1

        private fun normalize3(x: Float, y: Float, z: Float): FloatArray {
            val length = sqrt(x * x + y * y + z * z)
            return floatArrayOf(x / length, y / length, z / length)
        }

        private const val LIT_VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            uniform mat4 uSceneMatrix;
            attribute vec4 aPosition;
            attribute vec3 aNormal;
            attribute vec4 aColor;
            attribute float aAmbient;
            varying vec4 vColor;
            varying vec3 vWorldNormal;
            varying float vAmbient;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
                vColor = aColor;
                vWorldNormal = normalize((uSceneMatrix * vec4(aNormal, 0.0)).xyz);
                vAmbient = aAmbient;
            }
        """
        private const val LIT_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec4 vColor;
            varying vec3 vWorldNormal;
            varying float vAmbient;
            uniform vec3 uKeyDir;
            uniform vec3 uFillDir;
            uniform vec3 uViewDir;
            void main() {
                vec3 n = normalize(vWorldNormal);
                vec3 key = normalize(uKeyDir);
                vec3 fill = normalize(uFillDir);
                vec3 v = normalize(uViewDir);
                float kd = max(dot(n, key), 0.0);
                float fd = max(dot(n, fill), 0.0);
                float rim = pow(1.0 - max(dot(n, v), 0.0), 2.0);
                vec3 halfV = normalize(key + v);
                float spec = pow(max(dot(n, halfV), 0.0), 32.0);
                float light = vAmbient * (0.40 + 0.62 * kd + 0.26 * fd + 0.14 * rim);
                vec3 lit = vColor.rgb * light + vec3(0.10 * spec);
                gl_FragColor = vec4(lit, vColor.a);
            }
        """
        private const val COLOR_VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            attribute vec4 aPosition;
            attribute vec4 aColor;
            varying vec4 vColor;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
                vColor = aColor;
            }
        """
        private const val COLOR_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec4 vColor;
            void main() {
                gl_FragColor = vColor;
            }
        """
        private const val SOLID_VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            attribute vec4 aPosition;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
            }
        """
        private const val SOLID_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """
    }
}
