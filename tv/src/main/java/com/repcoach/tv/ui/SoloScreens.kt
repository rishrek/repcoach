package com.repcoach.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.repcoach.core.Coaching
import com.repcoach.core.Exercise
import com.repcoach.core.ExerciseText
import com.repcoach.core.Protocol
import com.repcoach.core.RcMessage
import com.repcoach.tv.PlayerView
import com.repcoach.tv.TvState

@Composable
fun WaitingScreen(state: TvState, tvIp: String?) {
    Column(
        SafeArea.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Open RepCoach on your phone", color = TvColors.PrimaryText, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text("${tvIp ?: "No network"}:${Protocol.PORT}", color = TvColors.PrimaryText, fontSize = 96.sp, fontWeight = FontWeight.Bold)
        val error = state.serverError
        Text(
            if (error != null) "Server problem: $error" else "Phone and TV must be on the same Wi-Fi",
            color = if (error != null) TvColors.Fault else TvColors.SecondaryText,
            fontSize = 24.sp,
        )
    }
}

// skeleton on the left, everything else on the right
@Composable
private fun SplitLayout(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(SafeArea.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Box(Modifier.fillMaxHeight().weight(0.6f)) { left() }
        Column(Modifier.fillMaxHeight().weight(0.4f), verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) { right() }
    }
}

@Composable
fun SetupScreen(state: TvState, actions: TvActions) {
    val player = state.solo
    val focus = remember { FocusRequester() }
    InitialFocus(focus)
    Box(Modifier.fillMaxSize()) {
        SplitLayout(
            left = { SkeletonCanvas(player?.pose?.points, Modifier.fillMaxSize()) },
            right = {
                Text(ExerciseText.label(state.exercise), color = TvColors.PrimaryText, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                if (player?.setupOk == true) {
                    Text("Ready", color = TvColors.Clean, fontSize = 32.sp)
                } else {
                    Text(ExerciseText.notReady(state.exercise), color = TvColors.Setup, fontSize = 32.sp)
                }
                Text(ExerciseText.placementHint(state.exercise), color = TvColors.SecondaryText, fontSize = 24.sp)
                ExercisePicker(state.exercise, actions.chooseExercise, firstFocus = focus)
                Text("The set starts as soon as you're in view.", color = TvColors.SecondaryText, fontSize = 24.sp)
            },
        )
        DebugLine(state, SafeArea.align(Alignment.BottomStart))
    }
}

@Composable
fun WorkoutScreen(state: TvState, actions: TvActions) {
    val player = state.solo ?: return
    val focus = remember { FocusRequester() }
    InitialFocus(focus)
    // pressing select anywhere ends the set
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = actions.select),
    ) {
        val faulting = player.faultCue != null
        val ghost = rememberGhostPoints(state.ghost)
        SplitLayout(
            left = {
                SkeletonCanvas(
                    points = player.pose?.points,
                    modifier = Modifier.fillMaxSize(),
                    legColor = if (faulting) TvColors.Fault else TvColors.Bone,
                    ghost = ghost,
                )
            },
            right = { Counter(state, player) },
        )
        Column(SafeArea.fillMaxWidth().align(Alignment.TopCenter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            player.faultCue?.let { Banner(it, TvColors.Fault) }
            if (!player.setupOk) Banner(ExerciseText.notReady(state.exercise), TvColors.Setup)
        }
        DebugLine(state, SafeArea.align(Alignment.BottomStart))
    }
}

@Composable
private fun Counter(state: TvState, player: PlayerView) {
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(player.repSerial) {
        if (player.repSerial > 0) {
            pulse.animateTo(1.15f, tween(125))
            pulse.animateTo(1f, tween(125))
        }
    }
    Text(ExerciseText.label(state.exercise), color = TvColors.SecondaryText, fontSize = 32.sp)
    Text("${player.reps}", color = TvColors.PrimaryText, fontSize = 180.sp, fontWeight = FontWeight.Bold, modifier = Modifier.scale(pulse.value))
    Text("${player.clean} clean", color = TvColors.Clean, fontSize = 36.sp)
    Text("${player.flagged} flagged", color = TvColors.Fault, fontSize = 36.sp)
    Text(
        if (state.awaitingSummary) "Writing your summary..." else "Press Select to finish",
        color = TvColors.SecondaryText,
        fontSize = 24.sp,
    )
}

@Composable
fun SummaryScreen(summary: RcMessage.Summary, actions: TvActions) {
    val focus = remember { FocusRequester() }
    InitialFocus(focus)
    val cleanPercent = if (summary.reps == 0) 0 else summary.cleanReps * 100 / summary.reps
    val fault = Coaching.mostCommonFault(summary.faultCounts)
    Column(SafeArea.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically)) {
        Text("Set complete", color = TvColors.PrimaryText, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Stat(ExerciseText.label(summary.exercise), "${summary.reps}")
            Stat("Clean", "$cleanPercent%")
            Stat("Most common fault", fault?.let(Coaching::cue) ?: "None")
            Stat("Time", "${summary.durationSec} s")
        }
        Box(Modifier.fillMaxWidth().background(TvColors.Card, RoundedCornerShape(16.dp)).padding(28.dp)) {
            Text(summary.aiText, color = TvColors.PrimaryText, fontSize = 28.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            TvButton("New set", actions.newSet, focusRequester = focus)
            TvButton("Done", actions.done)
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(Modifier.width(320.dp).background(TvColors.Card, RoundedCornerShape(16.dp)).padding(24.dp)) {
        Text(label, color = TvColors.SecondaryText, fontSize = 24.sp)
        Text(value, color = TvColors.PrimaryText, fontSize = 48.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun IdleScreen(state: TvState, actions: TvActions) {
    val focus = remember { FocusRequester() }
    InitialFocus(focus)
    Column(
        SafeArea.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nice work.", color = TvColors.PrimaryText, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text("Pick an exercise and start a new set when you're ready.", color = TvColors.SecondaryText, fontSize = 28.sp)
        ExercisePicker(state.exercise, actions.chooseExercise)
        TvButton("New set", actions.newSet, focusRequester = focus)
    }
}

class TvActions(
    val select: () -> Unit,
    val newSet: () -> Unit,
    val done: () -> Unit,
    val chooseExercise: (Exercise) -> Unit,
    val startDuel: () -> Unit,
    val backToLobby: () -> Unit,
)
