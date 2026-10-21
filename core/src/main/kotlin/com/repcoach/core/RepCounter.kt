package com.repcoach.core

enum class RepState { UP, DOWN }

sealed interface RepStep {
    data object WentDown : RepStep

    // counted is false when it came too soon after the last rep
    data class WentUp(val counted: Boolean) : RepStep

    // went part of the way down and came back up
    data object Shallow : RepStep
}

// Going down and coming back up use different angles, so a bit of jitter around
// one of them can't count the same rep twice.
class RepCounter(private val config: CounterConfig) {
    var state = RepState.UP
        private set

    private var dippedPartial = false
    private var lastRepMs: Long? = null

    fun update(angle: Float, timestampMs: Long): RepStep? = when (state) {
        RepState.UP -> when {
            angle < config.downAngle -> {
                state = RepState.DOWN
                dippedPartial = false
                RepStep.WentDown
            }
            angle < config.partialAngle -> {
                dippedPartial = true
                null
            }
            angle > config.upAngle && dippedPartial -> {
                dippedPartial = false
                RepStep.Shallow
            }
            else -> null
        }
        RepState.DOWN -> if (angle > config.upAngle) {
            state = RepState.UP
            val counted = lastRepMs.let { it == null || timestampMs - it >= config.minRepMs }
            if (counted) lastRepMs = timestampMs
            RepStep.WentUp(counted)
        } else {
            null
        }
    }

    fun reset() {
        state = RepState.UP
        dippedPartial = false
        lastRepMs = null
    }
}

// A fault only fires once it holds for 3 frames in a row, and only once per rep.
class FormRules(private val faultFrames: Int) {
    private val streaks = mutableMapOf<FaultKind, Int>()
    private val fired = linkedSetOf<FaultKind>()

    val firedThisRep: List<FaultKind> get() = fired.toList()

    // call once per frame while down
    fun update(holding: Map<FaultKind, Boolean>): List<FaultKind> =
        holding.mapNotNull { (kind, holds) ->
            val streak = if (holds) (streaks[kind] ?: 0) + 1 else 0
            streaks[kind] = streak
            if (streak >= faultFrames && fired.add(kind)) kind else null
        }

    fun startRep() {
        streaks.clear()
        fired.clear()
    }
}

// ready after 30 good frames in a row, not ready again after 15 bad ones
class SetupCheck(private val readyFrames: Int, private val lostFrames: Int) {
    var ready = false
        private set

    private var goodStreak = 0
    private var badStreak = 0

    // only returns something when it changes
    fun update(visible: Boolean): Boolean? {
        if (visible) {
            goodStreak++
            badStreak = 0
        } else {
            badStreak++
            goodStreak = 0
        }
        val next = if (ready) badStreak < lostFrames else goodStreak >= readyFrames
        if (next == ready) return null
        ready = next
        return next
    }
}
