package dev.isachivka.bewareofsugar.reachability

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Decides what happened, and does no IO.
 *
 * Separated from the prober for the same reason `UpdateCheck.evaluate` is separated from `GitHubApi`:
 * every branch becomes a plain unit test instead of a network condition somebody has to reproduce.
 * It takes primitives rather than an OkHttp `Response` so that the whole status-code table is a table
 * test with no socket in it.
 */
object ReachabilityCheck {

    /**
     * The header the owner's router sets on responses it serves itself.
     *
     * A header and not the page's text. `classifyResponse` at `GitHubApi.kt:138` already makes this
     * choice for rate limiting and says why: keying control flow on a prose message breaks the day
     * the wording changes. The same argument applies to matching on "KeeneticOS Web Panel".
     *
     * This is vendor-specific and that is accepted rather than overlooked. **The trigger for removing
     * it is the owner changing routers** — at which point the state it feeds stops being reachable
     * and should go with it.
     */
    const val ROUTER_HEADER = "ndm-sysmode"

    /**
     * A response arrived. What it says decides only *what to do next*, never whether the service was
     * reached — by the time there is a status code at all, that question is settled.
     */
    fun of(code: Int, routerAnswered: Boolean, latencyMillis: Long): Reachability {
        // First, and regardless of the code. The router serving its own panel answers 200 today, but
        // "the router answered" is the diagnosis whatever status it chooses to answer with.
        if (routerAnswered) return Reachability.RouterAnswered(latencyMillis)

        return Reachability.Answered(code, latencyMillis, meaningOf(code))
    }

    /**
     * Nothing arrived. [stage] is how far the attempt got, which is what turns one timeout into three
     * different answers.
     */
    fun of(failure: Throwable, stage: CallStage, latencyMillis: Long): Reachability = when {
        // Unambiguous on its own: no address, so nothing was attempted.
        failure.isA<UnknownHostException>() -> Reachability.NameNotResolved

        // OkHttp raises this specifically when the certificate is valid but is not for this host.
        failure.isA<SSLPeerUnverifiedException>() ->
            Reachability.TlsRejected(TlsFailure.HostnameMismatch, latencyMillis)

        // Order matters within the certificate family: an expired certificate is also a
        // CertificateException, and the two are opposite diagnoses - one is the owner's maintenance
        // and the other is somebody else's certificate.
        failure.causedBy<CertificateExpiredException>() ||
            failure.causedBy<CertificateNotYetValidException>() ->
            Reachability.TlsRejected(TlsFailure.Expired, latencyMillis)

        failure.causedBy<CertificateException>() ->
            Reachability.TlsRejected(TlsFailure.Untrusted, latencyMillis)

        failure.isA<SSLException>() ->
            Reachability.TlsRejected(TlsFailure.HandshakeFailed, latencyMillis)

        failure.isA<ConnectException>() -> Reachability.ConnectionRefused

        // A timeout says nothing by itself. Where it happened says everything.
        failure.isA<SocketTimeoutException>() -> when (stage) {
            CallStage.Starting, CallStage.NameResolved, CallStage.Connected ->
                Reachability.ConnectionTimedOut
            // Started the handshake and never finished it. No certificate was rejected, so this is
            // not a statement about anybody's certificate - it is the shape a filter that blocks on
            // the server name leaves behind.
            CallStage.Handshaking ->
                Reachability.TlsRejected(TlsFailure.HandshakeFailed, latencyMillis)
            CallStage.Secured, CallStage.AwaitingResponse -> Reachability.NoAnswerInTime
        }

        // Never a synonym for "down". An unforeseen failure that quietly became one of the states
        // above would be this object lying about how much it knows.
        else -> Reachability.CheckFailed(failure.javaClass.simpleName)
    }

    private fun meaningOf(code: Int): AnswerMeaning = when (code) {
        401, 403 -> AnswerMeaning.NeedsAuth
        // The ingress answered and the thing behind it did not. The only answer that points at the
        // homelab rather than at the network: the door opened, so nothing is filtering.
        502, 503, 504 -> AnswerMeaning.GatewayDown
        in 200..299 -> AnswerMeaning.Reachable
        in 300..399 -> AnswerMeaning.Redirecting
        in 500..599 -> AnswerMeaning.ServiceError
        else -> AnswerMeaning.AnsweredOddly
    }
}

/** True when this exception is of type [T]. */
private inline fun <reified T : Throwable> Throwable.isA(): Boolean = this is T

/**
 * True when [T] appears anywhere in the cause chain.
 *
 * The chain has to be walked rather than the immediate cause inspected, and the reason is worth
 * stating: **the JVM and Android do not report a rejected certificate the same way.** The JVM wraps
 * it in `sun.security.validator.ValidatorException`, Android's Conscrypt raises its own
 * `CertificateException` subclass, and neither is available to name on the other platform. What both
 * put somewhere in the chain is `java.security.cert.CertificateException`, or
 * `CertificateExpiredException` for a lapsed one — the two types this classifier looks for.
 *
 * That difference is exactly why the TLS states are also tested on a device: a JVM test proving this
 * proves it for the platform the tests run on, not for the platform the owner runs on.
 *
 * Bounded, because a self-referencing chain would otherwise hang the check rather than fail it.
 */
private inline fun <reified T : Throwable> Throwable.causedBy(): Boolean {
    var cause: Throwable? = this
    var depth = 0
    while (cause != null && depth < MAX_CAUSE_DEPTH) {
        if (cause is T) return true
        cause = cause.cause.takeIf { it !== cause }
        depth++
    }
    return false
}

private const val MAX_CAUSE_DEPTH = 16
