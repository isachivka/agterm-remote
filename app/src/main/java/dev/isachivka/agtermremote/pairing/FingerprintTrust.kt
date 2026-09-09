package dev.isachivka.agtermremote.pairing

import dev.isachivka.agtermremote.wire.PinnedTrust
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * The trust decision for the ONE handshake that happens before anything is pinned.
 *
 * ### Why this exists next to [PinnedTrust] rather than instead of it
 *
 * They pin different things at different moments and neither can do the other's job.
 *
 * [PinnedTrust] compares the bridge's **exact DER**, which the phone only has once a successful
 * enrolment has handed it over. Before that the phone holds 32 bytes off a QR code — a digest, not a
 * certificate — so the comparison has to run the other way: hash what was presented and compare the
 * digests. Same strength, because SHA-256 is what makes "the exact bytes" checkable at all; different
 * input, because that is all the code carries.
 *
 * The two are also *ordered*, and the order is the point of the design: this one authenticates the
 * connection over which the real certificate arrives, and from the next connection onwards
 * [PinnedTrust] compares bytes. Nothing keeps using a fingerprint after it has the thing itself.
 *
 * ### This narrows. It never widens.
 *
 * A permissive trust manager and this one use the same platform hook and are opposites — which is why
 * the argument sits next to the code rather than in a design document. What is accepted here is
 * exactly one certificate, identified by a digest the owner's own screen produced, over a connection
 * that has not yet been given the one-time token. **A man in the middle fails here, before any secret
 * is offered**, and that sentence is the whole security argument for showing a token on a screen.
 *
 * ### No validity check, deliberately, and the asymmetry with [PinnedTrust] is real
 *
 * [PinnedTrust] checks `NotAfter` because a pinned self-signed certificate is its own trust anchor and
 * PKIX does not validate an anchor's own dates — so nothing else would. This one does not, and that is
 * not an omission carried over by inattention:
 *
 *  - The bridge refuses to pin a phone whose certificate is outside its validity window, and its own
 *    certificate is minted by the same code with the same twenty-year life. An expired bridge
 *    certificate at this moment means the laptop cannot serve anybody, which is a laptop problem the
 *    phone cannot fix by refusing differently.
 *  - Refusing here would report "this is not the laptop in the code" for a laptop that IS the one in
 *    the code, sending the owner to re-scan forever. The honest failure surfaces on the next
 *    connection, in [PinnedTrust], where the remedy — mint a new certificate on the Mac — is what
 *    that verdict already says.
 *
 * The dates are therefore judged once, by the layer that pins bytes, and not twice with two remedies.
 */
class FingerprintTrust(private val expected: ByteArray) : X509TrustManager {

    init {
        // A digest is 32 bytes. Anything else cannot be one, so it could never match and every
        // handshake made with it would fail identically to a wrong laptop — which is the wrong thing
        // to tell the owner. It is a broken pairing code, and it is caught where the code is handed
        // over rather than where a connection is blamed.
        require(expected.size == DIGEST_LENGTH) {
            "a SHA-256 fingerprint is $DIGEST_LENGTH bytes, not ${expected.size}"
        }
    }

    /**
     * Whether this manager refused the laptop, readable without an exception.
     *
     * **This is how the caller learns the reason, and the exception is not.** The lesson is
     * [PinnedTrust.verdict]'s and was measured there: a refusal has to travel from here, through the
     * provider's handshake machinery, to a caller that branches on it, and everything in between is
     * provider *behaviour* rather than contract. JSSE wraps whatever a trust manager throws and
     * nothing promises the original survives as a cause. It does on the JVM provider the tests run
     * against; the app runs on Conscrypt.
     *
     * Recovering it from a cause chain would therefore be a mechanism that passes in CI and can fail
     * on the owner's phone — reporting "your Mac is not answering" about a Mac that answered with the
     * wrong certificate, which is the exact confusion the four-armed result type exists to prevent.
     *
     * `@Volatile` because the handshake may run the trust check on a different thread from the one
     * that reads this.
     */
    @Volatile
    var refusedTheLaptop: Boolean = false
        private set

    /**
     * Hash the leaf, compare with the digest off the code.
     *
     * **`chain[0]` and nothing else.** The leaf is the peer; everything after it is an issuer the peer
     * nominated, and this design has no issuers — each side self-signs. Searching the chain for the
     * pinned certificate would be catastrophic and would look thorough: the bridge's certificate is
     * public, so anyone could present their own key and staple the real one behind it. The test that
     * holds this passes the pinned certificate as `chain[1]` and requires a refusal.
     *
     * [MessageDigest.isEqual] rather than `contentEquals`: it is the platform's constant-time
     * comparison. Nothing secret is being compared here — both sides are public — so this is not
     * load-bearing, and it is used anyway because the alternative is a comparison whose timing depends
     * on a value from the network, and arguing each such case separately is how one of them gets it
     * wrong.
     */
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val presented = chain?.firstOrNull() ?: refuse("the laptop presented no certificate")
        val actual = MessageDigest.getInstance("SHA-256").digest(presented.encoded)
        if (!MessageDigest.isEqual(expected, actual)) {
            // No fingerprints in the message. Both are public, but this string can reach a log, and
            // a fingerprint identifies the owner's laptop.
            refuse("this is not the laptop the pairing code names")
        }
    }

    /**
     * Records the verdict, then throws the type the interface names.
     *
     * Both halves matter. [refusedTheLaptop] is how the caller actually learns the reason, because it
     * crosses no provider boundary. The throw is what makes the *handshake* fail, and it is a
     * [CertificateException] because that is what [X509TrustManager] documents — throwing anything
     * else invites the provider to treat it as an internal error rather than as a rejected peer.
     */
    private fun refuse(why: String): Nothing {
        refusedTheLaptop = true
        throw CertificateException(why)
    }

    /**
     * Never called: this is installed on a client, and the bridge's decision about the phone is made
     * on the laptop by the Go side's byte-exact pinning.
     *
     * **Loud rather than polite**, exactly as [PinnedTrust] is. A method that quietly did nothing here
     * would read as handling a case that cannot occur, and would be preserved as such.
     */
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw IllegalStateException(
            "FingerprintTrust is a client-side trust manager and must never be asked to judge a client",
        )
    }

    /**
     * Empty, and correct rather than lazy: there is no issuer anywhere in this design. Returning the
     * pinned certificate would describe it as a certificate authority, which is the one thing the
     * trust model says does not exist.
     */
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private companion object {
        const val DIGEST_LENGTH = 32
    }
}
