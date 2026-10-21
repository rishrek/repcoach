package com.repcoach.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.repcoach.tv.Screen
import com.repcoach.tv.TvState

@Composable
fun TvApp(state: TvState, tvIp: String?, actions: TvActions) {
    Box(Modifier.fillMaxSize().background(TvColors.Background)) {
        when (state.screen) {
            Screen.WAITING -> WaitingScreen(state, tvIp)
            Screen.SETUP -> SetupScreen(state, actions)
            Screen.WORKOUT -> WorkoutScreen(state, actions)
            Screen.SUMMARY -> state.summary?.let { SummaryScreen(it, actions) } ?: IdleScreen(state, actions)
            Screen.IDLE -> IdleScreen(state, actions)
            Screen.DUEL_LOBBY -> DuelLobbyScreen(state, actions)
            Screen.COUNTDOWN -> CountdownScreen(state)
            Screen.DUEL -> DuelScreen(state)
            Screen.DUEL_END -> DuelEndScreen(state, actions)
        }
    }
}
