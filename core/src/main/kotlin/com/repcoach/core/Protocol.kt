package com.repcoach.core

// network settings both apps share
object Protocol {
    const val PORT = 8765
    const val SERVICE_TYPE = "_repcoach._tcp"
    const val MAX_POSES_PER_SECOND = 15

    // the phone image is portrait 3 by 4, the TV needs this so the body doesn't look stretched
    const val POSE_ASPECT = 3f / 4f

    const val RECONNECT_MS = 2_000L
}

// what we actually send for a frame, only the image points and rounded
fun PoseFrame.toPoseMessage(timestampMs: Long): RcMessage.Pose =
    RcMessage.Pose(timestampMs, image.map { listOf(it.x, it.y, it.visibility) }).rounded()

// Lets through a fixed number of events a second, evenly spaced. A plain minimum gap only gave
// us about 10 a second from a 30 fps camera, so this keeps a running schedule instead.
class RateLimiter(private val perSecond: Int) {
    // using whole numbers here because floats slowly drifted and let 16 through in one second
    private val interval = 1_000L
    private var next: Long? = null

    fun allow(timestampMs: Long): Boolean {
        val now = timestampMs * perSecond
        val scheduled = next
        if (scheduled != null && now < scheduled) return false
        // after a long pause start over so we don't send a burst
        next = if (scheduled == null || now - scheduled > interval) now + interval else scheduled + interval
        return true
    }
}
