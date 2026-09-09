package dev.isachivka.bewareofsugar.reachability

/**
 * What happened when the app tried to reach one service.
 *
 * This type is the milestone. The owner is standing on a filtered network asking *which of my things
 * can I get to from here*, and the answer is only useful if it distinguishes the ways the attempt can
 * fail: a name that would not resolve, a port that would not open, a handshake that did not complete
 * and a service that answered with an error are four different days, and collapsing any of them into
 * "down" throws away the entire reason for building this.
 *
 * Every state carries the latency where there is one. On a filtered network "up, but four seconds" is
 * a real answer.
 */
sealed interface Reachability {

    /** Nothing has been tried yet, and nothing is being claimed. */
    data object NotChecked : Reachability

    data object Checking : Reachability

    /**
     * The service answered. What it said is in [meaning]; that it said anything is the point.
     *
     * **A 401 is up.** Most of these services require authentication, and reporting an auth challenge
     * as "down" would render a perfectly healthy homelab as broken — three of the ten answer this way
     * from an unfiltered network.
     */
    data class Answered(
        val code: Int,
        val latencyMillis: Long,
        val meaning: AnswerMeaning,
    ) : Reachability

    /**
     * The owner's router answered instead of the service.
     *
     * Measured, not theorised: an unknown subdomain returns HTTP 200 serving the KeeneticOS panel,
     * under the same valid wildcard certificate as everything else. So a missing proxy rule, or a
     * backend that has stopped, would otherwise render as a perfectly healthy green row — the
     * interception problem the whole design guards against, occurring inside the owner's own
     * infrastructure rather than on the hostile network.
     */
    data class RouterAnswered(val latencyMillis: Long) : Reachability

    /** DNS did not give an address. On a filtered network, the first place answers are tampered with. */
    data object NameNotResolved : Reachability

    /** Something actively refused the connection. */
    data object ConnectionRefused : Reachability

    /** The connection never opened at all. The port is blocked, or nothing is listening. */
    data object ConnectionTimedOut : Reachability

    /**
     * TLS did not complete, or completed with a certificate that could not be accepted.
     *
     * Never worked around. OkHttp validates by default and nothing in this app weakens it; a
     * certificate that does not verify is a *result this screen reports*, and the one result on it
     * that means somebody is doing something to the owner.
     */
    data class TlsRejected(val reason: TlsFailure, val latencyMillis: Long) : Reachability

    /** Connected and secured, then silence. */
    data object NoAnswerInTime : Reachability

    /**
     * Something else went wrong.
     *
     * Carries the exception's simple class name and nothing else — no message, because a message can
     * carry a URL, and no stack trace. It exists so that an unforeseen failure arrives on screen as
     * an unforeseen failure rather than being quietly folded into one of the states above, which
     * would be the classifier lying about how much it knows.
     */
    data class CheckFailed(val cause: String) : Reachability
}

/**
 * What a service's answer meant.
 *
 * All six mean *you reached it*. They differ only in what the owner does next, which is why they are
 * one enum inside [Reachability.Answered] rather than six top-level states: the network question is
 * already settled by the time any of these applies.
 */
enum class AnswerMeaning {

    /** 2xx. */
    Reachable,

    /** 401 or 403 — it is asking who you are, which means you got to it. */
    NeedsAuth,

    /** 3xx, typically to the service's own login page. */
    Redirecting,

    /**
     * 502, 503 or 504 — the ingress answered and the thing behind it did not.
     *
     * Worth its own value because it is the one answer that points at the homelab rather than at the
     * network: the door opened, so nothing is filtering, and the service itself is not running.
     */
    GatewayDown,

    /** Another 5xx. It answered, and it is unhappy. */
    ServiceError,

    /** Another 4xx. Still proof the request arrived somewhere that speaks HTTP. */
    AnsweredOddly,
}

/**
 * Why TLS did not produce a usable connection.
 *
 * The split down [tampering] is the one the PM ruled on, and it is a fact about the diagnosis rather
 * than a styling choice — which is why it lives here, next to the states, and is pinned by a unit
 * test at this layer instead of being re-derived by whatever draws the screen.
 */
enum class TlsFailure(
    /**
     * True when this failure is evidence that something is standing between the phone and the
     * service.
     *
     * REQ-0005 states loud styling as two closed clauses: something the owner just did that did not
     * work, and evidence that something is tampering with their connection. This flag is the second
     * clause, and only these two failures satisfy it.
     */
    val tampering: Boolean,
) {

    /**
     * The certificate could not be verified.
     *
     * Loud, and **deliberately loud about a cause that may not be tampering.** On Android this state
     * also swallows [Expired]: the platform discards a certificate outside its validity window while
     * building the path, so a lapsed certificate and an unknown chain arrive as the same exception
     * with the same reason and the same message. Ruled 2026-07-27: keep it loud, and make the copy
     * name both causes rather than build a custom trust manager to recover the distinction.
     *
     * So the sentence attached to this state may not claim interference. REQ-0005 carries the
     * required wording — it says the phone cannot tell which, and tells the owner to check the
     * certificate's date.
     */
    Untrusted(tampering = true),

    /** A valid certificate for a different name. Someone else's certificate, more obviously. */
    HostnameMismatch(tampering = true),

    /**
     * The owner's own certificate has lapsed — or has not started.
     *
     * **Deliberately quiet.** The current wildcard expires 2026-09-19, so this state is not
     * hypothetical; it will fire. Rendering it as alarm would tell the owner they are being
     * intercepted when the truth is that their certificate ran out, which is the same defect as
     * collapsing DNS failure into "down", moved from the state model into the palette.
     *
     * **Not reachable on Android**, and that is a measured fact rather than an oversight. Android's
     * trust manager discards an out-of-date certificate while building the path and reports a path it
     * could not complete — identical exception, reason and message to an unknown chain — so on the
     * phone an expired certificate arrives as [Untrusted] instead, and is loud.
     *
     * Ruled rather than fixed: the app stops claiming the distinction, and [Untrusted]'s copy names
     * both causes. Recovering it would need a custom trust manager, and a codebase whose entire TLS
     * story is "there is no custom trust manager anywhere" is worth more than one clause of
     * precision.
     *
     * The value is kept because the distinction is real, is what the JVM reports, and is what the
     * tone rule is written against. `TlsClassificationInstrumentedTest` pins Android's behaviour, so
     * a platform version that starts reporting expiry properly will fail that test and say so.
     */
    Expired(tampering = false),

    /**
     * The handshake did not finish, and no certificate was the reason.
     *
     * The signature of SNI-based filtering, which is why it is quiet: it is the network doing this,
     * not evidence about anybody's certificate. The ingress requires SNI — a request without a
     * matching one is answered with TLS alert 112 rather than with a certificate — so a filter that
     * reads or blocks on the server name lands here.
     */
    HandshakeFailed(tampering = false),
}

/**
 * How far a request got before it failed.
 *
 * Needed because a timeout on its own says nothing about *where* it happened, and where it happened
 * is the diagnosis: never connected is a blocked port, connected but never secured is a blocked
 * handshake, and secured but never answered is a service that went quiet. The alternative is reading
 * the timeout's message text, which is control flow on prose and breaks the day the wording changes.
 */
enum class CallStage {
    Starting,
    NameResolved,
    Connected,
    Handshaking,
    Secured,
    AwaitingResponse,
}
