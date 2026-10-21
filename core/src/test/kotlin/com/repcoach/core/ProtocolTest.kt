package com.repcoach.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProtocolTest {
    private fun passed(limiter: RateLimiter, frameMs: Long, durationMs: Long) =
        (0 until durationMs step frameMs).count { limiter.allow(it) }

    @Test
    fun `30 fps camera is cut to 15 per second`() {
        assertEquals(150, passed(RateLimiter(15), 33, 10_000), 2.0)
    }

    @Test
    fun `slower input than the limit passes almost everything`() {
        assertTrue(passed(RateLimiter(15), 100, 10_000) >= 99)
    }

    @Test
    fun `never more than 15 in any one second`() {
        val limiter = RateLimiter(15)
        val allowed = (0 until 10_000L step 10).filter { limiter.allow(it) }
        val worst = allowed.maxOf { start -> allowed.count { it in start until start + 1_000 } }
        assertTrue(worst <= 15, "worst second had $worst")
    }

    @Test
    fun `no burst after a pause`() {
        val limiter = RateLimiter(15)
        repeat(10) { limiter.allow(it * 33L) }
        val afterPause = (5_000L until 5_200L step 33).count { limiter.allow(it) }
        assertTrue(afterPause <= 4, "let $afterPause through in 200 ms")
    }

    @Test
    fun `pose message carries image points rounded to 3 decimals`() {
        val frame = Synth.frame(100f, kneeRatio = 0.7f)
        val message = frame.toPoseMessage(42L)
        assertEquals(42L, message.timestamp)
        assertEquals(Lm.COUNT, message.points.size)
        assertEquals(listOf(0.43f, 0.7f, 0.9f), message.points[Lm.LEFT_KNEE])
        assertTrue(message.points.flatten().all { round3(it) == it })
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Double) =
        assertEquals(expected.toDouble(), actual.toDouble(), tolerance)
}
