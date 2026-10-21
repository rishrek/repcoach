package com.repcoach.phone.pose

import android.content.Context
import android.os.SystemClock
import com.repcoach.core.Coach
import com.repcoach.core.Exercise
import com.repcoach.core.PoseFrame
import com.repcoach.core.RcMessage
import com.repcoach.core.SetResult
import com.repcoach.phone.net.Summarizer
import com.repcoach.phone.net.TvLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

data class SessionState(
    val frame: PoseFrame? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val fps: Float = 0f,
    val model: String = "",
    val delegate: String = "",
    val exercise: Exercise = Exercise.SQUAT,
    val setupReady: Boolean = false,
    val active: Boolean = false,
    val trackedAngle: Float? = null,
    val reps: Int = 0,
    val playerId: String? = null,
    val roomFull: String? = null,
    val lastSummary: String? = null,
    val recording: Boolean = false,
    val recordedFrames: Int = 0,
    val recordingPath: String? = null,
    val error: String? = null,
)

// Takes camera results, runs them through Coach and sends whatever comes out to the TV.
// Also handles the csv recorder and the summary call at the end of a set.
class PoseSession(context: Context, private val summarizer: Summarizer) : AutoCloseable {
    private val coach = Coach()
    private val recorder = SessionRecorder(context.getExternalFilesDir(null) ?: context.filesDir)
    private val summaries = Executors.newSingleThreadExecutor()
    private val _state = MutableStateFlow(SessionState())

    val state: StateFlow<SessionState> = _state.asStateFlow()
    val link = TvLink(::onTvMessage)
    val tracker = PoseTracker(context, ::onPose, ::onError)

    fun setRecording(on: Boolean) {
        val prefix = coach.exercise.name.lowercase()
        val file = if (on) recorder.start(prefix) else recorder.stop().let { recorder.file }
        _state.update { it.copy(recording = recorder.recording, recordingPath = file?.absolutePath, recordedFrames = recorder.frames) }
    }

    fun selectExercise(exercise: Exercise) {
        handle(coach.selectExercise(exercise))
        refresh()
    }

    override fun close() {
        recorder.stop()
        tracker.close()
        link.close()
        summaries.shutdown()
    }

    private fun onPose(result: PoseResult) {
        recorder.write(result.timestampMs, result.frame ?: PoseFrame.EMPTY)
        handle(coach.onFrame(result.timestampMs, result.frame))
        _state.update {
            it.copy(
                frame = result.frame,
                imageWidth = result.imageWidth,
                imageHeight = result.imageHeight,
                fps = tracker.fps,
                model = tracker.model.label,
                delegate = tracker.delegate,
                recordedFrames = recorder.frames,
                error = null,
            )
        }
        refresh()
    }

    private fun onTvMessage(message: RcMessage) {
        // same clock the pose timestamps use
        handle(coach.onMessage(message, SystemClock.uptimeMillis()))
        refresh()
    }

    private fun handle(output: Coach.Output) {
        output.messages.forEach(link::send)
        output.finished?.let(::summarize)
    }

    // runs on a background thread, then sends the summary to the TV
    private fun summarize(result: SetResult) {
        runCatching { summaries.execute { sendSummary(result) } }
    }

    private fun sendSummary(result: SetResult) {
        val text = summarizer.summarize(result)
        link.send(coach.summary(result, text))
        _state.update { it.copy(lastSummary = "${result.reps} reps, ${result.cleanReps} clean. $text") }
    }

    private fun refresh() {
        val analyzer = coach.analyzer
        _state.update {
            it.copy(
                exercise = coach.exercise,
                setupReady = analyzer.setupReady,
                active = coach.active,
                trackedAngle = analyzer.trackedAngle,
                reps = analyzer.reps,
                playerId = coach.playerId,
                roomFull = coach.roomFull,
            )
        }
    }

    private fun onError(message: String) {
        _state.update { it.copy(error = message) }
    }
}
