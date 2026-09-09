package dev.isachivka.agtermremote.wire

import dev.isachivka.agtermremote.pairing.StreamKind
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
 * ### The scheme is DATA, and both of its values are load-bearing
 *
 * `ws://` is right when the phone reaches this bridge itself: a forwarded port, or a mesh network
 * that carries the packets. `wss://` is right when something publishes the Mac and terminates HTTPS
 * at its edge — a router that proxies rather than forwards, a tunnel, a reverse proxy in front. Both
 * are real deployments, both are in the onboarding's own list, and **neither can be inferred from
 * anything this app can see.**
 *
 * It was a constant twice, and both constants were wrong for somebody. `wss://` alone could not reach
 * a bridge with nothing in front of it; `ws://` alone could not reach a proxying router — which is
 * the deployment this project was written for. So it arrives in the pairing payload at version 2,
 * from the one place that knows: the owner, on the Mac.
 *
 * **Trying one and falling back to the other was considered and rejected.** It leaks nothing, since
 * the token is only sent after the inner handshake, but it doubles the worst-case connect and
 * replaces a declared fact with a heuristic — in a codebase whose whole failure-reporting discipline
 * is that it never guesses a cause.
 *
 * Neither value is a trust decision. The outer hop authenticates nobody in either form; the laptop is
 * identified by the byte-pinned mTLS *inside* the upgraded stream, which is the entire reason
 * `frontdoor` exists.
 *
 * ### The address arrives already bracketed
 *
 * Callers pass a *dial address* — `EnrollPayload.dialAddress` or `ConnectionProfile.dialAddress` —
 * because an IPv6 literal is bare in both the payload and the stored profile, and re-bracketing it in
 * a third place is how two of them end up disagreeing. The result is pinned across languages by
 * `dial_url` on every accept vector.
 */
internal object BridgeUrl {
    fun of(dialAddress: String, kind: StreamKind): String = "${scheme(kind)}://$dialAddress/"

    /**
     * The two spellings, and the only place the mapping exists on this side.
     *
     * `when` with no `else`, so a third [StreamKind] stops this compiling rather than silently
     * dialling plaintext.
     */
    private fun scheme(kind: StreamKind): String = when (kind) {
        StreamKind.DirectTcp -> "ws"
        StreamKind.DirectTls -> "wss"
    }
}
