package com.repcoach.tv.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.repcoach.core.ExerciseText
import com.repcoach.tv.PlayerView
import com.repcoach.tv.TvController
import com.repcoach.tv.TvState
import kotlinx.coroutines.delay

private fun name(slot: Int) = if (slot == 2) "Player 2" else "Player 1"

@Composable
fun DuelLobbyScreen(state: TvState, actions: TvActions) {
    val focus = remember { FocusRequester() }
    InitialFocus(focus)
    Box(Modifier.fillMaxSize()) {
        Column(SafeArea.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)) {
            Text("Duel: ${ExerciseText.label(state.exercise)}", color = TvColors.PrimaryText, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(
                "First to ${TvController.DUEL_TARGET} clean reps wins. Flagged reps don't count. ${TvController.DUEL_LIMIT_MS / 1000} seconds.",
                color = TvColors.SecondaryText,
                fontSize = 28.sp,
            )
            Row(Modifier.fillMaxWidth().height(360.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                listOf(1, 2).forEach { slot -> LobbyCard(slot, state.players[slot], Modifier.weight(1f).fillMaxHeight()) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                TvButton("Start duel", actions.startDuel, focusRequester = focus)
                ExercisePicker(state.exercise, actions.chooseExercise)
            }
        }
        DebugLine(state, SafeArea.align(Alignment.BottomStart))
    }
}

@Composable
private fun LobbyCard(slot: Int, player: PlayerView?, modifier: Modifier) {
    Row(modifier.background(TvColors.Card, RoundedCornerShape(16.dp)).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(name(slot), color = TvColors.player(slot), fontSize = 40.sp, fontWeight = FontWeight.Bold)
            when {
                player == null -> Text("Waiting for a phone", color = TvColors.SecondaryText, fontSize = 24.sp)
                player.setupOk -> Text("In view", color = TvColors.Clean, fontSize = 28.sp)
                else -> Text(player.statusText.ifEmpty { "Getting ready" }, color = TvColors.Setup, fontSize = 24.sp)
            }
        }
        SkeletonCanvas(player?.pose?.points, Modifier.weight(1f).fillMaxHeight(), color = TvColors.player(slot))
    }
}

@Composable
fun CountdownScreen(state: TvState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("${state.countdown ?: ""}", color = TvColors.PrimaryText, fontSize = 240.sp, fontWeight = FontWeight.Bold)
    }
}

// ticks 5 times a second so the duel timer keeps moving
@Composable
private fun rememberUptime(): Long {
    val now by produceState(SystemClock.uptimeMillis()) {
        while (true) {
            delay(200)
            value = SystemClock.uptimeMillis()
        }
    }
    return now
}

@Composable
fun DuelScreen(state: TvState) {
    val now = rememberUptime()
    val remaining = state.duelEndsAt?.let { ((it - now).coerceAtLeast(0) + 999) / 1000 } ?: 0
    Box(Modifier.fillMaxSize()) {
        Row(SafeArea.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            listOf(1, 2).forEach { slot ->
                DuelHalf(slot, state.players[slot], leading = state.duelLeader == slot, modifier = Modifier.weight(1f).fillMaxHeight())
            }
        }
        Text(
            "%d:%02d".format(remaining / 60, remaining % 60),
            color = TvColors.PrimaryText,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            modifier = SafeArea.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun DuelHalf(slot: Int, player: PlayerView?, leading: Boolean, modifier: Modifier) {
    val color = TvColors.player(slot)
    Box(modifier) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                name(slot) + if (leading) " (leading)" else "",
                color = color,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
            )
            Row(Modifier.weight(1f).fillMaxWidth()) {
                SkeletonCanvas(
                    player?.pose?.points,
                    Modifier.weight(1f).fillMaxHeight(),
                    color = TvColors.Bone,
                    legColor = if (player?.faultCue != null) TvColors.Fault else TvColors.Bone,
                )
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${player?.clean ?: 0}", color = color, fontSize = 150.sp, fontWeight = FontWeight.Bold)
                    Text("clean", color = TvColors.Clean, fontSize = 28.sp)
                    Text("${player?.flagged ?: 0} flagged", color = TvColors.Fault, fontSize = 28.sp)
                }
            }
        }
        player?.faultCue?.let { Banner(it, TvColors.Fault, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) }
    }
}

@Composable
fun DuelEndScreen(state: TvState, actions: TvActions) {
    val outcome = state.duelOutcome ?: return
    val focus = remember { FocusRequester() }
    InitialFocus(focus)
    Column(
        SafeArea.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val winner = outcome.winner
        Text(
            if (winner == null) "It's a tie" else "${name(winner)} wins!",
            color = winner?.let(TvColors::player) ?: TvColors.PrimaryText,
            fontSize = 96.sp,
            fontWeight = FontWeight.Bold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            listOf(1, 2).forEach { slot ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(name(slot), color = TvColors.player(slot), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Text("${outcome.clean[slot - 1]} clean", color = TvColors.Clean, fontSize = 32.sp)
                    Text("${outcome.flagged[slot - 1]} flagged", color = TvColors.Fault, fontSize = 32.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            TvButton("Rematch", actions.startDuel, focusRequester = focus)
            TvButton("Back to lobby", actions.backToLobby)
        }
    }
}
