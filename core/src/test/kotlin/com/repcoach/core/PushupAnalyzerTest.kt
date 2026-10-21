package com.repcoach.core

import com.repcoach.core.AnalyzerEvent.FaultRaised
import com.repcoach.core.AnalyzerEvent.RepCounted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PushupAnalyzerTest {
    private val analyzer = PushupAnalyzer()
    private var t = 0L
    private val events = mutableListOf<AnalyzerEvent>()

    private fun feed(angles: List<Float>, hipDrop: Float = 0f, rightSide: Boolean = false) = angles.forEach {
        events += analyzer.analyze(Synth.pushupFrame(it, hipDrop, rightSide), t)
        t += Synth.FRAME_MS
    }

    private fun pushup(bottom: Float = 80f) = Synth.repAngles(top = 170f, bottom = bottom)

    @Test
    fun `ten clean push-ups count ten`() {
        feed(Synth.standing(30, 170f))
        repeat(10) { feed(pushup()) }
        val reps = events.filterIsInstance<RepCounted>()
        assertEquals((1..10).toList(), reps.map { it.number })
        assertTrue(reps.all { it.clean })
        assertEquals(Exercise.PUSHUP, analyzer.exercise)
    }

    @Test
    fun `shallow push-up raises shallow and is not counted`() {
        feed(Synth.standing(30, 170f))
        feed(pushup(bottom = 110f))
        assertEquals(0, analyzer.reps)
        assertEquals(listOf(FaultRaised(FaultKind.SHALLOW)), events.filterIsInstance<FaultRaised>())
    }

    @Test
    fun `sagging hips raise hips sag on that rep`() {
        feed(Synth.standing(30, 170f))
        feed(pushup())
        feed(pushup(), hipDrop = 0.1f)
        feed(pushup())
        val reps = events.filterIsInstance<RepCounted>()
        assertEquals(listOf(true, false, true), reps.map { it.clean })
        assertEquals(listOf(FaultKind.HIPS_SAG), reps[1].faults)
    }

    @Test
    fun `piked hips raise hips pike`() {
        feed(Synth.standing(30, 170f))
        feed(pushup(), hipDrop = -0.1f)
        assertEquals(listOf(FaultRaised(FaultKind.HIPS_PIKE)), events.filterIsInstance<FaultRaised>())
    }

    @Test
    fun `works from the right side too`() {
        feed(Synth.standing(30, 170f), rightSide = true)
        repeat(3) { feed(pushup(), rightSide = true) }
        assertEquals(3, analyzer.reps)
        assertTrue(analyzer.setupReady)
    }

    @Test
    fun `setup is not ready when the wrist is hidden`() {
        repeat(40) {
            val frame = Synth.pushupFrame(170f)
            val world = frame.world.toMutableList().also { w -> w[Lm.LEFT_WRIST] = w[Lm.LEFT_WRIST].copy(visibility = 0.2f) }
            analyzer.analyze(frame.copy(world = world), it * 33L)
        }
        assertFalse(analyzer.setupReady)
    }

    @Test
    fun `factory makes the right analyzer`() {
        assertEquals(Exercise.SQUAT, ExerciseAnalyzer.create(Exercise.SQUAT).exercise)
        assertEquals(Exercise.PUSHUP, ExerciseAnalyzer.create(Exercise.PUSHUP).exercise)
    }

    @Test
    fun `rep start fires once per rep and min angle reflects depth`() {
        feed(Synth.standing(30, 170f))
        feed(pushup(bottom = 85f))
        feed(pushup(bottom = 70f))
        assertEquals(2, events.count { it is AnalyzerEvent.RepStarted })
        val (first, second) = events.filterIsInstance<RepCounted>()
        assertTrue(second.minAngle < first.minAngle)
    }
}
