package dev.isachivka.agtermremote.wire

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status

/**
 * The inner half of the wire: pinned mTLS driven by hand over a byte stream.
 *
 * The router proxies rather than forwarding, so there is no `Socket` to hand to
 * `SSLSocketFactory` — only a WebSocket byte stream. `SSLEngine` is therefore driven directly:
 * `wrap` and `unwrap`, a handshake loop, and four buffers.
 *
 * ### Why this is written by hand rather than the easier way
 *
 * The easier way is a fake `Socket` over the stream so `SSLSocketFactory` does the work. It was
 * rejected not for how much risk it carries but for **where the risk lands**: it depends on which
 * `Socket` methods `SSLSocketImpl` happens to call, which is not contractual, so it fails on a
 * platform update, on the owner's phone, on the one device that cannot be iterated on or
 * instrumented. A defect CI can never see is worse than a harder defect CI can.
 *
 * ### The cost, and what answers it
 *
 * `SSLEngine` buffer bugs present as **stalls**, and a stall on the filtered network this feature
 * exists to measure is indistinguishable from the feature working slowly. So:
 *
 *  - **Nothing here waits without a bound**, and every bound names its stage — see [WireFailure.TimedOut].
 *  - **Every state this driver cannot reach fails loudly** rather than being handled politely.
 *    Careful handling of an impossible state is an unfalsifiable sentence in code form: it reads as
 *    thoroughness and is inherited as such.
 *
 * ### What was measured, and what is only argued
 *
 * The second bullet was written before this path had ever run, which made it a hope. It has now been
 * instrumented under `TlsDriverTest` — every `SSLEngineResult` recorded against the call site that
 * produced it — and these are the states that actually occurred:
 *
 * ```
 * handshake loop   NEED_WRAP · NEED_UNWRAP · NOT_HANDSHAKING
 * handshake wrap   OK -> NEED_UNWRAP · OK -> FINISHED
 * handshake unwrap OK -> NEED_TASK · OK -> NEED_UNWRAP · OK -> NEED_WRAP · BUFFER_UNDERFLOW
 * pump unwrap      OK -> FINISHED · OK -> NOT_HANDSHAKING · BUFFER_UNDERFLOW
 * write wrap       OK
 * ```
 *
 * Everything not in that list is either excluded by a contract quoted at the branch that refuses it,
 * or is reachable-but-unobserved and says so. **The distinction is the point**: "cannot happen" and
 * "did not happen here" are different claims, and only one of them earns an assertion.
 *
 * Two branches did not survive the measurement. Both read as diligence and neither could run: a
 * `FINISHED` arm on a status the javadoc says that getter never returns, and a `BUFFER_OVERFLOW`
 * handler that retried at an identical size forever, in the one loop with no deadline over it.
 */
internal class TlsDriver private constructor(
    private val engine: SSLEngine,
    private val transportIn: InputStream,
    private val transportOut: OutputStream,
) {

    /** Application bytes decrypted but not yet handed to the caller. */
    private var decrypted: ByteBuffer = ByteBuffer.allocate(0)

    /** Ciphertext read from the transport but not yet consumed by the engine. */
    private val inbound: ByteBuffer =
        ByteBuffer.allocate(engine.session.packetBufferSize).apply { limit(0) }

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (!decrypted.hasRemaining()) {
                if (!pump()) return -1
            }
            val n = minOf(len, decrypted.remaining())
            decrypted.get(b, off, n)
            return n
        }
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            val source = ByteBuffer.wrap(b, off, len)
            // A caller's payload can exceed one TLS record, so wrap until the source is drained
            // rather than assuming one call suffices. This is the write half of the straddle case.
            while (source.hasRemaining()) {
                val out = ByteBuffer.allocate(engine.session.packetBufferSize)
                val result = engine.wrap(source, out)
                when (result.status) {
                    Status.OK -> emit(out)
                    Status.CLOSED -> throw WireException(WireFailure.CannotReach)
                    Status.BUFFER_OVERFLOW -> throw unreachable(
                        "BUFFER_OVERFLOW from wrap into a buffer freshly allocated at the engine's " +
                            "own packetBufferSize, which is defined as the largest packet it can " +
                            "generate. This branch previously retried with a doubled buffer, which " +
                            "read as prudence and described a state that cannot occur",
                    )
                    Status.BUFFER_UNDERFLOW -> throw unreachable(
                        "BUFFER_UNDERFLOW from wrap: it means the SOURCE was short, and the source " +
                            "here is a fully-populated caller buffer",
                    )
                }
            }
            transportOut.flush()
        }
    }

    private fun emit(buffer: ByteBuffer) {
        buffer.flip()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        transportOut.write(bytes)
    }

    /**
     * Moves the stream forward by one step. Returns false at end of stream.
     *
     * The read half of the straddle case: a TLS record may arrive split across several transport
     * reads, and [Status.BUFFER_UNDERFLOW] is exactly the engine saying so. Reading more and retrying
     * is the whole mechanism, and getting it wrong is how this driver would stall rather than fail.
     */
    private fun pump(): Boolean {
        while (true) {
            if (inbound.hasRemaining()) {
                val out = ByteBuffer.allocate(engine.session.applicationBufferSize)
                val result = engine.unwrap(inbound, out)
                when (result.status) {
                    Status.OK -> {
                        runDelegatedTasks()
                        if (out.position() > 0) {
                            out.flip()
                            decrypted = out
                            return true
                        }
                        // A handshake record carries no application data; keep going. NEED_WRAP here
                        // is the post-handshake case — TLS 1.3 KeyUpdate on a connection held open,
                        // which is what this app does — so it is answered rather than ignored. It did
                        // not occur in the measured run; kept because it is genuinely reachable on a
                        // long-lived stream, which a short test does not stay open long enough to see.
                        if (result.handshakeStatus == HandshakeStatus.NEED_WRAP) handshakeWrap()
                        continue
                    }
                    Status.BUFFER_UNDERFLOW -> {
                        // Not enough for a whole record. Read more and retry - this is the frame
                        // boundary case, and the reason the straddle test exists.
                        if (!fill()) return false
                        continue
                    }
                    Status.BUFFER_OVERFLOW -> throw unreachable(
                        "BUFFER_OVERFLOW from unwrap into a buffer freshly allocated at the engine's " +
                            "own applicationBufferSize. This branch previously did `continue`, which " +
                            "re-allocated the SAME size and retried identically: an unbounded spin, " +
                            "in a loop with no deadline over it, wearing the words 'documented remedy'",
                    )
                    Status.CLOSED -> return false
                }
            }
            if (!fill()) return false
        }
    }

    /** Reads more ciphertext into [inbound]. Returns false at end of stream. */
    private fun fill(): Boolean {
        inbound.compact()
        val scratch = ByteArray(inbound.remaining())
        val n = transportIn.read(scratch)
        if (n <= 0) {
            inbound.flip()
            return false
        }
        inbound.put(scratch, 0, n)
        inbound.flip()
        return true
    }

    /**
     * Emits one handshake record.
     *
     * **The result is checked.** Discarding an `SSLEngineResult` is how the stall in this driver was
     * built: a call whose outcome nobody reads cannot report that it did nothing, so the loop above
     * spins on a status that never changes.
     */
    private fun handshakeWrap() {
        val out = ByteBuffer.allocate(engine.session.packetBufferSize)
        val result = engine.wrap(EMPTY, out)
        when (result.status) {
            Status.OK -> Unit
            Status.CLOSED -> throw WireException(WireFailure.CannotReach)
            Status.BUFFER_OVERFLOW, Status.BUFFER_UNDERFLOW -> throw unreachable(
                "${result.status} from a handshake wrap: the destination is freshly allocated at the " +
                    "engine's own packetBufferSize and the source is empty by construction",
            )
        }
        emit(out)
        transportOut.flush()
    }

    /**
     * Runs every task the engine is waiting on.
     *
     * **`getDelegatedTask()` is a mutating getter: it DEQUEUES.** Kotlin's property syntax makes
     * `engine.delegatedTask` read like a field, so testing it in a loop condition takes a task off the
     * queue and drops it on the floor unrun. That was the stall — the engine sat in `NEED_TASK`
     * forever, the loop spun without blocking, and the only thing that ever ended it was the deadline.
     * It is written this way, fetching exactly once per iteration, so the value that is tested is the
     * value that is run.
     *
     * Run inline rather than on a pool: this driver is already on a background thread and a pool would
     * add a scheduling boundary to the part of the handshake that must stay bounded.
     */
    private fun runDelegatedTasks() {
        while (true) {
            val task = engine.delegatedTask ?: return
            task.run()
        }
    }

    /**
     * Drives the handshake to completion.
     *
     * **The deadline is not a network bound and must not be read as one.** Waiting on the far end is
     * bounded a layer below, in `WebSocketStream`, and surfaces there as `AwaitingResponse`. What this
     * one catches is the case where nothing is waiting at all: the engine reports a status this loop
     * does not advance, and the loop spins at full speed forever. That is a defect in this file, not a
     * slow laptop, and [WireFailure.TimedOut] with [WireFailure.TimedOut.Stage.Handshaking] is what it
     * is for.
     *
     * It has already done that job once rather than hypothetically: `getDelegatedTask` dequeues, the
     * tasks were fetched and dropped unrun, and the engine sat in `NEED_TASK` while this loop turned.
     * The bound is why that arrived as a named failure at a line number in seconds instead of as a
     * phone that never finishes connecting.
     */
    private fun handshake(deadlineNanos: Long) {
        engine.beginHandshake()
        while (true) {
            if (System.nanoTime() > deadlineNanos) {
                throw WireException(WireFailure.TimedOut(WireFailure.TimedOut.Stage.Handshaking))
            }
            when (val status = engine.handshakeStatus) {
                HandshakeStatus.NEED_WRAP -> handshakeWrap()
                HandshakeStatus.NEED_UNWRAP -> {
                    // A false from `fill` is end of stream, not a stall: the far end closed mid
                    // handshake. Naming it TimedOut would report a bound that was never reached and
                    // send iteration 3's copy to "it is taking too long" about a shut door.
                    if (!inbound.hasRemaining() && !fill()) {
                        throw WireException(WireFailure.CannotReach)
                    }
                    val out = ByteBuffer.allocate(engine.session.applicationBufferSize)
                    val result = engine.unwrap(inbound, out)
                    // The RESULT decides what to do next, not the handshake status alone. Reading it
                    // fixed a real defect — on a partial record the status stays NEED_UNWRAP while
                    // `inbound` still holds bytes, so the loop could spin without ever reading more.
                    // It was NOT the stall this driver actually had; that one was the delegated tasks,
                    // and it was found by instrumenting rather than by reasoning. Recorded because the
                    // gap between the two is the whole lesson: a plausible fix that changes nothing
                    // looks exactly like a correct one until something measures it.
                    when (result.status) {
                        Status.BUFFER_UNDERFLOW ->
                            if (!fill()) throw WireException(WireFailure.CannotReach)
                        Status.CLOSED -> throw WireException(WireFailure.CannotReach)
                        Status.OK -> runDelegatedTasks()
                        Status.BUFFER_OVERFLOW -> throw unreachable(
                            "BUFFER_OVERFLOW during handshake unwrap: the destination is freshly " +
                                "allocated at the engine's own applicationBufferSize, and a handshake " +
                                "record carries no application data to overflow it",
                        )
                    }
                }
                // Not reached in the measured run — every NEED_TASK there came back from `unwrap` and
                // was run on the spot. Kept because `wrap` may also return it and nothing here runs
                // tasks on that path, so this is the arm that would catch it.
                HandshakeStatus.NEED_TASK -> runDelegatedTasks()
                HandshakeStatus.NOT_HANDSHAKING -> return
                else -> throw unreachable(
                    "handshake status $status from getHandshakeStatus(). Two values are excluded by " +
                        "the platform's own contract rather than by belief: FINISHED, which the " +
                        "javadoc says 'is never generated by SSLEngine.getHandshakeStatus()', and " +
                        "NEED_UNWRAP_AGAIN, which it says 'only applies to DTLS'. FINISHED had an arm " +
                        "here that could never be entered. Any other value is a platform change worth " +
                        "stopping for",
                )
            }
        }
    }

    /**
     * The failure for a state this driver cannot reach.
     *
     * Loud on purpose. A polite branch here would read as careful handling of a case that never
     * occurs, and would be preserved forever by readers who assume it once did.
     */
    private fun unreachable(why: String): Throwable = IllegalStateException("unreachable: $why")

    companion object {
        private val EMPTY: ByteBuffer = ByteBuffer.allocate(0)

        /** Bounds the cause walk. A cause cycle is rare and a hang is not an acceptable way to meet it. */
        private const val MAX_CAUSE_DEPTH = 16

        /**
         * Why the handshake failed, asked of the trust manager first.
         *
         * **Order is the whole point.** [PinnedTrust.verdict] is a field on an object this method
         * already holds, so reading it crosses no provider boundary: it is true on the JVM and on
         * Conscrypt for the same reason. The cause walk below is a fallback for a refusal raised
         * somewhere this code did not put it, not the mechanism — a verdict recovered from a chain no
         * provider promises to preserve is a distinction that passes in CI and can vanish on the
         * owner's phone, which is the failure mode this whole class is arranged to avoid.
         *
         * Anything with no verdict behind it is [WireFailure.Malformed]: a genuine protocol failure,
         * and it must never be reported as a pinning one.
         */
        private fun verdictFor(trust: PinnedTrust, e: Throwable): WireFailure =
            trust.verdict
                ?: generateSequence(e) { it.cause }
                    .take(MAX_CAUSE_DEPTH)
                    .firstNotNullOfOrNull {
                        (it as? PinnedTrustRefusal)?.failure ?: (it as? WireException)?.failure
                    }
                ?: WireFailure.Malformed

        /**
         * Wraps a transport in pinned mTLS.
         *
         * [identity] is the phone's own certificate and key — on a device, backed by the hardware
         * keystore, whose private key has no extractable form. It is injected because it differs
         * between the device and a test rig; **the trust decision is not**, and is built here from
         * [pinnedBridgeCertificate] so no caller can supply a weaker one.
         */
        fun open(
            transportIn: InputStream,
            transportOut: OutputStream,
            pinnedBridgeCertificate: X509Certificate,
            identity: KeyManager,
            handshakeTimeoutSeconds: Long = 15,
        ): TlsDriver {
            val trust = PinnedTrust(pinnedBridgeCertificate)
            val context = SSLContext.getInstance("TLSv1.3").apply {
                init(arrayOf(identity), arrayOf(trust), null)
            }
            val engine = context.createSSLEngine().apply {
                useClientMode = true
            }
            val driver = TlsDriver(engine, transportIn, transportOut)
            try {
                driver.handshake(System.nanoTime() + handshakeTimeoutSeconds * 1_000_000_000L)
            } catch (e: WireException) {
                // Already a verdict the caller can branch on - re-wrapping it could only lose
                // information.
                throw e
            } catch (e: IllegalStateException) {
                // Rule 3's loudness. A state this driver cannot reach must not be quietly converted
                // into a WireFailure the copy will describe as an ordinary network problem.
                throw e
            } catch (e: Exception) {
                throw WireException(verdictFor(trust, e), e)
            }
            return driver
        }
    }
}
