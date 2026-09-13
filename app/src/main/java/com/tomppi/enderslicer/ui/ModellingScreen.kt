package com.tomppi.enderslicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.withSettings
import com.tomppi.enderslicer.modelling.CameraOwner
import com.tomppi.enderslicer.modelling.ModellingCamera
import com.tomppi.enderslicer.viewer.ModelSurfaceView
import com.tomppi.enderslicer.viewer.ViewerOrientation

private const val CameraEchoGuardMs = 350L
private val ChatHeight = 260.dp
private val BarPadding = 10.dp

/**
 * Modelling from scratch: one model, one conversation, one camera.
 *
 * Deliberately not the floating [AiChatOverlay]. That one has to stay out of the
 * way because the user paints on the model to aim the agent; here the agent and
 * the user are looking at the same object through the same camera, so the model
 * gets the room and the chat sits under it.
 *
 * @param incomingCamera the agent's camera, or null. Applied only while the
 *   agent owns the camera - otherwise the user's orbit would be yanked away
 *   mid-inspection, which is the one thing the pause exists to prevent.
 * @param onCameraMoved the user moved the camera; carries the whole shared
 *   state, because yaw and pitch alone do not say how close the eye is.
 */
@Composable
fun ModellingScreen(
    state: MainUiState,
    messages: List<AiChatMessage>,
    busy: Boolean,
    status: String?,
    owner: CameraOwner,
    /** False while the agent is working: the camera is the agent's until it stops. */
    canTakeCamera: Boolean,
    onSend: (String) -> Unit,
    onExit: () -> Unit,
    onTakeCamera: () -> Unit,
    onHandBackCamera: () -> Unit,
    onCameraMoved: (ViewerOrientation, Float) -> Unit,
    incomingCamera: ModellingCamera?,
    modifier: Modifier = Modifier,
) {
    val effectivePrinter: PrinterDefinition = state.printer.withSettings(state.settings)
    var modelView by remember(effectivePrinter) { mutableStateOf<ModelSurfaceView?>(null) }
    val camera = incomingCamera
    // While the agent's camera is being applied the view reports the change
    // straight back; without this the app would echo it as a user move and take
    // ownership of a camera the agent had just set.
    var applyingRemote by remember { mutableStateOf(false) }

    LaunchedEffect(camera?.rev) {
        val view = modelView ?: return@LaunchedEffect
        if (camera == null || owner != CameraOwner.AGENT) return@LaunchedEffect
        applyingRemote = true
        view.restoreOrientation(ViewerOrientation(camera.yawDeg, camera.pitchDeg))
        view.restoreDistanceMm(camera.distanceMm)
        // Both restores are queued onto the GL thread and report back through
        // onOrientationChanged a frame later. Clearing the flag immediately
        // would let that report be published as a user move, echoing the
        // agent's own camera back at it with a new revision.
        delay(CameraEchoGuardMs)
        applyingRemote = false
    }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(tonalElevation = 3.dp) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                TextButton(onClick = onExit) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Exit")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Modelling",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { if (owner == CameraOwner.USER) onHandBackCamera() else onTakeCamera() },
                    enabled = owner == CameraOwner.USER || canTakeCamera,
                ) {
                    Text(if (owner == CameraOwner.USER) "Hand back" else "Take camera")
                }
            }
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            factory = { context -> ModelSurfaceView(context, effectivePrinter).also { modelView = it } },
            update = { view ->
                view.setMesh(state.mesh)
                view.cameraInteractive = owner == CameraOwner.USER
                view.onOrientationChanged = { orientation ->
                    if (!applyingRemote) onCameraMoved(orientation, view.currentDistanceMm())
                }
            },
        )

        Surface(tonalElevation = 6.dp) {
            Column(modifier = Modifier.fillMaxWidth().height(ChatHeight)) {
                HorizontalDivider()
                if (owner == CameraOwner.USER) {
                    Text(
                        text = "You have the camera. Send a message to hand it back.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().padding(BarPadding),
                    )
                }
                ChatTranscript(
                    messages = messages,
                    busy = busy,
                    status = status,
                    onSend = onSend,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ChatTranscript(
    messages: List<AiChatMessage>,
    busy: Boolean,
    status: String?,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val scroll = rememberScrollState()

    LaunchedEffect(messages.size) { scroll.animateScrollTo(scroll.maxValue) }

    Column(modifier = modifier.padding(horizontal = BarPadding)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(vertical = BarPadding),
        ) {
            if (messages.isEmpty()) {
                Text(
                    text = "Describe the part you want, or just say hello. " +
                        "The agent works on the model you can see.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            messages.forEach { message ->
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = if (message.fromUser) TextAlign.End else TextAlign.Start,
                    color = if (message.fromUser) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (status != null) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = BarPadding),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(if (busy) "Working…" else "Tell the agent what to change") },
                singleLine = false,
                maxLines = 3,
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        draft = ""
                        onSend(text)
                    }
                }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        draft = ""
                        onSend(text)
                    }
                },
                enabled = draft.isNotBlank(),
            ) {
                Text("Send")
            }
        }
    }
}
