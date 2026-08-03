package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tomppi.enderslicer.engine.GcodeNozzlePath
import com.tomppi.enderslicer.engine.GcodeNozzlePathParser
import com.tomppi.enderslicer.viewer.NozzlePathSurfaceView
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

private sealed interface NozzlePathLoadState {
    data object Loading : NozzlePathLoadState
    data class Ready(val path: GcodeNozzlePath) : NozzlePathLoadState
    data class Failed(val message: String) : NozzlePathLoadState
}

@Composable
internal fun NozzlePathView(gcodePath: String, modifier: Modifier = Modifier) {
    val loadState by produceState<NozzlePathLoadState>(NozzlePathLoadState.Loading, gcodePath) {
        value = withContext(Dispatchers.IO) {
            runCatching { GcodeNozzlePathParser.parse(File(gcodePath)) }
                .fold(
                    onSuccess = NozzlePathLoadState::Ready,
                    onFailure = { NozzlePathLoadState.Failed(it.message ?: "Unable to parse nozzle path") },
                )
        }
    }

    when (val current = loadState) {
        NozzlePathLoadState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is NozzlePathLoadState.Failed -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(current.message, modifier = Modifier.padding(24.dp))
        }
        is NozzlePathLoadState.Ready -> NozzlePathPlayer(current.path, modifier)
    }
}

@Composable
private fun NozzlePathPlayer(path: GcodeNozzlePath, modifier: Modifier) {
    var moveIndex by rememberSaveable(path.sourceMoveCount) { mutableIntStateOf(0) }
    var playing by rememberSaveable(path.sourceMoveCount) { mutableStateOf(false) }
    val safeIndex = moveIndex.coerceIn(0, max(path.moveCount - 1, 0))

    LaunchedEffect(playing, path.moveCount) {
        while (playing && isActive) {
            if (moveIndex >= path.moveCount - 1) {
                playing = false
                break
            }
            moveIndex = (moveIndex + max(path.moveCount / 600, 1)).coerceAtMost(path.moveCount - 1)
            delay(16)
        }
    }

    Column(modifier = modifier) {
        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            factory = { context -> NozzlePathSurfaceView(context) },
            update = { view -> view.setPath(path, safeIndex) },
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                val offset = safeIndex * GcodeNozzlePath.VALUES_PER_MOVE
                Text(
                    "Move ${safeIndex + 1}/${path.moveCount} · Z %.3f mm · %.1f mm/s".format(
                        path.moves[offset + GcodeNozzlePath.Z2],
                        path.moves[offset + GcodeNozzlePath.SPEED],
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "${path.extrusionMoveCount} extrusion moves · ${path.travelMoveCount} travel moves" +
                        if (path.truncated) " · sampled from ${path.sourceMoveCount}" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = safeIndex.toFloat(),
                    onValueChange = { moveIndex = it.roundToInt().coerceIn(0, path.moveCount - 1) },
                    valueRange = 0f..max(path.moveCount - 1, 1).toFloat(),
                    enabled = path.moveCount > 1,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (playing) {
                        Button(onClick = { playing = false }, modifier = Modifier.weight(1f)) { Text("Pause") }
                    } else {
                        Button(
                            onClick = {
                                if (moveIndex >= path.moveCount - 1) moveIndex = 0
                                playing = true
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Play") }
                    }
                    OutlinedButton(
                        onClick = { playing = false; moveIndex = 0 },
                        modifier = Modifier.weight(1f),
                    ) { Text("Restart") }
                }
                Text(
                    "Gray is travel. Extrusion changes from blue at low Z to red at high Z. Drag to orbit, pinch to zoom, use two fingers to pan, and double-tap to reset.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
