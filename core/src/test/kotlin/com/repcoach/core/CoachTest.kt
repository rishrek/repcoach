package com.repcoach.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoachTest {
    private val coach = Coach()
    private var t = 0L
    private val sent = mutableListOf<RcMessage>()
    private val finished = mutableListOf<SetResult>()

    private fun take(output: Coach.Output) {
        sent += output.messages
        output.finished?.let { finished += it }
    }

    private fun feed(angles: List<Float>, kneeRatio: Float = 1f) = angles.forEach {
        take(coach.onFrame(t, Synth.frame(it, kneeRatio = kneeRatio)))
        t += Synth.FRAME_MS
    }

    private fun control(action: ControlAction, autoEnd: Boolean = true, exercise: Exercise? = null) =
        take(coach.onMessage(RcMessage.Control(action, exercise, autoEnd), t))

    private inline fun <reified M : RcMessage> sentOf() = sent.filterIsInstance<M>()

    private fun readyAndStarted(autoEnd: Boolean = true) {
        feed(Synth.standing(30))
        control(ControlAction.START_SET, autoEnd)
    }

    @Test
    fun `status goes out when setup changes, before any set`() {
        feed(Synth.standing(30))
        assertEquals(listOf(true), sentOf<RcMessage.Status>().map { it.setupOk })
        assertFalse(coach.active)
    }

    @Test
    fun `no reps are sent before START_SET`() {
        feed(Synth.standing(30))
        repeat(2) { feed(Synth.repAngles()) }
        assertEquals(emptyList<RcMessage.Rep>(), sentOf<RcMessage.Rep>())
    }

    @Test
    fun `a started set sends reps, faults and rep starts`() {
        readyAndStarted()
        feed(Synth.repAngles())
        feed(Synth.repAngles(), kneeRatio = 0.6f)
        assertEquals(listOf(1, 2), sentOf<RcMessage.Rep>().map { it.number })
        assertEquals(listOf(true, false), sentOf<RcMessage.Rep>().map { it.clean })
        assertEquals(listOf(FaultKind.KNEES_CAVE), sentOf<RcMessage.Fault>().map { it.kind })
        assertEquals(2, sentOf<RcMessage.RepStart>().size)
    }

    @Test
    fun `poses go out at most 15 per second and are rounded`() {
        feed(Synth.standing(60)) // two seconds of frames
        val poses = sentOf<RcMessage.Pose>()
        assertTrue(poses.size in 29..31, "sent ${poses.size}")
        assertTrue(poses.first().points.flatten().all { round3(it) == it })
    }

    @Test
    fun `hello sets the player id, which tags everything after`() {
        take(coach.onMessage(RcMessage.Hello("P2"), t))
        assertEquals("P2", sent.single().playerId) // the status it replies with
        readyAndStarted()
        feed(Synth.repAngles())
        assertTrue(sent.all { it.playerId == "P2" })
    }

    @Test
    fun `set ends by itself after 8 idle seconds`() {
        readyAndStarted()
        repeat(3) { feed(Synth.repAngles()) }
        feed(Synth.standing(300))
        val result = finished.single()
        assertEquals(3, result.reps)
        assertEquals(3, result.cleanReps)
        assertEquals(Exercise.SQUAT, result.exercise)
        assertTrue(result.durationSec in 11..13, "duration ${result.durationSec}")
        assertFalse(coach.active)
    }

    @Test
    fun `duel sets don't end by themselves, only on END_SET`() {
        readyAndStarted(autoEnd = false)
        feed(Synth.repAngles())
        feed(Synth.standing(300))
        assertEquals(emptyList<SetResult>(), finished)
        control(ControlAction.END_SET)
        assertEquals(1, finished.single().reps)
    }

    @Test
    fun `END_SET when no set is running does nothing`() {
        control(ControlAction.END_SET)
        assertEquals(emptyList<SetResult>(), finished)
    }

    @Test
    fun `reset stops the set and resends status`() {
        readyAndStarted()
        feed(Synth.repAngles())
        sent.clear()
        control(ControlAction.RESET)
        assertFalse(coach.active)
        assertEquals(0, coach.analyzer.reps)
        assertEquals(true, (sent.single() as RcMessage.Status).setupOk)
    }

    @Test
    fun `ghost is sent for the first clean rep and for each deeper one only`() {
        readyAndStarted()
        feed(Synth.repAngles(bottom = 92f))
        feed(Synth.repAngles(bottom = 80f))
        feed(Synth.repAngles(bottom = 92f))
        assertEquals(3, sentOf<RcMessage.Rep>().size)
        val ghosts = sentOf<RcMessage.Ghost>()
        assertEquals(2, ghosts.size)
        ghosts.forEach { ghost ->
            assertTrue(ghost.frames.size in 2..Coach.MAX_GHOST_FRAMES)
            assertEquals(0L, ghost.frames.first().t)
            assertTrue(ghost.frames.zipWithNext().all { (a, b) -> b.t > a.t })
        }
    }

    @Test
    fun `flagged reps never become the ghost`() {
        readyAndStarted()
        feed(Synth.repAngles(), kneeRatio = 0.6f)
        assertEquals(emptyList<RcMessage.Ghost>(), sentOf<RcMessage.Ghost>())
    }

    @Test
    fun `picking push-ups on the phone switches and tells the TV`() {
        feed(Synth.standing(30))
        sent.clear()
        take(coach.selectExercise(Exercise.PUSHUP))
        assertEquals(Exercise.PUSHUP, coach.analyzer.exercise)
        val control = sentOf<RcMessage.Control>().single()
        assertEquals(ControlAction.SET_EXERCISE to Exercise.PUSHUP, control.action to control.exercise)
        val status = sentOf<RcMessage.Status>().single()
        assertEquals(false, status.setupOk)
        assertEquals(Exercise.PUSHUP, status.exercise)
    }

    @Test
    fun `START_SET can carry the exercise`() {
        control(ControlAction.START_SET, exercise = Exercise.PUSHUP)
        assertEquals(Exercise.PUSHUP, coach.exercise)
        assertTrue(coach.active)
    }

    @Test
    fun `room full is remembered until a hello arrives`() {
        take(coach.onMessage(RcMessage.RoomFull("full"), t))
        assertEquals("full", coach.roomFull)
        take(coach.onMessage(RcMessage.Hello("P1"), t))
        assertNull(coach.roomFull)
    }

    @Test
    fun `summary message carries the result and player`() {
        take(coach.onMessage(RcMessage.Hello("P1"), t))
        val result = SetResult(Exercise.PUSHUP, 5, 4, mapOf(FaultKind.HIPS_SAG to 1), 30)
        val summary = coach.summary(result, "Nice.") as RcMessage.Summary
        assertEquals(RcMessage.Summary(5, 4, mapOf(FaultKind.HIPS_SAG to 1), 30, "Nice.", Exercise.PUSHUP, "P1"), summary)
    }

    @Test
    fun `downsample keeps first and last and caps the size`() {
        val items = (0 until 200).toList()
        val picked = Coach.downsample(items, 60)
        assertEquals(60, picked.size)
        assertEquals(0, picked.first())
        assertEquals(199, picked.last())
        assertEquals(listOf(1, 2, 3), Coach.downsample(listOf(1, 2, 3), 60))
        assertNotNull(picked.zipWithNext().all { (a, b) -> b > a }.takeIf { it })
    }
}
