package dev.isachivka.bewareofsugar.wire

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.X509TrustManager

/**
 * The inner trust decision: exactly one certificate, and it must not have expired.
 *
 * **This narrows. It never widens.** A permissive trust manager accepts anything; this accepts one
 * thing, compared byte for byte. The two use the same platform hook and are opposites, which is why
 * the argument lives next to the code.
 *
 * The certificate this pins is the *bridge's*, presented over a stream the router has already
 * terminated TLS on. That outer decision — is the router who it says it is — is ordinary public-CA
 * validation made elsewhere, and it says nothing about who is behind the proxy. Collapsing them would
 * make this class decorative.
 */
internal class PinnedTrust(
    private val pinned: X509Certificate,
    private val now: () -> Date = { Date() },
) : X509TrustManager {

    /**
     * The verdict this trust manager reached, readable without an exception.
     *
     * **This is how the driver learns the reason, and the exception is not.** A refusal has to travel
     * from here, through the provider's handshake machinery, to a caller that branches on it — and
     * everything in between is provider *behaviour* rather than contract. JSSE wraps whatever a trust
     * manager throws, and whether the original survives as a cause is not promised anywhere. It does
     * survive on the JVM provider these tests run against; the app runs on Conscrypt.
     *
     * Recovering the verdict from a cause chain would therefore have been a mechanism that passes in
     * CI and can fail on the owner's phone — collapsing [WireFailure.NotPinned] and
     * [WireFailure.CertificateExpired] into "the laptop is not answering" about a laptop that answered
     * with the wrong certificate. That is the same failure shape that disqualified a fake-`Socket`
     * driver, reached from the other side.
     *
     * A field on an object the driver already holds crosses no provider boundary at all.
     *
     * `@Volatile` because the handshake may run the trust check on a different thread from the one
     * that reads this.
     */
    @Volatile
    var verdict: WireFailure? = null
        private set

    /**
     * Byte-exact, and then the dates.
     *
     * **The dates are the half that is easy to leave out, and leaving it out fails silently in the
     * good direction.** REQ-0005 measured why: PKIX does not validate a trust anchor's own validity,
     * and a pinned self-signed certificate *is* its own anchor — so nothing checks `NotAfter` unless
     * this does. Without it the identity outlives its stated lifetime, `NotAfter` becomes decorative,
     * nothing forces rotation, and everything keeps working so nothing ever reveals it.
     *
     * The two failures are deliberately distinguishable, because the remedies differ: a wrong
     * certificate means the phone is talking to the wrong machine and must be re-paired; an expired
     * one means the laptop must mint a new certificate. Copy that said "your laptop is not answering"
     * for either would be wrong, and copy that said "re-pair" for an expiry would send the owner to
     * the wrong device.
     */
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val presented = chain?.firstOrNull() ?: refuse(WireFailure.NotPinned)

        // Exactly one, and exactly these bytes. Not "signed by", not "same public key" - a
        // certificate correctly signed by the pinned certificate's own key is still not it.
        if (chain.size != 1 || !presented.encoded.contentEquals(pinned.encoded)) {
            refuse(WireFailure.NotPinned)
        }

        try {
            // Checked on what the PEER PRESENTED, not on the pinned copy. They are identical bytes at
            // this line, so today it makes no difference to behaviour — but "the certificate we were
            // handed is currently valid" is the property being asserted, and reading the dates off our
            // own copy states a different one. It would become quietly wrong the moment the equality
            // above is ever loosened, and it would still pass.
            presented.checkValidity(now())
        } catch (e: CertificateException) {
            refuse(WireFailure.CertificateExpired, e)
        }
    }

    /**
     * Records the verdict, then throws the type the interface names.
     *
     * **Both halves matter and neither is redundant.** [verdict] is how the driver actually learns the
     * reason, because it crosses no provider boundary. The throw is what makes the *handshake* fail,
     * and it is a [CertificateException] because that is what `X509TrustManager` documents — throwing
     * something else invites the provider to treat it as an internal error rather than as a rejected
     * peer, which is a different failure with a different message.
     */
    private fun refuse(failure: WireFailure, cause: Throwable? = null): Nothing {
        verdict = failure
        throw PinnedTrustRefusal(failure, cause)
    }

    /**
     * Never called: this trust manager is only ever installed on a client, and the bridge's own
     * decision about the phone is made on the laptop by the Go side's byte-exact pinning.
     *
     * **Fails loudly rather than politely.** A method that quietly did nothing here would be an
     * unfalsifiable sentence — it would read as handling a case that cannot occur, and the next
     * person would preserve it.
     */
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw IllegalStateException(
            "PinnedTrust is a client-side trust manager and must never be asked to judge a client",
        )
    }

    /**
     * Deliberately empty.
     *
     * This is not an unreachable branch dressed as coverage: the platform calls it, and an empty
     * array is the correct answer. There are no accepted *issuers* because there is no issuer in this
     * design at all — each side self-signs and pins the other's exact bytes. Returning the pinned
     * certificate here would describe it as a CA, which is the thing REQ-0008 §5 says must not exist.
     */
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * A pinning refusal, in the type `X509TrustManager` says a refusal is.
 *
 * `checkServerTrusted` documents [CertificateException]. Throwing a [WireException] instead — which is
 * not one — left the provider to wrap an exception of a type it was not told to expect, and left the
 * driver recovering the reason by walking a cause chain no provider promises to preserve. It worked on
 * the JVM provider the tests run against and might not on Conscrypt, which is where the app runs: the
 * distinction between "the identity is wrong" and "the laptop is not answering" would have failed on
 * the owner's phone while passing in CI.
 *
 * It carries the [failure] so the cause chain still works where the provider does preserve it. That is
 * the fallback; [PinnedTrust.verdict] is the mechanism.
 */
internal class PinnedTrustRefusal(
    val failure: WireFailure,
    cause: Throwable? = null,
) : CertificateException(failure.toString(), cause)
