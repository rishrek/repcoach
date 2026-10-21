package com.repcoach.core

import kotlinx.serialization.Serializable

// what we post to the lambda
@Serializable
data class SummaryRequest(
    val exercise: Exercise,
    val reps: Int,
    val cleanReps: Int,
    val faultCounts: Map<FaultKind, Int>,
    val durationSec: Int,
)

// what the lambda sends back
@Serializable
data class SummaryResponse(val aiText: String)

fun SetResult.toRequest() = SummaryRequest(exercise, reps, cleanReps, faultCounts, durationSec)

// text we show for each exercise
object ExerciseText {
    fun label(exercise: Exercise) = when (exercise) {
        Exercise.SQUAT -> "Squats"
        Exercise.PUSHUP -> "Push-ups"
    }

    fun placementHint(exercise: Exercise) = when (exercise) {
        Exercise.SQUAT -> "Phone at hip height, 2 to 3 m away, about 30 degrees to your side."
        Exercise.PUSHUP -> "Phone on the floor, 1 to 1.5 m away, side view, whole body in frame."
    }

    fun notReady(exercise: Exercise) = when (exercise) {
        Exercise.SQUAT -> "Step back so I can see your whole body"
        Exercise.PUSHUP -> "Get side on to the phone, whole body in view"
    }

    private fun noun(exercise: Exercise, count: Int) = when (exercise) {
        Exercise.SQUAT -> if (count == 1) "squat" else "squats"
        Exercise.PUSHUP -> if (count == 1) "push-up" else "push-ups"
    }

    fun count(exercise: Exercise, count: Int) = "$count ${noun(exercise, count)}"
}

// cue words plus the summary we fall back to when bedrock isn't reachable
object Coaching {
    // short text for the red banner on the TV
    fun cue(kind: FaultKind) = when (kind) {
        FaultKind.KNEES_CAVE -> "Knees out"
        FaultKind.CHEST_DOWN -> "Chest up"
        FaultKind.SHALLOW -> "Go lower"
        FaultKind.HIPS_SAG -> "Hips up"
        FaultKind.HIPS_PIKE -> "Hips down"
    }

    // highest count wins, ties go to whichever fault is listed first
    fun mostCommonFault(counts: Map<FaultKind, Int>): FaultKind? =
        FaultKind.entries.filter { (counts[it] ?: 0) > 0 }.maxByOrNull { counts[it] ?: 0 }

    // three sentences, same as what we ask bedrock for
    fun fallbackText(result: SetResult): String {
        val fault = mostCommonFault(result.faultCounts)
        val first = if (result.reps == 0) {
            "No reps were counted this set."
        } else {
            "You did ${ExerciseText.count(result.exercise, result.reps)} and ${result.cleanReps} were clean."
        }
        val second = when {
            fault != null -> fix(fault)
            result.reps == 0 -> "Make sure the phone can see your whole body."
            else -> "Your form held up well, so keep the same steady tempo."
        }
        val third = if (fault != null) "Next set, think \"${cue(fault).lowercase()}\" on every rep." else "Next set, aim for one more clean rep."
        return "$first $second $third"
    }

    private fun fix(kind: FaultKind) = when (kind) {
        FaultKind.KNEES_CAVE -> "The main fix is your knees: keep them pushed out over your toes."
        FaultKind.CHEST_DOWN -> "The main fix is your chest: keep it up as you go down."
        FaultKind.SHALLOW -> "The main fix is depth: go a little lower on every rep."
        FaultKind.HIPS_SAG -> "The main fix is your hips: squeeze your glutes so they stay in line."
        FaultKind.HIPS_PIKE -> "The main fix is your hips: lower them into one straight line."
    }
}

// stops the same cue from being spoken again within 3 seconds
class CueGate(private val minGapMs: Long = 3_000) {
    private val last = mutableMapOf<String, Long>()

    fun allow(key: String, nowMs: Long): Boolean {
        val previous = last[key]
        if (previous != null && nowMs - previous < minGapMs) return false
        last[key] = nowMs
        return true
    }

    fun clear() = last.clear()
}
