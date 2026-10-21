package com.repcoach.core

// winner is 1 or 2, null means a tie
data class DuelOutcome(val winner: Int?, val clean: List<Int>, val flagged: List<Int>)

// First to 10 clean reps wins and flagged reps don't count.
// If time runs out the one with more clean reps wins, same count is a tie.
class DuelMatch(val target: Int = 10, val limitMs: Long = 60_000) {
    private val clean = IntArray(2)
    private val flagged = IntArray(2)

    var startMs: Long? = null
        private set
    var outcome: DuelOutcome? = null
        private set

    val running: Boolean get() = startMs != null && outcome == null

    fun start(nowMs: Long) {
        clean.fill(0)
        flagged.fill(0)
        outcome = null
        startMs = nowMs
    }

    fun cleanReps(player: Int) = clean[player - 1]
    fun flaggedReps(player: Int) = flagged[player - 1]

    // whoever has more clean reps right now, null if level
    val leader: Int?
        get() = when {
            clean[0] > clean[1] -> 1
            clean[1] > clean[0] -> 2
            else -> null
        }

    fun remainingMs(nowMs: Long): Long = startMs?.let { (limitMs - (nowMs - it)).coerceAtLeast(0) } ?: limitMs

    // returns the outcome if this rep finished the match
    fun onRep(player: Int, isClean: Boolean): DuelOutcome? {
        if (!running || player !in 1..2) return null
        if (!isClean) {
            flagged[player - 1]++
            return null
        }
        clean[player - 1]++
        if (clean[player - 1] >= target) return end(player)
        return null
    }

    // call this every so often, it ends the match when time is up
    fun tick(nowMs: Long): DuelOutcome? {
        val start = startMs ?: return null
        if (outcome != null || nowMs - start < limitMs) return null
        return end(leader)
    }

    private fun end(winner: Int?) = DuelOutcome(winner, clean.toList(), flagged.toList()).also { outcome = it }
}
