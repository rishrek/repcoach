package com.repcoach.phone.pose

import com.repcoach.core.PoseCsv
import com.repcoach.core.PoseFrame
import java.io.BufferedWriter
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// saves frames to a csv named after the exercise and the time
class SessionRecorder(private val dir: File) {
    private var writer: BufferedWriter? = null

    var file: File? = null
        private set
    var frames = 0
        private set

    val recording: Boolean @Synchronized get() = writer != null

    @Synchronized
    fun start(prefix: String = "squat"): File {
        stop()
        dir.mkdirs()
        val name = "${prefix}_${LocalDateTime.now().format(STAMP)}.csv"
        val target = File(dir, name)
        writer = target.bufferedWriter().apply {
            write(PoseCsv.HEADER)
            newLine()
        }
        file = target
        frames = 0
        return target
    }

    @Synchronized
    fun write(timestampMs: Long, frame: PoseFrame) {
        val out = writer ?: return
        out.write(PoseCsv.toRow(timestampMs, frame))
        out.newLine()
        frames++
    }

    @Synchronized
    fun stop() {
        writer?.close()
        writer = null
    }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    }
}
