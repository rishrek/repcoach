package com.repcoach.core

import com.repcoach.core.AnalyzerEvent.FaultRaised
import com.repcoach.core.AnalyzerEvent.RepCounted
import com.repcoach.core.AnalyzerEvent.SetEnded
import com.repcoach.core.AnalyzerEvent.SetupChanged
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SquatAnalyzerTest {
    // feeds frames 33 ms apart and keeps every event with its time
    private class Session(val analyzer: SquatAnalyzer = SquatAnalyzer()) {
        var t = 0L
        val events = mutableListOf<Pair<Long, AnalyzerEvent>>()

        fun feed(frames: List<PoseFrame>) = frames.forEach { frame ->
            analyzer.analyze(frame, t).forEach { events += t to it }
            t += Synth.FRAME_MS
        }

        fun feedAngles(angles: List<Float>, kneeRatio: Float = 1f, lean: Float = 10f) =
            feed(angles.map { Synth.frame(it, kneeRatio = kneeRatio, lean = lean) })

        // 30 standing frames so setup is ready
        fun getReady() = feedAngles(Synth.standing(30))

        inline fun <reified E : AnalyzerEvent> all(): List<E> = events.map { it.second }.filterIsInstance<E>()
    }

    @Test
    fun `setup becomes ready after 30 frames and reps before that are ignored`() {
        val s = Session()
        s.feedAngles(Synth.repAngles().take(29))
        assertEquals(emptyList<RepCounted>(), s.all<RepCounted>())
        s.feedAngles(Synth.standing(1))
        assertEquals(listOf(SetupChanged(true)), s.all<SetupChanged>())
    }

    @Test
    fun `ten clean squats give ten clean reps numbered 1 to 10`() {
        val s = Session()
        s.getReady()
        repeat(10) { s.feedAngles(Synth.repAngles()) }
        val reps = s.all<RepCounted>()
        assertEquals((1..10).toList(), reps.map { it.number })
        assertTrue(reps.all { it.clean })
        assertEquals(10, s.analyzer.cleanReps)
        assertEquals(emptyList<FaultRaised>(), s.all<FaultRaised>())
    }

    @Test
    fun `knee cave on rep 5 flags only rep 5`() {
        val s = Session()
        s.getReady()
        repeat(10) { i -> s.feedAngles(Synth.repAngles(), kneeRatio = if (i == 4) 0.6f else 1f) }
        val reps = s.all<RepCounted>()
        assertEquals(10, reps.size)
        assertEquals(listOf(5), reps.filterNot { it.clean }.map { it.number })
        assertEquals(listOf(FaultKind.KNEES_CAVE), reps[4].faults)
        assertEquals(listOf(FaultRaised(FaultKind.KNEES_CAVE)), s.all<FaultRaised>())
        assertEquals(mapOf(FaultKind.KNEES_CAVE to 1), s.analyzer.faultCounts)
    }

    @Test
    fun `knee cave is called within half a second of reaching the bottom`() {
        val s = Session()
        s.getReady()
        s.feedAngles(Synth.standing(5))
        val caveStart = s.t
        s.feedAngles(List(30) { 85f }, kneeRatio = 0.6f)
        val faultAt = s.events.first { it.second == FaultRaised(FaultKind.KNEES_CAVE) }.first
        assertTrue(faultAt - caveStart <= 500, "fault took ${faultAt - caveStart} ms")
    }

    @Test
    fun `leaning forward raises chest down`() {
        val s = Session()
        s.getReady()
        s.feedAngles(Synth.repAngles(), lean = 60f)
        assertEquals(listOf(FaultRaised(FaultKind.CHEST_DOWN)), s.all<FaultRaised>())
        assertEquals(false, s.all<RepCounted>().single().clean)
    }

    @Test
    fun `shallow squat raises shallow and is not counted`() {
        val s = Session()
        s.getReady()
        s.feedAngles(Synth.repAngles(bottom = 125f))
        assertEquals(emptyList<RepCounted>(), s.all<RepCounted>())
        assertEquals(listOf(FaultRaised(FaultKind.SHALLOW)), s.all<FaultRaised>())
    }

    @Test
    fun `set ends once, 8 seconds after the last movement`() {
        val s = Session()
        s.getReady()
        s.feedAngles(Synth.repAngles())
        val lastRepAt = s.events.first { it.second is RepCounted }.first
        s.feedAngles(Synth.standing(400))
        val ended = s.events.filter { it.second is SetEnded }
        assertEquals(1, ended.size)
        assertEquals(SetEnded(reps = 1, cleanReps = 1), ended.single().second)
        assertTrue(ended.single().first - lastRepAt in 8_000L..8_100L)
    }

    @Test
    fun `set does not end without a rep`() {
        val s = Session()
        s.getReady()
        s.feedAngles(Synth.standing(400))
        assertEquals(emptyList<SetEnded>(), s.all<SetEnded>())
    }

    @Test
    fun `frames with no visible leg are skipped without losing the rep`() {
        val s = Session()
        s.getReady()
        s.feedAngles(Synth.repAngles().take(15))
        s.feed(List(5) { Synth.frame(85f, leftVisible = false, rightVisible = false) })
        s.feedAngles(Synth.repAngles().drop(15))
        assertEquals(1, s.all<RepCounted>().size)
    }

    @Test
    fun `reset clears the set but keeps setup ready`() {
        val s = Session()
        s.getReady()
        repeat(3) { s.feedAngles(Synth.repAngles()) }
        s.analyzer.reset()
        assertEquals(0, s.analyzer.reps)
        assertEquals(RepState.UP, s.analyzer.state)
        assertTrue(s.analyzer.setupReady)
        s.events.clear()
        s.feedAngles(Synth.repAngles())
        assertEquals(listOf(1), s.all<RepCounted>().map { it.number })
    }
}
