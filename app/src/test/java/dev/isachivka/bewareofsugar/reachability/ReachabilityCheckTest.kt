package dev.isachivka.bewareofsugar.reachability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException

/**
 * Every branch of the decision this milestone exists to make.
 *
 * The status codes here are chosen to cover the mapping, deliberately **not** copied from what the
 * real hosts return. Those were measured from an unfiltered network, and what the same hosts return
 * through a filter is the thing nobody knows yet — so a test asserting "navidrome gives 302" would be
 * pinning an accident of where I was standing when I ran curl.
 */
class ReachabilityCheckTest {

    private val latency = 123L

    private fun meaning(code: Int): AnswerMeaning {
        val result = ReachabilityCheck.of(code, routerAnswered = false, latencyMillis = latency)
        return (result as Reachability.Answered).meaning
    }

    // -- Answers -----------------------------------------------------------------------------------

    @Test
    fun `a 401 is up, which is the rule this whole screen turns on`() {
        // Most of these services require authentication. Calling an auth challenge "down" reports a
        // working homelab as broken, and three of the ten answer this way.
        assertEquals(AnswerMeaning.NeedsAuth, meaning(401))
        assertEquals(AnswerMeaning.NeedsAuth, meaning(403))
    }

    @Test
    fun `a redirect to a login page is up`() {
        assertEquals(AnswerMeaning.Redirecting, meaning(301))
        assertEquals(AnswerMeaning.Redirecting, meaning(302))
        assertEquals(AnswerMeaning.Redirecting, meaning(307))
    }

    @Test
    fun `a success is up`() {
        assertEquals(AnswerMeaning.Reachable, meaning(200))
        assertEquals(AnswerMeaning.Reachable, meaning(204))
    }

    /**
     * The one answer that points at the homelab rather than at the network.
     *
     * A gateway error means the door opened — so nothing is filtering — and the thing behind it is
     * not running. Folding it into the other 5xx would lose the only diagnosis on this screen the
     * owner can fix from their sofa.
     */
    @Test
    fun `a gateway error is the ingress reporting that the service behind it is down`() {
        assertEquals(AnswerMeaning.GatewayDown, meaning(502))
        assertEquals(AnswerMeaning.GatewayDown, meaning(503))
        assertEquals(AnswerMeaning.GatewayDown, meaning(504))
    }

    @Test
    fun `another server error is the service answering unhappily`() {
        assertEquals(AnswerMeaning.ServiceError, meaning(500))
        assertEquals(AnswerMeaning.ServiceError, meaning(507))
    }

    @Test
    fun `another client error still proves something spoke HTTP`() {
        assertEquals(AnswerMeaning.AnsweredOddly, meaning(404))
        assertEquals(AnswerMeaning.AnsweredOddly, meaning(405))
        assertEquals(AnswerMeaning.AnsweredOddly, meaning(418))
    }

    @Test
    fun `an answer carries its code and its latency`() {
        val result = ReachabilityCheck.of(418, routerAnswered = false, latencyMillis = 4_100)

        assertEquals(Reachability.Answered(418, 4_100, AnswerMeaning.AnsweredOddly), result)
    }

    // -- The router answering for the service ------------------------------------------------------

    /**
     * Measured: an unknown subdomain returns 200 with the KeeneticOS panel under the same valid
     * wildcard certificate. Without this state that is a green row for a service that is not there.
     */
    @Test
    fun `the router answering is not the service answering`() {
        assertEquals(
            Reachability.RouterAnswered(latency),
            ReachabilityCheck.of(200, routerAnswered = true, latencyMillis = latency),
        )
    }

    @Test
    fun `the router answering wins over whatever status it chose`() {
        // "The router answered" is the diagnosis regardless of the code it answers with, so this
        // cannot be a special case of 200.
        listOf(200, 302, 401, 404, 500).forEach { code ->
            assertTrue(
                "code $code",
                ReachabilityCheck.of(code, routerAnswered = true, latencyMillis = latency)
                    is Reachability.RouterAnswered,
            )
        }
    }

    // -- Failures ----------------------------------------------------------------------------------

    private fun failure(e: Throwable, stage: CallStage = CallStage.NameResolved): Reachability =
        ReachabilityCheck.of(e, stage, latency)

    @Test
    fun `a name that does not resolve is its own answer`() {
        assertEquals(Reachability.NameNotResolved, failure(UnknownHostException("no")))
    }

    @Test
    fun `a refused connection is its own answer`() {
        assertEquals(Reachability.ConnectionRefused, failure(ConnectException("refused")))
    }

    /**
     * The four states a timeout becomes, which is the reason [CallStage] exists at all. A timeout on
     * its own says nothing; where it happened says everything.
     */
    @Test
    fun `where a timeout happened is the diagnosis`() {
        val timeout = SocketTimeoutException("timeout")

        assertEquals(Reachability.ConnectionTimedOut, failure(timeout, CallStage.Starting))
        assertEquals(Reachability.ConnectionTimedOut, failure(timeout, CallStage.NameResolved))
        assertEquals(Reachability.ConnectionTimedOut, failure(timeout, CallStage.Connected))
        assertEquals(Reachability.NoAnswerInTime, failure(timeout, CallStage.Secured))
        assertEquals(Reachability.NoAnswerInTime, failure(timeout, CallStage.AwaitingResponse))
    }

    /**
     * A handshake that starts and never finishes, with no certificate rejected, is the shape a filter
     * blocking on the server name leaves behind. The ingress requires SNI — a request without a
     * matching one is answered with an alert rather than a certificate — so this is the state that
     * SNI filtering lands in.
     */
    @Test
    fun `a timeout mid-handshake is a handshake failure, not a dead port`() {
        assertEquals(
            Reachability.TlsRejected(TlsFailure.HandshakeFailed, latency),
            failure(SocketTimeoutException("timeout"), CallStage.Handshaking),
        )
    }

    // -- The certificate family --------------------------------------------------------------------

    @Test
    fun `a certificate for another host is a hostname mismatch`() {
        assertEquals(
            Reachability.TlsRejected(TlsFailure.HostnameMismatch, latency),
            failure(SSLPeerUnverifiedException("Hostname not verified")),
        )
    }

    /**
     * Order matters here, and getting it wrong is silent: `CertificateExpiredException` *is* a
     * `CertificateException`, so a classifier that tested for the general case first would report
     * every lapsed certificate as an untrusted one — turning the owner's own maintenance into an
     * accusation of interception. That is the exact confusion the PM's ruling on the error colours
     * exists to prevent, one layer further down.
     */
    @Test
    fun `an expired certificate is not an untrusted one`() {
        val expired = SSLHandshakeException("failed").initCause(
            CertificateException("chain", CertificateExpiredException("NotAfter")),
        )

        assertEquals(
            Reachability.TlsRejected(TlsFailure.Expired, latency),
            failure(expired),
        )
    }

    @Test
    fun `a certificate that is not valid yet is treated as the same maintenance problem`() {
        val notYet = SSLHandshakeException("failed").initCause(CertificateNotYetValidException("NotBefore"))

        assertEquals(Reachability.TlsRejected(TlsFailure.Expired, latency), failure(notYet))
    }

    @Test
    fun `a chain that does not lead to a trusted root is untrusted`() {
        val untrusted = SSLHandshakeException("failed").initCause(
            CertificateException("PKIX failed", CertPathValidatorException("no trust anchor")),
        )

        assertEquals(Reachability.TlsRejected(TlsFailure.Untrusted, latency), failure(untrusted))
    }

    @Test
    fun `a TLS failure with no certificate in it is a handshake failure`() {
        assertEquals(
            Reachability.TlsRejected(TlsFailure.HandshakeFailed, latency),
            failure(SSLProtocolException("unrecognized name")),
        )
    }

    /**
     * A cause cycle must fail the check rather than hang it.
     *
     * Two exceptions rather than one: the JDK refuses `initCause(this)`, so a self-referencing chain
     * cannot be built at all and testing for it would prove nothing. A pair pointing at each other is
     * constructible, and is what the depth bound is actually there for.
     */
    @Test
    fun `a cause cycle terminates`() {
        val first = IOException("a")
        val second = IOException("b")
        first.initCause(second)
        second.initCause(first)

        assertEquals(Reachability.CheckFailed("IOException"), failure(first))
    }

    // -- The catch-all -----------------------------------------------------------------------------

    /**
     * Never a synonym for "down". An unforeseen failure quietly becoming one of the states above
     * would be the classifier claiming to know more than it does, which on this screen is the whole
     * failure mode.
     */
    @Test
    fun `an unforeseen failure says so, and names its type without its message`() {
        val result = failure(IllegalStateException("https://something.with.a/url?token=xyz"))

        assertEquals(Reachability.CheckFailed("IllegalStateException"), result)
        assertFalse(result.toString().contains("url"))
    }

    // -- The ruling ---------------------------------------------------------------------------------

    /**
     * The split the PM ruled on, pinned as a fact about the diagnosis rather than left for whatever
     * draws the screen to re-derive.
     *
     * Untrusted and mismatched mean someone is standing between the phone and the service. Expired
     * means the owner's certificate lapsed — due 2026-09-19, inside this milestone, so this is not
     * hypothetical — and a handshake that simply failed is the network, not evidence about anyone's
     * certificate.
     */
    @Test
    fun `only the two failures that mean interference are marked as tampering`() {
        assertTrue(TlsFailure.Untrusted.tampering)
        assertTrue(TlsFailure.HostnameMismatch.tampering)
        assertFalse(TlsFailure.Expired.tampering)
        assertFalse(TlsFailure.HandshakeFailed.tampering)

        assertEquals(2, TlsFailure.entries.count { it.tampering })
    }
}
