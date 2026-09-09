package dev.isachivka.bewareofsugar.wire

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The outer half of the wire, exercised as a byte stream against a real WebSocket on the JVM.
 *
 * What these cannot cover is the inner mTLS - that is the driver's half and its own review. What they
 * establish is the precondition the driver depends on: that this layer presents a continuous stream
 * rather than message-shaped lumps, and that a stall here is nameable rather than a hang.
 */
class WebSocketStreamTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer() }
    @After fun tearDown() { server.close() }

    private fun wsUrl() = server.url("/").toString().replaceFirst("http", "ws")

    private fun echo(): String {
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    webSocket.send(bytes)
                }

                // Answer the closing handshake. Without it MockWebServer waits for a peer that has
                // already gone and fails teardown - a test-side omission, not a client one: the
                // client sends a correct close frame either way.
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                }
            }).build(),
        )
        server.start()
        return wsUrl()
    }

    private fun readFully(stream: WebSocketStream, size: Int, chunk: Int): ByteArray {
        val got = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = stream.input.read(got, read, minOf(chunk, size - read))
            if (n <= 0) break
            read += n
        }
        assertEquals("short read", size, read)
        return got
    }

    @Test
    fun `bytes survive a round trip`() {
        val url = echo()
        val payload = ByteArray(64) { (it * 3).toByte() }

        WebSocketStream.open(url).use { stream ->
            stream.output.write(payload)
            assertTrue(payload.contentEquals(readFully(stream, payload.size, 64)))
        }
    }

    /**
     * The precondition for the driver's straddle test: a caller reading in small chunks sees a
     * continuous stream, not message boundaries. If this layer leaked framing, the driver above would
     * inherit it as a TLS record that arrives in pieces it did not ask for.
     */
    @Test
    fun `a payload larger than one read is reassembled in order`() {
        val url = echo()
        val payload = ByteArray(8192) { (it % 251).toByte() }

        WebSocketStream.open(url).use { stream ->
            stream.output.write(payload)
            // Deliberately awkward chunk size: the stream must not require the caller to know the
            // message size, or any multiple of it.
            assertTrue(payload.contentEquals(readFully(stream, payload.size, 97)))
        }
    }

    /**
     * A stall is nameable.
     *
     * A server that accepts the upgrade and then says nothing is exactly what a buffer bug in the
     * driver above will look like from here, and on the network this feature exists for that is
     * indistinguishable from slowness. It must surface as a type naming its stage, never as a hang.
     */
    @Test
    fun `a silent far end times out as a named stall rather than hanging`() {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {}).build())
        server.start()

        val stream = WebSocketStream.open(wsUrl())
        val started = System.nanoTime()
        val failure = runCatching { stream.input.read(ByteArray(1)) }.exceptionOrNull()
        val elapsed = (System.nanoTime() - started) / 1_000_000_000.0

        val wire = failure as? WireException
        assertTrue("a stall must be a WireException, was $failure", wire != null)
        assertEquals(WireFailure.TimedOut(WireFailure.TimedOut.Stage.AwaitingResponse), wire!!.failure)
        assertTrue("must be bounded, took ${elapsed}s", elapsed < WebSocketStream.READ_TIMEOUT_SECONDS + 5)
    }

    @Test
    fun `a refused upgrade is not reported as a stall`() {
        server.enqueue(MockResponse.Builder().code(404).build())
        server.start()

        val failure = runCatching { WebSocketStream.open(wsUrl()) }.exceptionOrNull() as? WireException

        assertTrue("must fail", failure != null)
        assertTrue(
            "a refusal is not a stall and must not be reported as one",
            failure!!.failure !is WireFailure.TimedOut,
        )
    }

    /**
     * A backlog is reported as ours, and reported promptly.
     *
     * The far end here does nothing wrong: it sends well-formed messages faster than this side drains
     * them. That is a defect of the reader, and calling it [WireFailure.Malformed] - as this did -
     * would blame the bridge for the app's own backlog and send the owner to the wrong machine.
     *
     * **The wait is on the condition, not on a clock.** The test proceeds only once the client has
     * actually cancelled the socket, which is the observable consequence of the overflow. A sleep here
     * would pass on a fast machine and be re-run on a slow one, which is how a real failure gets
     * dismissed as a flake.
     */
    @Test
    fun `a backlog is reported as ours rather than as the far end misbehaving`() {
        val clientGaveUp = java.util.concurrent.CountDownLatch(1)
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    // Comfortably past the queue, with nothing on this side draining it.
                    repeat(WebSocketStream.MAX_QUEUED_MESSAGES * 2) {
                        webSocket.send(ByteString.of(*ByteArray(64)))
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) =
                    clientGaveUp.countDown()

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                    clientGaveUp.countDown()
            }).build(),
        )
        server.start()

        val stream = WebSocketStream.open(wsUrl())
        assertTrue(
            "the client must overflow and cancel; if it did not, this test proves nothing",
            clientGaveUp.await(10, java.util.concurrent.TimeUnit.SECONDS),
        )

        val started = System.nanoTime()
        val failure = runCatching { stream.input.read(ByteArray(64)) }.exceptionOrNull()
        val elapsed = (System.nanoTime() - started) / 1_000_000_000.0

        // **The FIRST read, not eventually.** Once the socket has been cancelled the stream is dead,
        // and handing up the messages that were queued before it died is worse than dropping them
        // outright: the mTLS above would consume a record stream with a hole in it and fail much later
        // with a MAC error that points nowhere near here. Draining first would also make this test
        // race the cancel callback for the end-of-stream marker, and pass or hang on who won.
        assertEquals(WireFailure.ReaderFellBehind, (failure as? WireException)?.failure)
        assertTrue("the backlog must surface at once, took ${elapsed}s", elapsed < 5)
    }

    /** A 5xx from the proxy is the shape a port mismatch takes, and must not read as a network fault. */
    @Test
    fun `a proxy error is reported as the bridge not listening`() {
        server.enqueue(MockResponse.Builder().code(502).build())
        server.start()

        val failure = runCatching { WebSocketStream.open(wsUrl()) }.exceptionOrNull() as? WireException

        assertEquals(WireFailure.BridgeNotListening, failure?.failure)
    }
}
