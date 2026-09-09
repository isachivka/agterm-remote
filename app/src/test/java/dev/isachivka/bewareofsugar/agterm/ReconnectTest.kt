package dev.isachivka.bewareofsugar.agterm

import dev.isachivka.bewareofsugar.wire.WireFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which failures are waited out, and — far more important — which are not.
 *
 * The owner was shown an error screen for a two-second bridge restart during one of our own deploys.
 * The message was correct; handing them a dead end with a button for an outage on a machine in the
 * same room was not.
 */
class ReconnectTest {

    @Test
    fun `a restarting bridge is waited out`() {
        // What a deploy looks like from the phone: refused, or a router with nothing behind it.
        assertTrue(Reconnect.isTransient(WireFailure.CannotReach))
        assertTrue(Reconnect.isTransient(WireFailure.BridgeNotListening))
    }

    /**
     * **The rule that must never bend.**
     *
     * Retrying a pinned-door refusal would turn a security boundary into a spinner: the owner would
     * see a delay where they should see a refusal, and the one screen that tells them to act would
     * arrive late or not at all. These three name an identity — the laptop's, its certificate's dates,
     * or this phone's own key — and no amount of waiting changes any of them.
     */
    @Test
    fun `nothing about identity is ever retried`() {
        assertFalse(
            "a wrong certificate was treated as an outage",
            Reconnect.isTransient(WireFailure.NotPinned),
        )
        assertFalse(
            "an expired certificate was treated as an outage",
            Reconnect.isTransient(WireFailure.CertificateExpired),
        )
        assertFalse(
            "this phone's own unusable key was treated as an outage",
            Reconnect.isTransient(WireFailure.IdentityUnusable),
        )
    }

    /** Nothing to reconnect to. The remedy is pairing, and a spinner would hide it. */
    @Test
    fun `an unpaired phone is not retried`() {
        assertFalse(Reconnect.isTransient(WireFailure.NotPaired))
    }

    /**
     * A stall is the shape this app's own buffer bugs take — which is why the type exists and why
     * every wait in the driver is bounded and named. Retrying one quietly would hide the class of
     * defect the bound was added to expose.
     */
    @Test
    fun `a stall is not a restart`() {
        for (stage in WireFailure.TimedOut.Stage.entries) {
            assertFalse(
                "a stall at $stage was retried, hiding the defect the bound exists to expose",
                Reconnect.isTransient(WireFailure.TimedOut(stage)),
            )
        }
    }

    /** Neither of these is an outage, and both are worth seeing rather than smoothing over. */
    @Test
    fun `protocol faults and our own backlog are not retried`() {
        assertFalse(Reconnect.isTransient(WireFailure.Malformed))
        assertFalse(Reconnect.isTransient(WireFailure.ReaderFellBehind))
    }

    /**
     * The quiet phase has to outlast a deploy, or the screen appears anyway and the retry bought
     * nothing. Measured on the owner's machine: two restarts thirty seconds apart, each about two
     * seconds of outage.
     */
    @Test
    fun `the quiet phase outlasts a deploy`() {
        val total = Reconnect.QUIET_RETRY_DELAYS_MS.sum()
        assertTrue("the quiet phase is only ${total}ms; a deploy is longer than that", total >= 10_000)
        // And it backs off rather than hammering a bridge that is trying to start.
        assertTrue(
            "the delays do not back off",
            Reconnect.QUIET_RETRY_DELAYS_MS.zipWithNext().all { (a, b) -> b >= a },
        )
    }

    /** Healing must keep happening while the screen is up, at a rate a battery can afford. */
    @Test
    fun `the healing interval is neither a hammer nor a nap`() {
        assertTrue(Reconnect.HEAL_INTERVAL_MS >= 1_000)
        assertTrue(Reconnect.HEAL_INTERVAL_MS <= 10_000)
    }
}
