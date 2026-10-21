package com.repcoach.core

// Push ups filmed from the side. We use whichever side of the body the camera sees better.
class PushupAnalyzer(val config: PushupConfig = PushupConfig()) : RepAnalyzer(config) {
    override val exercise = Exercise.PUSHUP

    private class Side(val shoulder: Int, val elbow: Int, val wrist: Int, val hip: Int, val knee: Int, val ankle: Int) {
        val points = listOf(shoulder, elbow, wrist, hip, knee, ankle)
    }

    override fun bodyVisible(frame: PoseFrame): Boolean {
        val side = sideFacingCamera(frame)
        return side.points.all { frame.world[it].visibility > config.minVisibility }
    }

    override fun measureAngle(frame: PoseFrame): Float? {
        val side = sideFacingCamera(frame)
        val w = frame.world
        if (listOf(side.shoulder, side.elbow, side.wrist).any { w[it].visibility <= config.minVisibility }) return null
        return Geometry.angle(w[side.shoulder], w[side.elbow], w[side.wrist])
    }

    override fun faultsHolding(frame: PoseFrame): Map<FaultKind, Boolean> {
        val side = sideFacingCamera(frame)
        val bent = Geometry.angle(frame.world[side.shoulder], frame.world[side.hip], frame.world[side.ankle])
            ?.let { it < config.bodyAngleMin } == true
        val offset = Geometry.hipOffset(frame.image[side.shoulder], frame.image[side.hip], frame.image[side.ankle])
        return mapOf(
            FaultKind.HIPS_SAG to (bent && offset != null && offset > 0f),
            FaultKind.HIPS_PIKE to (bent && offset != null && offset < 0f),
        )
    }

    private fun sideFacingCamera(frame: PoseFrame): Side {
        fun score(side: Side) = side.points.sumOf { frame.world[it].visibility.toDouble() }
        return if (score(LEFT) >= score(RIGHT)) LEFT else RIGHT
    }

    private companion object {
        val LEFT = Side(Lm.LEFT_SHOULDER, Lm.LEFT_ELBOW, Lm.LEFT_WRIST, Lm.LEFT_HIP, Lm.LEFT_KNEE, Lm.LEFT_ANKLE)
        val RIGHT = Side(Lm.RIGHT_SHOULDER, Lm.RIGHT_ELBOW, Lm.RIGHT_WRIST, Lm.RIGHT_HIP, Lm.RIGHT_KNEE, Lm.RIGHT_ANKLE)
    }
}
