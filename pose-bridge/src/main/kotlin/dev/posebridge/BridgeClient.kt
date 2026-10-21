package dev.posebridge

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

enum class LinkStatus { IDLE, CONNECTING, CONNECTED, RETRYING }

// called on the network threads
interface BridgeClientListener {
    fun onStatus(status: LinkStatus, error: String?) {}
    fun onMessage(text: String) {}
}

// Keeps trying to stay connected. If the connection fails or drops it tries again every
// couple of seconds until you call disconnect. Every attempt gets a new socket because an
// old WebSocketClient can't really be reused.
class BridgeClient(
    private val listener: BridgeClientListener,
    private val reconnectMs: Long = 2_000,
    private val connectionLostSeconds: Int = 4,
) : AutoCloseable {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bridge-client").apply { isDaemon = true } }
    private var retry: ScheduledFuture<*>? = null
    private var uri: URI? = null

    @Volatile private var active: Connection? = null

    @Volatile var status = LinkStatus.IDLE
        private set

    // Never connect or close a socket while holding our own lock. The socket's read thread
    // holds its lock when it calls onClose and then wants ours, we hit that deadlock in testing.

    // calling this again with the same address does nothing
    fun connect(target: URI) {
        val (old, next) = synchronized(this) {
            if (uri == target && active != null) return
            val old = detach()
            uri = target
            old to prepare()
        }
        old?.close()
        next?.connect()
    }

    fun disconnect() {
        val old = synchronized(this) {
            detach().also { report(LinkStatus.IDLE, null) }
        }
        old?.close()
    }

    // returns false when we're not connected
    fun send(text: String): Boolean {
        val current = active ?: return false
        if (!current.isOpen) return false
        return runCatching { current.send(text) }.isSuccess
    }

    override fun close() {
        disconnect()
        scheduler.shutdownNow()
    }

    // call with the lock held, returns the old socket so it can be closed outside
    private fun detach(): Connection? {
        uri = null // clear this first so closing doesn't trigger a retry
        retry?.cancel(false)
        retry = null
        return active.also { active = null }
    }

    // call with the lock held, the caller connects the socket afterwards
    private fun prepare(): Connection? {
        val target = uri ?: return null
        report(LinkStatus.CONNECTING, null)
        return Connection(target).also { active = it }
    }

    private fun reopen() {
        synchronized(this) { prepare() }?.connect()
    }

    @Synchronized
    private fun dropped(from: Connection, reason: String?) {
        if (from !== active || uri == null) return
        active = null
        report(LinkStatus.RETRYING, reason?.takeIf { it.isNotBlank() })
        retry = runCatching { scheduler.schedule(::reopen, reconnectMs, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    private fun report(next: LinkStatus, error: String?) {
        status = next
        listener.onStatus(next, error)
    }

    private inner class Connection(target: URI) : WebSocketClient(target) {
        init {
            connectionLostTimeout = connectionLostSeconds
        }

        override fun onOpen(handshake: ServerHandshake) {
            if (this === active) report(LinkStatus.CONNECTED, null)
        }

        override fun onMessage(message: String) {
            if (this === active) listener.onMessage(message)
        }

        // a failed connect calls onError and then onClose, so we retry from onClose
        override fun onClose(code: Int, reason: String?, remote: Boolean) = dropped(this, reason)

        override fun onError(ex: Exception) = Unit
    }

    companion object {
        // builds the websocket address, ipv6 hosts need brackets
        fun uriFor(host: String, port: Int): URI {
            val h = if (':' in host && !host.startsWith("[")) "[$host]" else host
            return URI("ws://$h:$port")
        }
    }
}
