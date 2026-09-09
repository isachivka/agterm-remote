package dev.isachivka.agtermremote.wire

import java.io.InputStream
import java.io.OutputStream

/**
 * A transport, reduced to the two things the mTLS above it actually needs.
 *
 * [WebSocketStream] is the only production implementation and it is not a subtype of this — see
 * [asByteStream]. The interface exists so that the layer above can be driven over a plain socket in a
 * test without the WebSocket framing, and so that the transport is nameable at all: before this, the
 * only way to reach [TlsDriver] was through a class that opens a real network connection in its
 * factory.
 *
 * **It is not an extension point.** Nothing outside this module can implement it, and nothing inside
 * it should acquire a second production implementation without a reason written down here: the whole
 * argument for pinning happening *above* the transport is that the transport is dumb.
 */
internal interface ByteStream : AutoCloseable {
    val input: InputStream
    val output: OutputStream
}

/**
 * The WebSocket as a byte stream.
 *
 * An adapter rather than `WebSocketStream : ByteStream`, because a public class cannot expose an
 * internal supertype and making the transport interface public would put a seam in this app's API
 * that only tests use.
 */
internal fun WebSocketStream.asByteStream(): ByteStream {
    val socket = this
    return object : ByteStream {
        override val input: InputStream get() = socket.input
        override val output: OutputStream get() = socket.output
        override fun close() = socket.close()
    }
}

/**
 * Where the phone dials, as a URL.
 *
 * ### `ws://` and not `wss://`, which is a correction rather than a preference
 *
 * The bridge in this repository terminates no outer TLS: `main.go` hands a bare `net.Listen("tcp")`
 * straight to `frontdoor.Listen`, so what is on the wire at the address the pairing code names is a
 * plain HTTP upgrade. This said `wss://` — carried over from a deployment where a router terminated
 * TLS in front — and against the bridge this project builds, that could not connect at all.
 *
 * The pinned mTLS *inside* the upgrade is what authenticates the laptop, and it is unchanged by this:
 * the outer TLS was never the trust decision, which is the entire reason `frontdoor` exists. What is
 * given up is the deployment where a proxy in front does terminate TLS — that needs a scheme the
 * pairing payload does not carry today, and it is a field in the payload rather than a guess here.
 *
 * ### The address arrives already bracketed
 *
 * Callers pass a *dial address* — `EnrollPayload.dialAddress` or `ConnectionProfile.dialAddress` —
 * because an IPv6 literal is bare in both the payload and the stored profile, and re-bracketing it in
 * a third place is how two of them end up disagreeing.
 */
internal object BridgeUrl {
    fun of(dialAddress: String): String = "ws://$dialAddress/"
}
