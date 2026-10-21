package com.repcoach.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

object Geometry {
    // angle at b in degrees, null if two of the points sit on top of each other
    fun angle(a: Landmark, b: Landmark, c: Landmark): Float? {
        val ux = (a.x - b.x).toDouble()
        val uy = (a.y - b.y).toDouble()
        val uz = (a.z - b.z).toDouble()
        val vx = (c.x - b.x).toDouble()
        val vy = (c.y - b.y).toDouble()
        val vz = (c.z - b.z).toDouble()
        val lengths = sqrt(ux * ux + uy * uy + uz * uz) * sqrt(vx * vx + vy * vy + vz * vz)
        if (lengths == 0.0) return null
        val cos = ((ux * vx + uy * vy + uz * vz) / lengths).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos)).toFloat()
    }

    // averages both knees when we can see both, otherwise uses whichever leg is visible
    fun kneeAngle(frame: PoseFrame, minVisibility: Float): Float? {
        val left = legAngle(frame.world, Lm.LEFT_HIP, Lm.LEFT_KNEE, Lm.LEFT_ANKLE, minVisibility)
        val right = legAngle(frame.world, Lm.RIGHT_HIP, Lm.RIGHT_KNEE, Lm.RIGHT_ANKLE, minVisibility)
        return if (left != null && right != null) (left + right) / 2f else left ?: right
    }

    private fun legAngle(world: List<Landmark>, hip: Int, knee: Int, ankle: Int, minVisibility: Float): Float? {
        val visible = listOf(hip, knee, ankle).all { world[it].visibility > minVisibility }
        return if (visible) angle(world[hip], world[knee], world[ankle]) else null
    }

    // how far the torso leans away from straight up, 0 when standing tall
    fun torsoLean(frame: PoseFrame): Float? {
        val w = frame.world
        val x = (w[Lm.LEFT_SHOULDER].x + w[Lm.RIGHT_SHOULDER].x - w[Lm.LEFT_HIP].x - w[Lm.RIGHT_HIP].x) / 2.0
        val y = (w[Lm.LEFT_SHOULDER].y + w[Lm.RIGHT_SHOULDER].y - w[Lm.LEFT_HIP].y - w[Lm.RIGHT_HIP].y) / 2.0
        val z = (w[Lm.LEFT_SHOULDER].z + w[Lm.RIGHT_SHOULDER].z - w[Lm.LEFT_HIP].z - w[Lm.RIGHT_HIP].z) / 2.0
        val length = sqrt(x * x + y * y + z * z)
        if (length == 0.0) return null
        return Math.toDegrees(acos((-y / length).coerceIn(-1.0, 1.0))).toFloat()
    }

    // knee width compared to ankle width, knees are caving in when this drops under about 0.8
    fun kneeRatio(frame: PoseFrame, minVisibility: Float, minAnkleWidth: Float): Float? {
        val i = frame.image
        val points = listOf(Lm.LEFT_KNEE, Lm.RIGHT_KNEE, Lm.LEFT_ANKLE, Lm.RIGHT_ANKLE)
        if (points.any { i[it].visibility <= minVisibility }) return null
        val ankleWidth = abs(i[Lm.LEFT_ANKLE].x - i[Lm.RIGHT_ANKLE].x)
        if (ankleWidth < minAnkleWidth) return null
        return abs(i[Lm.LEFT_KNEE].x - i[Lm.RIGHT_KNEE].x) / ankleWidth
    }
}

// positive when the hip sits below the shoulder to ankle line, negative when it's above it
fun Geometry.hipOffset(shoulder: Landmark, hip: Landmark, ankle: Landmark): Float? {
    val dx = ankle.x - shoulder.x
    if (kotlin.math.abs(dx) < 1e-4f) return null
    val lineY = shoulder.y + (ankle.y - shoulder.y) * (hip.x - shoulder.x) / dx
    return hip.y - lineY
}

// simple moving average to smooth out the angle
class Ema(private val alpha: Float) {
    var value: Float? = null
        private set

    fun update(x: Float): Float {
        val next = value?.let { alpha * x + (1 - alpha) * it } ?: x
        value = next
        return next
    }

    fun reset() {
        value = null
    }
}
