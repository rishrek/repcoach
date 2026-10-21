package com.repcoach.phone.ui

import androidx.camera.core.ImageProxy
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.repcoach.core.Exercise
import com.repcoach.core.ExerciseText
import com.repcoach.phone.net.LinkState
import com.repcoach.phone.pose.PoseTracker
import com.repcoach.phone.pose.SessionState
import dev.posebridge.LinkStatus

private val CleanGreen = Color(0xFF22C55E)
private val SetupAmber = Color(0xFFF59E0B)
private val FaultRed = Color(0xFFEF4444)
private val Panel = Color(0xCC000000)

// Main phone screen after picking a TV. The camera keeps running the whole time, during a
// set we just cover it with a black screen.
@Composable
fun SessionScreen(
    state: SessionState,
    link: LinkState,
    onFrame: (ImageProxy) -> Unit,
    onExercise: (Exercise) -> Unit,
    onRecordingChange: (Boolean) -> Unit,
    onChangeTv: () -> Unit,
) {
    KeepScreenOn()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(onFrame, Modifier.fillMaxSize())
        when {
            state.roomFull != null -> RoomFull(state.roomFull, onChangeTv)
            state.active -> WorkoutCover(state, link)
            else -> SetupOverlay(state, link, onExercise, onRecordingChange)
        }
    }
}

@Composable
private fun SetupOverlay(
    state: SessionState,
    link: LinkState,
    onExercise: (Exercise) -> Unit,
    onRecordingChange: (Boolean) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        SkeletonOverlay(state.frame, state.imageWidth, state.imageHeight, Modifier.fillMaxSize())
        if (state.setupReady) ReadyTick(Modifier.align(Alignment.Center).size(160.dp))

        Row(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            DebugCorner(state, onRecordingChange)
            LinkChip(link, state.playerId)
        }

        StatusCard(state, onExercise, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp))
    }
}

@Composable
private fun StatusCard(state: SessionState, onExercise: (Exercise) -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Exercise.entries.forEach { exercise ->
                FilterChip(
                    selected = state.exercise == exercise,
                    onClick = { onExercise(exercise) },
                    label = { Text(ExerciseText.label(exercise)) },
                    colors = FilterChipDefaults.filterChipColors(
                        labelColor = Color.White,
                        selectedContainerColor = Color.White,
                        selectedLabelColor = Color.Black,
                    ),
                )
            }
        }
        val (title, color) = when {
            state.error != null -> state.error to FaultRed
            state.setupReady -> "Ready. The TV starts the set." to CleanGreen
            else -> ExerciseText.notReady(state.exercise) to SetupAmber
        }
        Text(title, color = color, style = MaterialTheme.typography.titleLarge)
        Text(ExerciseText.placementHint(state.exercise), color = Color.White, style = MaterialTheme.typography.bodyLarge)
        state.lastSummary?.let {
            Text("Last set: $it", color = Color(0xFF9AA4B2), style = MaterialTheme.typography.bodyMedium, maxLines = 3)
        }
    }
}

@Composable
private fun WorkoutCover(state: SessionState, link: LinkState) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Look at your TV", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(
            "${ExerciseText.label(state.exercise)}, ${state.reps} reps",
            color = Color(0xFF9AA4B2),
            fontSize = 22.sp,
        )
        LinkChip(link, state.playerId)
    }
}

@Composable
private fun RoomFull(message: String, onChangeTv: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = Color.White, fontSize = 24.sp, textAlign = TextAlign.Center)
        Button(onClick = onChangeTv) { Text("Choose another TV") }
    }
}

// tap the fps box to open the debug menu
@Composable
private fun DebugCorner(state: SessionState, onRecordingChange: (Boolean) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val fpsColor = if (state.fps < PoseTracker.MIN_FPS) SetupAmber else Color.White
    Column(
        Modifier.background(Panel, RoundedCornerShape(8.dp)).clickable { open = !open }.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DebugText("%.0f fps | %s | %s".format(state.fps, state.model, state.delegate), fpsColor)
        if (state.recording) DebugText("REC ${state.recordedFrames} frames", FaultRed)
        if (open) {
            DebugText("angle ${state.trackedAngle?.let { "%.0f deg".format(it) } ?: "none"} | reps ${state.reps}")
            Row(verticalAlignment = Alignment.CenterVertically) {
                DebugText("Record CSV  ")
                Switch(checked = state.recording, onCheckedChange = onRecordingChange)
            }
            state.recordingPath?.let { DebugText(it.substringAfterLast('/')) }
        }
    }
}

@Composable
private fun LinkChip(link: LinkState, playerId: String?) {
    val (text, color) = when (link.status) {
        LinkStatus.CONNECTED -> "TV connected" to CleanGreen
        LinkStatus.CONNECTING -> "Connecting to TV..." to SetupAmber
        LinkStatus.RETRYING -> "TV lost, retrying..." to SetupAmber
        LinkStatus.IDLE -> "No TV" to Color.White
    }
    Column(Modifier.background(Panel, RoundedCornerShape(8.dp)).padding(8.dp), horizontalAlignment = Alignment.End) {
        DebugText(text, color)
        listOfNotNull(link.host, playerId?.let { "player $it" }).forEach { DebugText(it) }
    }
}

@Composable
private fun DebugText(text: String, color: Color = Color.White) {
    Text(text, color = color, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
}

@Composable
private fun ReadyTick(modifier: Modifier) {
    Canvas(modifier) {
        drawCircle(CleanGreen, radius = size.minDimension / 2)
        val tick = Path().apply {
            moveTo(size.width * 0.28f, size.height * 0.52f)
            lineTo(size.width * 0.44f, size.height * 0.68f)
            lineTo(size.width * 0.74f, size.height * 0.36f)
        }
        drawPath(tick, Color.White, style = Stroke(width = size.width * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
