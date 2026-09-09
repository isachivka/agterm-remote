package dev.isachivka.agtermremote.wire

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The outer half of the wire: a WebSocket to the router, presented as a byte stream.
 *
 * The router in front of the laptop PROXIES rather than forwards, so it terminates its own TLS and
 * the client certificate cannot survive the trip. mTLS therefore runs *inside* this stream, and this class
 * exists to give the driver above it something that reads and writes bytes.
 *
 * ### Two trust decisions, and this class makes only one of them
 *
 * **This layer trusts the router's certificate by ordinary public-CA validation, and nothing else.**
 * The client is unmodified: platform trust store, platform hostname verifier, no exceptions. Measured
 * on-device rather than assumed: the chain reaches ISRG Root X1 on Android's own store.
 *
 * The *inner* decision — which laptop this actually is — is not made here and must never be. It is
 * the pinned mTLS above, terminating on the laptop. Collapsing the two would make the pinning
 * decorative: the router being who it says it is tells you nothing about who is behind it.
 *
 * ### Every wait is bounded, and a stall is nameable
 *
 * A stall on the network this feature exists to measure is indistinguishable from the feature working
 * slowly, so no wait here is unbounded and each one names its stage. That matters most for the driver
 * above: its failure mode is buffer handling, which presents as a hang rather than an error.
 */
class WebSocketStream private constructor(
    private val socket: WebSocket,
    private val incoming: LinkedBlockingQueue<Chunk>,
    private val failure: AtomicReference<WireFailure?>,
) : AutoCloseable {

    /** A received message, or the end of the stream. */
    private sealed interface Chunk {
        @JvmInline value class Data(val bytes: ByteArray) : Chunk
        data object End : Chunk
    }

    val input: InputStream = object : InputStream() {
        private var pending: ByteArray = ByteArray(0)
        private var offset = 0
        private var finished = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (offset >= pending.size) {
                if (finished) return -1
                // Bounded, and the bound names itself. A queue poll with no timeout is precisely the
                // unbounded wait that turns a buffer bug into a support question.
                val chunk = incoming.poll(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    ?: throw WireException(WireFailure.TimedOut(WireFailure.TimedOut.Stage.AwaitingResponse))
                when (chunk) {
                    is Chunk.End -> {
                        finished = true
                        failure.get()?.let { throw WireException(it) }
                        return -1
                    }
                    is Chunk.Data -> {
                        pending = chunk.bytes
                        offset = 0
                    }
                }
            }
            val n = minOf(len, pending.size - offset)
            System.arraycopy(pending, offset, b, off, n)
            offset += n
            return n
        }
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            // One message per write. The driver above hands whole TLS records, and splitting them
            // further would only make the frame boundaries the straddle test cares about finer.
            if (!socket.send(b.toByteString(off, len))) {
                throw WireException(failure.get() ?: WireFailure.CannotReach)
            }
        }
    }

    override fun close() {
        socket.close(NORMAL_CLOSURE, null)
    }

    companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 20L
        /** Generous: the driver above reads continuously, so a backlog this deep means it has stopped. */
        const val MAX_QUEUED_MESSAGES = 256
        private const val NORMAL_CLOSURE = 1000

        /**
         * Opens the WebSocket, or fails with a reason the copy can branch on.
         *
         * The client is deliberately built here rather than injected: a caller supplying one could
         * supply a weakened one, and this is the layer where that would be invisible.
         */
        @Throws(WireException::class)
        fun open(url: String): WebSocketStream {
            val client = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // the stream is long-lived; bounds live above
                .pingInterval(30, TimeUnit.SECONDS)
                .build()

            // Bounded. Not an attack surface behind the router's TLS, but an unbounded queue is a
            // memory bound that exists only in the reader keeping up, and that is a property of the
            // driver above rather than of anything asserted here.
            val incoming = LinkedBlockingQueue<Chunk>(MAX_QUEUED_MESSAGES)
            val failure = AtomicReference<WireFailure?>(null)
            val opened = LinkedBlockingQueue<Boolean>(1)

            val socket = client.newWebSocket(
                Request.Builder().url(url).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened.offer(true)
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        // A full queue means the reader has stopped draining while the far end keeps
                        // sending. Failing is right: silently dropping would corrupt the TLS stream
                        // above in a way that presents as a handshake or MAC error much later, with
                        // nothing pointing here.
                        //
                        // Reported as OURS. This said Malformed first, which claims the far end spoke
                        // non-protocol — but the far end spoke perfectly and this reader fell behind.
                        if (!incoming.offer(Chunk.Data(bytes.toByteArray()))) {
                            failure.compareAndSet(null, WireFailure.ReaderFellBehind)
                            webSocket.cancel()
                            // Everything already queued goes, and the end marker takes its place.
                            //
                            // Two reasons, and the first is correctness rather than tidiness. The
                            // stream is dead the moment a message is dropped, so handing up the ones
                            // that arrived before it died gives the mTLS above a record stream with a
                            // hole in it: it fails later, as a MAC error, pointing nowhere near here.
                            //
                            // The second is that `offer` into a FULL queue is exactly what just
                            // failed. Enqueuing the marker without making room is a line that reads
                            // like an end-of-stream signal and silently is not, and the reader would
                            // sit until its 20-second bound and then name the wrong thing.
                            incoming.clear()
                            incoming.offer(Chunk.End)
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        incoming.offer(Chunk.End)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        failure.compareAndSet(null, classify(response))
                        opened.offer(false)
                        incoming.offer(Chunk.End)
                    }
                },
            )

            val ok = opened.poll(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                ?: throw WireException(WireFailure.TimedOut(WireFailure.TimedOut.Stage.Connecting))
            if (!ok) throw WireException(failure.get() ?: WireFailure.CannotReach)

            return WebSocketStream(socket, incoming, failure)
        }

        /**
         * Turns a transport failure into something the copy can branch on.
         *
         * **There is exactly one distinction here, and everything else is deliberately
         * undifferentiated.** A 5xx from the proxy means the router answered and the bridge behind it
         * did not — the shape a port mismatch takes — and reporting that as "cannot reach" would send
         * the owner looking at their carrier for two digits disagreeing between a router page and a
         * config file.
         *
         * Everything else is [WireFailure.CannotReach], including cases this code could easily
         * *appear* to tell apart. A DNS failure, a refused connection, a dropped route and a TLS
         * failure are four different exception types and the same fact from here: **the laptop is not
         * answering and this app cannot observe why.** That rule has held throughout, and an arm that
         * matched on exception type while returning the same value
         * would read as a considered classification and classify nothing — preserved by the next
         * person as a distinction that never existed.
         *
         * If a genuine distinction is ever available, it earns a case by producing a different value.
         */
        private fun classify(response: Response?): WireFailure =
            if (response != null && response.code in 500..599) {
                WireFailure.BridgeNotListening
            } else {
                WireFailure.CannotReach
            }
    }
}
