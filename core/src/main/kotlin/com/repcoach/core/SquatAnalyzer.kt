package com.repcoach.core

sealed interface AnalyzerEvent {
    // minAngle is how deep the rep went, smaller means deeper
    data class RepCounted(val number: Int, val clean: Boolean, val faults: List<FaultKind>, val minAngle: Float) : AnalyzerEvent
    data class FaultRaised(val kind: FaultKind) : AnalyzerEvent
    data class SetupChanged(val ready: Boolean) : AnalyzerEvent

    data class RepStarted(val timestampMs: Long) : AnalyzerEvent

    // nothing happened for 8 seconds after at least one rep
    data class SetEnded(val reps: Int, val cleanReps: Int) : AnalyzerEvent
}

// no android code or clocks in here so we can replay recordings in tests
interface ExerciseAnalyzer {
    val exercise: Exercise
    val reps: Int
    val cleanReps: Int
    val faultCounts: Map<FaultKind, Int>
    val state: RepState
    val setupReady: Boolean

    // knee for squats, elbow for push ups
    val trackedAngle: Float?

    fun analyze(frame: PoseFrame, timestampMs: Long): List<AnalyzerEvent>

    // new set, clears the counts but keeps setup as it was
    fun reset()

    companion object {
        fun create(exercise: Exercise): ExerciseAnalyzer = when (exercise) {
            Exercise.SQUAT -> SquatAnalyzer()
            Exercise.PUSHUP -> PushupAnalyzer()
        }
    }
}

// Shared part of both analyzers. Each exercise only has to say what counts as visible,
// which angle to track and which faults are happening. Nothing counts until the whole
// body is in view.
abstract class RepAnalyzer(private val counterConfig: CounterConfig) : ExerciseAnalyzer {
    private val setup = SetupCheck(counterConfig.setupReadyFrames, counterConfig.setupLostFrames)
    private val smoothing = Ema(counterConfig.smoothingAlpha)
    private val counter = RepCounter(counterConfig)
    private val form = FormRules(counterConfig.faultFrames)
    private val faults = mutableMapOf<FaultKind, Int>()

    private var lastChangeMs: Long? = null
    private var setEnded = false
    private var repStartSent = false
    private var minAngle = Float.MAX_VALUE

    final override var reps = 0
        private set
    final override var cleanReps = 0
        private set
    final override val faultCounts: Map<FaultKind, Int> get() = faults.toMap()
    final override var trackedAngle: Float? = null
        private set
    final override val state: RepState get() = counter.state
    final override val setupReady: Boolean get() = setup.ready

    protected abstract fun bodyVisible(frame: PoseFrame): Boolean
    protected abstract fun measureAngle(frame: PoseFrame): Float?
    protected abstract fun faultsHolding(frame: PoseFrame): Map<FaultKind, Boolean>

    final override fun analyze(frame: PoseFrame, timestampMs: Long): List<AnalyzerEvent> {
        val events = mutableListOf<AnalyzerEvent>()
        setup.update(bodyVisible(frame))?.let { events += AnalyzerEvent.SetupChanged(it) }
        if (setup.ready) trackMotion(frame, timestampMs, events)
        checkSetEnd(timestampMs, events)
        return events
    }

    final override fun reset() {
        smoothing.reset()
        counter.reset()
        form.startRep()
        faults.clear()
        reps = 0
        cleanReps = 0
        trackedAngle = null
        lastChangeMs = null
        setEnded = false
        repStartSent = false
        minAngle = Float.MAX_VALUE
    }

    private fun trackMotion(frame: PoseFrame, timestampMs: Long, events: MutableList<AnalyzerEvent>) {
        val raw = measureAngle(frame) ?: return
        val angle = smoothing.update(raw)
        trackedAngle = angle
        minAngle = minOf(minAngle, angle)

        if (counter.state == RepState.UP) {
            if (!repStartSent && angle < counterConfig.repStartAngle) {
                repStartSent = true
                minAngle = angle
                events += AnalyzerEvent.RepStarted(timestampMs)
            } else if (repStartSent && angle > counterConfig.upAngle) {
                repStartSent = false // small dip that never turned into a rep
            }
        }

        when (val step = counter.update(angle, timestampMs)) {
            RepStep.WentDown -> {
                form.startRep()
                lastChangeMs = timestampMs
            }
            is RepStep.WentUp -> {
                lastChangeMs = timestampMs
                repStartSent = false
                if (step.counted) {
                    val repFaults = form.firedThisRep
                    reps++
                    if (repFaults.isEmpty()) cleanReps++
                    setEnded = false
                    events += AnalyzerEvent.RepCounted(reps, repFaults.isEmpty(), repFaults, minAngle)
                }
                minAngle = Float.MAX_VALUE
            }
            RepStep.Shallow -> {
                lastChangeMs = timestampMs
                repStartSent = false
                minAngle = Float.MAX_VALUE
                raise(FaultKind.SHALLOW, events)
            }
            null -> Unit
        }

        if (counter.state == RepState.DOWN) {
            form.update(faultsHolding(frame)).forEach { raise(it, events) }
        }
    }

    private fun raise(kind: FaultKind, events: MutableList<AnalyzerEvent>) {
        faults[kind] = (faults[kind] ?: 0) + 1
        events += AnalyzerEvent.FaultRaised(kind)
    }

    private fun checkSetEnd(timestampMs: Long, events: MutableList<AnalyzerEvent>) {
        val last = lastChangeMs ?: return
        if (!setEnded && reps > 0 && timestampMs - last >= counterConfig.setEndMs) {
            setEnded = true
            events += AnalyzerEvent.SetEnded(reps, cleanReps)
        }
    }
}

class SquatAnalyzer(val config: RepConfig = RepConfig()) : RepAnalyzer(config) {
    override val exercise = Exercise.SQUAT

    val kneeAngle: Float? get() = trackedAngle

    override fun bodyVisible(frame: PoseFrame) = SETUP_POINTS.all { frame.world[it].visibility > config.minVisibility }

    override fun measureAngle(frame: PoseFrame) = Geometry.kneeAngle(frame, config.minVisibility)

    override fun faultsHolding(frame: PoseFrame): Map<FaultKind, Boolean> {
        val ratio = Geometry.kneeRatio(frame, config.minVisibility, config.minAnkleWidth)
        val lean = Geometry.torsoLean(frame)
        return mapOf(
            FaultKind.KNEES_CAVE to (ratio != null && ratio < config.kneeRatioMin),
            FaultKind.CHEST_DOWN to (lean != null && lean > config.torsoLeanMax),
        )
    }

    companion object {
        val SETUP_POINTS = listOf(
            Lm.LEFT_SHOULDER, Lm.RIGHT_SHOULDER, Lm.LEFT_HIP, Lm.RIGHT_HIP,
            Lm.LEFT_KNEE, Lm.RIGHT_KNEE, Lm.LEFT_ANKLE, Lm.RIGHT_ANKLE,
        )
    }
}
