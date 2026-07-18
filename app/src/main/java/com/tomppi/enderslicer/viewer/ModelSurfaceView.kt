package com.tomppi.enderslicer.viewer

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.tomppi.enderslicer.model.PrinterDefinition
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

class ModelSurfaceView(
    context: Context,
    private val printer: PrinterDefinition,
) : GLSurfaceView(context) {
    private val modelRenderer = ModelRenderer(printer)
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private var previousX = 0f
    private var previousY = 0f

    init {
        setEGLContextClientVersion(2)
        setRenderer(modelRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setMesh(mesh: StlMesh?) {
        queueEvent { modelRenderer.setMesh(mesh) }
        requestRender()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previousX = event.x
                previousY = event.y
            }
            MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress) {
                val dx = event.x - previousX
                val dy = event.y - previousY
                modelRenderer.rotate(dx * 0.35f, dy * 0.35f)
                previousX = event.x
                previousY = event.y
                requestRender()
            }
        }
        return true
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            modelRenderer.zoom(detector.scaleFactor)
            requestRender()
            return true
        }
    }
}

private class ModelRenderer(
    private val printer: PrinterDefinition,
) : GLSurfaceView.Renderer {
    private var mesh: StlMesh? = null
    private var meshBuffer: FloatBuffer? = null
    private var meshProgram = 0
    private var lineProgram = 0
    private var gridBuffer: FloatBuffer? = null
    private var gridVertexCount = 0
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var yaw = -28f
    private var pitch = 58f
    private var zoom = 1f

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val scene = FloatArray(16)
    private val modelLocal = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val modelView = FloatArray(16)
    private val mvp = FloatArray(16)

    fun setMesh(value: StlMesh?) {
        mesh = value
        meshBuffer = value?.interleavedVertices?.let(::floatBuffer)
    }

    fun rotate(deltaYaw: Float, deltaPitch: Float) {
        yaw += deltaYaw
        pitch = (pitch + deltaPitch).coerceIn(10f, 85f)
    }

    fun zoom(scaleFactor: Float) {
        zoom = (zoom * scaleFactor).coerceIn(0.35f, 3.5f)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.055f, 0.065f, 0.08f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        meshProgram = createProgram(MESH_VERTEX_SHADER, MESH_FRAGMENT_SHADER)
        lineProgram = createProgram(LINE_VERTEX_SHADER, LINE_FRAGMENT_SHADER)
        buildGrid()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = max(width, 1)
        viewportHeight = max(height, 1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val bedMax = max(printer.widthMm, printer.depthMm).toFloat()
        val meshHeight = mesh?.bounds?.height ?: 0f
        val distance = (bedMax * 1.55f + meshHeight * 0.35f) / zoom
        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()

        Matrix.perspectiveM(projection, 0, 42f, aspect, 1f, 3000f)
        Matrix.setLookAtM(view, 0, 0f, -distance, distance * 0.62f, 0f, 0f, 0f, 0f, 0f, 1f)

        Matrix.setIdentityM(scene, 0)
        Matrix.rotateM(scene, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(scene, 0, yaw, 0f, 0f, 1f)
        Matrix.translateM(
            scene,
            0,
            (-printer.widthMm / 2.0).toFloat(),
            (-printer.depthMm / 2.0).toFloat(),
            0f,
        )

        drawGrid()
        drawMesh()
    }

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

    private fun drawMesh() {
        val currentMesh = mesh ?: return
        val buffer = meshBuffer ?: return

        Matrix.setIdentityM(modelLocal, 0)
        Matrix.translateM(
            modelLocal,
            0,
            (printer.widthMm / 2.0 - currentMesh.bounds.centerX).toFloat(),
            (printer.depthMm / 2.0 - currentMesh.bounds.centerY).toFloat(),
            -currentMesh.bounds.minZ,
        )
        Matrix.multiplyMM(modelMatrix, 0, scene, 0, modelLocal, 0)
        Matrix.multiplyMM(modelView, 0, view, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)

        GLES20.glUseProgram(meshProgram)
        val position = GLES20.glGetAttribLocation(meshProgram, "aPosition")
        val normal = GLES20.glGetAttribLocation(meshProgram, "aNormal")
        val mvpLocation = GLES20.glGetUniformLocation(meshProgram, "uMvpMatrix")
        val modelLocation = GLES20.glGetUniformLocation(meshProgram, "uModelMatrix")

        buffer.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 6 * 4, buffer)
        buffer.position(3)
        GLES20.glEnableVertexAttribArray(normal)
        GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 6 * 4, buffer)
        GLES20.glUniformMatrix4fv(mvpLocation, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(modelLocation, 1, false, modelMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, currentMesh.triangleCount * 3)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(normal)
    }

    private fun buildGrid() {
        val values = ArrayList<Float>()
        val width = printer.widthMm.toFloat()
        val depth = printer.depthMm.toFloat()
        var x = 0f
        while (x <= width + 0.01f) {
            values += x; values += 0f; values += 0f
            values += x; values += depth; values += 0f
            x += 10f
        }
        var y = 0f
        while (y <= depth + 0.01f) {
            values += 0f; values += y; values += 0f
            values += width; values += y; values += 0f
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

    private companion object {
        const val MESH_VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            uniform mat4 uModelMatrix;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            varying vec3 vNormal;
            void main() {
                gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
                vNormal = mat3(uModelMatrix) * aNormal;
            }
        """
        const val MESH_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 vNormal;
            void main() {
                vec3 normal = normalize(vNormal);
                vec3 lightDirection = normalize(vec3(0.35, -0.70, 0.62));
                float diffuse = max(dot(normal, lightDirection), 0.16);
                vec3 base = vec3(0.14, 0.58, 0.86);
                gl_FragColor = vec4(base * diffuse, 1.0);
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
