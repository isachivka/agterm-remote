package dev.isachivka.bewareofsugar.reachability

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What ten rows add up to.
 *
 * The line this covers is the difference between a tool and a table, and it is also the line most
 * able to overclaim — so the boundary it must not cross is tested as deliberately as the answers it
 * must give.
 */
class CheckSummaryTest {

    private val total = 10

    private fun summary(vararg results: Reachability) = summaryOf(results.toList(), total)

    private fun repeated(n: Int, r: Reachability) = List(n) { r }

    private val up = Reachability.Answered(200, 50, AnswerMeaning.Reachable)

    @Test
    fun `nothing checked is not a verdict`() {
        assertEquals(CheckSummary.NotChecked, summaryOf(repeated(10, Reachability.NotChecked), total))
        assertEquals(CheckSummary.NotChecked, summaryOf(emptyList(), total))
    }

    @Test
    fun `a partial set is still running, not a verdict about the ones that finished`() {
        val partial = repeated(3, up) + repeated(7, Reachability.Checking)

        assertEquals(CheckSummary.Running(3, 10), summaryOf(partial, total))
    }

    @Test
    fun `everything answered`() {
        assertEquals(CheckSummary.Reached(10, 10), summaryOf(repeated(10, up), total))
    }

    @Test
    fun `some answered`() {
        val mixed = repeated(4, up) + repeated(6, Reachability.ConnectionTimedOut)

        assertEquals(CheckSummary.Reached(4, 10), summaryOf(mixed, total))
    }

    /**
     * The one that turns ten red rows into a diagnosis.
     *
     * Ten unrelated services do not break in the same way at the same moment, so a single failure
     * layer across all of them is a statement about the network. Note what it is *not* allowed to
     * say — see the test below.
     */
    @Test
    fun `nothing answered and all in the same way is one diagnosis`() {
        assertEquals(
            CheckSummary.NothingReached(10, FailurePattern.AllSameLayer),
            summaryOf(repeated(10, Reachability.ConnectionTimedOut), total),
        )
        assertEquals(
            CheckSummary.NothingReached(10, FailurePattern.AllSameLayer),
            summaryOf(repeated(10, Reachability.NameNotResolved), total),
        )
    }

    /**
     * A refused connection and a timeout are both *connection* failures, so they are the same layer
     * even though they are different states. The layer is what "the same way" means; anything finer
     * would report "no single explanation" for a network blocking one port two ways.
     */
    @Test
    fun `the same layer is about the layer, not about the exact state`() {
        val bothConnectionFailures =
            repeated(5, Reachability.ConnectionRefused) + repeated(5, Reachability.ConnectionTimedOut)

        assertEquals(
            CheckSummary.NothingReached(10, FailurePattern.AllSameLayer),
            summaryOf(bothConnectionFailures, total),
        )
    }

    /**
     * A TLS rejection and a DNS failure are different stories, and claiming one would be inventing
     * the half that is missing.
     */
    @Test
    fun `different layers get no single explanation`() {
        val mixed = repeated(5, Reachability.NameNotResolved) +
            repeated(5, Reachability.TlsRejected(TlsFailure.HandshakeFailed, 1))

        assertEquals(CheckSummary.NothingReached(10, FailurePattern.Mixed), summaryOf(mixed, total))
    }

    /**
     * Every name reached the router and none of them reached a service. Nothing is filtering — the
     * connections opened and the certificates verified — so this points squarely at the homelab, and
     * it is the most specific thing this screen can ever say.
     */
    @Test
    fun `the router answering everything is its own story`() {
        assertEquals(
            CheckSummary.NothingReached(10, FailurePattern.RouterOnly),
            summaryOf(repeated(10, Reachability.RouterAnswered(40)), total),
        )
    }

    @Test
    fun `the router answering is not the service answering`() {
        // Otherwise a homelab with nothing running would report ten services reachable.
        val summary = summaryOf(repeated(10, Reachability.RouterAnswered(40)), total)

        assertEquals(false, summary is CheckSummary.Reached)
    }

    /**
     * **The boundary.** The summary describes the failures observed and never asserts that the ten
     * services share infrastructure.
     *
     * Over IPv4 they do — one address, one port, one ingress. But all ten also publish distinct IPv6
     * addresses, and the app cannot know which family the phone used. So the model carries a
     * *pattern of failures*, and there is deliberately no value here meaning "your ingress is down":
     * that would be a claim about the world made from evidence that does not support it.
     */
    @Test
    fun `the model can describe failures and cannot assert shared infrastructure`() {
        assertEquals(
            setOf(FailurePattern.AllSameLayer, FailurePattern.Mixed, FailurePattern.RouterOnly),
            FailurePattern.entries.toSet(),
        )
    }
}
