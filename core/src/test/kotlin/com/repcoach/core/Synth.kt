package com.repcoach.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// fake frames and angle sequences for the tests
object Synth {
    const val FRAME_MS = 33L
    private const val HIDDEN = 0.1f

    // builds a frame with exactly these knee angles, lean and knee width
    fun frame(
        kneeAngle: Float,
        rightKneeAngle: Float = kneeAngle,
        lean: Float = 10f,
        kneeRatio: Float = 1f,
        leftVisible: Boolean = true,
        rightVisible: Boolean = true,
        visibility: Float = 0.9f,
    ): PoseFrame {
        val world = MutableList(Lm.COUNT) { Landmark(0f, 0f, 0f, visibility) }
        val image = MutableList(Lm.COUNT) { Landmark(0.5f, 0.5f, 0f, visibility) }

        fun leg(hip: Int, knee: Int, ankle: Int, shoulder: Int, x: Float, angle: Float, visible: Boolean) {
            val vis = if (visible) visibility else HIDDEN
            val theta = Math.toRadians(angle.toDouble())
            val tilt = Math.toRadians(lean.toDouble())
            // hip sits right above the knee and the ankle swings out to make the angle
            world[hip] = Landmark(x, 0f, 0f, vis)
            world[knee] = Landmark(x, 0.45f, 0f, vis)
            world[ankle] = Landmark(x, (0.45 - 0.45 * cos(theta)).toFloat(), (0.45 * sin(theta)).toFloat(), vis)
            world[shoulder] = Landmark(x, (-0.5 * cos(tilt)).toFloat(), (0.5 * sin(tilt)).toFloat(), visibility)
        }
        leg(Lm.LEFT_HIP, Lm.LEFT_KNEE, Lm.LEFT_ANKLE, Lm.LEFT_SHOULDER, -0.1f, kneeAngle, leftVisible)
        leg(Lm.RIGHT_HIP, Lm.RIGHT_KNEE, Lm.RIGHT_ANKLE, Lm.RIGHT_SHOULDER, 0.1f, rightKneeAngle, rightVisible)

        image[Lm.LEFT_KNEE] = Landmark(0.5f - 0.1f * kneeRatio, 0.7f, 0f, visibility)
        image[Lm.RIGHT_KNEE] = Landmark(0.5f + 0.1f * kneeRatio, 0.7f, 0f, visibility)
        image[Lm.LEFT_ANKLE] = Landmark(0.4f, 0.9f, 0f, visibility)
        image[Lm.RIGHT_ANKLE] = Landmark(0.6f, 0.9f, 0f, visibility)
        return PoseFrame(world, image)
    }

    // one smooth rep from the top down and back up, then a few frames standing still
    fun repAngles(top: Float = 170f, bottom: Float = 85f, frames: Int = 30, hold: Int = 6): List<Float> =
        List(frames) { i -> bottom + (top - bottom) * (1 + cos(2 * PI * i / frames).toFloat()) / 2 } +
            List(hold) { top }

    fun standing(frames: Int, angle: Float = 172f): List<Float> = List(frames) { angle }

    // push up seen from the side, hipDrop pushes the hip down or up out of line
    fun pushupFrame(elbowAngle: Float, hipDrop: Float = 0f, rightSide: Boolean = false, visibility: Float = 0.9f): PoseFrame {
        val world = MutableList(Lm.COUNT) { Landmark(0f, 0f, 0f, HIDDEN) }
        val image = MutableList(Lm.COUNT) { Landmark(0.5f, 0.5f, 0f, HIDDEN) }
        fun put(index: Int, x: Float, y: Float) {
            world[index] = Landmark(x, y, 0f, visibility)
            image[index] = Landmark(0.2f + 0.5f * x, 0.4f + 0.5f * y, 0f, visibility)
        }
        val theta = Math.toRadians(elbowAngle.toDouble())
        val s = if (rightSide) 1 else 0
        put(Lm.LEFT_SHOULDER + s, 0f, 0f)
        put(Lm.LEFT_ELBOW + s, 0f, 0.3f)
        put(Lm.LEFT_WRIST + s, (0.3 * sin(theta)).toFloat(), (0.3 - 0.3 * cos(theta)).toFloat())
        put(Lm.LEFT_HIP + s, 0.5f, hipDrop)
        put(Lm.LEFT_KNEE + s, 0.75f, hipDrop / 2)
        put(Lm.LEFT_ANKLE + s, 1f, 0f)
        return PoseFrame(world, image)
    }
}
