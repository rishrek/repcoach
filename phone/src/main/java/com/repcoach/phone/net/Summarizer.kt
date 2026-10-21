package com.repcoach.phone.net

import android.util.Log
import com.repcoach.core.Coaching
import com.repcoach.core.RcJson
import com.repcoach.core.SetResult
import com.repcoach.core.SummaryRequest
import com.repcoach.core.SummaryResponse
import com.repcoach.core.toRequest
import java.net.HttpURLConnection
import java.net.URL

// Asks our lambda for the coaching summary, the lambda talks to bedrock. The phone only
// knows the url and the shared key. If anything fails we use our own text instead.
class Summarizer(private val url: String, private val key: String) {
    // blocks, so don't call it on the main thread
    fun summarize(result: SetResult): String {
        if (url.isBlank()) return Coaching.fallbackText(result)
        return runCatching { callLambda(result) }.getOrElse {
            Log.w(TAG, "Lambda failed, using the built-in summary", it)
            Coaching.fallbackText(result)
        }
    }

    private fun callLambda(result: SetResult): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            // both together stay under 5 seconds
            connection.connectTimeout = 2_500
            connection.readTimeout = 2_500
            connection.doOutput = true
            connection.setRequestProperty("content-type", "application/json")
            connection.setRequestProperty("x-repcoach-key", key)
            val body = RcJson.json.encodeToString(SummaryRequest.serializer(), result.toRequest())
            connection.outputStream.use { it.write(body.toByteArray()) }
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            return RcJson.json.decodeFromString(SummaryResponse.serializer(), response).aiText.trim()
                .ifEmpty { error("empty aiText") }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "Summarizer"
    }
}
