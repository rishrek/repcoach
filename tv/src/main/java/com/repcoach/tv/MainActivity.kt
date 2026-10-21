package com.repcoach.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.repcoach.core.RcMessage
import com.repcoach.tv.audio.Sounds
import com.repcoach.tv.net.TvAnnouncer
import com.repcoach.tv.net.TvHub
import com.repcoach.tv.net.localIpv4
import com.repcoach.tv.ui.TvActions
import com.repcoach.tv.ui.TvApp

class MainActivity : ComponentActivity(), TvOutput {
    private val main = Handler(Looper.getMainLooper())
    private var ui by mutableStateOf(TvState())
    private var tvIp by mutableStateOf<String?>(null)

    private lateinit var controller: TvController
    private lateinit var hub: TvHub
    private lateinit var sounds: Sounds
    private lateinit var announcer: TvAnnouncer

    private val ticker = object : Runnable {
        override fun run() {
            controller.tick()
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sounds = Sounds(this)
        announcer = TvAnnouncer(this)
        controller = TvController(
            out = this,
            scheduler = { delayMs, action -> main.postDelayed(action, delayMs) },
            clock = SystemClock::uptimeMillis,
            onChange = { ui = it },
        )
        hub = TvHub(main, controller)
        val actions = TvActions(
            select = controller::select,
            newSet = controller::newSet,
            done = controller::done,
            chooseExercise = controller::chooseExercise,
            startDuel = controller::startDuel,
            backToLobby = controller::backToLobby,
        )
        setContent { TvApp(ui, tvIp, actions) }
    }

    override fun onStart() {
        super.onStart()
        tvIp = localIpv4()
        hub.start()
        announcer.start()
        main.post(ticker)
    }

    override fun onStop() {
        main.removeCallbacks(ticker)
        announcer.stop()
        hub.stop()
        controller.reset()
        super.onStop()
    }

    override fun onDestroy() {
        sounds.close()
        super.onDestroy()
    }

    override fun send(slot: Int, message: RcMessage) = hub.send(slot, message)

    override fun play(clip: String) = sounds.play(clip)

    private companion object {
        const val TICK_MS = 250L
    }
}
