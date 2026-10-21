package dev.posebridge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

// real sockets on localhost
class BridgeLoopbackTest {
    private val port = ServerSocket(0).use { it.localPort }
    private val closeables = mutableListOf<() -> Unit>()

    @AfterEach
    fun tearDown() = closeables.reversed().forEach { runCatching { it() } }

    private class ServerEvents : BridgeServerListener {
        val log = CopyOnWriteArrayList<String>()
        override fun onStarted() { log += "started" }
        override fun onJoined(slot: Int, address: String) { log += "joined $slot" }
        override fun onLeft(slot: Int) { log += "left $slot" }
        override fun onMessage(slot: Int, text: String) { log += "msg $slot $text" }
        override fun onError(message: String) { log += "error $message" }
    }

    private class ClientEvents : BridgeClientListener {
        val messages = CopyOnWriteArrayList<String>()
        val statuses = CopyOnWriteArrayList<LinkStatus>()
        override fun onStatus(status: LinkStatus, error: String?) { statuses += status }
        override fun onMessage(text: String) { messages += text }
    }

    private fun server(events: BridgeServerListener, max: Int = 2, replace: Boolean = false, full: String? = null) =
        BridgeServer(port, max, events, full, replace).also { s ->
            s.start()
            closeables += { s.stop() }
        }

    private fun client(events: BridgeClientListener, reconnectMs: Long = 300) =
        BridgeClient(events, reconnectMs).also { c ->
            c.connect(BridgeClient.uriFor("127.0.0.1", port))
            closeables += { c.close() }
        }

    private fun eventually(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    @Test
    fun `messages flow both ways`() {
        val serverEvents = ServerEvents()
        val bridge = server(serverEvents)
        eventually("server start") { "started" in serverEvents.log }

        val phoneEvents = ClientEvents()
        val phone = client(phoneEvents)
        eventually("join") { "joined 1" in serverEvents.log }
        eventually("client connected") { phone.status == LinkStatus.CONNECTED }

        assertTrue(phone.send("""{"type":"fault","kind":"KNEES_CAVE"}"""))
        eventually("message at server") { serverEvents.log.any { it.startsWith("msg 1 ") } }
        assertTrue(bridge.send(1, "hello"))
        bridge.broadcast("everyone")
        eventually("messages at client") { phoneEvents.messages == listOf("hello", "everyone") }
    }

    @Test
    fun `two clients get two slots and a third gets room full`() {
        val serverEvents = ServerEvents()
        val bridge = server(serverEvents, max = 2, full = "room full")
        eventually("server start") { "started" in serverEvents.log }

        client(ClientEvents())
        eventually("first join") { "joined 1" in serverEvents.log }
        client(ClientEvents())
        eventually("second join") { "joined 2" in serverEvents.log }
        assertEquals(listOf(1, 2), bridge.connectedSlots)

        val third = ClientEvents()
        client(third)
        eventually("room full message") { "room full" in third.messages }
        assertEquals(listOf(1, 2), bridge.connectedSlots)
    }

    @Test
    fun `a client from the same address takes over its slot`() {
        val serverEvents = ServerEvents()
        val bridge = server(serverEvents, max = 2, replace = true)
        eventually("server start") { "started" in serverEvents.log }

        val first = client(ClientEvents())
        eventually("first join") { "joined 1" in serverEvents.log }
        val second = client(ClientEvents())
        eventually("second takes slot 1") { serverEvents.log.count { it == "joined 1" } == 2 }
        assertEquals(listOf(1), bridge.connectedSlots)
        assertTrue(second.send("still here"))
        eventually("message from new socket") { "msg 1 still here" in serverEvents.log }
        assertTrue(first.status != LinkStatus.IDLE) // old one keeps retrying, the app would stop it
    }

    @Test
    fun `client keeps retrying until the server appears, and again after it restarts`() {
        val phoneEvents = ClientEvents()
        val phone = client(phoneEvents)
        eventually("retrying with no server") { phone.status == LinkStatus.RETRYING }

        val serverEvents = ServerEvents()
        val first = BridgeServer(port, 2, serverEvents, replaceSameAddress = false)
        first.start()
        eventually("connected once the server is up") { phone.status == LinkStatus.CONNECTED }

        first.stop()
        eventually("notices the server went away") { phone.status == LinkStatus.RETRYING }
        Thread.sleep(300)

        val second = server(ServerEvents())
        eventually("reconnected to the restarted server", timeoutMs = 8_000) { phone.status == LinkStatus.CONNECTED }
        assertTrue(phone.send("back"))
        second.stop()
    }

    @Test
    fun `disconnect frees the slot`() {
        val serverEvents = ServerEvents()
        val bridge = server(serverEvents)
        eventually("server start") { "started" in serverEvents.log }
        val phone = client(ClientEvents())
        eventually("join") { "joined 1" in serverEvents.log }

        phone.disconnect()
        eventually("left") { "left 1" in serverEvents.log }
        assertEquals(emptyList<Int>(), bridge.connectedSlots)
        assertEquals(LinkStatus.IDLE, phone.status)
    }

    @Test
    fun `ipv6 hosts get brackets`() {
        assertEquals("ws://[fe80::1]:8765", BridgeClient.uriFor("fe80::1", 8765).toString())
        assertEquals("ws://192.168.1.4:8765", BridgeClient.uriFor("192.168.1.4", 8765).toString())
    }
}
