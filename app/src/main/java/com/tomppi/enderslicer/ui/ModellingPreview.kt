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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Rendered at the size the view actually occupies, so the frame is as sharp as
 * the screen and no sharper. Workbench is a GPU rasteriser, so its cost is fill
 * rate on an Adreno 740 rather than the tens of milliseconds per frame Cycles
 * spends per sample. Measured warm on the default scene:
 *
 *   256 -> 19 ms, 384 -> 31 ms, 512 -> 56 ms, 640 -> 66 ms, 768 -> 80 ms
 *
 * which is why the two ends differ: a full-size frame is sharp but far too slow
 * to drag, and a small one drags smoothly but looks soft.
 */
private const val MaxPreviewEdge = 1280
private const val MinPreviewEdge = 128
/** Longest edge while a finger is down: about 40 ms a frame. */
private const val InteractiveEdge = 512
/** How long after the last gesture frame the full-resolution one is asked for. */
private const val SettleMs = 220L

private const val DegreesPerPixel = 0.35f
private const val MinDistanceMm = 0.2f
private const val MaxDistanceMm = 20_000f

/**
 * Puts the camera at a distance that frames a sphere of [radius].
 *
 * A 42-degree vertical field of view covers `2 * d * tan(21)` at distance d, so
 * `d = r / tan(21)` puts the sphere's edge exactly on the frame; the extra gives
 * it a little air. Without this the orbit distance is a guess, and a guess is
 * wrong by orders of magnitude between a 2-unit default cube and a 200 mm plate.
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
 * waiting, so a fast drag skips intermediate views instead of building a backlog
 * of renders that arrive after the finger has moved on. Rendering runs on the
 * engine's main thread - the same thread the agent's commands run on - so
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
    var viewSize by remember { mutableStateOf(IntSize(512, 512)) }
    var status by remember { mutableStateOf<String?>("Looking at the engine's scene...") }
    var interacting by remember { mutableStateOf(false) }
    val lastGestureAt = remember { AtomicLong(0L) }

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
        // The engine boots on its default scene, which is a cube. If a model has
        // been sent to it and it is still holding that cube, load the model now:
        // showing a cube after the user has sent one reads as a bug, and the
        // engine cannot tell them apart on its own. Anything the agent has built
        // means a real mesh is present, and that is never overwritten.
        val handoff = File(blenderDir, "imports/current.stl")
        if (handoff.isFile) {
            val untouched = withContext(Dispatchers.IO) {
                runCatching { client.isOnDefaultScene() }.getOrDefault(false)
            }
            if (untouched) {
                status = "Loading your model into the engine..."
                val loaded = withContext(Dispatchers.IO) {
                    runCatching { client.importModel(handoff, blenderDir) }.getOrDefault(false)
                }
                if (!loaded) status = "Could not load the model into the engine"
            }
        }
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

    // When the finger lifts, ask once more at full size. This is what makes the
    // drag cheap without the picture staying soft.
    LaunchedEffect(interacting) {
        if (!interacting) return@LaunchedEffect
        while (System.currentTimeMillis() - lastGestureAt.get() < SettleMs) delay(40)
        interacting = false
        requested.value = requested.value?.copy()
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
            val edge = if (interacting) InteractiveEdge else MaxPreviewEdge
            val (width, height) = renderSize(viewSize, edge)
            val outcome = withContext(Dispatchers.IO) {
                runCatching { client.renderPreview(camera, width, height, file) }
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
            .onSizeChanged { viewSize = it }
            .pointerInput(interactive) {
                if (!interactive) return@pointerInput
                detectTransformGestures { _, pan, zoom, _ ->
                    yaw = wrapDegrees(yaw - pan.x * DegreesPerPixel)
                    // Drag down to look from higher up, which is what the app's
                    // own viewer has always done.
                    pitch = (pitch + pan.y * DegreesPerPixel).coerceIn(-89f, 89f)
                    if (zoom.isFinite() && zoom > 0f) {
                        distance = (distance / zoom).coerceIn(MinDistanceMm, MaxDistanceMm)
                    }
                    interacting = true
                    lastGestureAt.set(System.currentTimeMillis())
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

/**
 * The view's aspect ratio, scaled so its longest edge is at most [longest].
 *
 * The engine renders at the shape it is asked for, so matching the view's aspect
 * means no letterboxing and no wasted pixels; scaling to a common longest edge
 * means a fold or a rotation changes the count of pixels rather than the framing.
 */
private fun renderSize(view: IntSize, longest: Int): Pair<Int, Int> {
    val width = view.width.coerceAtLeast(1)
    val height = view.height.coerceAtLeast(1)
    val scale = (longest.toFloat() / maxOf(width, height)).coerceAtMost(1f)
    return Pair(
        (width * scale).roundToInt().coerceIn(MinPreviewEdge, longest),
        (height * scale).roundToInt().coerceIn(MinPreviewEdge, longest),
    )
}

private fun wrapDegrees(value: Float): Float {
    var wrapped = value % 360f
    if (wrapped < 0f) wrapped += 360f
    return wrapped
}
