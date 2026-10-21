package com.repcoach.tv.net

import android.os.Handler
import android.util.Log
import com.repcoach.core.Protocol
import com.repcoach.core.RcJson
import com.repcoach.core.RcMessage
import com.repcoach.tv.TvController
import dev.posebridge.BridgeServer
import dev.posebridge.BridgeServerListener

// Server for up to two phones. Everything gets posted to the main thread so the
// controller never runs on two threads at once.
class TvHub(private val main: Handler, private val controller: TvController) {
    private val server = BridgeServer(
        port = Protocol.PORT,
        maxClients = 2,
        listener = object : BridgeServerListener {
            override fun onStarted() {
                main.post { controller.onServerError(null) }
            }

            override fun onJoined(slot: Int, address: String) {
                main.post { controller.onJoined(slot, address) }
            }

            override fun onLeft(slot: Int) {
                main.post { controller.onLeft(slot) }
            }

            override fun onMessage(slot: Int, text: String) {
                val message = runCatching { RcJson.decode(text) }.getOrElse {
                    Log.w(TAG, "Bad message: ${text.take(80)}", it)
                    return
                }
                main.post { controller.onMessage(slot, message) }
            }

            override fun onError(message: String) {
                main.post { controller.onServerError(message) }
            }
        },
        roomFullMessage = RcJson.encode(RcMessage.RoomFull("This TV already has two players.")),
    )

    fun start() = server.start()

    fun stop() = server.stop()

    fun send(slot: Int, message: RcMessage) {
        server.send(slot, RcJson.encode(message))
    }

    private companion object {
        const val TAG = "TvHub"
    }
}
