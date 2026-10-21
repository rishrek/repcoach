package com.repcoach.phone.net

import android.util.Log
import com.repcoach.core.Protocol
import com.repcoach.core.RcJson
import com.repcoach.core.RcMessage
import dev.posebridge.BridgeClient
import dev.posebridge.BridgeClientListener
import dev.posebridge.LinkStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LinkState(val host: String? = null, val status: LinkStatus = LinkStatus.IDLE, val error: String? = null)

// connection to the TV, tries again every 2 seconds if it drops
class TvLink(private val onMessage: (RcMessage) -> Unit) : AutoCloseable {
    private val _state = MutableStateFlow(LinkState())
    val state: StateFlow<LinkState> = _state.asStateFlow()

    @Volatile private var host: String? = null

    private val client = BridgeClient(
        object : BridgeClientListener {
            override fun onStatus(status: LinkStatus, error: String?) {
                _state.value = LinkState(if (status == LinkStatus.IDLE) null else host, status, error)
            }

            override fun onMessage(text: String) {
                runCatching { RcJson.decode(text) }
                    .onSuccess(onMessage)
                    .onFailure { Log.w(TAG, "Bad message: ${text.take(80)}", it) }
            }
        },
        reconnectMs = Protocol.RECONNECT_MS,
    )

    fun connect(host: String) {
        this.host = host
        client.connect(BridgeClient.uriFor(host, Protocol.PORT))
    }

    fun disconnect() {
        host = null
        client.disconnect()
    }

    fun send(message: RcMessage): Boolean = client.send(RcJson.encode(message))

    override fun close() = client.close()

    private companion object {
        const val TAG = "TvLink"
    }
}
