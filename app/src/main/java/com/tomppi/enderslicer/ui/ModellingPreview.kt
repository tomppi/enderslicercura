package com.tomppi.enderslicer.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.modelling.CameraOwner
import com.tomppi.enderslicer.modelling.EnginePreviewClient
import com.tomppi.enderslicer.modelling.ModellingCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/** Square, and small on purpose: this is a viewfinder, not a render. */
private const val PreviewSize = 256

private const val DegreesPerPixel = 0.35f
private const val MinDistanceMm = 0.2f
private const val MaxDistanceMm = 20_000f

/**
 * Puts the camera at a distance that frames a sphere of [radius].
 *
 * A 42-degree vertical field of view covers `2 * d * tan(21)` at distance d, so
 * `d = r / tan(21)` puts the sphere's edge exactly on the frame; 1.15 gives it
 * a little air. Without this the orbit distance is a guess, and a guess is
 * wrong by orders of magnitude between a 2-unit default cube and a 200 mm plate
 * - the first attempt framed a cube as a single pixel.
 */
private const val FramingFactor = 2.9f

/**
 * The engine's own view of the model.
 *
 * There is no second camera here. The app asks the engine to point its camera
 * and render, and shows the resulting frame, so what the user sees is exactly
 * what the agent sees - same scene, same camera, same shading. Orbiting sends a
 * new camera and asks for another frame.
 *
 * Frames are coalesced rather than queued: only the newest camera is ever
 * waiting, so a fast drag skips intermediate views instead of building a
 * backlog of renders that arrive after the finger has moved on. Rendering runs
 * on the engine's main thread - the same thread the agent's commands run on - so
 * falling behind would also mean starving the agent.
 */
@Composable
fun ModellingPreview(
    blenderDir: File,
    initialCamera: ModellingCamera?,
    /** False while the agent owns the camera: gestures are refused, not queued. */
    interactive: Boolean,
    onCameraChanged: (ModellingCamera) -> Unit,
    modifier: Modifier = Modifier,
) {
    val client = remember { EnginePreviewClient() }
    DisposableEffect(client) { onDispose { client.close() } }

    var yaw by remember { mutableStateOf(initialCamera?.yawDeg ?: -28f) }
    var pitch by remember { mutableStateOf(initialCamera?.pitchDeg ?: 22f) }
    // Replaced by a framing derived from the engine's scene as soon as we know it.
    var distance by remember { mutableStateOf(initialCamera?.distanceMm ?: 0f) }
    var target by remember {
        mutableStateOf(
            floatArrayOf(
                initialCamera?.targetX ?: 0f,
                initialCamera?.targetY ?: 0f,
                initialCamera?.targetZ ?: 0f,
            ),
        )
    }

    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf<String?>("Looking at the engine's scene...") }

    fun snapshot(): ModellingCamera = ModellingCamera(
        yawDeg = yaw,
        pitchDeg = pitch,
        distanceMm = distance,
        targetX = target[0],
        targetY = target[1],
        targetZ = target[2],
        owner = if (interactive) CameraOwner.USER else CameraOwner.AGENT,
    )

    val requested = remember { mutableStateOf<ModellingCamera?>(null) }

    // Ask the engine what it is holding, once, and frame that. The app does not
    // derive this from the mesh it happens to have: the engine is the scene.
    LaunchedEffect(client) {
        val bounds = withContext(Dispatchers.IO) {
            runCatching { client.sceneBounds() }.getOrNull()
        }
        if (bounds != null) {
            target = floatArrayOf(bounds[0], bounds[1], bounds[2])
            if (initialCamera == null && bounds[3] > 0f) {
                distance = (bounds[3] * FramingFactor).coerceIn(MinDistanceMm, MaxDistanceMm)
            }
        }
        if (distance <= 0f) distance = 120f
        requested.value = snapshot()
        onCameraChanged(requested.value!!)
    }

    // The agent moved the camera: adopt it and re-render. Only while it owns the
    // camera - otherwise this would yank the view out from under a user who is
    // looking at something.
    LaunchedEffect(initialCamera?.rev) {
        val cam = initialCamera ?: return@LaunchedEffect
        if (interactive) return@LaunchedEffect
        yaw = cam.yawDeg
        pitch = cam.pitchDeg
        distance = cam.distanceMm
        target = floatArrayOf(cam.targetX, cam.targetY, cam.targetZ)
        requested.value = snapshot()
    }

    // One render at a time, always the newest camera.
    LaunchedEffect(client) {
        val file = File(blenderDir, "preview.png")
        while (currentCoroutineContext().isActive) {
            val camera = requested.value
            if (camera == null) {
                delay(50)
                continue
            }
            val outcome = withContext(Dispatchers.IO) {
                runCatching { client.renderPreview(camera, PreviewSize, PreviewSize, file) }
            }
            val failure = outcome.exceptionOrNull()
            if (failure != null) {
                status = failure.message?.take(120) ?: "The engine is not answering"
                delay(1_000)
                continue
            }
            if (outcome.getOrDefault(false)) {
                val decoded = withContext(Dispatchers.IO) { client.readPreview(file) }
                if (decoded != null) {
                    frame = decoded
                    status = null
                } else {
                    status = "The engine rendered nothing"
                }
            } else {
                status = "The engine refused the render"
            }
            snapshotFlow { requested.value }.first { it != camera }
        }
    }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(interactive) {
                if (!interactive) return@pointerInput
                detectTransformGestures { _, pan, zoom, _ ->
                    yaw = wrapDegrees(yaw - pan.x * DegreesPerPixel)
                    pitch = (pitch + pan.y * DegreesPerPixel).coerceIn(-89f, 89f)
                    if (zoom.isFinite() && zoom > 0f) {
                        distance = (distance / zoom).coerceIn(MinDistanceMm, MaxDistanceMm)
                    }
                    val next = snapshot()
                    requested.value = next
                    onCameraChanged(next)
                }
            },
    ) {
        frame?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "The engine's view of the model",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        status?.let { message ->
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize().padding(24.dp),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!interactive) {
            Text(
                text = "The agent has the camera",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
            )
        }
    }
}

private fun wrapDegrees(value: Float): Float {
    var wrapped = value % 360f
    if (wrapped < 0f) wrapped += 360f
    return wrapped
}
