package dev.isachivka.bewareofsugar.agterm

import dev.isachivka.bewareofsugar.wire.WireFailure

/**
 * Which failures are worth waiting out, and for how long.
 *
 * ### The two seconds that produced an error screen
 *
 * 2026-07-30, 19:05:44: the owner was shown *"Your router answered, but nothing was listening behind
 * it"*. It was **correct**. Their bridge log:
 *
 * ```
 * 19:05:16  launchd: starting bridge built from main d57fb6c
 * 19:05:46  launchd: starting bridge built from main d57fb6c
 * ```
 *
 * They took the screenshot two seconds before the second start of one deploy. The router stayed up
 * throughout, so it accepted the connection and found nothing behind it — which is what the message
 * says, and the message is why that was diagnosed in a minute. **The copy is not the defect.** Handing
 * someone a dead end with a button, for a two-second outage on a machine in the same room, is.
 *
 * ### What is retried, and what must never be
 *
 * **Retried:** the connection was refused, reset, or reached a router with nothing behind it. Those
 * are what a restarting bridge looks like from here, and they heal on their own.
 *
 * **Never retried: anything about identity.** [WireFailure.NotPinned] and
 * [WireFailure.CertificateExpired] are the pinned door saying no, and [WireFailure.IdentityUnusable]
 * is this phone's own key. Retrying a security boundary silently would turn it into a spinner: the
 * owner would see a delay where they should see a refusal, and the one screen that tells them to act
 * would arrive late or not at all.
 *
 * **Also not retried: [WireFailure.TimedOut].** A stall is the shape this app's own buffer bugs take —
 * that is why the type exists and why every wait in the driver is bounded and named. Quietly retrying
 * one would hide the class of defect the bound was added to expose. A stall is not a restart.
 */
object Reconnect {

    /**
     * Whether waiting is likely to help.
     *
     * Exhaustive on purpose rather than a default-false `when`: a failure added later should make this
     * fail to compile and force a decision, instead of silently joining the retried set or the
     * un-retried one depending on which way the default happens to fall.
     */
    fun isTransient(failure: WireFailure): Boolean = when (failure) {
        // A bridge that is restarting. Refused, reset, or a router with nothing behind it.
        WireFailure.CannotReach, WireFailure.BridgeNotListening -> true

        // The identity is wrong and the owner must act. No amount of waiting changes it.
        WireFailure.NotPinned, WireFailure.CertificateExpired, WireFailure.IdentityUnusable -> false

        // Nothing to reconnect to; the remedy is pairing.
        WireFailure.NotPaired -> false

        // The far end spoke non-protocol, or this app stopped draining the stream. Neither is an
        // outage, and both are worth seeing rather than smoothing over.
        WireFailure.Malformed, WireFailure.ReaderFellBehind -> false

        // A stall. See the note above: retrying it would hide the defect the bound exists to expose.
        is WireFailure.TimedOut -> false
    }

    /**
     * How long to wait before each quiet attempt, in milliseconds, before the failure screen appears.
     *
     * **Short at first, because most of these are over in two seconds.** The total is about twelve
     * seconds, which covers a deploy comfortably — including the double restart that currently costs
     * two outages thirty seconds apart, where a phone can catch the second one while waiting out the
     * first.
     *
     * Backing off rather than hammering: a bridge that is starting has better things to do than answer
     * a phone every hundred milliseconds, and a laptop that is genuinely off gets six attempts rather
     * than a hundred and twenty.
     */
    val QUIET_RETRY_DELAYS_MS = listOf(400L, 800L, 1_600L, 3_000L, 3_000L, 3_000L)

    /**
     * How often to keep trying once the failure screen IS showing.
     *
     * **It must heal without a tap.** The owner should not have to notice that the thing fixed itself;
     * a screen that sits there with a button, next to a laptop that came back a minute ago, teaches
     * them that the button is the only thing that works.
     *
     * Slower than the quiet phase, because by now this is a real outage rather than a restart, and the
     * phone is on someone's battery.
     */
    const val HEAL_INTERVAL_MS = 3_000L
}
