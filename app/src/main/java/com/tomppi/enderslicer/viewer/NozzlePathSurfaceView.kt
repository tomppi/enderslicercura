package com.tomppi.enderslicer.viewer

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.tomppi.enderslicer.engine.GcodeNozzlePath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

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
        preserveEGLContextOnPause = true
        setRenderer(pathRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        isClickable = true
    }

    fun setPath(path: GcodeNozzlePath, selectedMoveIndex: Int) {
        queueEvent {
            pathRenderer.setPath(path)
            pathRenderer.setSelectedMove(selectedMoveIndex)
        }
        // setPath resets the camera on the GL thread.
        queueEvent { notifyOrientation() }
        requestRender()
    }

    /** Invoked on the main thread whenever the turntable yaw/pitch changes. */
    var onOrientationChanged: ((ViewerOrientation) -> Unit)? = null

    fun currentOrientation(): ViewerOrientation = pathRenderer.orientation

    private fun notifyOrientation() {
        val listener = onOrientationChanged ?: return
        post { listener(pathRenderer.orientation) }
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
            requestRender()
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onDoubleTap(event: MotionEvent): Boolean {
            pathRenderer.resetCamera()
            notifyOrientation()
            requestRender()
            return true
        }
    }
}

private class NozzlePathRenderer : GLSurfaceView.Renderer {
    private var path: GcodeNozzlePath? = null
    private var selectedMoveIndex = 0
    private var pathPositions: FloatBuffer? = null
    private var pathColors: FloatBuffer? = null
    private var gridPositions: FloatBuffer? = null
    private var gridVertexCount = 0
    private var markerPositions: FloatBuffer? = null
    private var markerVertexCount = 0
    private var markerGlowPositions: FloatBuffer? = null
    private var markerGlowVertexCount = 0

    private var colorProgram = 0
    private var solidProgram = 0
    private var maxLineWidth = 1f
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
    private val modelView = FloatArray(16)
    private val mvp = FloatArray(16)

    fun setPath(value: GcodeNozzlePath) {
        if (path === value) return
        path = value
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

    val orientation: ViewerOrientation
        get() = ViewerOrientation(yaw, pitch)

    fun rotate(deltaYaw: Float, deltaPitch: Float) {
        yaw = wrapDegrees(yaw + deltaYaw)
        pitch = (pitch + deltaPitch).coerceIn(-85f, 85f)
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

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val current = path ?: return
        val distance = cameraDistance()
        val aspect = viewportWidth.toFloat() / viewportHeight
        val radius = sceneRadius(current)
        val nearPlane = max(0.1f, distance - radius * 1.6f)
        val farPlane = max(nearPlane + 100f, distance + radius * 2.8f + 100f)

        Matrix.perspectiveM(projection, 0, FIELD_OF_VIEW, aspect, nearPlane, farPlane)
        Matrix.setLookAtM(view, 0, 0f, -distance, distance * 0.58f, 0f, 0f, 0f, 0f, 0f, 1f)
        Matrix.translateM(view, 0, panX, panY, 0f)
        Matrix.setIdentityM(scene, 0)
        Matrix.rotateM(scene, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(scene, 0, yaw, 0f, 0f, 1f)
        Matrix.translateM(
            scene,
            0,
            -(current.minX + current.maxX) * 0.5f,
            -(current.minY + current.maxY) * 0.5f,
            -(current.minZ + current.maxZ) * 0.5f,
        )
        Matrix.multiplyMM(modelView, 0, view, 0, scene, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)

        drawSolidLines(gridPositions, gridVertexCount, 1f, 0.24f, 0.30f, 0.40f, 0.48f)
        drawColoredLines((selectedMoveIndex + 1) * 2)
        drawSolidLines(markerGlowPositions, markerGlowVertexCount, 12f, 1f, 0.55f, 0.05f, 0.55f)
        drawSolidLines(markerPositions, markerVertexCount, 6f, 1f, 1f, 1f, 1f)
    }

    private fun buildPathBuffers(value: GcodeNozzlePath) {
        val vertexCount = value.moveCount * 2
        val positions = allocate(vertexCount * 3)
        val colors = allocate(vertexCount * 4)
        val source = value.moves
        var offset = 0
        repeat(value.moveCount) {
            val zRatio = if (value.maxZ > value.minZ) {
                ((source[offset + GcodeNozzlePath.Z2] - value.minZ) / (value.maxZ - value.minZ)).coerceIn(0f, 1f)
            } else 0f
            val extrusion = source[offset + GcodeNozzlePath.KIND] == GcodeNozzlePath.Kind.EXTRUSION.code
            val color = if (extrusion) hsv(240f - 240f * zRatio, 0.95f, 1f, 1f) else floatArrayOf(0.50f, 0.54f, 0.64f, 0.32f)
            positions.put(source[offset + GcodeNozzlePath.X1])
            positions.put(source[offset + GcodeNozzlePath.Y1])
            positions.put(source[offset + GcodeNozzlePath.Z1])
            positions.put(source[offset + GcodeNozzlePath.X2])
            positions.put(source[offset + GcodeNozzlePath.Y2])
            positions.put(source[offset + GcodeNozzlePath.Z2])
            repeat(2) { colors.put(color) }
            offset += GcodeNozzlePath.VALUES_PER_MOVE
        }
        positions.position(0)
        colors.position(0)
        pathPositions = positions
        pathColors = colors
    }

    private fun buildGrid(value: GcodeNozzlePath) {
        val width = max(value.maxX - value.minX, 1f)
        val depth = max(value.maxY - value.minY, 1f)
        val step = gridStep(max(width, depth))
        val minX = floor(value.minX / step) * step
        val maxX = kotlin.math.ceil(value.maxX / step) * step
        val minY = floor(value.minY / step) * step
        val maxY = kotlin.math.ceil(value.maxY / step) * step
        val xLines = ((maxX - minX) / step).toInt() + 1
        val yLines = ((maxY - minY) / step).toInt() + 1
        val buffer = allocate((xLines + yLines) * 2 * 3)
        val z = value.minZ
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

    private fun drawColoredLines(vertexCount: Int) {
        val positions = pathPositions ?: return
        val colors = pathColors ?: return
        if (vertexCount <= 0) return
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
        lineWidth(PATH_WIDTH)
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

    private fun cameraDistance(): Float {
        val current = path ?: return 300f
        return max(sceneRadius(current) * 2.8f / zoom, 20f)
    }

    private fun sceneRadius(value: GcodeNozzlePath): Float {
        val dx = max(value.maxX - value.minX, 1f)
        val dy = max(value.maxY - value.minY, 1f)
        val dz = max(value.maxZ - value.minZ, 1f)
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
        private const val MAX_ZOOM = 24f
        private const val PATH_WIDTH = 3.4f
        // Eye sits at (0, -distance, 0.58*distance); true eye distance is distance * sqrt(1 + 0.58^2).
        private const val CAMERA_EYE_DISTANCE_SCALE = 1.1561f

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
