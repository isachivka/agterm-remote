package dev.isachivka.bewareofsugar.reachability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the launcher tile says, and what "available" means.
 *
 * The tile is a summary of the rows below it. If this and the screen can ever disagree, the tile is a
 * second opinion — and on a launcher, where there is nothing to compare it against, a second opinion
 * is just a confident number.
 */
class TileVerdictTest {

    private val up = Reachability.Answered(200, 40, AnswerMeaning.Reachable)
    private val down = Reachability.ConnectionTimedOut

    private fun verdict(vararg r: Reachability, total: Int = 10) = tileVerdictOf(r.toList(), total)

    private fun repeated(n: Int, r: Reachability) = List(n) { r }

    // -- The predicate -----------------------------------------------------------------------------

    @Test
    fun `the service answering is available, whatever it said`() {
        // A 401 is up. That rule was settled in REQ-0005 and does not get quietly re-decided here.
        assertTrue(isAvailable(Reachability.Answered(200, 1, AnswerMeaning.Reachable)))
        assertTrue(isAvailable(Reachability.Answered(401, 1, AnswerMeaning.NeedsAuth)))
        assertTrue(isAvailable(Reachability.Answered(302, 1, AnswerMeaning.Redirecting)))
        assertTrue(isAvailable(Reachability.Answered(404, 1, AnswerMeaning.AnsweredOddly)))
    }

    /**
     * The case the brief names as most likely to be got wrong.
     *
     * You reached the owner's router and not their service: the network is fine, and the thing they
     * wanted is not there. Counting it as available would make the tile green for a homelab with
     * nothing running on it.
     */
    @Test
    fun `the router answering is not the service being available`() {
        assertFalse(isAvailable(Reachability.RouterAnswered(40)))
    }

    @Test
    fun `reached but not usable is not available`() {
        assertFalse(isAvailable(Reachability.Answered(502, 1, AnswerMeaning.GatewayDown)))
        assertFalse(isAvailable(Reachability.Answered(500, 1, AnswerMeaning.ServiceError)))
    }

    @Test
    fun `nothing that failed is available`() {
        listOf(
            Reachability.NameNotResolved,
            Reachability.ConnectionRefused,
            Reachability.ConnectionTimedOut,
            Reachability.NoAnswerInTime,
            Reachability.TlsRejected(TlsFailure.Untrusted, 1),
            Reachability.TlsRejected(TlsFailure.Expired, 1),
            Reachability.CheckFailed("X"),
        ).forEach { assertFalse("$it", isAvailable(it)) }
    }

    // -- The four states ---------------------------------------------------------------------------

    /**
     * The state the owner did not name, and the one the fixed green tile got wrong: **nothing has
     * been asked is not the same as nothing is available.** A tile reading *0 of 10 available* before
     * any check has run is the old lie with the colours reversed.
     */
    @Test
    fun `nothing checked is not zero available`() {
        val v = verdict(*repeated(10, Reachability.NotChecked).toTypedArray())

        assertEquals(TileLevel.NotChecked, v.level)
        assertFalse(v.busy)
    }

    @Test
    fun `a check in progress says so rather than showing a moving tally`() {
        // Half settled and half still going would otherwise flash green as the fast services land
        // and fall to amber as the slow ones fail - a number changing under the owner's eye while
        // claiming to be an answer.
        val v = verdict(*(repeated(5, up) + repeated(5, Reachability.Checking)).toTypedArray())

        assertEquals(TileLevel.NotChecked, v.level)
        assertTrue(v.busy)
    }

    @Test
    fun `nothing available is grey territory, and it is a real answer`() {
        val v = verdict(*repeated(10, down).toTypedArray())

        assertEquals(TileLevel.None, v.level)
        assertEquals(0, v.available)
        assertFalse(v.busy)
    }

    /**
     * The boundary, which is the whole reason "more than half" had to be written down. The owner said
     * *more than half*, and half is not more than half.
     */
    @Test
    fun `five of ten is not more than half, and six is`() {
        assertEquals(
            TileLevel.Some,
            verdict(*(repeated(5, up) + repeated(5, down)).toTypedArray()).level,
        )
        assertEquals(
            TileLevel.Most,
            verdict(*(repeated(6, up) + repeated(4, down)).toTypedArray()).level,
        )
    }

    @Test
    fun `one of ten is some, and ten of ten is most`() {
        assertEquals(TileLevel.Some, verdict(*(repeated(1, up) + repeated(9, down)).toTypedArray()).level)
        assertEquals(TileLevel.Most, verdict(*repeated(10, up).toTypedArray()).level)
    }

    @Test
    fun `the count the subtitle shows is the count of available services`() {
        val v = verdict(*(repeated(7, up) + repeated(3, down)).toTypedArray())

        assertEquals(7, v.available)
        assertEquals(10, v.total)
    }

    /**
     * The router answering counts against the tally as well as against the predicate — the case where
     * the tile and the rows would most plausibly drift apart, because every connection succeeded.
     */
    @Test
    fun `a homelab where the router answers everything is not most available`() {
        val v = verdict(*repeated(10, Reachability.RouterAnswered(40)).toTypedArray())

        assertEquals(TileLevel.None, v.level)
        assertEquals(0, v.available)
    }

    // -- The denominator ---------------------------------------------------------------------------

    @Test
    fun `the denominator is the services, not what is on the launcher`() {
        // Hiding modules changes the launcher; it does not change how many services the owner has.
        assertEquals(ServiceRegistry.remote.size, tileVerdictOf(emptyList()).total)
    }

    @Test
    fun `a partial result set is never a verdict`() {
        // Nine of ten in, one still to come: not an answer, however tempting the nine look.
        val v = verdict(*repeated(9, up).toTypedArray(), total = 10)

        assertEquals(TileLevel.NotChecked, v.level)
    }
}
