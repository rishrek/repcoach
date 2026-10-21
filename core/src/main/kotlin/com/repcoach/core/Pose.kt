package com.repcoach.core

// mediapipe landmark numbers we use
object Lm {
    const val COUNT = 33
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28
}

// world landmarks are in metres with y pointing down, image ones go from 0 to 1
data class Landmark(val x: Float, val y: Float, val z: Float = 0f, val visibility: Float = 1f)

// one camera frame worth of landmarks
data class PoseFrame(val world: List<Landmark>, val image: List<Landmark>) {
    init {
        require(world.size == Lm.COUNT) { "expected ${Lm.COUNT} world landmarks, got ${world.size}" }
        require(image.size == Lm.COUNT) { "expected ${Lm.COUNT} image landmarks, got ${image.size}" }
    }

    companion object {
        // nobody in the frame
        val EMPTY = List(Lm.COUNT) { Landmark(0f, 0f, 0f, 0f) }.let { PoseFrame(it, it) }
    }
}

// bones to draw, the phone overlay and the TV both use this
object Skeleton {
    val BONES: List<Pair<Int, Int>> = listOf(
        11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16,
        11 to 23, 12 to 24, 23 to 24,
        23 to 25, 25 to 27, 27 to 29, 29 to 31, 27 to 31,
        24 to 26, 26 to 28, 28 to 30, 30 to 32, 28 to 32,
    )

    // the leg bones, these turn red on a fault
    val LEG_BONES: Set<Pair<Int, Int>> = BONES.filter { (a, b) -> a >= 23 && b >= 25 }.toSet()
}

// counts events in the last second, not thread safe
class FpsMeter(private val windowMs: Long = 1_000) {
    private val times = ArrayDeque<Long>()

    val value: Float get() = times.size * 1_000f / windowMs

    fun tick(timestampMs: Long): Float {
        times.addLast(timestampMs)
        return valueAt(timestampMs)
    }

    // lets the number drop back to zero once events stop coming in
    fun valueAt(timestampMs: Long): Float {
        while (times.isNotEmpty() && timestampMs - times.first() >= windowMs) times.removeFirst()
        return value
    }

    fun reset() = times.clear()
}

// thresholds the rep counter needs, each exercise has its own set so we can tune them separately
interface CounterConfig {
    val downAngle: Float
    val upAngle: Float
    val partialAngle: Float

    // going below this angle counts as the start of a rep, the ghost uses it
    val repStartAngle: Float
    val minRepMs: Long
    val smoothingAlpha: Float
    val faultFrames: Int
    val minVisibility: Float
    val setupReadyFrames: Int
    val setupLostFrames: Int
    val setEndMs: Long
}

// all the squat numbers in one place so we can tune them against our recordings
data class RepConfig(
    override val downAngle: Float = 100f,
    override val upAngle: Float = 160f,
    override val partialAngle: Float = 140f,
    override val repStartAngle: Float = 150f,
    override val minRepMs: Long = 600,
    override val smoothingAlpha: Float = 0.5f,
    val kneeRatioMin: Float = 0.8f,
    val torsoLeanMax: Float = 50f,
    override val faultFrames: Int = 3,
    override val minVisibility: Float = 0.6f,
    override val setupReadyFrames: Int = 30,
    override val setupLostFrames: Int = 15,
    override val setEndMs: Long = 8_000,
    val minAnkleWidth: Float = 0.01f,
) : CounterConfig

// push up numbers, here the angle we track is the elbow
data class PushupConfig(
    override val downAngle: Float = 90f,
    override val upAngle: Float = 155f,
    override val partialAngle: Float = 120f,
    override val repStartAngle: Float = 150f,
    override val minRepMs: Long = 600,
    override val smoothingAlpha: Float = 0.5f,
    // body angle under this means the body isn't straight anymore
    val bodyAngleMin: Float = 160f,
    override val faultFrames: Int = 3,
    override val minVisibility: Float = 0.6f,
    override val setupReadyFrames: Int = 30,
    override val setupLostFrames: Int = 15,
    override val setEndMs: Long = 8_000,
) : CounterConfig
