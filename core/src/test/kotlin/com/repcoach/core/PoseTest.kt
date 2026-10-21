package com.repcoach.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PoseTest {
    @Test
    fun `frame needs 33 landmarks of each kind`() {
        val short = List(10) { Landmark(0f, 0f) }
        assertThrows<IllegalArgumentException> { PoseFrame(short, PoseFrame.EMPTY.image) }
    }

    @Test
    fun `empty frame fails setup and has no knee angle`() {
        assertNull(Geometry.kneeAngle(PoseFrame.EMPTY, 0.6f))
        val squat = SquatAnalyzer()
        repeat(40) { squat.analyze(PoseFrame.EMPTY, it * 33L) }
        assertEquals(false, squat.setupReady)
    }

    @Test
    fun `bones use valid landmark indices`() {
        assertTrue(Skeleton.BONES.all { (a, b) -> a in 0 until Lm.COUNT && b in 0 until Lm.COUNT })
    }

    @Test
    fun `leg bones run from the hips down`() {
        assertEquals(
            setOf(23 to 25, 25 to 27, 27 to 29, 29 to 31, 27 to 31, 24 to 26, 26 to 28, 28 to 30, 30 to 32, 28 to 32),
            Skeleton.LEG_BONES,
        )
    }

    @Test
    fun `fps meter counts events in the last second`() {
        val meter = FpsMeter()
        repeat(60) { meter.tick(it * 33L) }
        assertEquals(31f, meter.value)
    }

    @Test
    fun `fps meter drops old events after a gap and resets`() {
        val meter = FpsMeter()
        repeat(30) { meter.tick(it * 33L) }
        assertEquals(1f, meter.tick(5_000L))
        meter.reset()
        assertEquals(0f, meter.value)
    }

    @Test
    fun `fps meter falls to zero when events stop`() {
        val meter = FpsMeter()
        repeat(15) { meter.tick(it * 66L) }
        assertEquals(15f, meter.valueAt(950L))
        assertEquals(0f, meter.valueAt(3_000L))
    }
}
