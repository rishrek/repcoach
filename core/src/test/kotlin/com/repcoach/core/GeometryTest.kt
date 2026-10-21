package com.repcoach.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GeometryTest {
    private val eps = 0.01f
    private val origin = Landmark(0f, 0f, 0f)

    @Test
    fun `right angle is 90 degrees`() {
        assertEquals(90f, Geometry.angle(Landmark(1f, 0f, 0f), origin, Landmark(0f, 1f, 0f))!!, eps)
    }

    @Test
    fun `straight line is 180 degrees`() {
        assertEquals(180f, Geometry.angle(Landmark(-1f, 0f, 0f), origin, Landmark(2f, 0f, 0f))!!, eps)
    }

    @Test
    fun `same direction is 0 degrees, not NaN`() {
        assertEquals(0f, Geometry.angle(Landmark(0.1f, 0.1f, 0.1f), origin, Landmark(0.3f, 0.3f, 0.3f))!!, 0.1f)
    }

    @Test
    fun `zero-length vector gives null`() {
        assertNull(Geometry.angle(origin, origin, Landmark(1f, 0f, 0f)))
    }

    @Test
    fun `knee angle averages both legs when both are visible`() {
        assertEquals(110f, Geometry.kneeAngle(Synth.frame(100f, rightKneeAngle = 120f), 0.6f)!!, eps)
    }

    @Test
    fun `knee angle uses the visible leg`() {
        val frame = Synth.frame(100f, rightKneeAngle = 120f, rightVisible = false)
        assertEquals(100f, Geometry.kneeAngle(frame, 0.6f)!!, eps)
    }

    @Test
    fun `knee angle is null when neither leg is visible`() {
        assertNull(Geometry.kneeAngle(Synth.frame(100f, leftVisible = false, rightVisible = false), 0.6f))
    }

    @Test
    fun `torso lean measures from straight up`() {
        assertEquals(0f, Geometry.torsoLean(Synth.frame(170f, lean = 0f))!!, eps)
        assertEquals(35f, Geometry.torsoLean(Synth.frame(170f, lean = 35f))!!, eps)
    }

    @Test
    fun `knee ratio is knee width over ankle width`() {
        assertEquals(0.7f, Geometry.kneeRatio(Synth.frame(100f, kneeRatio = 0.7f), 0.6f, 0.01f)!!, eps)
    }

    @Test
    fun `knee ratio is null when ankles are too close`() {
        val frame = Synth.frame(100f)
        val image = frame.image.toMutableList()
        image[Lm.RIGHT_ANKLE] = image[Lm.LEFT_ANKLE].copy(x = image[Lm.LEFT_ANKLE].x + 0.001f)
        assertNull(Geometry.kneeRatio(frame.copy(image = image), 0.6f, 0.01f))
    }

    @Test
    fun `knee ratio is null when a knee is hidden`() {
        val frame = Synth.frame(100f)
        val image = frame.image.toMutableList()
        image[Lm.LEFT_KNEE] = image[Lm.LEFT_KNEE].copy(visibility = 0.2f)
        assertNull(Geometry.kneeRatio(frame.copy(image = image), 0.6f, 0.01f))
    }

    @Test
    fun `hip offset is positive below the shoulder-ankle line and negative above`() {
        val shoulder = Landmark(0.2f, 0.5f)
        val ankle = Landmark(0.8f, 0.5f)
        assertEquals(0.05f, Geometry.hipOffset(shoulder, Landmark(0.5f, 0.55f), ankle)!!, 1e-4f)
        assertEquals(-0.05f, Geometry.hipOffset(shoulder, Landmark(0.5f, 0.45f), ankle)!!, 1e-4f)
        assertNull(Geometry.hipOffset(shoulder, Landmark(0.5f, 0.5f), Landmark(0.2f, 0.9f)))
    }

    @Test
    fun `ema passes the first value then blends`() {
        val ema = Ema(0.5f)
        assertEquals(100f, ema.update(100f))
        assertEquals(120f, ema.update(140f))
        ema.reset()
        assertEquals(50f, ema.update(50f))
    }
}
