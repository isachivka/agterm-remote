package dev.isachivka.agtermremote.wire

/**
 * Why the phone could not talk to the laptop.
 *
 * **A type, not a message.** The copy has to say *re-pair* for one of these and *your
 * laptop is not answering* for another, and it cannot make that distinction from a string. Iteration
 * 3 branches on this exhaustively.
 *
 * The distinction that costs the most to get wrong is [NotPinned] and [CertificateExpired] against
 * everything else. Those two mean *the identity is wrong and the owner must act*; the rest mean *the
 * laptop is not answering and the app cannot say why* — a rule this project has held throughout
 * and held to over the Immich report.
 */
sealed interface WireFailure {

    /** No laptop has been paired. The remedy is pairing, not retrying. */
    data object NotPaired : WireFailure

    // --- The identity is wrong. The owner must act; retrying will never help. --------------------

    /**
     * The bridge presented a certificate that is not the pinned one, byte for byte.
     *
     * Includes the case that looks most like success: a certificate correctly signed by the pinned
     * certificate's own key. Pinning compares bytes and has no concept of an issuer.
     */
    data object NotPinned : WireFailure

    /**
     * The pinned certificate's own validity window has passed.
     *
     * **Its own type, because byte-exactness alone would accept it forever.** PKIX does not validate
     * a trust anchor's dates and a pinned self-signed certificate IS its own anchor, so nothing
     * checks them unless this app does. Without this, `NotAfter` is decorative, the identity outlives
     * its stated lifetime, and nothing ever forces rotation — and it fails silently in the good
     * direction, so nothing reveals it.
     *
     * Separate from [NotPinned] because the remedy differs: this one means the laptop must mint a new
     * certificate, not that the phone is talking to the wrong machine.
     */
    data object CertificateExpired : WireFailure

    // --- The laptop is not answering, and the app does not guess why -----------------------------

    /** The name did not resolve, the connection was refused, or the route did not reach anything. */
    data object CannotReach : WireFailure

    /**
     * The router answered but the bridge behind it did not.
     *
     * Distinguishable because it is the shape a port mismatch takes — the proxy returns an error of
     * its own rather than the connection failing — and reporting it as [CannotReach] would send the
     * owner looking at their network for a two-digit disagreement between a router page and a config
     * file.
     */
    data object BridgeNotListening : WireFailure

    // --- Stalls, which must never be mistaken for slowness ----------------------------------------

    /**
     * Something took longer than its bound.
     *
     * **A stall gets its own type and its own stage**, because on the filtered network this feature
     * exists for a stall is indistinguishable from the feature working slowly — and because the
     * driver's own buffer bugs present exactly this way. An unbounded wait is how such a bug becomes
     * a support question instead of a stack trace.
     */
    data class TimedOut(val stage: Stage) : WireFailure {
        /**
         * **Every value here has a constructor, and that was checked rather than intended.**
         *
         * `WebSocketStream` builds `Connecting` and `AwaitingResponse`; `TlsDriver` builds
         * `Handshaking`. A fourth, `Idle`, was written here in advance for the driver and the driver
         * never had a use for it: an idle read is already bounded one layer down, where it is called
         * `AwaitingResponse`. It was deleted rather than kept for a future caller, because an enum
         * value nothing constructs is an unreachable branch wearing a different hat — it reads as
         * coverage and the next person preserves a state that never occurs.
         */
        enum class Stage { Connecting, Handshaking, AwaitingResponse }
    }

    /**
     * This phone's own key can never sign again. A new identity is the only remedy.
     *
     * **There is no retry for it and there must not be one.** No sequence of unlocks or taps ever
     * helps, so a retry button here would teach the owner that buttons are decorative. This value
     * outlived its sibling: `AuthenticationDeclined` meant the owner was asked to unlock and said no,
     * and it went with the unlock itself on 2026-07-29 — a key that never expires is never waiting
     * for anybody.
     *
     * The case that forced it into existence: **every key minted before `DIGEST_NONE` was added to the
     * spec is permanently in this state.** TLS 1.3 asks the key to sign a raw digest and the keystore
     * refuses. Before this value existed, that threw uncaught out of a pre-flight check that runs
     * before any socket opens — so the first launch after the digest fix crashed on opening Terminal
     * for exactly the owners who already had a key, which is all of them.
     *
     * It names the phone, not the laptop. The identity that is wrong is ours.
     */
    data object IdentityUnusable : WireFailure

    // --- Ours, not theirs -------------------------------------------------------------------------

    /**
     * This app stopped draining the stream while the far end kept sending.
     *
     * **Its own value because the blame is its own.** It was filed under [Malformed] first, which says
     * the far end spoke non-protocol — and a backlog says nothing of the sort. The bridge was behaving
     * correctly and the reader here was not, so copy derived from [Malformed] would have told the
     * owner their laptop was misbehaving about a laptop that was fine, and sent them to the wrong
     * machine to look for it.
     *
     * The remedy is to reconnect, so the copy is close to [CannotReach]'s. What must differ is the
     * cause it names, because a defect of ours reported as a defect of theirs is a defect that never
     * gets found.
     */
    data object ReaderFellBehind : WireFailure

    /**
     * The far end spoke, and what it said was not the protocol.
     *
     * **Decision owed in the driver**: the bridge's front door answers `400` — not a 5xx — when it is
     * up and refuses an upgrade, so a *live* bridge currently classifies as [CannotReach] and the app
     * would say "your laptop is not answering" about a laptop that answered. This may be that case's
     * home. Recorded here rather than fixed here, because the right answer depends on what the driver
     * can distinguish once it exists.
     */
    data object Malformed : WireFailure
}

/** Thrown across the driver's internals; converted to a [WireFailure] at the boundary. */
class WireException(val failure: WireFailure, cause: Throwable? = null) :
    Exception(failure.toString(), cause)
