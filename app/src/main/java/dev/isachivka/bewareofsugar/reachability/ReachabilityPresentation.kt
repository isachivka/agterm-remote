package dev.isachivka.bewareofsugar.reachability

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.isachivka.bewareofsugar.R
import dev.isachivka.bewareofsugar.ui.AppIcons

/**
 * How loudly a row speaks.
 *
 * REQ-0004's rule, as REQ-0005 restated it in two closed clauses:
 *
 * > **Loud styling is for exactly two things: something the owner just did that did not work, and
 * > evidence that something is tampering with their connection.**
 *
 * On this screen only the second clause can apply — nothing here is something the owner did — and it
 * applies to exactly one state. Everything else is a network being a network, which is not the
 * owner's mistake and does not get an alarm.
 */
enum class RowTone {
    /** Nothing has been asked yet, or is being asked now. */
    Neutral,

    /** The service answered. */
    Up,

    /** It answered and something behind it is wrong — the homelab's problem, not the network's. */
    Degraded,

    /** It did not answer. Quiet: on a filtered network this is the expected outcome, not a fault. */
    Unreachable,

    /**
     * The certificate could not be verified.
     *
     * The only loud tone on this screen, and it is loud about a cause that **may not be tampering**:
     * Android reports an expired certificate identically to an unknown chain, so this fires for both.
     * That is why [ReachabilityRow.detail] for this state names both causes rather than accusing the
     * network — the tone says *look at this*, and the sentence says *the phone cannot tell which*.
     */
    Tampering,
}

/**
 * Everything a row shows, decided once.
 *
 * @param detail the sentence under the headline, or null where the headline says it all. This is the
 * half that carries the app's uncertainty, and `ReachabilityPresentationTest` asserts it exists for
 * every state that has something the owner can act on.
 */
data class ReachabilityRow(
    val tone: RowTone,
    @param:DrawableRes val icon: Int,
    @param:StringRes val headline: Int,
    @param:StringRes val detail: Int?,
    /** True while a check is running, so the row can spin instead of pretending to be a verdict. */
    val busy: Boolean = false,
    /** True when latency is worth showing beside the headline. */
    val showsLatency: Boolean = false,
)

/**
 * Pure and exhaustive, so all nineteen outcomes are a unit test rather than nineteen screenshots.
 *
 * Ten states, two of which carry sub-kinds. A twentieth fails to compile rather than rendering grey,
 * which is the same guarantee `presentationOf(UpdateStatus)` gives the updater.
 */
fun rowFor(reachability: Reachability): ReachabilityRow = when (reachability) {
    Reachability.NotChecked ->
        ReachabilityRow(RowTone.Neutral, AppIcons.Schedule, R.string.reach_not_checked, null)

    Reachability.Checking ->
        ReachabilityRow(RowTone.Neutral, AppIcons.Sync, R.string.reach_checking, null, busy = true)

    is Reachability.Answered -> answered(reachability)

    is Reachability.RouterAnswered -> ReachabilityRow(
        RowTone.Degraded,
        AppIcons.Router,
        R.string.reach_router_answered,
        R.string.reach_router_answered_detail,
        showsLatency = true,
    )

    Reachability.NameNotResolved -> ReachabilityRow(
        RowTone.Unreachable,
        AppIcons.Dns,
        R.string.reach_no_dns,
        R.string.reach_no_dns_detail,
    )

    Reachability.ConnectionRefused -> ReachabilityRow(
        RowTone.Unreachable,
        AppIcons.Error,
        R.string.reach_refused,
        R.string.reach_refused_detail,
    )

    Reachability.ConnectionTimedOut -> ReachabilityRow(
        RowTone.Unreachable,
        AppIcons.Error,
        R.string.reach_no_connection,
        R.string.reach_no_connection_detail,
    )

    Reachability.NoAnswerInTime -> ReachabilityRow(
        RowTone.Unreachable,
        AppIcons.Schedule,
        R.string.reach_no_answer,
        R.string.reach_no_answer_detail,
    )

    is Reachability.TlsRejected -> tls(reachability.reason)

    is Reachability.CheckFailed -> ReachabilityRow(
        RowTone.Unreachable,
        AppIcons.Help,
        R.string.reach_check_failed,
        R.string.reach_check_failed_detail,
    )
}

private fun answered(answered: Reachability.Answered): ReachabilityRow = when (answered.meaning) {
    AnswerMeaning.Reachable ->
        ReachabilityRow(RowTone.Up, AppIcons.CheckCircle, R.string.reach_up, null, showsLatency = true)

    // The headline rule of the whole milestone is that an auth challenge is proof you got there -
    // and the row now says so without a sentence. "Reachable" beside a 401 on the facts line is the
    // explanation, and a paragraph explaining that a reachable service is reachable is text nobody
    // reads on the rows they are scrolling past to find the ones that are not.
    //
    // Suppressed only for the two that render as plainly "Reachable". Everything with something to
    // act on keeps its sentence - see the states below, which are the ones the owner opens this
    // screen for.
    AnswerMeaning.NeedsAuth -> ReachabilityRow(
        RowTone.Up,
        AppIcons.CheckCircle,
        R.string.reach_up,
        detail = null,
        showsLatency = true,
    )

    AnswerMeaning.Redirecting -> ReachabilityRow(
        RowTone.Up,
        AppIcons.CheckCircle,
        R.string.reach_up,
        detail = null,
        showsLatency = true,
    )

    // The door opened and the thing behind it is not running: the one diagnosis on this screen the
    // owner can fix from the sofa, so it must not look like the network's fault.
    AnswerMeaning.GatewayDown -> ReachabilityRow(
        RowTone.Degraded,
        AppIcons.Error,
        R.string.reach_gateway_down,
        R.string.reach_gateway_down_detail,
        showsLatency = true,
    )

    AnswerMeaning.ServiceError -> ReachabilityRow(
        RowTone.Degraded,
        AppIcons.Error,
        R.string.reach_service_error,
        R.string.reach_service_error_detail,
        showsLatency = true,
    )

    AnswerMeaning.AnsweredOddly -> ReachabilityRow(
        RowTone.Up,
        AppIcons.Help,
        R.string.reach_answered_oddly,
        R.string.reach_answered_oddly_detail,
        showsLatency = true,
    )
}

private fun tls(reason: TlsFailure): ReachabilityRow = when (reason) {
    // The two that reach here as one on Android. The copy is what carries that, not the tone.
    TlsFailure.Untrusted, TlsFailure.HostnameMismatch -> ReachabilityRow(
        RowTone.Tampering,
        AppIcons.VerifiedUser,
        R.string.reach_cert_unverified,
        R.string.reach_cert_unverified_detail,
    )

    // Unreachable on Android, where it arrives as Untrusted above. Kept with copy of its own so that
    // a platform which starts reporting expiry properly does not ship a blank row.
    TlsFailure.Expired -> ReachabilityRow(
        RowTone.Unreachable,
        AppIcons.Schedule,
        R.string.reach_cert_expired,
        R.string.reach_cert_expired_detail,
    )

    TlsFailure.HandshakeFailed -> ReachabilityRow(
        RowTone.Unreachable,
        AppIcons.VisibilityOff,
        R.string.reach_handshake_failed,
        R.string.reach_handshake_failed_detail,
    )
}
