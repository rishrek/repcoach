package com.repcoach.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RepCounterTest {
    private val config = RepConfig()

    private fun run(angles: List<Float>, counter: RepCounter = RepCounter(config)): List<RepStep> =
        angles.mapIndexedNotNull { i, angle -> counter.update(angle, i * Synth.FRAME_MS) }

    private fun counted(steps: List<RepStep>) = steps.count { it == RepStep.WentUp(counted = true) }

    @Test
    fun `ten clean reps count ten`() {
        val angles = Synth.standing(10) + List(10) { Synth.repAngles() }.flatten()
        val steps = run(angles)
        assertEquals(10, counted(steps))
        assertTrue(steps.none { it == RepStep.Shallow })
    }

    @Test
    fun `three shallow reps count zero and flag three`() {
        val angles = Synth.standing(10) + List(3) { Synth.repAngles(bottom = 125f) }.flatten()
        val steps = run(angles)
        assertEquals(0, counted(steps))
        assertEquals(3, steps.count { it == RepStep.Shallow })
    }

    @Test
    fun `jitter around the down threshold counts one rep`() {
        val jitter = List(20) { i -> if (i % 2 == 0) 99f else 101f }
        val steps = run(Synth.standing(5) + jitter + Synth.standing(5))
        assertEquals(listOf(RepStep.WentDown, RepStep.WentUp(counted = true)), steps)
    }

    @Test
    fun `jitter around the up threshold counts one rep`() {
        val jitter = List(20) { i -> if (i % 2 == 0) 159f else 161f }
        val steps = run(Synth.standing(5) + List(5) { 90f } + jitter)
        assertEquals(1, counted(steps))
    }

    @Test
    fun `a rep closer than minRepMs to the previous one is not counted`() {
        val counter = RepCounter(config)
        val results = listOf(
            170f to 0L, 90f to 100L, 170f to 200L, // first rep
            90f to 300L, 170f to 400L, // only 200 ms later so too soon
            90f to 900L, 170f to 1_000L, // 800 ms after the first one so it counts
        ).mapNotNull { (angle, t) -> counter.update(angle, t) }
        val ups = results.filterIsInstance<RepStep.WentUp>().map { it.counted }
        assertEquals(listOf(true, false, true), ups)
    }

    @Test
    fun `exact threshold values do not change state`() {
        val counter = RepCounter(config)
        assertEquals(null, counter.update(100f, 0))
        assertEquals(RepState.UP, counter.state)
        counter.update(99f, 33)
        assertEquals(null, counter.update(160f, 66))
        assertEquals(RepState.DOWN, counter.state)
    }

    @Test
    fun `reset returns to UP and forgets the last rep time`() {
        val counter = RepCounter(config)
        counter.update(90f, 0)
        counter.update(170f, 100)
        counter.update(90f, 200)
        counter.reset()
        assertEquals(RepState.UP, counter.state)
        counter.update(90f, 300)
        assertEquals(RepStep.WentUp(counted = true), counter.update(170f, 400))
    }
}

class FormRulesTest {
    private fun knees(caved: Boolean) = mapOf(FaultKind.KNEES_CAVE to caved, FaultKind.CHEST_DOWN to false)
    private fun chest(down: Boolean) = mapOf(FaultKind.KNEES_CAVE to false, FaultKind.CHEST_DOWN to down)

    @Test
    fun `fault fires on the third bad frame in a row`() {
        val rules = FormRules(3)
        assertEquals(emptyList<FaultKind>(), rules.update(knees(true)))
        assertEquals(emptyList<FaultKind>(), rules.update(knees(true)))
        assertEquals(listOf(FaultKind.KNEES_CAVE), rules.update(knees(true)))
    }

    @Test
    fun `fault fires once per rep`() {
        val rules = FormRules(3)
        val fired = List(10) { rules.update(chest(true)) }.flatten()
        assertEquals(listOf(FaultKind.CHEST_DOWN), fired)
        assertEquals(listOf(FaultKind.CHEST_DOWN), rules.firedThisRep)
    }

    @Test
    fun `a good frame breaks the streak`() {
        val rules = FormRules(3)
        rules.update(knees(true))
        rules.update(knees(true))
        rules.update(knees(false))
        rules.update(knees(true))
        assertEquals(emptyList<FaultKind>(), rules.update(knees(true)))
    }

    @Test
    fun `a new rep can fire the same fault again`() {
        val rules = FormRules(3)
        repeat(3) { rules.update(knees(true)) }
        rules.startRep()
        assertEquals(emptyList<FaultKind>(), rules.firedThisRep)
        val fired = List(3) { rules.update(knees(true)) }.flatten()
        assertEquals(listOf(FaultKind.KNEES_CAVE), fired)
    }

    @Test
    fun `nothing holding never fires`() {
        val rules = FormRules(3)
        assertTrue(List(10) { rules.update(knees(false)) }.flatten().isEmpty())
    }
}

class SetupCheckTest {
    @Test
    fun `ready after 30 visible frames, not 29`() {
        val check = SetupCheck(30, 15)
        repeat(29) { assertEquals(null, check.update(true)) }
        assertEquals(true, check.update(true))
        assertTrue(check.ready)
    }

    @Test
    fun `stays ready through 14 bad frames and drops on the 15th`() {
        val check = SetupCheck(30, 15)
        repeat(30) { check.update(true) }
        repeat(14) { assertEquals(null, check.update(false)) }
        assertEquals(false, check.update(false))
    }

    @Test
    fun `one bad frame restarts the ready count`() {
        val check = SetupCheck(30, 15)
        repeat(29) { check.update(true) }
        check.update(false)
        repeat(29) { check.update(true) }
        assertEquals(false, check.ready)
        assertEquals(true, check.update(true))
    }
}
