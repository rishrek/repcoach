# pose-bridge

A tiny Kotlin library for streaming pose data from phones to a TV (or any host) over local Wi-Fi with WebSockets. Extracted from [RepCoach](../README.md).

- **`BridgeServer`**: fixed player slots (e.g. 2 for a duel). A client beyond the limit gets a "room full" message and is closed. A client reconnecting from the same address takes its old slot straight back. Dead connections are found by ping/pong.
- **`BridgeClient`**: keeps a link up. It retries every few seconds whenever the connection fails or drops, until you disconnect. It reports `IDLE / CONNECTING / CONNECTED / RETRYING`.
- Plain Kotlin on the JVM: works in Android apps (including Fire OS, no Google Play Services) and desktop tools. Message format is yours: it sends text.

## Use

```kotlin
// TV / host
val server = BridgeServer(port = 8765, maxClients = 2, listener = object : BridgeServerListener {
    override fun onJoined(slot: Int, address: String) = println("player $slot from $address")
    override fun onMessage(slot: Int, text: String) = println("$slot: $text")
}, roomFullMessage = """{"type":"roomFull"}""")
server.start()
server.send(1, "hello")

// Phone / client
val client = BridgeClient(object : BridgeClientListener {
    override fun onStatus(status: LinkStatus, error: String?) = println(status)
    override fun onMessage(text: String) = println(text)
})
client.connect(BridgeClient.uriFor("192.168.1.20", 8765))
client.send("""{"type":"pose","points":[...]}""")
```

Listener callbacks run on network threads. Post them to your UI thread as needed.

## Lock rule

Neither class ever calls into a socket (connect, close) while holding its own lock. The socket's read thread holds the socket's lock while it delivers `onClose`, so doing it the other way round deadlocks. The tests caught exactly that.

## Tests

Real sockets on localhost: messages both ways, room full, slot takeover, retry until the server appears and after it restarts.

```bash
./gradlew :pose-bridge:test
```

## Depends on

[Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket) 1.6.0 (MIT).

## License

MIT, see [LICENSE](LICENSE).
