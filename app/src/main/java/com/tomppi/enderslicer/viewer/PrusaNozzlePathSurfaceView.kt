package com.tomppi.enderslicer.viewer

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.tomppi.enderslicer.engine.PrusaNozzlePath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * GPU-backed nozzle-path renderer for PrusaSlicer output.
 * Geometry comes from Prusa’s own ;WIDTH: / ;HEIGHT: markers (authoritative bead
 * width and layer height), never from extrusion-delta estimation. All geometry
 * lives in vertex buffer objects (GPU memory), uploaded once per path.
 * Features: turntable orbit, pinch zoom, two-finger pan, double-tap reset,
 * tap-to-pick, grid, nozzle crosshair, travel lines, speed colors, ortho mode.
 */
class PrusaNozzlePathSurfaceView(context: Context) : GLSurfaceView(context) {
    private val pathRenderer = PrusaNozzlePathRenderer()
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())
    private var previousX = 0f
    private var previousY = 0f
    private var previousFocusX = 0f
    private var previousFocusY = 0f
    private var panning = false

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(pathRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        isClickable = true
    }

    private var lastQueuedPath: PrusaNozzlePath? = null
    private var lastQueuedMove = -1

    fun setPath(path: PrusaNozzlePath, selectedMoveIndex: Int) {
        // AndroidView.update fires on every recomposition (playback ticks
        // included); skip unchanged calls so the GL queue is not flooded.
        if (lastQueuedPath === path && lastQueuedMove == selectedMoveIndex) return
        lastQueuedPath = path
        lastQueuedMove = selectedMoveIndex
        queueEvent {
            pathRenderer.setPath(path)
            pathRenderer.setSelectedMove(selectedMoveIndex)
        }
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

    /** When true, extrusion colours follow print speed instead of layer height. */
    var colorBySpeed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            queueEvent { pathRenderer.setColorBySpeed(value) }
            requestRender()
        }

    /** Orthographic (true-width) camera for measuring the path. */
    var orthographic: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            queueEvent { pathRenderer.orthographic = value }
            requestRender()
        }

    var onOrientationChanged: ((ViewerOrientation) -> Unit)? = null
    var onZoomChanged: ((Float) -> Unit)? = null
    var onMovePicked: ((Int) -> Unit)? = null

    fun currentOrientation(): ViewerOrientation = pathRenderer.orientation

    fun currentZoom(): Float = pathRenderer.zoomLevel

    /** Resets yaw, pitch, zoom, pan and the orbit pivot back to the model fit. */
    fun resetView() {
        queueEvent { pathRenderer.resetCamera() }
        queueEvent { notifyOrientation() }
        queueEvent { notifyZoom() }
        requestRender()
    }

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
                    if (panning) pathRenderer.panPixels(focusX - previousFocusX, focusY - previousFocusY)
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
}

/** GL renderer: geometry build, VBO upload, draw, camera, picking. */
internal class PrusaNozzlePathRenderer : GLSurfaceView.Renderer {
    var orthographic = false

    private var path: PrusaNozzlePath? = null
    private var selectedMoveIndex = 0
    private var colorBySpeed = false
    private var showTravels = true

    internal var ribbonPositions: FloatBuffer? = null
    internal var ribbonNormals: FloatBuffer? = null
    internal var ribbonColors: FloatBuffer? = null
    internal var ribbonAmbient: FloatBuffer? = null
    private var travelPositions: FloatBuffer? = null
    private var travelColors: FloatBuffer? = null
    private var gridPositions: FloatBuffer? = null
    private var gridVertexCount = 0
    private var markerPositions: FloatBuffer? = null
    private var markerGlowPositions: FloatBuffer? = null
    private var markerVertexCount = 0
    private var markerGlowVertexCount = 0
    internal var ribbonPrefix = IntArray(1)
    internal var travelPrefix = IntArray(1)

    private var pathVbos = IntArray(0)
    private var uploadedPath: PrusaNozzlePath? = null
    private var uploadedColorBySpeed = false

    private var modelMinX = -115f
    private var modelMaxX = 115f
    private var modelMinY = -115f
    private var modelMaxY = 115f
    private var modelMinZ = 0f
    private var modelMaxZ = 250f
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var maxLineWidth = 1f
    private var yaw = PrusaNozzlePathViewDefaults.DEFAULT_YAW
    private var pitch = PrusaNozzlePathViewDefaults.DEFAULT_PITCH
    private var zoom = PrusaNozzlePathViewDefaults.DEFAULT_ZOOM
    private var panX = 0f
    private var panY = 0f

    private var litProgram = 0
    private var colorProgram = 0
    private var solidProgram = 0
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val scene = FloatArray(16)
    private val modelView = FloatArray(16)
    private val mvp = FloatArray(16)
    private val pickIn = FloatArray(4)
    private val pickOut = FloatArray(4)

    val orientation: ViewerOrientation
        get() = ViewerOrientation(yaw, pitch)

    val zoomLevel: Float
        get() = zoom

    fun setPath(value: PrusaNozzlePath) {
        if (path === value) return
        path = value
        selectedMoveIndex = if (value.moveCount <= 0) 0 else selectedMoveIndex.coerceIn(0, value.moveCount - 1)
        try {
            buildPathBuffers(value)
            buildGrid(value)
            buildMarker()
            Log.i("PrusaNozzlePathView", "geometry built: " + value.moveCount + " moves")
        } catch (error: Throwable) {
            Log.e("PrusaNozzlePathView", "Unable to build prusa nozzle-path buffers", error)
            ribbonPositions = null
            ribbonNormals = null
            ribbonColors = null
            ribbonAmbient = null
            travelPositions = null
            travelColors = null
        }
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

    fun setShowTravels(value: Boolean) { showTravels = value }

    fun setColorBySpeed(value: Boolean) {
        if (colorBySpeed == value) return
        colorBySpeed = value
        val current = path ?: return
        try {
            rebuildColors(current)
        } catch (error: Throwable) {
            // Keep the previous colors and model; a failed recolor must never
            // blank the rendered path.
            Log.e("PrusaNozzlePathView", "Unable to recolor prusa nozzle path", error)
        }
    }

    fun rotate(deltaYaw: Float, deltaPitch: Float) {
        yaw = wrapDegrees(yaw + deltaYaw)
        pitch = wrapDegrees(pitch + deltaPitch)
    }

    fun zoom(scaleFactor: Float) {
        if (!scaleFactor.isFinite() || scaleFactor <= 0f) return
        zoom = (zoom * scaleFactor).coerceIn(PrusaNozzlePathViewDefaults.MIN_ZOOM, PrusaNozzlePathViewDefaults.MAX_ZOOM)
    }

    fun panPixels(deltaX: Float, deltaY: Float) {
        if (!deltaX.isFinite() || !deltaY.isFinite()) return
        val visibleHeight = 2f * cameraDistance() * PrusaNozzlePathViewDefaults.CAMERA_EYE_DISTANCE_SCALE *
            tan(Math.toRadians(PrusaNozzlePathViewDefaults.FIELD_OF_VIEW / 2.0)).toFloat()
        val worldPerPixel = visibleHeight / max(viewportHeight, 1)
        panX += deltaX * worldPerPixel
        panY -= deltaY * worldPerPixel
    }

    fun resetCamera() {
        yaw = PrusaNozzlePathViewDefaults.DEFAULT_YAW
        pitch = PrusaNozzlePathViewDefaults.DEFAULT_PITCH
        zoom = PrusaNozzlePathViewDefaults.DEFAULT_ZOOM
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
        litProgram = createProgram(LIT_VERTEX_SHADER, LIT_FRAGMENT_SHADER)
        colorProgram = createProgram(COLOR_VERTEX_SHADER, COLOR_FRAGMENT_SHADER)
        solidProgram = createProgram(SOLID_VERTEX_SHADER, SOLID_FRAGMENT_SHADER)
        pathVbos = IntArray(0)
        uploadedPath = null
        uploadedColorBySpeed = false
        maxLineWidth = queryMaxLineWidth()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = max(width, 1)
        viewportHeight = max(height, 1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        try { drawFrame() } catch (error: Throwable) { Log.e("PrusaNozzlePathView", "frame failed", error) }
    }

    private fun drawFrame() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val current = path ?: return
        val aspect = viewportWidth.toFloat() / viewportHeight
        computeCamera(aspect)
        val upTo = (selectedMoveIndex + 1).coerceAtMost(ribbonPrefix.size - 1)
        val ribbonCount = ribbonPrefix[upTo]
        drawSolidLines(gridPositions, gridVertexCount, 1f, 0.24f, 0.30f, 0.40f, 0.48f)
        ensureUploads(current)
        // Hard clamp: never draw past the emitted vertex data. A mismatch here
        // previously produced "model disappears after a certain point" and
        // scrambled speed colors; the clamp degrades to fewer ribbons and
        // logs the mismatch for diagnosis.
        val maxRibbonVerts = (ribbonPositions?.limit() ?: 0) / 3
        val requested = ribbonCount * WINDOW_VERTICES
        val litVerts = if (requested <= maxRibbonVerts) {
            requested
        } else {
            Log.e("PrusaNozzlePathView", "ribbon overdraw: requested " + requested + " vertices, have " + maxRibbonVerts)
            maxRibbonVerts
        }
        if (pathVbos.size == 6 && pathVbos[0] != 0) {
            drawLitTrianglesVbo(litVerts)
        } else {
            drawLitTriangles(litVerts)
        }
        if (showTravels) {
            val maxTravelVerts = (travelPositions?.limit() ?: 0) / 3
            val travelRequested = travelPrefix[upTo] * 2
            val travelVerts = if (travelRequested <= maxTravelVerts) {
                travelRequested
            } else {
                Log.e("PrusaNozzlePathView", "travel overdraw: requested " + travelRequested + " vertices, have " + maxTravelVerts)
                maxTravelVerts
            }
            if (pathVbos.size == 6 && pathVbos[4] != 0) {
                drawColoredLinesVbo(travelVerts, PrusaNozzlePathViewDefaults.TRAVEL_WIDTH)
            } else {
                drawColoredLines(travelVerts, PrusaNozzlePathViewDefaults.TRAVEL_WIDTH)
            }
        }
        drawSolidLines(markerGlowPositions, markerGlowVertexCount, 8f, 0.85f, 0.55f, 0.30f, 0.20f)
        drawSolidLines(markerPositions, markerVertexCount, 4.5f, 1f, 1f, 1f, 1f)
    }
    /** Builds continuous ribbon strips from marker-driven runs. */
    internal fun buildPathBuffers(value: PrusaNozzlePath) {
        val source = value.moves
        val n = value.moveCount
        val ribbonVertex = DirectFloatSink()
        val ribbonNormal = DirectFloatSink()
        val ribbonColor = DirectFloatSink()
        val ribbonAmbientValues = DirectFloatSink()
        val travelVertex = DirectFloatSink()
        val travelColor = DirectFloatSink()
        var windowCount = 0
        var travelCount = 0
        ribbonPrefix = IntArray(n + 1)
        travelPrefix = IntArray(n + 1)
        val (minSpeed, maxSpeed) = speedRange(value)
        val speedSpan = maxSpeed - minSpeed
        forEachWindow(value) { kind, winStart, winLast, sx, sy, sz, ex, ey, ez, runWidth, runHeight ->
            if (kind == PrusaNozzlePath.Kind.EXTRUSION.code) {
                val wo = winStart * PrusaNozzlePath.VALUES_PER_MOVE
                val speedRatio = if (speedSpan > 0f) {
                    ((source[wo + PrusaNozzlePath.SPEED] - minSpeed) / speedSpan).coerceIn(0f, 1f)
                } else 0f
                val zRatio = if (value.maxZ > value.minZ) {
                    ((ez - value.minZ) / (value.maxZ - value.minZ)).coerceIn(0f, 1f)
                } else 0f
                val color = hsv(extrusionHue(zRatio, speedRatio, colorBySpeed), RIBBON_SATURATION, RIBBON_VALUE, 1f)
                // Window boundaries are polyline points of the run: the
                // shared miter normal is identical for the closing window
                // and the next opening window, so corner vertices coincide
                // exactly (continuous strip - no junction slivers).
                val sN = boundaryNormal(winStart, source)
                val eN = boundaryNormal(winLast + 1, source)
                addRibbonMove(
                    ribbonVertex, ribbonNormal, ribbonColor, ribbonAmbientValues,
                    sx, sy, sz, ex, ey, ez,
                    runWidth,
                    runHeight,
                    sN.first, sN.second,
                    eN.first, eN.second,
                    color,
                )
                windowCount++
            } else {
                // Skip pure-Z layer transitions (vertical scratches).
                val travelDx = ex - sx
                val travelDy = ey - sy
                val travelDz = ez - sz
                val travelLen = sqrt(travelDx * travelDx + travelDy * travelDy + travelDz * travelDz)
                val verticalOnly = travelLen > 1e-7f && sqrt(travelDx * travelDx + travelDy * travelDy) < travelLen * 0.25f
                if (!verticalOnly) {
                    travelVertex += sx; travelVertex += sy; travelVertex += sz
                    travelVertex += ex; travelVertex += ey; travelVertex += ez
                    repeat(2) { travelColor += 0.50f; travelColor += 0.54f; travelColor += 0.64f; travelColor += 0.20f }
                    travelCount++
                }
            }
            for (m in winStart until winLast) {
                ribbonPrefix[m + 1] = windowCount
                travelPrefix[m + 1] = travelCount
            }
            ribbonPrefix[winLast + 1] = windowCount
            travelPrefix[winLast + 1] = travelCount
        }
        ribbonPositions = ribbonVertex.toFloatBuffer()
        ribbonNormals = ribbonNormal.toFloatBuffer()
        ribbonColors = ribbonColor.toFloatBuffer()
        ribbonAmbient = ribbonAmbientValues.toFloatBuffer()
        travelPositions = travelVertex.toFloatBuffer()
        travelColors = travelColor.toFloatBuffer()
        // Camera bounds from the printed part only (extrusion moves).
        var pMinX = Float.POSITIVE_INFINITY; var pMaxX = Float.NEGATIVE_INFINITY
        var pMinY = Float.POSITIVE_INFINITY; var pMaxY = Float.NEGATIVE_INFINITY
        var pMinZ = Float.POSITIVE_INFINITY; var pMaxZ = Float.NEGATIVE_INFINITY
        for (m in 0 until n) {
            val o = m * PrusaNozzlePath.VALUES_PER_MOVE
            if (source[o + PrusaNozzlePath.KIND] == PrusaNozzlePath.Kind.EXTRUSION.code) {
                pMinX = min(pMinX, min(source[o + PrusaNozzlePath.X1], source[o + PrusaNozzlePath.X2]))
                pMaxX = max(pMaxX, max(source[o + PrusaNozzlePath.X1], source[o + PrusaNozzlePath.X2]))
                pMinY = min(pMinY, min(source[o + PrusaNozzlePath.Y1], source[o + PrusaNozzlePath.Y2]))
                pMaxY = max(pMaxY, max(source[o + PrusaNozzlePath.Y1], source[o + PrusaNozzlePath.Y2]))
                pMinZ = min(pMinZ, min(source[o + PrusaNozzlePath.Z1], source[o + PrusaNozzlePath.Z2]))
                pMaxZ = max(pMaxZ, max(source[o + PrusaNozzlePath.Z1], source[o + PrusaNozzlePath.Z2]))
            }
        }
        modelMinX = pMinX; modelMaxX = pMaxX
        modelMinY = pMinY; modelMaxY = pMaxY
        modelMinZ = pMinZ; modelMaxZ = pMaxZ
    }

    /**
     * Rebuilds ONLY the color stream: geometry, normals, ambient and the
     * per-move prefixes are color-independent. The speed-colors toggle must
     * not reallocate the whole ribbon set - with the previous set still live
     * that duplicate build was the memory pressure that first crashed and,
     * once guarded, nulled the buffers so no model was visible at all. On
     * failure the previous colors simply remain (the model stays visible).
     */
    private fun rebuildColors(value: PrusaNozzlePath) {
        val source = value.moves
        val (minSpeed, maxSpeed) = speedRange(value)
        val speedSpan = maxSpeed - minSpeed
        val colorSink = DirectFloatSink()
        forEachWindow(value) { kind, winStart, _, sx, sy, sz, ex, ey, ez, runWidth, runHeight ->
            if (kind == PrusaNozzlePath.Kind.EXTRUSION.code) {
                val wo = winStart * PrusaNozzlePath.VALUES_PER_MOVE
                val speedRatio = if (speedSpan > 0f) {
                    ((source[wo + PrusaNozzlePath.SPEED] - minSpeed) / speedSpan).coerceIn(0f, 1f)
                } else 0f
                val zRatio = if (value.maxZ > value.minZ) {
                    ((ez - value.minZ) / (value.maxZ - value.minZ)).coerceIn(0f, 1f)
                } else 0f
                val color = hsv(extrusionHue(zRatio, speedRatio, colorBySpeed), RIBBON_SATURATION, RIBBON_VALUE, 1f)
                repeat(PrusaNozzlePathViewDefaults.WINDOW_VERTICES) {
                    colorSink += color[0]; colorSink += color[1]; colorSink += color[2]; colorSink += color[3]
                }
            }
        }
        ribbonColors = colorSink.toFloatBuffer()
    }

    /**
     * Walks the natural runs (same kind + collinear + same width marker)
     * split into one window per move, in emission order. One window per move,
     * always: full fidelity - ribbon geometry uses the machine's memory; if a
     * journey truly cannot fit, the system kills the process instead of us
     * degrading the preview with coarser windows.
     */
    private fun forEachWindow(
        value: PrusaNozzlePath,
        onWindow: (kind: Float, winStart: Int, winLast: Int, sx: Float, sy: Float, sz: Float, ex: Float, ey: Float, ez: Float, runWidth: Float, runHeight: Float) -> Unit,
    ) {
        val source = value.moves
        val n = value.moveCount
        val stride = 1
        var moveIndex = 0
        while (moveIndex < n) {
            val oi = moveIndex * PrusaNozzlePath.VALUES_PER_MOVE
            val kind = source[oi + PrusaNozzlePath.KIND]
            val runWidth = source[oi + PrusaNozzlePath.WIDTH]
            val runHeight = source[oi + PrusaNozzlePath.HEIGHT]
            // Natural run: same kind + collinear + same width marker.
            var runEnd = moveIndex + 1
            var fdx = source[oi + PrusaNozzlePath.X2] - source[oi + PrusaNozzlePath.X1]
            var fdy = source[oi + PrusaNozzlePath.Y2] - source[oi + PrusaNozzlePath.Y1]
            val firstLen = sqrt(fdx * fdx + fdy * fdy)
            if (firstLen > 1e-7f) { fdx /= firstLen; fdy /= firstLen }
            while (runEnd < n &&
                source[runEnd * PrusaNozzlePath.VALUES_PER_MOVE + PrusaNozzlePath.KIND] == kind &&
                source[runEnd * PrusaNozzlePath.VALUES_PER_MOVE + PrusaNozzlePath.WIDTH] == runWidth) {
                val ro = runEnd * PrusaNozzlePath.VALUES_PER_MOVE
                var ndx = source[ro + PrusaNozzlePath.X2] - source[ro + PrusaNozzlePath.X1]
                var ndy = source[ro + PrusaNozzlePath.Y2] - source[ro + PrusaNozzlePath.Y1]
                val nlen = sqrt(ndx * ndx + ndy * ndy)
                if (nlen > 1e-7f && fdx * ndx / nlen + fdy * ndy / nlen < TURN_SPLIT_DOT) break
                runEnd++
            }
            // Emit windows of up to stride moves inside this run.
            var winStart = moveIndex
            while (winStart < runEnd) {
                val winLast = min(winStart + stride, runEnd) - 1
                val wo = winStart * PrusaNozzlePath.VALUES_PER_MOVE
                val wlo = winLast * PrusaNozzlePath.VALUES_PER_MOVE
                onWindow(
                    kind, winStart, winLast,
                    source[wo + PrusaNozzlePath.X1], source[wo + PrusaNozzlePath.Y1], source[wo + PrusaNozzlePath.Z1],
                    source[wlo + PrusaNozzlePath.X2], source[wlo + PrusaNozzlePath.Y2], source[wlo + PrusaNozzlePath.Z2],
                    runWidth, runHeight,
                )
                winStart = winLast + 1
            }
            moveIndex = runEnd
        }
    }

    /** Print-speed range over all extrusion moves (for speed-color scaling). */
    private fun speedRange(value: PrusaNozzlePath): Pair<Float, Float> {
        val source = value.moves
        val n = value.moveCount
        var minSpeed = Float.POSITIVE_INFINITY
        var maxSpeed = Float.NEGATIVE_INFINITY
        for (m in 0 until n) {
            val o = m * PrusaNozzlePath.VALUES_PER_MOVE
            if (source[o + PrusaNozzlePath.KIND] == PrusaNozzlePath.Kind.EXTRUSION.code) {
                val speed = source[o + PrusaNozzlePath.SPEED]
                minSpeed = min(minSpeed, speed)
                maxSpeed = max(maxSpeed, speed)
            }
        }
        return Pair(minSpeed, maxSpeed)
    }
    /**
     * Unit LEFT normal of the sweep at polyline point [k] - the point between
     * moves k-1 and k - as the miter of the two adjacent segment tangents.
     * Both the window that ends at this point and the window that starts at it
     * query the same index, so they receive the exact same normal and their
     * corner vertices coincide: the strip is continuous even across stride
     * windows and run boundaries. Degenerate 180-degree reversals fall back to
     * the incoming tangent so the corner stays a butt joint, not a crossing.
     */
    private fun boundaryNormal(k: Int, source: FloatArray): Pair<Float, Float> {
        val n = source.size / PrusaNozzlePath.VALUES_PER_MOVE
        val inO = if (k > 0) (k - 1) * PrusaNozzlePath.VALUES_PER_MOVE else -1
        val outO = if (k < n) k * PrusaNozzlePath.VALUES_PER_MOVE else -1
        val inKind = inO >= 0 && source[inO + PrusaNozzlePath.KIND] == PrusaNozzlePath.Kind.EXTRUSION.code
        val outKind = outO >= 0 && source[outO + PrusaNozzlePath.KIND] == PrusaNozzlePath.Kind.EXTRUSION.code
        val i1x = if (inO >= 0) source[inO + PrusaNozzlePath.X2] - source[inO + PrusaNozzlePath.X1] else 0f
        val i1y = if (inO >= 0) source[inO + PrusaNozzlePath.Y2] - source[inO + PrusaNozzlePath.Y1] else 0f
        val o1x = if (outO >= 0) source[outO + PrusaNozzlePath.X2] - source[outO + PrusaNozzlePath.X1] else 0f
        val o1y = if (outO >= 0) source[outO + PrusaNozzlePath.Y2] - source[outO + PrusaNozzlePath.Y1] else 0f
        val iLen = sqrt(i1x * i1x + i1y * i1y)
        val oLen = sqrt(o1x * o1x + o1y * o1y)
        if (inKind && outKind && extrusionSeg(k - 1, k, source)) {
            // One continuous chain: miter of the two segment tangents, so the
            // closing window and the opening window share the exact same cap.
            val mx = if (iLen > 1e-7f) i1x / iLen else 0f
            val my = if (iLen > 1e-7f) i1y / iLen else 0f
            val m2 = (mx + o1x / oLen) * (mx + o1x / oLen) + (my + o1y / oLen) * (my + o1y / oLen)
            if (m2 >= 1e-3f && oLen > 1e-7f) {
                val len = sqrt(m2)
                return Pair(-(my + o1y / oLen) / len, (mx + o1x / oLen) / len)
            }
            if (iLen > 1e-7f) return Pair(-i1y / iLen, i1x / iLen)
            if (oLen > 1e-7f) return Pair(-o1y / oLen, o1x / oLen)
            return Pair(1f, 0f)
        }
        // Not one continuous chain (run boundary, travel, width step): each
        // side of the boundary caps with ITS OWN tangent so bead ends stay
        // perpendicular to the bead and are never skewed by a neighbour.
        if (inKind && iLen > 1e-7f) return Pair(-i1y / iLen, i1x / iLen)
        if (outKind && oLen > 1e-7f) return Pair(-o1y / oLen, o1x / oLen)
        return Pair(1f, 0f)
    }
    /**
     * True when moves [a] and [b] are consecutive extrusion segments of one
     * continuous chain: same kind, same width marker, and the end of [a]
     * within [CHAIN_EPS] of the start of [b] (gcode coordinate rounding).
     */
    private fun extrusionSeg(a: Int, b: Int, source: FloatArray): Boolean {
        if (a < 0 || b >= source.size / PrusaNozzlePath.VALUES_PER_MOVE) return false
        val oa = a * PrusaNozzlePath.VALUES_PER_MOVE
        val ob = b * PrusaNozzlePath.VALUES_PER_MOVE
        if (source[oa + PrusaNozzlePath.KIND] != PrusaNozzlePath.Kind.EXTRUSION.code) return false
        if (source[ob + PrusaNozzlePath.KIND] != PrusaNozzlePath.Kind.EXTRUSION.code) return false
        if (source[oa + PrusaNozzlePath.WIDTH] != source[ob + PrusaNozzlePath.WIDTH]) return false
        val dx = source[ob + PrusaNozzlePath.X1] - source[oa + PrusaNozzlePath.X2]
        val dy = source[ob + PrusaNozzlePath.Y1] - source[oa + PrusaNozzlePath.Y2]
        return dx * dx + dy * dy <= CHAIN_EPS * CHAIN_EPS
    }
    /**
     * One strip segment (window) of a continuous bead: a box from the window
     * start to its end. [startN] and [endN] are the unit LEFT normals shared
     * with the neighbouring windows (central-difference average at each run
     * boundary), so adjacent segments touch exactly - no junction slivers.
     */
    private fun addRibbonMove(
        vertex: DirectFloatSink,
        normals: DirectFloatSink,
        colors: DirectFloatSink,
        ambient: DirectFloatSink,
        sx: Float, sy: Float, sz: Float,
        ex: Float, ey: Float, ez: Float,
        width: Float,
        height: Float,
        startNx: Float, startNy: Float,
        endNx: Float, endNy: Float,
        color: FloatArray,
    ) {
        val half = width * 0.5f
        val px = startNx * half
        val py = startNy * half
        val qx = endNx * half
        val qy = endNy * half
        // Continuous strip: the end quad of the previous window uses the SAME
        // boundary normals, so corners coincide at shared boundaries.
        // Quad corners: a/b at start (offset by START normals), c/d at end
        // (offset by END normals). Shared boundary => same corner coordinates
        // in both the closing and opening window.
        val ax = sx - px; val ay = sy - py
        val bx = sx + px; val by = sy + py
        val cx = ex + qx; val cy = ey + qy
        val dxd = ex - qx; val dyd = ey - qy
        fun push(vertexCount: Int) {
            repeat(vertexCount) {
                colors += color[0]
                colors += color[1]
                colors += color[2]
                colors += color[3]
            }
        }
        // Top face.
        vertex += ax; vertex += ay; vertex += sz + height
        vertex += bx; vertex += by; vertex += sz + height
        vertex += cx; vertex += cy; vertex += ez + height
        vertex += ax; vertex += ay; vertex += sz + height
        vertex += cx; vertex += cy; vertex += ez + height
        vertex += dxd; vertex += dyd; vertex += ez + height
        repeat(6) { normals += 0f; normals += 0f; normals += 1f }
        push(6)
        repeat(6) { ambient += PrusaNozzlePathViewDefaults.TOP_AMBIENT }
        // Left side face: per-vertex (Gouraud) normals - the start vertices
        // carry the START boundary normal and the end vertices the END one,
        // so shading interpolates continuously along the wall instead of
        // jumping per window (flat-faced banding looked like warped walls).
        vertex += ax; vertex += ay; vertex += sz
        vertex += dxd; vertex += dyd; vertex += ez
        vertex += dxd; vertex += dyd; vertex += ez + height
        normals += -startNx; normals += -startNy; normals += 0f
        normals += -endNx; normals += -endNy; normals += 0f
        normals += -endNx; normals += -endNy; normals += 0f
        push(3)
        ambient += PrusaNozzlePathViewDefaults.SIDE_BASE_AMBIENT
        ambient += PrusaNozzlePathViewDefaults.SIDE_BASE_AMBIENT
        ambient += PrusaNozzlePathViewDefaults.SIDE_TOP_AMBIENT
        vertex += ax; vertex += ay; vertex += sz
        vertex += dxd; vertex += dyd; vertex += ez + height
        vertex += ax; vertex += ay; vertex += sz + height
        normals += -startNx; normals += -startNy; normals += 0f
        normals += -endNx; normals += -endNy; normals += 0f
        normals += -startNx; normals += -startNy; normals += 0f
        push(3)
        ambient += PrusaNozzlePathViewDefaults.SIDE_BASE_AMBIENT
        ambient += PrusaNozzlePathViewDefaults.SIDE_TOP_AMBIENT
        ambient += PrusaNozzlePathViewDefaults.SIDE_TOP_AMBIENT
        // Right side face: same Gouraud normals on the + side.
        vertex += bx; vertex += by; vertex += sz
        vertex += cx; vertex += cy; vertex += ez
        vertex += cx; vertex += cy; vertex += ez + height
        normals += startNx; normals += startNy; normals += 0f
        normals += endNx; normals += endNy; normals += 0f
        normals += endNx; normals += endNy; normals += 0f
        push(3)
        ambient += PrusaNozzlePathViewDefaults.SIDE_BASE_AMBIENT
        ambient += PrusaNozzlePathViewDefaults.SIDE_BASE_AMBIENT
        ambient += PrusaNozzlePathViewDefaults.SIDE_TOP_AMBIENT
        vertex += bx; vertex += by; vertex += sz
        vertex += cx; vertex += cy; vertex += ez + height
        vertex += bx; vertex += by; vertex += sz + height
        normals += startNx; normals += startNy; normals += 0f
        normals += endNx; normals += endNy; normals += 0f
        normals += startNx; normals += startNy; normals += 0f
        push(3)
        ambient += PrusaNozzlePathViewDefaults.SIDE_BASE_AMBIENT
        ambient += PrusaNozzlePathViewDefaults.SIDE_TOP_AMBIENT
        ambient += PrusaNozzlePathViewDefaults.SIDE_TOP_AMBIENT
    }
    private fun buildGrid(value: PrusaNozzlePath) {
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
        val offset = selectedMoveIndex.coerceIn(0, current.moveCount - 1) * PrusaNozzlePath.VALUES_PER_MOVE
        val x = current.moves[offset + PrusaNozzlePath.X2]
        val y = current.moves[offset + PrusaNozzlePath.Y2]
        val z = current.moves[offset + PrusaNozzlePath.Z2]
        val size = max(sceneRadius(current) * 0.022f, 0.9f)
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

    fun pickNearestMove(screenX: Float, screenY: Float): Int {
        try {
            return pickNearestMoveUnsafe(screenX, screenY)
        } catch (error: Throwable) {
            Log.e("PrusaNozzlePathView", "pick failed", error)
            return -1
        }
    }

    private fun pickNearestMoveUnsafe(screenX: Float, screenY: Float): Int {
        val current = path ?: return -1
        if (current.moveCount <= 0) return -1
        val aspect = viewportWidth.toFloat() / max(viewportHeight, 1)
        computeCamera(aspect)
        val source = current.moves
        var bestDistanceSq = Float.POSITIVE_INFINITY
        var bestIndex = -1
        for (moveIndex in 0 until current.moveCount) {
            val offset = moveIndex * PrusaNozzlePath.VALUES_PER_MOVE
            project(source, offset + PrusaNozzlePath.X1, offset + PrusaNozzlePath.Y1, offset + PrusaNozzlePath.Z1)
            val ax = pickOut[0] / pickOut[3]
            val ay = pickOut[1] / pickOut[3]
            project(source, offset + PrusaNozzlePath.X2, offset + PrusaNozzlePath.Y2, offset + PrusaNozzlePath.Z2)
            val bx = pickOut[0] / pickOut[3]
            val by = pickOut[1] / pickOut[3]
            val sx1 = (ax + 1f) * 0.5f * viewportWidth
            val sy1 = (1f - ay) * 0.5f * viewportHeight
            val sx2 = (bx + 1f) * 0.5f * viewportWidth
            val sy2 = (1f - by) * 0.5f * viewportHeight
            val distanceSq = segmentDistanceSq(screenX, screenY, sx1, sy1, sx2, sy2)
            if (distanceSq < bestDistanceSq) {
                val behind = pickOut[3] <= 0f
                if (!behind) {
                    bestDistanceSq = distanceSq
                    bestIndex = moveIndex
                }
            }
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
    private fun ensureUploads(current: PrusaNozzlePath) {
        if (uploadedPath === current && uploadedColorBySpeed == colorBySpeed && pathVbos.size == 6) return
        if (pathVbos.size != 6) {
            val ids = IntArray(6)
            GLES20.glGenBuffers(6, ids, 0)
            pathVbos = ids
        }
        val buffers = listOf(ribbonPositions, ribbonNormals, ribbonColors, ribbonAmbient, travelPositions, travelColors)
        for (index in buffers.indices) {
            val data = buffers[index] ?: continue
            data.position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, pathVbos[index])
            GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER,
                data.remaining() * Float.SIZE_BYTES,
                data,
                GLES20.GL_STATIC_DRAW,
            )
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        uploadedPath = current
        uploadedColorBySpeed = colorBySpeed
    }

    private fun drawLitTrianglesVbo(vertexCount: Int) {
        if (vertexCount <= 0) return
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
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, pathVbos[0])
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 12, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, pathVbos[1])
        GLES20.glEnableVertexAttribArray(normal)
        GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 12, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, pathVbos[2])
        GLES20.glEnableVertexAttribArray(color)
        GLES20.glVertexAttribPointer(color, 4, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, pathVbos[3])
        GLES20.glEnableVertexAttribArray(ambientLoc)
        GLES20.glVertexAttribPointer(ambientLoc, 1, GLES20.GL_FLOAT, false, 4, 0)
        GLES20.glUniformMatrix4fv(matrix, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(sceneMatrix, 1, false, scene, 0)
        GLES20.glUniform3f(keyDir, KEY_LIGHT[0], KEY_LIGHT[1], KEY_LIGHT[2])
        GLES20.glUniform3f(fillDir, FILL_LIGHT[0], FILL_LIGHT[1], FILL_LIGHT[2])
        GLES20.glUniform3f(viewDir, 0f, -VIEW_EYE_Y, VIEW_EYE_Z)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(normal)
        GLES20.glDisableVertexAttribArray(color)
        GLES20.glDisableVertexAttribArray(ambientLoc)
    }

    private fun drawColoredLinesVbo(vertexCount: Int, width: Float) {
        if (vertexCount <= 0) return
        GLES20.glUseProgram(colorProgram)
        val position = GLES20.glGetAttribLocation(colorProgram, "aPosition")
        val color = GLES20.glGetAttribLocation(colorProgram, "aColor")
        val matrix = GLES20.glGetUniformLocation(colorProgram, "uMvpMatrix")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, pathVbos[4])
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 12, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, pathVbos[5])
        GLES20.glEnableVertexAttribArray(color)
        GLES20.glVertexAttribPointer(color, 4, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glUniformMatrix4fv(matrix, 1, false, mvp, 0)
        lineWidth(width)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, vertexCount)
        GLES20.glLineWidth(1f)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(color)
    }
    private fun drawLitTriangles(vertexCount: Int) {
        val positions = ribbonPositions ?: return
        val normals = ribbonNormals ?: return
        val colors = ribbonColors ?: return
        val ambient = ribbonAmbient ?: return
        if (vertexCount <= 0) return
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
        positions.position(0); normals.position(0); colors.position(0); ambient.position(0)
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

    private fun drawColoredLines(vertexCount: Int, width: Float) {
        val positions = travelPositions ?: return
        val colors = travelColors ?: return
        if (vertexCount <= 0) return
        GLES20.glUseProgram(colorProgram)
        val position = GLES20.glGetAttribLocation(colorProgram, "aPosition")
        val color = GLES20.glGetAttribLocation(colorProgram, "aColor")
        val matrix = GLES20.glGetUniformLocation(colorProgram, "uMvpMatrix")
        positions.position(0); colors.position(0)
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
    private fun computeCamera(aspect: Float): Float {
        val distance = cameraDistance()
        val radius = sceneRadius(requireNotNull(path))
        val nearPlane = max(0.05f, distance - radius * 1.6f)
        val farPlane = max(nearPlane + 100f, distance + radius * 2.8f + 100f)
        if (orthographic) {
            val halfHeight = distance * tan(Math.toRadians((PrusaNozzlePathViewDefaults.FIELD_OF_VIEW / 2.0f).toDouble())).toFloat()
            val halfWidth = halfHeight * aspect
            Matrix.orthoM(projection, 0, -halfWidth, halfWidth, -halfHeight, halfHeight, nearPlane, farPlane)
        } else {
            Matrix.perspectiveM(projection, 0, PrusaNozzlePathViewDefaults.FIELD_OF_VIEW, aspect, nearPlane, farPlane)
        }
        Matrix.setLookAtM(view, 0, 0f, -distance, distance * 0.62f, 0f, 0f, 0f, 0f, 0f, 1f)
        Matrix.translateM(view, 0, panX, panY, 0f)
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

    private fun cameraDistance(): Float {
        val current = path ?: return 300f
        return max(sceneRadius(current) * 3.4f / zoom, 2f)
    }

    private fun sceneRadius(value: PrusaNozzlePath): Float {
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

    private fun queryMaxLineWidth(): Float {
        val range = FloatArray(2)
        GLES20.glGetFloatv(GLES20.GL_ALIASED_LINE_WIDTH_RANGE, range, 0)
        return range[1].takeIf { it.isFinite() && it > 0f } ?: 1f
    }

    private fun lineWidth(width: Float) {
        GLES20.glLineWidth(width.coerceAtMost(maxLineWidth))
    }

    private companion object {
        const val WINDOW_VERTICES = 18
        const val TURN_SPLIT_DOT = 0.65f
        const val CHAIN_EPS = 0.05f
        const val RIBBON_SATURATION = 0.62f
        const val RIBBON_VALUE = 0.92f
        const val VIEW_EYE_Y = -0.85f
        const val VIEW_EYE_Z = 0.53f
        private val KEY_LIGHT = normalize3(0.52f, -0.58f, 0.63f)
        private val FILL_LIGHT = normalize3(-0.62f, 0.30f, 0.55f)
        private const val TAG = "PrusaNozzlePathView"
        private val LIT_VERTEX_SHADER = """
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            attribute vec4 aColor;
            attribute float aAmbient;
            uniform mat4 uMvpMatrix;
            uniform mat4 uSceneMatrix;
            uniform vec3 uKeyDir;
            uniform vec3 uFillDir;
            uniform vec3 uViewDir;
            varying vec4 vColor;
            varying vec3 vNormal;
            varying vec3 vWorldPos;
            varying float vAmbient;
            void main() {
                gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
                vNormal = (uSceneMatrix * vec4(aNormal, 0.0)).xyz;
                vec3 world = (uSceneMatrix * vec4(aPosition, 1.0)).xyz;
                vWorldPos = world;
                vColor = aColor;
                vAmbient = aAmbient;
            }
        """
        private val LIT_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec4 vColor;
            varying vec3 vNormal;
            varying vec3 vWorldPos;
            varying float vAmbient;
            uniform vec3 uKeyDir;
            uniform vec3 uFillDir;
            uniform vec3 uViewDir;
            void main() {
                vec3 n = normalize(vNormal);
                vec3 view = normalize(uViewDir);
                vec3 key = normalize(uKeyDir);
                vec3 fill = normalize(uFillDir);
                vec3 halfDir = normalize(key + view);
                float keyDiff = max(dot(n, key), 0.0);
                float fillDiff = max(dot(n, fill), 0.0);
                float rim = pow(max(1.0 - max(dot(n, view), 0.0), 0.0), 3.0);
                float spec = pow(max(dot(n, halfDir), 0.0), 32.0) * 0.25;
                float light = vAmbient * (0.30 + 0.55 * keyDiff + 0.22 * fillDiff);
                vec3 color = vColor.rgb * light + vec3(rim * 0.10);
                gl_FragColor = vec4(color, vColor.a);
            }
        """
        private val COLOR_VERTEX_SHADER = """
            attribute vec3 aPosition;
            attribute vec4 aColor;
            uniform mat4 uMvpMatrix;
            varying vec4 vColor;
            void main() {
                gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
                vColor = aColor;
            }
        """
        private val COLOR_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec4 vColor;
            void main() { gl_FragColor = vColor; }
        """
        private val SOLID_VERTEX_SHADER = """
            attribute vec3 aPosition;
            uniform mat4 uMvpMatrix;
            void main() { gl_Position = uMvpMatrix * vec4(aPosition, 1.0); }
        """
        private val SOLID_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }
        """
        private fun normalize3(x: Float, y: Float, z: Float): FloatArray {
            val len = sqrt(x * x + y * y + z * z)
            return floatArrayOf(x / len, y / len, z / len)
        }
    }
}

/** Constants shared by the Prusa nozzle-path surface and its renderer. */
internal object PrusaNozzlePathViewDefaults {
    const val DEFAULT_YAW = -32f
    const val DEFAULT_PITCH = 58f
    const val DEFAULT_ZOOM = 1f
    const val MIN_ZOOM = 0.25f
    const val MAX_ZOOM = 60f
    const val FIELD_OF_VIEW = 42f
    const val CAMERA_EYE_DISTANCE_SCALE = 1.1527f
    const val TRAVEL_WIDTH = 1.5f
    const val TOP_AMBIENT = 0.98f
    const val SIDE_TOP_AMBIENT = 0.94f
    const val SIDE_BASE_AMBIENT = 0.86f
    const val WINDOW_VERTICES = 18
    const val TURN_SPLIT_DOT = 0.65f
    const val RIBBON_SATURATION = 0.62f
    const val RIBBON_VALUE = 0.92f
    const val VIEW_EYE_Y = -0.85f
    const val VIEW_EYE_Z = 0.53f
}