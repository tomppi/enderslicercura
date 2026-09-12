package com.tomppi.enderslicer.viewer

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.tomppi.enderslicer.annotation.AnnotationGesture
import com.tomppi.enderslicer.annotation.Point3
import com.tomppi.enderslicer.annotation.SegmentEnd
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.supportpaint.SupportPaintMode
import com.tomppi.enderslicer.supportpaint.SupportPaintState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

class ModelSurfaceView(
    context: Context,
    private val printer: PrinterDefinition,
) : GLSurfaceView(context) {
    private val modelRenderer = ModelRenderer(printer)
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())
    private var previousX = 0f
    private var previousY = 0f
    private var previousFocusX = 0f
    private var previousFocusY = 0f
    private var panning = false
    private val paintPickExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val pendingPaintCoordinates = java.util.concurrent.atomic.AtomicReference<FloatArray?>(null)
    private val paintPickLock = Any()
    private var paintPickScheduled = false

    /** When not [SupportPaintMode.NONE], a single-finger drag paints instead of rotating. */
    var paintMode: SupportPaintMode = SupportPaintMode.NONE
        set(value) {
            if (field == value) return
            field = value
            queueEvent { modelRenderer.setPaintActive(value != SupportPaintMode.NONE) }
            requestRender()
        }

    /** Invoked on the UI thread with the model triangle hit by a paint stroke. */
    var onPaintHit: ((MeshPicker.Hit) -> Unit)? = null

    /**
     * When true, taps place annotation points and drags may move a handle.
     *
     * A drag that does not begin on a handle still orbits the camera - that is
     * the whole point of the mode. Consuming every drag for placing is what made
     * the model impossible to turn while annotating.
     */
    var annotationActive: Boolean = false

    /** Invoked on the UI thread when a tap places an annotation point. */
    var onAnnotationTap: ((AnnotationGesture) -> Unit)? = null

    /** Invoked on the UI thread while a handle drag moves, to slide that handle. */
    var onAnnotationAdjust: ((SegmentEnd, AnnotationGesture) -> Unit)? = null

    /** Invoked once when holding a handle starts a height-only adjustment. */
    var onAnnotationZAdjustStart: ((SegmentEnd) -> Unit)? = null

    /** Invoked on the UI thread while a height adjustment drags. */
    var onAnnotationZAdjust: ((AnnotationGesture) -> Unit)? = null

    /** Invoked when a height adjustment ends. */
    var onAnnotationZAdjustEnd: (() -> Unit)? = null

    private val pendingAnnotationCoordinates =
        java.util.concurrent.atomic.AtomicReference<FloatArray?>(null)
    private var annotationScheduled = false

    private val pendingAnnotationProbe =
        java.util.concurrent.atomic.AtomicReference<FloatArray?>(null)
    private var annotationProbeScheduled = false

    /** True between ACTION_DOWN and the handle probe answering. */
    private var annotationDeciding = false
    private var annotationGrabbed: SegmentEnd? = null
    private var annotationDownX = 0f
    private var annotationDownY = 0f
    private var annotationMoved = false
    private var annotationAccumDx = 0f
    private var annotationAccumDy = 0f

    /** A tap whose UP arrived before the handle probe answered. */
    private var annotationPendingTap: FloatArray? = null

    /** True once a hold on a handle has switched to height-only adjustment. */
    private var annotationZMode = false

    /**
     * Switches a held handle to height adjustment.
     *
     * Deliberately a hold rather than a second control: height is changed
     * rarely and per point, so it does not deserve permanent screen space on a
     * phone. Cancelled by any movement, so it never fights an ordinary drag.
     */
    private val annotationHold = Runnable {
        val end = annotationGrabbed ?: return@Runnable
        if (annotationMoved) return@Runnable
        annotationZMode = true
        onAnnotationZAdjustStart?.invoke(end)
    }

    fun setAnnotationOverlay(overlay: AnnotationOverlay?) {
        queueEvent { modelRenderer.setAnnotationOverlay(overlay) }
        requestRender()
    }

    /** Invoked on the main thread whenever the turntable yaw/pitch changes. */
    var onOrientationChanged: ((ViewerOrientation) -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(modelRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        isClickable = true
    }

    fun setMesh(mesh: StlMesh?) {
        queueEvent { modelRenderer.setMesh(mesh) }
        // setMesh resets the camera on the GL thread for a new model.
        queueEvent { notifyOrientation() }
        requestRender()
    }

    fun currentOrientation(): ViewerOrientation = modelRenderer.orientation

    /**
     * Restores the turntable yaw/pitch after the surface view is recreated
     * (for example when the app moves away from the Plate tab and back).
     * Zoom and pan keep their defaults; only the orbit is restored.
     */
    fun restoreOrientation(orientation: ViewerOrientation) {
        queueEvent { modelRenderer.setOrientation(orientation) }
        requestRender()
    }

    fun setPaintState(paint: SupportPaintState) {
        queueEvent { modelRenderer.setPaintState(paint) }
        requestRender()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val painting = paintMode != SupportPaintMode.NONE
        val annotating = annotationActive && !painting

        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previousX = event.x
                previousY = event.y
                panning = false
                if (painting) {
                    pendingPaintCoordinates.set(floatArrayOf(event.x, event.y))
                    schedulePaintPick()
                } else if (annotating) {
                    // Whether this becomes a handle drag or an orbit is decided
                    // by a hit test that runs off the UI thread, so hold the
                    // touch until it answers rather than guessing.
                    annotationDownX = event.x
                    annotationDownY = event.y
                    annotationMoved = false
                    annotationGrabbed = null
                    annotationDeciding = true
                    annotationAccumDx = 0f
                    annotationAccumDy = 0f
                    pendingAnnotationProbe.set(floatArrayOf(event.x, event.y))
                    scheduleAnnotationProbe()
                }
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
                        modelRenderer.panPixels(
                            deltaX = focusX - previousFocusX,
                            deltaY = focusY - previousFocusY,
                        )
                    }
                    previousFocusX = focusX
                    previousFocusY = focusY
                    panning = true
                    requestRender()
                } else if (!scaleDetector.isInProgress) {
                    if (painting) {
                        pendingPaintCoordinates.set(floatArrayOf(event.x, event.y))
                        schedulePaintPick()
                    } else if (annotating && annotationDeciding) {
                        // Accumulate until the probe answers; the movement is
                        // applied as an orbit if it turns out not to be a handle.
                        annotationAccumDx += event.x - previousX
                        annotationAccumDy += event.y - previousY
                        previousX = event.x
                        previousY = event.y
                    } else if (annotating && annotationGrabbed != null) {
                        if (!annotationZMode) {
                            // Movement means this is a placement drag, not a hold.
                            removeCallbacks(annotationHold)
                            annotationMoved = true
                        }
                        previousX = event.x
                        previousY = event.y
                        pendingAnnotationCoordinates.set(floatArrayOf(event.x, event.y))
                        scheduleAnnotationGesture()
                    } else {
                        val dx = event.x - previousX
                        val dy = event.y - previousY
                        modelRenderer.rotate(dx * 0.35f, dy * 0.35f)
                        previousX = event.x
                        previousY = event.y
                        notifyOrientation()
                        requestRender()
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                panning = false
                if (event.pointerCount - 1 == 1) {
                    val remainingIndex = if (event.actionIndex == 0) 1 else 0
                    previousX = event.getX(remainingIndex)
                    previousY = event.getY(remainingIndex)
                }
            }

            MotionEvent.ACTION_UP -> {
                performClick()
                panning = false
                if (annotationActive && !painting && !annotationMoved && annotationGrabbed == null) {
                    val coordinates = floatArrayOf(event.x, event.y)
                    if (annotationDeciding) {
                        // The probe has not answered yet; hold the tap so a touch
                        // that turns out to be on a handle does not also place a point.
                        annotationPendingTap = coordinates
                    } else {
                        pendingAnnotationCoordinates.set(coordinates)
                        scheduleAnnotationGesture()
                    }
                }
                removeCallbacks(annotationHold)
                if (annotationZMode) {
                    annotationZMode = false
                    onAnnotationZAdjustEnd?.invoke()
                }
                annotationDeciding = false
                annotationGrabbed = null
            }

            MotionEvent.ACTION_CANCEL -> panning = false
        }
        return true
    }

    private fun notifyOrientation() {
        val listener = onOrientationChanged ?: return
        post { listener(modelRenderer.orientation) }
    }

    private fun schedulePaintPick() {
        synchronized(paintPickLock) {
            if (paintPickScheduled) return
            paintPickScheduled = true
        }
        paintPickExecutor.execute {
            try {
                while (true) {
                    val coordinates = pendingPaintCoordinates.getAndSet(null) ?: break
                    val hit = modelRenderer.pickTriangle(coordinates[0], coordinates[1]) ?: continue
                    post { onPaintHit?.invoke(hit) }
                }
            } finally {
                synchronized(paintPickLock) { paintPickScheduled = false }
                if (pendingPaintCoordinates.get() != null) schedulePaintPick()
            }
        }
    }

    /**
     * Resolves pending annotation gestures off the UI thread.
     *
     * Same shape as [schedulePaintPick]: at most one run is in flight, and
     * coordinates that arrive during a run are picked up by the drain loop
     * rather than queued as separate tasks.
     */
    private fun scheduleAnnotationGesture() {
        synchronized(paintPickLock) {
            if (annotationScheduled) return
            annotationScheduled = true
        }
        paintPickExecutor.execute {
            try {
                while (true) {
                    val coordinates = pendingAnnotationCoordinates.getAndSet(null) ?: break
                    val gesture = modelRenderer.annotationGestureAt(coordinates[0], coordinates[1])
                        ?: continue
                    // Read the grab state on the UI thread, at delivery: the same
                    // queue carries taps and handle drags, and which one this is
                    // depends on whether a handle is held when it lands.
                    post {
                        val end = annotationGrabbed
                        when {
                            end == null -> onAnnotationTap?.invoke(gesture)
                            annotationZMode -> onAnnotationZAdjust?.invoke(gesture)
                            else -> onAnnotationAdjust?.invoke(end, gesture)
                        }
                    }
                }
            } finally {
                synchronized(paintPickLock) { annotationScheduled = false }
                if (pendingAnnotationCoordinates.get() != null) scheduleAnnotationGesture()
            }
        }
    }

    /**
     * Resolves whether a touch landed on an annotation handle.
     *
     * Same shape as [scheduleAnnotationGesture]: one run in flight, drained
     * rather than queued. The answer decides whether the touch becomes a handle
     * drag or an orbit, so movement is held until it arrives.
     */
    private fun scheduleAnnotationProbe() {
        synchronized(paintPickLock) {
            if (annotationProbeScheduled) return
            annotationProbeScheduled = true
        }
        paintPickExecutor.execute {
            try {
                while (true) {
                    val coordinates = pendingAnnotationProbe.getAndSet(null) ?: break
                    val handle = modelRenderer.annotationHandleAt(
                        coordinates[0],
                        coordinates[1],
                        HANDLE_TOUCH_RADIUS_PX,
                    )
                    post { applyAnnotationProbe(handle) }
                }
            } finally {
                synchronized(paintPickLock) { annotationProbeScheduled = false }
                if (pendingAnnotationProbe.get() != null) scheduleAnnotationProbe()
            }
        }
    }

    private fun applyAnnotationProbe(handle: SegmentEnd?) {
        annotationDeciding = false
        annotationGrabbed = handle
        if (handle != null) {
            // A handle is held, so the held movement is not an orbit. The first
            // MOVE delivers the ray that slides it, unless the finger stays put
            // long enough to mean height instead.
            annotationAccumDx = 0f
            annotationAccumDy = 0f
            postDelayed(annotationHold, HANDLE_LONG_PRESS_MILLIS)
            return
        }
        // Not a handle, so the movement the user already made becomes an orbit.
        if (annotationAccumDx != 0f || annotationAccumDy != 0f) {
            modelRenderer.rotate(annotationAccumDx * 0.35f, annotationAccumDy * 0.35f)
            annotationAccumDx = 0f
            annotationAccumDy = 0f
            notifyOrientation()
            requestRender()
        }
        // A tap that completed before this answer is still a tap.
        annotationPendingTap?.let { coordinates ->
            annotationPendingTap = null
            pendingAnnotationCoordinates.set(coordinates)
            scheduleAnnotationGesture()
        }
    }

    override fun onDetachedFromWindow() {
        paintPickExecutor.shutdown()
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun pointerFocusX(event: MotionEvent): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) total += event.getX(index)
        return total / event.pointerCount
    }

    private fun pointerFocusY(event: MotionEvent): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) total += event.getY(index)
        return total / event.pointerCount
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            modelRenderer.zoom(detector.scaleFactor)
            requestRender()
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onDoubleTap(event: MotionEvent): Boolean {
            modelRenderer.resetCamera()
            notifyOrientation()
            requestRender()
            return true
        }
    }

    private companion object {
        /**
         * Touch slop for grabbing an end handle, in pixels.
         *
         * Comfortably larger than the drawn marker because a finger is wider
         * than a cross, and small enough that it cannot swallow a tap meant for
         * the model behind it.
         */
        const val HANDLE_TOUCH_RADIUS_PX = 44f

        /**
         * How long a handle must be held to switch to height adjustment.
         *
         * Long because it is a mode change rather than a tap, and it must not
         * fire while the user is simply resting a finger before dragging.
         */
        const val HANDLE_LONG_PRESS_MILLIS = 2_000L
    }
}

private class ModelRenderer(
    private val printer: PrinterDefinition,
) : GLSurfaceView.Renderer {
    @Volatile private var mesh: StlMesh? = null
    private var meshBuffer: FloatBuffer? = null
    private var paintColors: PaintColorBuffer? = null
    private var annotationOverlay: AnnotationOverlay? = null
    private var annotationBuffer: FloatBuffer? = null
    // GPU-side (VBO) copies of the mesh; the model renders from VRAM after a
    // single upload, like a game, instead of re-reading CPU memory per frame.
    private var meshVbo = 0
    private var colorVbo = 0
    private var uploadedMesh: StlMesh? = null
    private var colorUploaded = false
    private var paintState: SupportPaintState = SupportPaintState()
    private var paintActive = false
    private var meshProgram = 0
    private var lineProgram = 0

    /**
     * Widest line this driver will actually draw.
     *
     * GLES only guarantees 1px, so a requested annotation thickness has to be
     * clamped or it silently draws at 1px on some devices and not others.
     */
    private var maxLineWidth = 1f
    private var gridBuffer: FloatBuffer? = null
    private var gridVertexCount = 0
    private var viewportWidth = 1
    private var viewportHeight = 1
    @Volatile private var yaw = DEFAULT_YAW
    @Volatile private var pitch = DEFAULT_PITCH
    @Volatile private var zoom = DEFAULT_ZOOM
    @Volatile private var panX = 0f
    @Volatile private var panY = 0f

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val scene = FloatArray(16)
    private val modelLocal = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val modelView = FloatArray(16)
    private val mvp = FloatArray(16)

    fun setMesh(value: StlMesh?) {
        if (mesh === value) return
        val isNewModel = value?.displayName != mesh?.displayName
        mesh = value
        meshBuffer = value?.let { mesh ->
            mesh.interleavedVertices.directOrNull()
                ?: mesh.interleavedVertices.arrayOrNull()?.let(::floatBuffer)
        }
        uploadedMesh = null
        colorUploaded = false
        paintColors = null
        rebuildColorBuffer()
        if (isNewModel) resetCamera()
    }

    fun setPaintState(value: SupportPaintState) {
        if (paintState == value) return
        paintState = value
        rebuildColorBuffer()
    }

    /**
     * Applies a single paint edit, rewriting only the triangles in [changed].
     *
     * A stroke expands to a bounded set of triangles, so this keeps the update
     * O(brush) instead of rebuilding a colour buffer sized by the whole mesh.
     * Falls back to a full rebuild when the buffer is missing or the mesh
     * changed underneath it.
     */
    fun applyPaintEdit(value: SupportPaintState, changed: Set<Int>) {
        if (paintState == value) return
        paintState = value
        val buffer = paintColors
        if (buffer == null || buffer.triangleCount != mesh?.triangleCount) {
            rebuildColorBuffer()
            return
        }
        buffer.apply(value, changed)
    }

    fun setPaintActive(value: Boolean) {
        if (paintActive == value) return
        paintActive = value
        rebuildColorBuffer()
    }

    private fun cameraSnapshot(currentMesh: StlMesh) = MeshPicker.CameraSnapshot(
        viewportWidth = viewportWidth.toFloat(),
        viewportHeight = viewportHeight.toFloat(),
        yaw = yaw,
        pitch = pitch,
        zoom = zoom,
        panX = panX,
        panY = panY,
        meshBounds = currentMesh.bounds,
    )

    fun pickTriangle(screenX: Float, screenY: Float): MeshPicker.Hit? {
        val currentMesh = mesh ?: return null
        return MeshPicker.pick(
            mesh = currentMesh,
            printer = printer,
            camera = cameraSnapshot(currentMesh),
            screenX = screenX,
            screenY = screenY,
        )
    }

    /**
     * Resolves a screen position into an annotation placement.
     *
     * A hit gives an exact surface point and its triangle. A miss is not
     * discarded: the ray is intersected with the plane through the model's
     * centre that faces the camera, so a gesture in empty space still produces
     * a real 3D position at a predictable depth. Either way the ray travels
     * with the result, because a later depth-preserving move needs it.
     */
    fun annotationGestureAt(screenX: Float, screenY: Float): AnnotationGesture? {
        val currentMesh = mesh ?: return null
        val camera = cameraSnapshot(currentMesh)
        val ray = MeshPicker.ray(printer, camera, screenX, screenY) ?: return null
        val hit = MeshPicker.pick(currentMesh, printer, camera, screenX, screenY)
        val bounds = currentMesh.bounds
        val position = if (hit != null) {
            Point3(hit.x, hit.y, hit.z)
        } else {
            val t = (bounds.centerX - ray.originX) * ray.dirX +
                (bounds.centerY - ray.originY) * ray.dirY +
                (bounds.centerZ - ray.originZ) * ray.dirZ
            Point3(
                ray.originX + ray.dirX * t,
                ray.originY + ray.dirY * t,
                ray.originZ + ray.dirZ * t,
            )
        }
        return AnnotationGesture(
            position = position,
            faceIndex = hit?.triangleIndex,
            rayOrigin = Point3(ray.originX, ray.originY, ray.originZ),
            rayDirection = Point3(ray.dirX, ray.dirY, ray.dirZ),
            screenX = screenX,
            screenY = screenY,
        )
    }

    val orientation: ViewerOrientation
        get() = ViewerOrientation(yaw, pitch)

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
        val distance = cameraDistance()
        val eyeDistance = distance * CAMERA_EYE_DISTANCE_SCALE
        val visibleHeight = 2f * eyeDistance * tan(Math.toRadians(FIELD_OF_VIEW_DEGREES / 2.0)).toFloat()
        val worldPerPixel = visibleHeight / max(viewportHeight, 1).toFloat()
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

    fun setOrientation(orientation: ViewerOrientation) {
        yaw = wrapDegrees(orientation.yawDegrees)
        pitch = wrapDegrees(orientation.pitchDegrees)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.055f, 0.065f, 0.08f, 1f)
        GLES20.glClearDepthf(1f)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        GLES20.glDepthFunc(GLES20.GL_LESS)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        meshProgram = createProgram(MESH_VERTEX_SHADER, MESH_FRAGMENT_SHADER)
        lineProgram = createProgram(LINE_VERTEX_SHADER, LINE_FRAGMENT_SHADER)
        val widthRange = FloatArray(2)
        GLES20.glGetFloatv(GLES20.GL_ALIASED_LINE_WIDTH_RANGE, widthRange, 0)
        maxLineWidth = widthRange[1].takeIf { it.isFinite() && it > 0f } ?: 1f
        // A new GL context invalidates old VBO ids.
        meshVbo = 0
        colorVbo = 0
        uploadedMesh = null
        colorUploaded = false
        buildGrid()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = max(width, 1)
        viewportHeight = max(height, 1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val fit = sceneFit(aspect)
        val distance = fit.distance

        Matrix.perspectiveM(projection, 0, FIELD_OF_VIEW_DEGREES, aspect, fit.nearPlane, fit.farPlane)
        Matrix.setLookAtM(view, 0, 0f, -distance, distance * 0.62f, 0f, 0f, 0f, 0f, 0f, 1f)
        Matrix.translateM(view, 0, panX, panY, 0f)

        Matrix.setIdentityM(scene, 0)
        Matrix.rotateM(scene, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(scene, 0, yaw, 0f, 0f, 1f)
        Matrix.translateM(scene, 0, -fit.centerX, -fit.centerY, -fit.centerZ)

        drawGrid()
        drawMesh()
        drawAnnotation()
    }

    /**
     * Draws the annotation overlay without depth testing.
     *
     * Annotation is an overlay on the model, not part of it: a point the user
     * placed on the far side of the mesh must still be visible, otherwise
     * orbiting to judge its depth would make it disappear.
     */
    private fun drawAnnotation() {
        val overlay = annotationOverlay ?: return
        val buffer = annotationBuffer ?: return
        if (overlay.isEmpty) return

        Matrix.multiplyMM(modelView, 0, view, 0, scene, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)

        GLES20.glUseProgram(lineProgram)
        val position = GLES20.glGetAttribLocation(lineProgram, "aPosition")
        val matrix = GLES20.glGetUniformLocation(lineProgram, "uMvpMatrix")
        val color = GLES20.glGetUniformLocation(lineProgram, "uColor")
        GLES20.glUniformMatrix4fv(matrix, 1, false, mvp, 0)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 3 * 4, buffer)
        GLES20.glLineWidth(overlay.thicknessPx.coerceIn(1f, maxLineWidth))
        if (overlay.lineVertexCount > 0) {
            GLES20.glUniform4f(color, ANNOTATION_COLOR[0], ANNOTATION_COLOR[1], ANNOTATION_COLOR[2], 1f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, overlay.lineVertexCount)
        }
        if (overlay.markerVertexCount > 0) {
            GLES20.glUniform4f(color, ANNOTATION_MARKER_COLOR[0], ANNOTATION_MARKER_COLOR[1], ANNOTATION_MARKER_COLOR[2], 1f)
            GLES20.glDrawArrays(GLES20.GL_LINES, overlay.lineVertexCount, overlay.markerVertexCount)
        }
        GLES20.glLineWidth(1f)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    /**
     * Which annotation handle a screen point is on, if any.
     *
     * Runs on the GL thread where the camera is consistent. Handles are drawn at
     * a fixed world size, so their touch target is a fixed pixel radius rather
     * than a projected size - a marker far from the camera is small on screen
     * but must still be grabbable.
     */
    fun annotationHandleAt(screenX: Float, screenY: Float, radiusPx: Float): SegmentEnd? {
        val currentMesh = mesh ?: return null
        val overlay = annotationOverlay ?: return null
        if (overlay.handles.isEmpty()) return null
        val camera = cameraSnapshot(currentMesh)
        var best: SegmentEnd? = null
        var bestDistance = radiusPx
        for ((end, position) in overlay.handles) {
            val screen = MeshPicker.project(printer, camera, position.x, position.y, position.z)
                ?: continue
            val dx = screen[0] - screenX
            val dy = screen[1] - screenY
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            if (distance <= bestDistance) {
                bestDistance = distance
                best = end
            }
        }
        return best
    }

    /** Replaces the overlay geometry; null clears it. */
    fun setAnnotationOverlay(value: AnnotationOverlay?) {
        annotationOverlay = value
        val vertices = value?.vertices
        if (vertices == null || vertices.isEmpty()) {
            annotationBuffer = null
            return
        }
        val direct = java.nio.ByteBuffer
            .allocateDirect(vertices.size * Float.SIZE_BYTES)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
        direct.put(vertices)
        direct.position(0)
        annotationBuffer = direct
    }

    private fun rebuildColorBuffer() {
        val currentMesh = mesh ?: run {
            paintColors = null
            colorUploaded = false
            return
        }
        // A uniform base colour needs no buffer: the shader constant path in
        // drawMesh covers it, keeping dense meshes off the direct-memory heap
        // until painting actually starts.
        if (!paintActive && paintState.isEmpty) {
            paintColors = null
            colorUploaded = false
            return
        }
        var buffer = paintColors
        if (buffer == null || buffer.triangleCount != currentMesh.triangleCount) {
            buffer = PaintColorBuffer(
                triangleCount = currentMesh.triangleCount,
                baseColor = BASE_COLOR,
                enforcerColor = ENFORCER_COLOR,
                blockerColor = BLOCKER_COLOR,
            )
            paintColors = buffer
            // A fresh buffer has no GPU-side copy yet, so force a full upload.
            colorUploaded = false
        }
        // Only triangles whose classification changed are rewritten.
        buffer.resync(paintState)
    }

    private fun cameraDistance(): Float = sceneFit(
        viewportWidth.toFloat() / max(viewportHeight, 1).toFloat(),
    ).distance

    private fun sceneFit(aspect: Float): SceneCameraFit.Fit = SceneCameraFit.calculate(
        printer = printer,
        meshBounds = mesh?.bounds,
        aspect = aspect.coerceAtLeast(0.01f),
        zoom = zoom,
        verticalFieldOfViewDegrees = FIELD_OF_VIEW_DEGREES,
    )

    private fun drawGrid() {
        val buffer = gridBuffer ?: return
        GLES20.glUseProgram(lineProgram)
        val position = GLES20.glGetAttribLocation(lineProgram, "aPosition")
        val matrix = GLES20.glGetUniformLocation(lineProgram, "uMvpMatrix")
        val color = GLES20.glGetUniformLocation(lineProgram, "uColor")

        Matrix.multiplyMM(modelView, 0, view, 0, scene, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)

        buffer.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 3 * 4, buffer)
        GLES20.glUniformMatrix4fv(matrix, 1, false, mvp, 0)
        GLES20.glUniform4f(color, 0.31f, 0.36f, 0.43f, 1f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridVertexCount)
        GLES20.glDisableVertexAttribArray(position)
    }

    /**
     * Uploads the interleaved mesh to a vertex buffer object exactly once per
     * model: afterwards the triangles live in GPU memory and every frame is a
     * pure GPU draw (the same way a game renders a static mesh), instead of
     * re-reading CPU memory through client-side pointers per frame.
     */
    private fun ensureMeshUpload(buffer: FloatBuffer) {
        if (meshVbo != 0 && uploadedMesh === mesh) return
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        meshVbo = ids[0]
        if (meshVbo == 0) return
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, meshVbo)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            buffer.remaining() * Float.SIZE_BYTES,
            buffer,
            GLES20.GL_STATIC_DRAW,
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        uploadedMesh = mesh
    }

    private fun drawMesh() {
        val currentMesh = mesh ?: return
        val buffer = meshBuffer ?: return

        // ModelPlacement has already written the mesh vertices into final
        // build-plate coordinates. Preserve those coordinates so the viewer,
        // CuraEngine input and exported G-code all show the same placement.
        Matrix.setIdentityM(modelLocal, 0)
        Matrix.multiplyMM(modelMatrix, 0, scene, 0, modelLocal, 0)
        Matrix.multiplyMM(modelView, 0, view, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)

        GLES20.glUseProgram(meshProgram)
        val position = GLES20.glGetAttribLocation(meshProgram, "aPosition")
        val normal = GLES20.glGetAttribLocation(meshProgram, "aNormal")
        val color = GLES20.glGetAttribLocation(meshProgram, "aColor")
        val mvpLocation = GLES20.glGetUniformLocation(meshProgram, "uMvpMatrix")
        val modelLocation = GLES20.glGetUniformLocation(meshProgram, "uModelMatrix")

        buffer.position(0)
        ensureMeshUpload(buffer)
        val vbo = meshVbo
        if (vbo != 0) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 6 * 4, 0)
            GLES20.glEnableVertexAttribArray(normal)
            GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 6 * 4, 12)
        } else {
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 6 * 4, buffer)
            buffer.position(3)
            GLES20.glEnableVertexAttribArray(normal)
            GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 6 * 4, buffer)
        }

        val colors = paintColors
        if (colors != null) {
            if (colorVbo == 0) {
                val ids = IntArray(1)
                GLES20.glGenBuffers(1, ids, 0)
                colorVbo = ids[0]
            }
            val data = colors.buffer
            if (colorVbo != 0) {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, colorVbo)
                if (!colorUploaded) {
                    // First upload after the buffer was built: send everything once.
                    data.position(0)
                    data.limit(data.capacity())
                    GLES20.glBufferData(
                        GLES20.GL_ARRAY_BUFFER,
                        data.capacity() * Float.SIZE_BYTES,
                        data,
                        GLES20.GL_DYNAMIC_DRAW,
                    )
                    colorUploaded = true
                    colors.takeDirtyRange()
                } else {
                    // Steady state: send only the float range the last edit touched.
                    val dirty = colors.takeDirtyRange()
                    if (dirty != null) {
                        val from = dirty.first
                        val length = dirty.last - dirty.first + 1
                        data.position(from)
                        data.limit(from + length)
                        GLES20.glBufferSubData(
                            GLES20.GL_ARRAY_BUFFER,
                            from * Float.SIZE_BYTES,
                            length * Float.SIZE_BYTES,
                            data,
                        )
                        data.limit(data.capacity())
                    }
                }
                GLES20.glEnableVertexAttribArray(color)
                GLES20.glVertexAttribPointer(color, 3, GLES20.GL_FLOAT, false, 3 * 4, 0)
            } else {
                data.position(0)
                GLES20.glEnableVertexAttribArray(color)
                GLES20.glVertexAttribPointer(color, 3, GLES20.GL_FLOAT, false, 3 * 4, data)
            }
        } else {
            GLES20.glDisableVertexAttribArray(color)
            GLES20.glVertexAttrib3f(color, BASE_COLOR[0], BASE_COLOR[1], BASE_COLOR[2])
        }

        GLES20.glUniformMatrix4fv(mvpLocation, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(modelLocation, 1, false, modelMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, currentMesh.triangleCount * 3)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(normal)
        GLES20.glDisableVertexAttribArray(color)
    }

    private fun buildGrid() {
        val values = ArrayList<Float>()
        val width = printer.widthMm.toFloat()
        val depth = printer.depthMm.toFloat()
        var x = 0f
        while (x <= width + 0.01f) {
            values += x; values += 0f; values += GRID_Z
            values += x; values += depth; values += GRID_Z
            x += 10f
        }
        var y = 0f
        while (y <= depth + 0.01f) {
            values += 0f; values += y; values += GRID_Z
            values += width; values += y; values += GRID_Z
            y += 10f
        }
        val array = FloatArray(values.size) { values[it] }
        gridBuffer = floatBuffer(array)
        gridVertexCount = array.size / 3
    }

    private fun createProgram(vertex: String, fragment: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertex)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment)
        return GLES20.glCreateProgram().also { program ->
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(program) }
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
        }
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(values); position(0) }
    }

    private fun wrapDegrees(value: Float): Float {
        var wrapped = value % 360f
        if (wrapped < -180f) wrapped += 360f
        if (wrapped >= 180f) wrapped -= 360f
        return wrapped
    }

    private companion object {
        const val DEFAULT_YAW = -28f
        const val DEFAULT_PITCH = 58f
        const val DEFAULT_ZOOM = 1f
        const val MIN_ZOOM = 0.08f
        const val MAX_ZOOM = 40f
        const val FIELD_OF_VIEW_DEGREES = 42f
        const val GRID_Z = -0.08f

        // Eye sits at (0, -distance, 0.62*distance); its true distance to the
        // target is distance * sqrt(1 + 0.62^2).
        const val CAMERA_EYE_DISTANCE_SCALE = 1.17666f

        val BASE_COLOR = floatArrayOf(0.14f, 0.58f, 0.86f)
        val ENFORCER_COLOR = floatArrayOf(0.20f, 0.85f, 0.32f)
        val BLOCKER_COLOR = floatArrayOf(0.90f, 0.25f, 0.22f)
val ANNOTATION_COLOR = floatArrayOf(1.00f, 0.76f, 0.22f)
val ANNOTATION_MARKER_COLOR = floatArrayOf(1.00f, 1.00f, 1.00f)

        const val MESH_VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            uniform mat4 uModelMatrix;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            attribute vec3 aColor;
            varying vec3 vNormal;
            varying vec3 vColor;
            void main() {
                gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
                vNormal = normalize(mat3(uModelMatrix) * aNormal);
                vColor = aColor;
            }
        """
        const val MESH_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 vNormal;
            varying vec3 vColor;
            void main() {
                vec3 normal = normalize(vNormal);
                if (!gl_FrontFacing) {
                    normal = -normal;
                }
                vec3 keyLight = normalize(vec3(0.35, -0.70, 0.62));
                vec3 fillLight = normalize(vec3(-0.55, 0.30, 0.72));
                float key = max(dot(normal, keyLight), 0.0);
                float fill = max(dot(normal, fillLight), 0.0);
                float lighting = 0.28 + key * 0.62 + fill * 0.22;
                gl_FragColor = vec4(vColor * min(lighting, 1.12), 1.0);
            }
        """
        const val LINE_VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            attribute vec3 aPosition;
            void main() {
                gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
            }
        """
        const val LINE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """
    }
}
