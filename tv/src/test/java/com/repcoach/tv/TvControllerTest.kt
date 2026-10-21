package com.repcoach.tv

import com.repcoach.core.ControlAction
import com.repcoach.core.Exercise
import com.repcoach.core.FaultKind
import com.repcoach.core.GhostFrame
import com.repcoach.core.RcMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// runs the whole TV flow with a fake clock so no TV is needed
class TvControllerTest {
    private var now = 0L
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private val sent = mutableListOf<Pair<Int, RcMessage>>()
    private val played = mutableListOf<String>()

    private val tv = TvController(
        out = object : TvOutput {
            override fun send(slot: Int, message: RcMessage) {
                sent += slot to message
            }

            override fun play(clip: String) {
                played += clip
            }
        },
        scheduler = { delayMs, action -> timers += (now + delayMs) to action },
        clock = { now },
    )

    private fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = timers.filter { it.first <= end }.minByOrNull { it.first } ?: break
            timers.remove(next)
            now = next.first
            next.second()
        }
        now = end
    }

    private fun controls(slot: Int) = sent.filter { it.first == slot }.map { it.second }.filterIsInstance<RcMessage.Control>().map { it.action }
    private fun ready(slot: Int, ok: Boolean = true) = tv.onMessage(slot, RcMessage.Status(ok, if (ok) "Ready" else "Step back"))
    private fun rep(slot: Int, n: Int, clean: Boolean = true) = tv.onMessage(slot, RcMessage.Rep(n, clean))

    private fun soloSetRunning() {
        tv.onJoined(1, "192.168.1.10")
        ready(1)
    }

    @Test
    fun `a phone joining gets hello and the exercise, and the TV shows setup`() {
        tv.onJoined(1, "192.168.1.10")
        assertEquals(RcMessage.Hello("P1"), sent[0].second)
        assertEquals(RcMessage.Control(ControlAction.SET_EXERCISE, exercise = Exercise.SQUAT), sent[1].second)
        assertEquals(Screen.SETUP, tv.state.screen)
        assertEquals(listOf("step_back"), played)
    }

    @Test
    fun `setup ok starts the solo set`() {
        soloSetRunning()
        assertEquals(Screen.WORKOUT, tv.state.screen)
        val start = sent.map { it.second }.filterIsInstance<RcMessage.Control>().last()
        assertEquals(RcMessage.Control(ControlAction.START_SET, exercise = Exercise.SQUAT, autoEnd = true), start)
        assertEquals("squats", played.last())
    }

    @Test
    fun `not ready keeps the setup screen`() {
        tv.onJoined(1, "a")
        ready(1, ok = false)
        assertEquals(Screen.SETUP, tv.state.screen)
    }

    @Test
    fun `reps are counted and spoken`() {
        soloSetRunning()
        rep(1, 1)
        rep(1, 2, clean = false)
        val p = tv.state.solo!!
        assertEquals(listOf(2, 1, 1), listOf(p.reps, p.clean, p.flagged))
        assertEquals(listOf("n_1", "n_2"), played.takeLast(2))
        rep(1, 21)
        assertEquals("nice_rep", played.last())
    }

    @Test
    fun `fault shows a red banner for 2 seconds and the cue plays at most every 3 seconds`() {
        soloSetRunning()
        tv.onMessage(1, RcMessage.Fault(FaultKind.KNEES_CAVE))
        assertEquals("Knees out", tv.state.solo!!.faultCue)
        assertEquals("knees_out", played.last())
        advance(1_000)
        tv.onMessage(1, RcMessage.Fault(FaultKind.KNEES_CAVE))
        assertEquals(1, played.count { it == "knees_out" })
        advance(1_999)
        assertEquals("Knees out", tv.state.solo!!.faultCue) // second fault restarted the banner
        advance(1)
        assertNull(tv.state.solo!!.faultCue)
        advance(1_000)
        tv.onMessage(1, RcMessage.Fault(FaultKind.KNEES_CAVE))
        assertEquals(2, played.count { it == "knees_out" })
    }

    @Test
    fun `the phone's summary shows the summary screen`() {
        soloSetRunning()
        val summary = RcMessage.Summary(10, 9, mapOf(FaultKind.KNEES_CAVE to 1), 40, "Good set.")
        tv.onMessage(1, summary)
        assertEquals(Screen.SUMMARY, tv.state.screen)
        assertEquals(summary, tv.state.summary)
        assertEquals("set_complete", played.last())
    }

    @Test
    fun `select ends the set and the TV builds a summary if the phone never answers`() {
        soloSetRunning()
        advance(20_000)
        rep(1, 1)
        tv.onMessage(1, RcMessage.Fault(FaultKind.SHALLOW))
        tv.select()
        assertEquals(ControlAction.END_SET, controls(1).last())
        assertTrue(tv.state.awaitingSummary)
        advance(TvController.SUMMARY_WAIT_MS)
        val summary = tv.state.summary!!
        assertEquals(Screen.SUMMARY, tv.state.screen)
        assertEquals(1, summary.reps)
        assertEquals(mapOf(FaultKind.SHALLOW to 1), summary.faultCounts)
        assertTrue(summary.aiText.startsWith("You did 1 squat"))
    }

    @Test
    fun `a late phone summary still wins over the fallback timer`() {
        soloSetRunning()
        tv.select()
        advance(3_000)
        tv.onMessage(1, RcMessage.Summary(3, 3, emptyMap(), 30, "From Bedrock."))
        advance(10_000)
        assertEquals("From Bedrock.", tv.state.summary!!.aiText)
    }

    @Test
    fun `new set and done`() {
        soloSetRunning()
        tv.onMessage(1, RcMessage.Summary(1, 1, emptyMap(), 5, "ok"))
        tv.newSet()
        // still in view so the new set starts right away
        assertEquals(listOf(ControlAction.RESET, ControlAction.START_SET), controls(1).takeLast(2))
        assertEquals(Screen.WORKOUT, tv.state.screen)
        tv.done()
        assertEquals(Screen.IDLE, tv.state.screen)
    }

    @Test
    fun `the last phone leaving goes back to waiting`() {
        soloSetRunning()
        tv.onLeft(1)
        assertEquals(Screen.WAITING, tv.state.screen)
        assertTrue(tv.state.players.isEmpty())
    }

    @Test
    fun `exercise picked on one phone is adopted and passed to the other`() {
        tv.onJoined(1, "a")
        tv.onJoined(2, "b")
        sent.clear()
        tv.onMessage(1, RcMessage.Control(ControlAction.SET_EXERCISE, exercise = Exercise.PUSHUP, playerId = "P1"))
        assertEquals(Exercise.PUSHUP, tv.state.exercise)
        assertEquals(listOf(2 to RcMessage.Control(ControlAction.SET_EXERCISE, exercise = Exercise.PUSHUP)), sent)
    }

    @Test
    fun `exercise picked with the remote goes to every phone`() {
        tv.onJoined(1, "a")
        sent.clear()
        tv.chooseExercise(Exercise.PUSHUP)
        assertEquals(listOf(1 to RcMessage.Control(ControlAction.SET_EXERCISE, exercise = Exercise.PUSHUP)), sent)
        assertFalse(tv.state.solo!!.setupOk)
    }

    @Test
    fun `ghost replays from the second rep on`() {
        soloSetRunning()
        val frames = listOf(GhostFrame(0, emptyList()), GhostFrame(500, emptyList()))
        tv.onMessage(1, RcMessage.RepStart(0))
        assertNull(tv.state.ghost) // nothing to replay on the first rep
        rep(1, 1)
        tv.onMessage(1, RcMessage.Ghost(frames))
        tv.onMessage(1, RcMessage.RepStart(2_000))
        assertEquals(frames, tv.state.ghost!!.frames)
    }

    private fun duelRunning() {
        tv.onJoined(1, "a")
        tv.onJoined(2, "b")
        tv.startDuel()
        advance(3_000)
    }

    @Test
    fun `a second phone opens the duel lobby, and the countdown starts both phones together`() {
        tv.onJoined(1, "a")
        tv.onJoined(2, "b")
        assertEquals(Screen.DUEL_LOBBY, tv.state.screen)
        assertEquals(RcMessage.Hello("P2"), sent.first { it.first == 2 }.second)

        tv.startDuel()
        assertEquals(Screen.COUNTDOWN, tv.state.screen)
        assertEquals(3, tv.state.countdown)
        assertEquals("countdown_go", played.last())
        advance(1_000)
        assertEquals(2, tv.state.countdown)
        advance(2_000)
        assertEquals(Screen.DUEL, tv.state.screen)
        val start = RcMessage.Control(ControlAction.START_SET, exercise = Exercise.SQUAT, autoEnd = false)
        assertEquals(listOf(1 to start, 2 to start), sent.takeLast(2))
    }

    @Test
    fun `first to ten clean reps wins and only the leader's numbers are spoken`() {
        duelRunning()
        played.clear()
        rep(1, 1)
        rep(2, 1, clean = false)
        rep(2, 2)
        assertEquals(listOf("n_1"), played) // P2 only drew level so nothing was said
        repeat(10) { rep(2, it + 3) }
        val outcome = tv.state.duelOutcome!!
        assertEquals(Screen.DUEL_END, tv.state.screen)
        assertEquals(2, outcome.winner)
        assertEquals(listOf(1, 10), outcome.clean)
        assertEquals(listOf(0, 1), outcome.flagged)
        assertEquals("player_two_wins", played.last())
        assertEquals(ControlAction.END_SET, controls(1).last())
        assertEquals(ControlAction.END_SET, controls(2).last())
    }

    @Test
    fun `at 60 seconds the duel ends on clean reps`() {
        duelRunning()
        rep(1, 1)
        advance(59_000)
        tv.tick()
        assertEquals(Screen.DUEL, tv.state.screen)
        advance(1_000)
        tv.tick()
        assertEquals(1, tv.state.duelOutcome!!.winner)
        assertEquals("player_one_wins", played.last())
    }

    @Test
    fun `rematch and back to lobby`() {
        duelRunning()
        advance(60_000)
        tv.tick()
        assertNull(tv.state.duelOutcome!!.winner) // nobody scored so it's a tie
        tv.startDuel()
        assertEquals(Screen.COUNTDOWN, tv.state.screen)
        tv.backToLobby()
        assertEquals(Screen.DUEL_LOBBY, tv.state.screen)
    }

    @Test
    fun `a player leaving mid-duel drops the other back to solo setup`() {
        duelRunning()
        tv.onLeft(2)
        assertEquals(ControlAction.RESET, controls(1).last())
        assertEquals(Screen.SETUP, tv.state.screen)
        assertEquals(1, tv.state.soloSlot)
    }

    @Test
    fun `a second phone joining mid-set waits, then the next screen is the lobby`() {
        soloSetRunning()
        tv.onJoined(2, "b")
        assertEquals(Screen.WORKOUT, tv.state.screen)
        rep(2, 1)
        assertEquals(0, tv.state.players[2]!!.reps) // P2 isn't playing
        tv.onMessage(1, RcMessage.Summary(1, 1, emptyMap(), 5, "ok"))
        tv.newSet()
        assertEquals(Screen.DUEL_LOBBY, tv.state.screen)
    }

    @Test
    fun `a phone reconnecting mid-set keeps its count`() {
        soloSetRunning()
        rep(1, 1)
        rep(1, 2)
        tv.onJoined(1, "192.168.1.10") // same slot back after a reconnect
        assertEquals(Screen.WORKOUT, tv.state.screen)
        assertEquals(2, tv.state.solo!!.reps)
        assertEquals(RcMessage.Hello("P1"), sent.map { it.second }.filterIsInstance<RcMessage.Hello>().last())
    }

    @Test
    fun `stale poses disappear and the message rate is tracked`() {
        tv.onJoined(1, "a")
        repeat(15) { tv.onMessage(1, RcMessage.Pose(it.toLong(), emptyList())); advance(66) }
        tv.tick()
        assertNotNull(tv.state.solo!!.pose)
        assertEquals(15f, tv.state.messagesPerSecond, 1f)
        advance(1_500)
        tv.tick()
        assertNull(tv.state.solo!!.pose)
        assertEquals(0f, tv.state.messagesPerSecond, 0f)
    }
}
