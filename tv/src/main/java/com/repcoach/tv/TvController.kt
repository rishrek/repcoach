package com.repcoach.tv

import com.repcoach.core.Coaching
import com.repcoach.core.ControlAction
import com.repcoach.core.CueGate
import com.repcoach.core.DuelMatch
import com.repcoach.core.DuelOutcome
import com.repcoach.core.Exercise
import com.repcoach.core.FaultKind
import com.repcoach.core.FpsMeter
import com.repcoach.core.GhostFrame
import com.repcoach.core.RcMessage
import com.repcoach.core.SetResult

enum class Screen { WAITING, SETUP, WORKOUT, SUMMARY, IDLE, DUEL_LOBBY, COUNTDOWN, DUEL, DUEL_END }

data class PlayerView(
    val slot: Int,
    val address: String,
    val pose: RcMessage.Pose? = null,
    val poseAt: Long = 0,
    val setupOk: Boolean = false,
    val statusText: String = "",
    val reps: Int = 0,
    val clean: Int = 0,
    val flagged: Int = 0,
    val faultCounts: Map<FaultKind, Int> = emptyMap(),
    // set for 2 seconds after a fault, legs are red while it's set
    val faultCue: String? = null,
    val faultSerial: Int = 0,
    // bumps on every rep so the counter can pulse
    val repSerial: Int = 0,
) {
    val playerId get() = "P$slot"
}

data class GhostPlay(val frames: List<GhostFrame>, val startedAt: Long, val serial: Int)

data class TvState(
    val screen: Screen = Screen.WAITING,
    val players: Map<Int, PlayerView> = emptyMap(),
    val exercise: Exercise = Exercise.SQUAT,
    val soloSlot: Int? = null,
    val summary: RcMessage.Summary? = null,
    val awaitingSummary: Boolean = false,
    val countdown: Int? = null,
    val duelEndsAt: Long? = null,
    val duelOutcome: DuelOutcome? = null,
    val duelLeader: Int? = null,
    val ghost: GhostPlay? = null,
    val messagesPerSecond: Float = 0f,
    val serverError: String? = null,
) {
    val solo: PlayerView? get() = soloSlot?.let(players::get) ?: players.values.minByOrNull { it.slot }
}

interface TvOutput {
    fun send(slot: Int, message: RcMessage)
    fun play(clip: String)
}

fun interface Scheduler {
    fun after(delayMs: Long, action: () -> Unit)
}

// Everything the TV decides lives here, screens, solo sets, duels, sounds and the ghost.
// The phones do the counting and this just reacts to them and to the remote.
// Only call it from the main thread. No android code so we can test it without a TV.
class TvController(
    private val out: TvOutput,
    private val scheduler: Scheduler,
    private val clock: () -> Long,
    private val onChange: (TvState) -> Unit = {},
) {
    var state = TvState()
        private set(value) {
            field = value
            onChange(value)
        }

    private val duel = DuelMatch(target = DUEL_TARGET, limitMs = DUEL_LIMIT_MS)
    private val cues = CueGate()
    private val meter = FpsMeter()
    private var ghostFrames: List<GhostFrame>? = null
    private var ghostSerial = 0
    private var countdownSerial = 0
    private var summarySerial = 0
    private var setStartedAt = 0L

    // app stopped so every phone is gone
    fun reset() {
        update { TvState(exercise = it.exercise) }
    }

    fun onJoined(slot: Int, address: String) {
        // a phone that lost wifi gets its old slot back so keep its counts
        update { it.copy(players = it.players + (slot to (it.players[slot]?.copy(address = address) ?: PlayerView(slot, address)))) }
        out.send(slot, RcMessage.Hello("P$slot"))
        out.send(slot, RcMessage.Control(ControlAction.SET_EXERCISE, exercise = state.exercise))
        when (state.screen) {
            Screen.WAITING, Screen.SETUP, Screen.IDLE -> enterSetup()
            else -> Unit // something is on screen already, the new phone waits
        }
    }

    fun onLeft(slot: Int) {
        val wasPlaying = slot == state.soloSlot || state.screen in DUEL_SCREENS
        update { it.copy(players = it.players - slot, soloSlot = it.soloSlot.takeIf { s -> s != slot }) }
        when {
            state.players.isEmpty() -> update { TvState(exercise = it.exercise, serverError = it.serverError) }
            state.screen in DUEL_SCREENS -> {
                state.players.keys.forEach { out.send(it, RcMessage.Control(ControlAction.RESET)) }
                enterSetup()
            }
            wasPlaying || state.screen == Screen.SETUP -> enterSetup()
        }
    }

    fun onServerError(message: String?) = update { it.copy(serverError = message) }

    fun onMessage(slot: Int, message: RcMessage) {
        val now = clock()
        meter.tick(now)
        if (slot !in state.players) return
        when (message) {
            is RcMessage.Pose -> player(slot) { it.copy(pose = message, poseAt = now) }
            is RcMessage.Status -> {
                player(slot) { it.copy(setupOk = message.setupOk, statusText = message.message) }
                if (state.screen == Screen.SETUP) maybeStartSolo()
            }
            is RcMessage.Control -> {
                val picked = message.exercise
                if (message.action == ControlAction.SET_EXERCISE && picked != null) {
                    // picked on a phone, use it and tell the other phone too
                    update { it.copy(exercise = picked) }
                    state.players.keys.filter { it != slot }.forEach {
                        out.send(it, RcMessage.Control(ControlAction.SET_EXERCISE, exercise = picked))
                    }
                }
            }
            is RcMessage.Rep -> onRep(slot, message)
            is RcMessage.Fault -> onFault(slot, message.kind)
            is RcMessage.RepStart -> if (state.screen == Screen.WORKOUT && slot == state.soloSlot) {
                val frames = ghostFrames
                if (frames != null && (state.players[slot]?.reps ?: 0) >= 1) {
                    update { it.copy(ghost = GhostPlay(frames, now, ++ghostSerial)) }
                }
            }
            is RcMessage.Ghost -> if (slot == state.soloSlot && state.screen == Screen.WORKOUT) ghostFrames = message.frames
            is RcMessage.Summary -> if (slot == state.soloSlot && state.screen == Screen.WORKOUT) showSummary(message)
            is RcMessage.Hello, is RcMessage.RoomFull -> Unit
        }
    }

    // select ends a solo set and the phone sends back the summary
    fun select() {
        if (state.screen != Screen.WORKOUT || state.awaitingSummary) return
        val slot = state.soloSlot ?: return
        out.send(slot, RcMessage.Control(ControlAction.END_SET))
        update { it.copy(awaitingSummary = true) }
        // if the phone never answers we make our own summary from what we saw
        val serial = ++summarySerial
        scheduler.after(SUMMARY_WAIT_MS) {
            if (serial == summarySerial && state.screen == Screen.WORKOUT && state.awaitingSummary) showSummary(localSummary())
        }
    }

    fun newSet() {
        state.players.keys.forEach { out.send(it, RcMessage.Control(ControlAction.RESET)) }
        enterSetup()
    }

    fun done() {
        state.players.keys.forEach { out.send(it, RcMessage.Control(ControlAction.RESET)) }
        update { it.copy(screen = Screen.IDLE, ghost = null, summary = null) }
    }

    fun chooseExercise(exercise: Exercise) {
        update { it.copy(exercise = exercise, players = it.players.mapValues { (_, p) -> p.copy(setupOk = false) }) }
        state.players.keys.forEach { out.send(it, RcMessage.Control(ControlAction.SET_EXERCISE, exercise = exercise)) }
    }

    fun startDuel() {
        if (state.players.size < 2 || state.screen !in setOf(Screen.DUEL_LOBBY, Screen.DUEL_END)) return
        val serial = ++countdownSerial
        update { it.copy(screen = Screen.COUNTDOWN, countdown = 3, duelOutcome = null, ghost = null) }
        out.play(Clips.COUNTDOWN_GO)
        fun step(n: Int) {
            scheduler.after(1_000) {
                if (serial != countdownSerial || state.screen != Screen.COUNTDOWN) return@after
                if (n > 1) {
                    update { it.copy(countdown = n - 1) }
                    step(n - 1)
                } else {
                    beginDuel()
                }
            }
        }
        step(3)
    }

    fun backToLobby() {
        state.players.keys.forEach { out.send(it, RcMessage.Control(ControlAction.RESET)) }
        enterSetup()
    }

    // called a few times a second for the duel clock and the debug numbers
    fun tick() {
        val now = clock()
        if (state.screen == Screen.DUEL) duel.tick(now)?.let(::endDuel)
        update {
            it.copy(
                messagesPerSecond = meter.valueAt(now),
                players = it.players.mapValues { (_, p) -> if (p.pose != null && now - p.poseAt > POSE_STALE_MS) p.copy(pose = null) else p },
            )
        }
    }

    private fun enterSetup() {
        if (state.players.size >= 2) {
            update { it.copy(screen = Screen.DUEL_LOBBY, soloSlot = null, ghost = null, summary = null, awaitingSummary = false) }
            return
        }
        val slot = state.players.keys.minOrNull()
        if (slot == null) {
            update { it.copy(screen = Screen.WAITING, soloSlot = null) }
            return
        }
        if (state.screen != Screen.SETUP) out.play(Clips.STEP_BACK)
        update { it.copy(screen = Screen.SETUP, soloSlot = slot, ghost = null, summary = null, awaitingSummary = false) }
        maybeStartSolo()
    }

    private fun maybeStartSolo() {
        val player = state.solo ?: return
        if (state.screen != Screen.SETUP || state.players.size != 1 || !player.setupOk) return
        ghostFrames = null
        cues.clear()
        setStartedAt = clock()
        update { s ->
            s.copy(
                screen = Screen.WORKOUT,
                soloSlot = player.slot,
                players = s.players.mapValues { (_, p) -> p.resetCounts() },
                ghost = null,
                awaitingSummary = false,
            )
        }
        out.send(player.slot, RcMessage.Control(ControlAction.START_SET, exercise = state.exercise))
        out.play(Clips.exercise(state.exercise))
    }

    private fun showSummary(summary: RcMessage.Summary) {
        summarySerial++
        update { it.copy(screen = Screen.SUMMARY, summary = summary, awaitingSummary = false, ghost = null) }
        out.play(Clips.SET_COMPLETE)
    }

    private fun localSummary(): RcMessage.Summary {
        val p = state.solo ?: PlayerView(0, "")
        val result = SetResult(state.exercise, p.reps, p.clean, p.faultCounts, ((clock() - setStartedAt) / 1_000).toInt())
        return RcMessage.Summary(p.reps, p.clean, p.faultCounts, result.durationSec, Coaching.fallbackText(result), state.exercise)
    }

    private fun onRep(slot: Int, rep: RcMessage.Rep) {
        when (state.screen) {
            Screen.WORKOUT -> {
                if (slot != state.soloSlot) return
                player(slot) { it.counted(rep.clean) }
                out.play(Clips.number(rep.number))
            }
            Screen.DUEL -> {
                player(slot) { it.counted(rep.clean) }
                val outcome = duel.onRep(slot, rep.clean)
                update { it.copy(duelLeader = duel.leader) }
                if (outcome != null) {
                    endDuel(outcome)
                } else if (rep.clean && duel.leader == slot) {
                    out.play(Clips.number(duel.cleanReps(slot)))
                }
            }
            else -> Unit
        }
    }

    private fun onFault(slot: Int, kind: FaultKind) {
        val counting = (state.screen == Screen.WORKOUT && slot == state.soloSlot) || state.screen == Screen.DUEL
        if (!counting) return
        val cue = Coaching.cue(kind)
        var serial = 0
        player(slot) { p ->
            serial = p.faultSerial + 1
            p.copy(faultCue = cue, faultSerial = serial, faultCounts = p.faultCounts + (kind to (p.faultCounts[kind] ?: 0) + 1))
        }
        if (cues.allow("$slot:$kind", clock())) out.play(Clips.cue(kind))
        scheduler.after(FAULT_BANNER_MS) {
            player(slot) { p -> if (p.faultSerial == serial) p.copy(faultCue = null) else p }
        }
    }

    private fun beginDuel() {
        val now = clock()
        duel.start(now)
        cues.clear()
        update { s ->
            s.copy(
                screen = Screen.DUEL,
                countdown = null,
                duelEndsAt = now + DUEL_LIMIT_MS,
                duelLeader = null,
                players = s.players.mapValues { (_, p) -> p.resetCounts() },
            )
        }
        // start both phones at the same time
        val start = RcMessage.Control(ControlAction.START_SET, exercise = state.exercise, autoEnd = false)
        state.players.keys.forEach { out.send(it, start) }
    }

    private fun endDuel(outcome: DuelOutcome) {
        if (state.screen != Screen.DUEL) return
        update { it.copy(screen = Screen.DUEL_END, duelOutcome = outcome, duelEndsAt = null) }
        state.players.keys.forEach { out.send(it, RcMessage.Control(ControlAction.END_SET)) }
        out.play(Clips.winner(outcome.winner))
    }

    private fun update(change: (TvState) -> TvState) {
        state = change(state)
    }

    private fun player(slot: Int, change: (PlayerView) -> PlayerView) = update { s ->
        val p = s.players[slot] ?: return@update s
        s.copy(players = s.players + (slot to change(p)))
    }

    private fun PlayerView.counted(isClean: Boolean) = copy(
        reps = reps + 1,
        clean = if (isClean) clean + 1 else clean,
        flagged = if (isClean) flagged else flagged + 1,
        repSerial = repSerial + 1,
    )

    private fun PlayerView.resetCounts() =
        copy(reps = 0, clean = 0, flagged = 0, faultCounts = emptyMap(), faultCue = null, repSerial = 0)

    companion object {
        const val DUEL_TARGET = 10
        const val DUEL_LIMIT_MS = 60_000L
        const val FAULT_BANNER_MS = 2_000L
        const val POSE_STALE_MS = 1_000L
        const val SUMMARY_WAIT_MS = 8_000L
        private val DUEL_SCREENS = setOf(Screen.DUEL_LOBBY, Screen.COUNTDOWN, Screen.DUEL, Screen.DUEL_END)
    }
}

// clip names, the files are in res raw
object Clips {
    const val STEP_BACK = "step_back"
    const val SET_COMPLETE = "set_complete"
    const val NICE_REP = "nice_rep"
    const val COUNTDOWN_GO = "countdown_go"

    val ALL = (1..20).map { "n_$it" } + listOf(
        "knees_out", "go_lower", "chest_up", NICE_REP, SET_COMPLETE, STEP_BACK, "hips_up", "hips_down",
        "squats", "push_ups", "player_one", "player_two", "player_one_wins", "player_two_wins", COUNTDOWN_GO,
    )

    fun number(n: Int) = if (n in 1..20) "n_$n" else NICE_REP

    fun cue(kind: FaultKind) = when (kind) {
        FaultKind.KNEES_CAVE -> "knees_out"
        FaultKind.CHEST_DOWN -> "chest_up"
        FaultKind.SHALLOW -> "go_lower"
        FaultKind.HIPS_SAG -> "hips_up"
        FaultKind.HIPS_PIKE -> "hips_down"
    }

    fun exercise(exercise: Exercise) = when (exercise) {
        Exercise.SQUAT -> "squats"
        Exercise.PUSHUP -> "push_ups"
    }

    fun winner(winner: Int?) = when (winner) {
        1 -> "player_one_wins"
        2 -> "player_two_wins"
        else -> SET_COMPLETE
    }
}
