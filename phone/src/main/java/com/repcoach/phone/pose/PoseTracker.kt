package com.repcoach.phone.pose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.repcoach.core.FpsMeter
import com.repcoach.core.Landmark
import com.repcoach.core.PoseFrame

// frame is null when nobody is in view
data class PoseResult(val timestampMs: Long, val frame: PoseFrame?, val imageWidth: Int, val imageHeight: Int)

// Runs mediapipe pose in live stream mode. We start with the full model and drop to the
// lite one if the phone can't keep up 15 fps.
class PoseTracker(
    context: Context,
    private val onResult: (PoseResult) -> Unit,
    private val onError: (String) -> Unit,
) {
    enum class Model(val asset: String, val label: String) {
        FULL("pose_landmarker_full.task", "full"),
        LITE("pose_landmarker_lite.task", "lite"),
    }

    private val context = context.applicationContext
    private val fpsMeter = FpsMeter()
    private var landmarker: PoseLandmarker? = null
    private var lastTimestampMs = 0L
    private var modelStartedMs = 0L
    private var closed = false

    @Volatile private var pendingModel: Model? = null

    @Volatile var model = Model.FULL
        private set

    @Volatile var delegate = ""
        private set

    @Volatile var fps = 0f
        private set

    // always closes the image
    @Synchronized
    fun detect(image: ImageProxy) {
        image.use { proxy ->
            if (closed) return
            pendingModel?.let { next ->
                landmarker?.close()
                landmarker = null
                model = next
                pendingModel = null
            }
            val pose = landmarker ?: create(model)?.also { landmarker = it } ?: return

            // mediapipe wants every timestamp to be bigger than the last
            val now = SystemClock.uptimeMillis()
            if (now <= lastTimestampMs) return
            lastTimestampMs = now

            val bitmap = proxy.toBitmap().rotated(proxy.imageInfo.rotationDegrees)
            pose.detectAsync(BitmapImageBuilder(bitmap).build(), now)
        }
    }

    @Synchronized
    fun close() {
        closed = true
        landmarker?.close()
        landmarker = null
    }

    private fun create(model: Model): PoseLandmarker? {
        fun build(delegate: Delegate): PoseLandmarker {
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(model.asset).setDelegate(delegate).build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setResultListener { result, input -> handle(result, input) }
                .setErrorListener { e -> onError(e.message ?: "pose error") }
                .build()
            return PoseLandmarker.createFromOptions(context, options)
        }
        modelStartedMs = SystemClock.uptimeMillis()
        fpsMeter.reset()
        return try {
            build(Delegate.GPU).also { delegate = "GPU" }
        } catch (e: Exception) {
            Log.w(TAG, "GPU delegate failed, using CPU", e)
            try {
                build(Delegate.CPU).also { delegate = "CPU" }
            } catch (e: Exception) {
                onError("Could not load ${model.asset}: ${e.message}")
                null
            }
        }
    }

    private fun handle(result: PoseLandmarkerResult, input: MPImage) {
        val t = result.timestampMs()
        fps = fpsMeter.tick(t)
        if (model == Model.FULL && pendingModel == null && t - modelStartedMs > WARMUP_MS && fps < MIN_FPS) {
            Log.i(TAG, "Full model at $fps fps, switching to lite")
            pendingModel = Model.LITE
        }
        onResult(PoseResult(t, result.toPoseFrame(), input.width, input.height))
    }

    private fun PoseLandmarkerResult.toPoseFrame(): PoseFrame? {
        val world = worldLandmarks().firstOrNull() ?: return null
        val image = landmarks().firstOrNull() ?: return null
        return PoseFrame(
            world = world.map { Landmark(it.x(), it.y(), it.z(), it.visibility().orElse(0f)) },
            image = image.map { Landmark(it.x(), it.y(), 0f, it.visibility().orElse(0f)) },
        )
    }

    private fun Bitmap.rotated(degrees: Int): Bitmap {
        if (degrees == 0) return this
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    companion object {
        private const val TAG = "PoseTracker"
        const val MIN_FPS = 15f
        private const val WARMUP_MS = 3_000L
    }
}
