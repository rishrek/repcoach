package com.repcoach.tv.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.repcoach.tv.Clips

// Plays the voice clips. We look them up by name so a missing clip just doesn't play
// and we can swap in new Polly clips without touching code.
class Sounds(context: Context) : AutoCloseable {
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()

    @SuppressLint("DiscouragedApi") // looking up by name on purpose
    private val ids: Map<String, Int> = Clips.ALL.mapNotNull { name ->
        val res = context.resources.getIdentifier(name, "raw", context.packageName)
        if (res == 0) null else name to pool.load(context, res, 1)
    }.toMap()

    fun play(name: String) {
        ids[name]?.let { pool.play(it, 1f, 1f, 1, 0, 1f) }
    }

    override fun close() = pool.release()
}
