package com.repcoach.core

// a finished set, before we get the summary text for it
data class SetResult(
    val exercise: Exercise,
    val reps: Int,
    val cleanReps: Int,
    val faultCounts: Map<FaultKind, Int>,
    val durationSec: Int,
)

// All the phone side logic lives here so we can test it without a phone.
// Frames and TV messages come in and we hand back what should be sent to the TV.
// Reps only count between START_SET and the end of the set, every message gets the
// player id the TV gave us, and the deepest clean rep gets sent over as the ghost.
// Camera frames and network messages come in on different threads, hence synchronized.
class Coach(exercise: Exercise = Exercise.SQUAT) {
    data class Output(val messages: List<RcMessage> = emptyList(), val finished: SetResult? = null)

    var exercise = exercise
        private set
    var analyzer: ExerciseAnalyzer = ExerciseAnalyzer.create(exercise)
        private set
    var playerId: String? = null
        private set
    var active = false
        private set

    // set when the TV tells us it already has two players
    var roomFull: String? = null
        private set

    private var autoEnd = true
    private var setStartMs = 0L
    private val poseLimiter = RateLimiter(Protocol.MAX_POSES_PER_SECOND)
    private val ghostFrames = mutableListOf<GhostFrame>()
    private var ghostStartMs: Long? = null
    private var bestDepth: Float? = null

    @Synchronized
    fun onFrame(timestampMs: Long, frame: PoseFrame?): Output {
        // still pass an empty frame so setup notices when the person walks off
        val events = analyzer.analyze(frame ?: PoseFrame.EMPTY, timestampMs)
        val wire = frame?.toPoseMessage(timestampMs)
        val out = mutableListOf<RcMessage>()
        var finished: SetResult? = null
        if (wire != null && poseLimiter.allow(timestampMs)) out += wire

        for (event in events) {
            when (event) {
                is AnalyzerEvent.SetupChanged -> out += statusMessage()
                is AnalyzerEvent.RepStarted -> if (active) {
                    out += RcMessage.RepStart(event.timestampMs)
                    ghostFrames.clear()
                    ghostStartMs = event.timestampMs
                }
                is AnalyzerEvent.RepCounted -> if (active) {
                    out += RcMessage.Rep(event.number, event.clean, event.faults)
                    val best = bestDepth
                    if (event.clean && (best == null || event.minAngle < best)) {
                        bestDepth = event.minAngle
                        ghostMessage()?.let { out += it }
                    }
                    stopGhost()
                }
                is AnalyzerEvent.FaultRaised -> if (active) {
                    out += RcMessage.Fault(event.kind)
                    if (event.kind == FaultKind.SHALLOW) stopGhost()
                }
                is AnalyzerEvent.SetEnded -> if (active && autoEnd) finished = finish(timestampMs)
            }
        }

        val start = ghostStartMs
        if (start != null && wire != null) ghostFrames += GhostFrame(timestampMs - start, wire.points)
        return Output(out.map { it.withPlayer(playerId) }, finished)
    }

    @Synchronized
    fun onMessage(message: RcMessage, nowMs: Long): Output = when (message) {
        is RcMessage.Hello -> {
            playerId = message.playerId
            roomFull = null
            Output(listOf(status()))
        }
        is RcMessage.RoomFull -> {
            roomFull = message.message
            Output()
        }
        is RcMessage.Control -> onControl(message, nowMs)
        else -> Output()
    }

    // exercise picked on the phone, switch and let the TV know
    @Synchronized
    fun selectExercise(next: Exercise): Output {
        if (next != exercise) switchTo(next)
        val messages = listOf(RcMessage.Control(ControlAction.SET_EXERCISE, exercise = next), statusMessage())
        return Output(messages.map { it.withPlayer(playerId) })
    }

    // resend this whenever the TV might have missed the last status
    @Synchronized
    fun status(): RcMessage = statusMessage().withPlayer(playerId)

    fun summary(result: SetResult, aiText: String): RcMessage = RcMessage.Summary(
        reps = result.reps,
        cleanReps = result.cleanReps,
        faultCounts = result.faultCounts,
        durationSec = result.durationSec,
        aiText = aiText,
        exercise = result.exercise,
    ).withPlayer(playerId)

    private fun onControl(control: RcMessage.Control, nowMs: Long): Output = when (control.action) {
        ControlAction.START_SET -> {
            control.exercise?.let { if (it != exercise) switchTo(it) }
            analyzer.reset()
            active = true
            autoEnd = control.autoEnd
            setStartMs = nowMs
            bestDepth = null
            stopGhost()
            Output()
        }
        ControlAction.END_SET -> if (active) Output(finished = finish(nowMs)) else Output()
        ControlAction.RESET -> {
            active = false
            analyzer.reset()
            stopGhost()
            Output(listOf(status()))
        }
        ControlAction.SET_EXERCISE -> {
            control.exercise?.let { if (it != exercise) switchTo(it) }
            Output(listOf(status()))
        }
    }

    private fun statusMessage() = RcMessage.Status(
        setupOk = analyzer.setupReady,
        message = if (analyzer.setupReady) "Ready" else ExerciseText.notReady(exercise),
        exercise = exercise,
    )

    private fun switchTo(next: Exercise) {
        exercise = next
        analyzer = ExerciseAnalyzer.create(next)
        active = false
        stopGhost()
    }

    private fun finish(nowMs: Long): SetResult {
        active = false
        stopGhost()
        return SetResult(
            exercise = exercise,
            reps = analyzer.reps,
            cleanReps = analyzer.cleanReps,
            faultCounts = analyzer.faultCounts,
            durationSec = ((nowMs - setStartMs + 500) / 1_000).toInt().coerceAtLeast(0),
        )
    }

    private fun ghostMessage(): RcMessage.Ghost? {
        if (ghostFrames.size < 2) return null
        return RcMessage.Ghost(downsample(ghostFrames, MAX_GHOST_FRAMES))
    }

    private fun stopGhost() {
        ghostStartMs = null
        ghostFrames.clear()
    }

    companion object {
        const val MAX_GHOST_FRAMES = 60

        // picks evenly spaced items, keeps the first and last one
        fun <T> downsample(items: List<T>, max: Int): List<T> {
            if (items.size <= max) return items.toList()
            return List(max) { i -> items[(i.toLong() * (items.size - 1) / (max - 1)).toInt()] }
        }
    }
}
