package com.tomppi.enderslicer.viewer

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.tomppi.enderslicer.model.PrinterDefinition
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.floor
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
    private var submittedMesh: StlMesh? = null
    private var previousX = 0f
    private var previousY = 0f
    private var previousFocusX = 0f
    private var previousFocusY = 0f
    private var panning = false

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(modelRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        isClickable = true
    }

    fun setMesh(mesh: StlMesh?) {
        if (submittedMesh === mesh) return
        submittedMesh = mesh
        queueEvent { modelRenderer.setMesh(mesh) }
        requestRender()
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
                    val dx = event.x - previousX
                    val dy = event.y - previousY
                    modelRenderer.rotate(dx * 0.35f, dy * 0.35f)
                    previousX = event.x
                    previousY = event.y
                    requestRender()
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
            }

            MotionEvent.ACTION_CANCEL -> panning = false
        }
        return true
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
            requestRender()
            return true
        }
    }
}

private class ModelRenderer(
    private val printer: PrinterDefinition,
) : GLSurfaceView.Renderer {
    private data class MeshProgram(
        val id: Int,
        val position: Int,
        val normal: Int,
        val mvp: Int,
        val model: Int,
    )

    private data class LineProgram(
        val id: Int,
        val position: Int,
        val mvp: Int,
        val color: Int,
    )

    private var mesh: StlMesh? = null
    private var meshBuffer: FloatBuffer? = null
    private var meshProgram: MeshProgram? = null
    private var lineProgram: LineProgram? = null
    private var gridBuffer: FloatBuffer? = null
    private var gridVertexCount = 0
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var yaw = DEFAULT_YAW
    private var pitch = DEFAULT_PITCH
    private var zoom = DEFAULT_ZOOM
    private var panX = 0f
    private var panY = 0f

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val scene = FloatArray(16)
    private val modelLocal = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val modelView = FloatArray(16)
    private val mvp = FloatArray(16)

    fun setMesh(value: StlMesh?) {
        if (mesh === value) return
        mesh = value
        meshBuffer = value?.interleavedVertices?.let(::floatBuffer)
        resetCamera()
    }

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
        val visibleHeight = 2f * distance * tan(Math.toRadians(FIELD_OF_VIEW_DEGREES / 2.0)).toFloat()
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

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.055f, 0.065f, 0.08f, 1f)
        GLES20.glClearDepthf(1f)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        GLES20.glDepthFunc(GLES20.GL_LESS)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        val meshId = createProgram(MESH_VERTEX_SHADER, MESH_FRAGMENT_SHADER)
        meshProgram = MeshProgram(
            id = meshId,
            position = requireAttribute(meshId, "aPosition"),
            normal = requireAttribute(meshId, "aNormal"),
            mvp = requireUniform(meshId, "uMvpMatrix"),
            model = requireUniform(meshId, "uModelMatrix"),
        )
        val lineId = createProgram(LINE_VERTEX_SHADER, LINE_FRAGMENT_SHADER)
        lineProgram = LineProgram(
            id = lineId,
            position = requireAttribute(lineId, "aPosition"),
            mvp = requireUniform(lineId, "uMvpMatrix"),
            color = requireUniform(lineId, "uColor"),
        )
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
        val program = lineProgram ?: return
        val buffer = gridBuffer ?: return
        GLES20.glUseProgram(program.id)

        Matrix.multiplyMM(modelView, 0, view, 0, scene, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)

        buffer.position(0)
        GLES20.glEnableVertexAttribArray(program.position)
        GLES20.glVertexAttribPointer(program.position, 3, GLES20.GL_FLOAT, false, 3 * 4, buffer)
        GLES20.glUniformMatrix4fv(program.mvp, 1, false, mvp, 0)
        GLES20.glUniform4f(program.color, 0.31f, 0.36f, 0.43f, 1f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridVertexCount)
        GLES20.glDisableVertexAttribArray(program.position)
    }

    private fun drawMesh() {
        val program = meshProgram ?: return
        val currentMesh = mesh ?: return
        val buffer = meshBuffer ?: return

        // ModelPlacement has already written the mesh vertices into final
        // build-plate coordinates. Preserve those coordinates so the viewer,
        // CuraEngine input and exported G-code all show the same placement.
        Matrix.setIdentityM(modelLocal, 0)
        Matrix.multiplyMM(modelMatrix, 0, scene, 0, modelLocal, 0)
        Matrix.multiplyMM(modelView, 0, view, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)

        GLES20.glUseProgram(program.id)
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(program.position)
        GLES20.glVertexAttribPointer(program.position, 3, GLES20.GL_FLOAT, false, 6 * 4, buffer)
        buffer.position(3)
        GLES20.glEnableVertexAttribArray(program.normal)
        GLES20.glVertexAttribPointer(program.normal, 3, GLES20.GL_FLOAT, false, 6 * 4, buffer)
        GLES20.glUniformMatrix4fv(program.mvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(program.model, 1, false, modelMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, currentMesh.triangleCount * 3)
        GLES20.glDisableVertexAttribArray(program.position)
        GLES20.glDisableVertexAttribArray(program.normal)
    }

    private fun buildGrid() {
        val width = printer.widthMm.toFloat()
        val depth = printer.depthMm.toFloat()
        val xLines = floor(width / GRID_STEP_MM).toInt() + 1
        val yLines = floor(depth / GRID_STEP_MM).toInt() + 1
        val values = FloatArray((xLines + yLines) * 2 * 3)
        var offset = 0
        repeat(xLines) { index ->
            val x = index * GRID_STEP_MM
            values[offset++] = x
            values[offset++] = 0f
            values[offset++] = GRID_Z
            values[offset++] = x
            values[offset++] = depth
            values[offset++] = GRID_Z
        }
        repeat(yLines) { index ->
            val y = index * GRID_STEP_MM
            values[offset++] = 0f
            values[offset++] = y
            values[offset++] = GRID_Z
            values[offset++] = width
            values[offset++] = y
            values[offset++] = GRID_Z
        }
        gridBuffer = floatBuffer(values)
        gridVertexCount = values.size / 3
    }

    private fun requireAttribute(program: Int, name: String): Int =
        GLES20.glGetAttribLocation(program, name).also { location ->
            check(location >= 0) { "OpenGL attribute $name is unavailable" }
        }

    private fun requireUniform(program: Int, name: String): Int =
        GLES20.glGetUniformLocation(program, name).also { location ->
            check(location >= 0) { "OpenGL uniform $name is unavailable" }
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
        const val MAX_ZOOM = 20f
        const val FIELD_OF_VIEW_DEGREES = 42f
        const val GRID_STEP_MM = 10f
        const val GRID_Z = -0.08f

        const val MESH_VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            uniform mat4 uModelMatrix;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            varying vec3 vNormal;
            void main() {
                gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
                vNormal = normalize(mat3(uModelMatrix) * aNormal);
            }
        """
        const val MESH_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 vNormal;
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
                vec3 base = vec3(0.14, 0.58, 0.86);
                gl_FragColor = vec4(base * min(lighting, 1.12), 1.0);
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
