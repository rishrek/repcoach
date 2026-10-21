package com.repcoach.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoachingTest {
    private fun sentences(text: String) = Regex("""[.!?](\s|$)""").findAll(text).count()

    @Test
    fun `every fault has a cue`() {
        assertEquals(
            listOf("Knees out", "Chest up", "Go lower", "Hips up", "Hips down"),
            FaultKind.entries.map(Coaching::cue),
        )
    }

    @Test
    fun `most common fault picks the highest count, ties by order`() {
        assertEquals(FaultKind.CHEST_DOWN, Coaching.mostCommonFault(mapOf(FaultKind.KNEES_CAVE to 1, FaultKind.CHEST_DOWN to 3)))
        assertEquals(FaultKind.KNEES_CAVE, Coaching.mostCommonFault(mapOf(FaultKind.CHEST_DOWN to 2, FaultKind.KNEES_CAVE to 2)))
        assertNull(Coaching.mostCommonFault(mapOf(FaultKind.SHALLOW to 0)))
        assertNull(Coaching.mostCommonFault(emptyMap()))
    }

    @Test
    fun `fallback text is always three sentences`() {
        val clean = SetResult(Exercise.SQUAT, 10, 10, emptyMap(), 40)
        val faulty = SetResult(Exercise.PUSHUP, 8, 5, mapOf(FaultKind.HIPS_SAG to 3), 30)
        val empty = SetResult(Exercise.SQUAT, 0, 0, emptyMap(), 9)
        listOf(clean, faulty, empty).forEach { assertEquals(3, sentences(Coaching.fallbackText(it)), Coaching.fallbackText(it)) }
        assertTrue(Coaching.fallbackText(clean).startsWith("You did 10 squats and 10 were clean."))
        assertTrue("hips up" in Coaching.fallbackText(faulty))
        assertTrue(Coaching.fallbackText(empty).startsWith("No reps"))
    }

    @Test
    fun `exercise text`() {
        assertEquals("Squats", ExerciseText.label(Exercise.SQUAT))
        assertEquals("Push-ups", ExerciseText.label(Exercise.PUSHUP))
        assertEquals("1 push-up", ExerciseText.count(Exercise.PUSHUP, 1))
        assertEquals("3 squats", ExerciseText.count(Exercise.SQUAT, 3))
        assertTrue("hip height" in ExerciseText.placementHint(Exercise.SQUAT))
        assertTrue("floor" in ExerciseText.placementHint(Exercise.PUSHUP))
        assertTrue(ExerciseText.notReady(Exercise.SQUAT).startsWith("Step back"))
    }

    @Test
    fun `summary request mirrors the set result`() {
        val result = SetResult(Exercise.SQUAT, 4, 3, mapOf(FaultKind.SHALLOW to 1), 20)
        assertEquals(SummaryRequest(Exercise.SQUAT, 4, 3, mapOf(FaultKind.SHALLOW to 1), 20), result.toRequest())
    }

    @Test
    fun `cue gate allows each cue once per three seconds`() {
        val gate = CueGate()
        assertTrue(gate.allow("knees_out", 0))
        assertFalse(gate.allow("knees_out", 2_999))
        assertTrue(gate.allow("chest_up", 100))
        assertTrue(gate.allow("knees_out", 3_000))
        gate.clear()
        assertTrue(gate.allow("knees_out", 3_001))
    }
}

class DuelTest {
    @Test
    fun `first to ten clean reps wins and flagged reps don't count`() {
        val duel = DuelMatch()
        duel.start(0)
        repeat(5) { duel.onRep(2, isClean = false) }
        repeat(9) { assertNull(duel.onRep(1, isClean = true)) }
        repeat(9) { duel.onRep(2, isClean = true) }
        assertEquals(1, duel.leader ?: 1)
        val outcome = duel.onRep(2, isClean = true)
        assertEquals(DuelOutcome(winner = 2, clean = listOf(9, 10), flagged = listOf(0, 5)), outcome)
        assertFalse(duel.running)
        assertNull(duel.onRep(1, isClean = true)) // match is already over
    }

    @Test
    fun `at the time limit most clean reps wins`() {
        val duel = DuelMatch()
        duel.start(1_000)
        repeat(4) { duel.onRep(1, isClean = true) }
        repeat(6) { duel.onRep(2, isClean = true) }
        assertNull(duel.tick(60_999))
        assertEquals(2, duel.tick(61_000)?.winner)
    }

    @Test
    fun `equal clean reps at the limit is a tie`() {
        val duel = DuelMatch(limitMs = 10_000)
        duel.start(0)
        duel.onRep(1, isClean = true)
        duel.onRep(2, isClean = true)
        val outcome = duel.tick(10_000)!!
        assertNull(outcome.winner)
        assertNull(duel.leader)
    }

    @Test
    fun `reps before the start are ignored and time counts down`() {
        val duel = DuelMatch()
        assertNull(duel.onRep(1, isClean = true))
        duel.start(0)
        assertEquals(0, duel.cleanReps(1))
        assertEquals(45_000, duel.remainingMs(15_000))
        assertEquals(0, duel.remainingMs(90_000))
    }
}
