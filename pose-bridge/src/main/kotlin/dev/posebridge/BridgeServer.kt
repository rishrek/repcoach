package dev.posebridge

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import kotlin.concurrent.thread

// called on the server's network threads
interface BridgeServerListener {
    fun onStarted() {}
    fun onJoined(slot: Int, address: String) {}
    fun onLeft(slot: Int) {}
    fun onMessage(slot: Int, text: String) {}
    fun onError(message: String) {}
}

// Server with a fixed number of player slots. One client too many gets the room full
// message and is closed. If a phone reconnects from the same address it gets its old slot
// back straight away, otherwise it would have to wait for the dead socket to time out.
class BridgeServer(
    private val port: Int,
    private val maxClients: Int,
    private val listener: BridgeServerListener,
    private val roomFullMessage: String? = null,
    private val replaceSameAddress: Boolean = true,
    private val connectionLostSeconds: Int = 4,
) {
    private val slots = arrayOfNulls<WebSocket>(maxClients)
    private val addresses = arrayOfNulls<String>(maxClients)
    private var server: Server? = null

    val connectedSlots: List<Int>
        @Synchronized get() = slots.indices.filter { slots[it] != null }.map { it + 1 }

    @Synchronized
    fun start() {
        if (server != null) return
        val next = Server().apply {
            isReuseAddr = true
            connectionLostTimeout = connectionLostSeconds
        }
        server = next // set this before start so onStart knows it's the current server
        next.start()
    }

    // stop blocks while it closes everything so do it on another thread
    @Synchronized
    fun stop() {
        val old = server ?: return
        server = null
        slots.fill(null)
        addresses.fill(null)
        thread(name = "bridge-server-stop") { runCatching { old.stop(1_000) } }
    }

    fun send(slot: Int, text: String): Boolean {
        val socket = synchronized(this) { slots.getOrNull(slot - 1) } ?: return false
        return runCatching { socket.send(text) }.isSuccess
    }

    fun broadcast(text: String) {
        val sockets = synchronized(this) { slots.filterNotNull() }
        sockets.forEach { runCatching { it.send(text) } }
    }

    private inner class Server : WebSocketServer(InetSocketAddress(port)) {
        private val current: Boolean get() = synchronized(this@BridgeServer) { server === this }

        override fun onStart() {
            if (current) listener.onStarted()
        }

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val address = conn.remoteSocketAddress?.address?.hostAddress ?: "?"
            var replaced: WebSocket? = null
            // only close sockets outside the lock, same reason as in BridgeClient
            val slot = synchronized(this@BridgeServer) {
                if (server !== this) return@synchronized -2
                val reuse = if (replaceSameAddress) addresses.indexOfFirst { it == address } else -1
                val index = if (reuse >= 0) reuse else slots.indexOfFirst { it == null }
                if (index < 0) return@synchronized -1
                replaced = slots[index]
                slots[index] = conn
                addresses[index] = address
                index + 1
            }
            if (slot == -2) return conn.close()
            if (slot < 0) {
                roomFullMessage?.let { runCatching { conn.send(it) } }
                conn.close()
                return
            }
            replaced?.close()
            listener.onJoined(slot, address)
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
            val slot = synchronized(this@BridgeServer) {
                val index = slots.indexOfFirst { it === conn }
                if (index >= 0) {
                    slots[index] = null
                    addresses[index] = null
                }
                index + 1
            }
            if (slot > 0) listener.onLeft(slot)
        }

        override fun onMessage(conn: WebSocket, message: String) {
            val slot = synchronized(this@BridgeServer) { slots.indexOfFirst { it === conn } + 1 }
            if (slot > 0) listener.onMessage(slot, message)
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            // no connection here means the server itself failed, usually the port is taken
            if (conn == null && current) listener.onError(ex.message ?: "server error")
        }
    }
}
