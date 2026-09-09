package dev.isachivka.bewareofsugar.reachability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every one of the nineteen things a row can say, and how loudly it says it.
 *
 * The tone half is a rule with a ruling behind it, and the copy half is where this milestone's one
 * admission of ignorance lives — so both are pinned here rather than left to a screenshot.
 */
class ReachabilityPresentationTest {

    private fun answered(code: Int, meaning: AnswerMeaning) =
        rowFor(Reachability.Answered(code, 100, meaning))

    // -- Tone ---------------------------------------------------------------------------------------

    /**
     * REQ-0005 states loud styling as two closed clauses. Nothing on this screen is *something the
     * owner just did*, so only the second clause can apply — and it applies to one state.
     */
    @Test
    fun `exactly one state is loud`() {
        val everyState = listOf(
            Reachability.NotChecked,
            Reachability.Checking,
            Reachability.Answered(200, 1, AnswerMeaning.Reachable),
            Reachability.Answered(502, 1, AnswerMeaning.GatewayDown),
            Reachability.Answered(500, 1, AnswerMeaning.ServiceError),
            Reachability.Answered(418, 1, AnswerMeaning.AnsweredOddly),
            Reachability.RouterAnswered(1),
            Reachability.NameNotResolved,
            Reachability.ConnectionRefused,
            Reachability.ConnectionTimedOut,
            Reachability.NoAnswerInTime,
            Reachability.TlsRejected(TlsFailure.Untrusted, 1),
            Reachability.TlsRejected(TlsFailure.HostnameMismatch, 1),
            Reachability.TlsRejected(TlsFailure.Expired, 1),
            Reachability.TlsRejected(TlsFailure.HandshakeFailed, 1),
            Reachability.CheckFailed("Whatever"),
        )

        val loud = everyState.filter { rowFor(it).tone == RowTone.Tampering }

        assertEquals(
            listOf(
                Reachability.TlsRejected(TlsFailure.Untrusted, 1),
                Reachability.TlsRejected(TlsFailure.HostnameMismatch, 1),
            ),
            loud,
        )
    }

    /**
     * A network that is filtering the owner is not the owner's mistake, so none of these gets an
     * alarm however red they feel to write.
     */
    @Test
    fun `a filtered network is never loud`() {
        listOf(
            Reachability.NameNotResolved,
            Reachability.ConnectionRefused,
            Reachability.ConnectionTimedOut,
            Reachability.NoAnswerInTime,
            Reachability.TlsRejected(TlsFailure.HandshakeFailed, 1),
        ).forEach {
            assertEquals("$it", RowTone.Unreachable, rowFor(it).tone)
        }
    }

    @Test
    fun `an expired certificate is not loud, on the platform that can see one`() {
        // Android reports it as Untrusted and this branch is unreachable there. It is kept, and
        // tested, so that a platform version which starts reporting expiry properly gets the quiet
        // treatment the ruling asked for rather than a state with no mapping.
        assertEquals(RowTone.Unreachable, rowFor(Reachability.TlsRejected(TlsFailure.Expired, 1)).tone)
    }

    // -- Up means answered, not 200 -----------------------------------------------------------------

    @Test
    fun `an auth challenge and a login redirect both read as reachable`() {
        assertEquals(RowTone.Up, answered(401, AnswerMeaning.NeedsAuth).tone)
        assertEquals(RowTone.Up, answered(403, AnswerMeaning.NeedsAuth).tone)
        assertEquals(RowTone.Up, answered(302, AnswerMeaning.Redirecting).tone)
        assertEquals(RowTone.Up, answered(200, AnswerMeaning.Reachable).tone)
    }

    /**
     * The two that point at the homelab rather than at the network get their own tone, because
     * telling the owner their network is filtering them when their service is simply not running
     * sends them to fix the wrong thing.
     */
    @Test
    fun `a gateway error and a service error point home, not at the network`() {
        assertEquals(RowTone.Degraded, answered(502, AnswerMeaning.GatewayDown).tone)
        assertEquals(RowTone.Degraded, answered(500, AnswerMeaning.ServiceError).tone)
        assertEquals(RowTone.Degraded, rowFor(Reachability.RouterAnswered(1)).tone)
    }

    // -- The copy -----------------------------------------------------------------------------------

    /**
     * **The ruling, pinned at the level it was ruled on.**
     *
     * Android cannot distinguish an expired certificate from an unknown chain, so this state is loud
     * about a cause that may not be tampering. What stops that being a lie is the sentence, not the
     * colour — so a tone test alone would pass on copy that still accused the network. This asserts
     * the sentence exists; `ReachabilityCopyTest` asserts what it says.
     */
    @Test
    fun `the certificate state is loud and carries a sentence`() {
        val row = rowFor(Reachability.TlsRejected(TlsFailure.Untrusted, 1))

        assertEquals(RowTone.Tampering, row.tone)
        assertNotNull("a loud row with no explanation is an accusation", row.detail)
    }

    /**
     * Every verdict the owner might have to act on says something in words.
     *
     * The acceptance test is a person on a network neither the author nor the reviewer can reach, and
     * the only channel back is a screenshot. A row whose meaning is carried by hue alone is a row
     * that arrives here as "one of them was orange".
     */
    @Test
    fun `every settled state that needs explaining explains itself in words`() {
        listOf(
            Reachability.Answered(502, 1, AnswerMeaning.GatewayDown),
            Reachability.Answered(500, 1, AnswerMeaning.ServiceError),
            Reachability.Answered(418, 1, AnswerMeaning.AnsweredOddly),
            Reachability.RouterAnswered(1),
            Reachability.NameNotResolved,
            Reachability.ConnectionRefused,
            Reachability.ConnectionTimedOut,
            Reachability.NoAnswerInTime,
            Reachability.TlsRejected(TlsFailure.Untrusted, 1),
            Reachability.TlsRejected(TlsFailure.HostnameMismatch, 1),
            Reachability.TlsRejected(TlsFailure.Expired, 1),
            Reachability.TlsRejected(TlsFailure.HandshakeFailed, 1),
            Reachability.CheckFailed("X"),
        ).forEach { state ->
            assertNotNull("$state has no sentence", rowFor(state).detail)
        }
    }

    /**
     * The exceptions, stated rather than implied.
     *
     * Everything that renders as plainly "Reachable" says so in one word and stops. A paragraph
     * explaining that a reachable service is reachable is text nobody reads, on exactly the rows
     * being scrolled past to find the ones that are not — and ten rows plus a summary have to fit in
     * one frame, which is the acceptance criterion this pays into.
     *
     * The 401 case is the one worth naming: the milestone's headline rule is that an auth challenge
     * is proof you got there, and the row still says so — "Reachable" beside the 401 on the facts
     * line, rather than a sentence under it.
     */
    @Test
    fun `everything that is plainly reachable says so without elaborating`() {
        assertNull(answered(200, AnswerMeaning.Reachable).detail)
        assertNull(answered(401, AnswerMeaning.NeedsAuth).detail)
        assertNull(answered(302, AnswerMeaning.Redirecting).detail)
    }

    @Test
    fun `the two states that are not verdicts do not pretend to be`() {
        assertNull(rowFor(Reachability.NotChecked).detail)
        assertNull(rowFor(Reachability.Checking).detail)
        assertTrue(rowFor(Reachability.Checking).busy)
        assertEquals(false, rowFor(Reachability.NotChecked).busy)
    }

    // -- Latency ------------------------------------------------------------------------------------

    @Test
    fun `latency is shown only where there was a round trip to time`() {
        assertTrue(answered(200, AnswerMeaning.Reachable).showsLatency)
        assertTrue(rowFor(Reachability.RouterAnswered(1)).showsLatency)

        // Nothing answered, so there is no round trip - only the timeout the app chose.
        assertEquals(false, rowFor(Reachability.ConnectionTimedOut).showsLatency)
        assertEquals(false, rowFor(Reachability.NameNotResolved).showsLatency)
        assertEquals(false, rowFor(Reachability.TlsRejected(TlsFailure.Untrusted, 1)).showsLatency)
    }
}
