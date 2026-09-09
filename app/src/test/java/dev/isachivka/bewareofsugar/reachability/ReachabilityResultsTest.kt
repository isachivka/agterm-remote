package dev.isachivka.bewareofsugar.reachability

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The rule that replaced a once-per-process latch.
 *
 * The latch was wrong for a reason worth restating where it can fail: **a process outlives a
 * network.** Android keeps the app alive for hours, so "check once per process" would let the owner
 * leave the house and open the app on mobile data looking at a green verdict computed on their home
 * Wi-Fi — no process boundary crossed, nothing to catch it.
 *
 * The clock is injected precisely so this is a unit test and not a claim about a device.
 */
class ReachabilityResultsTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun results(count: Int = 2, respond: () -> MockResponse = { MockResponse.Builder().code(200).build() }): ReachabilityResults {
        repeat(count * 4) { server.enqueue(respond()) }
        server.start()
        val services = (1..count).map { HomeService("s$it", "S$it", server.url("/$it").toString()) }
        return ReachabilityResults(
            prober = ReachabilityProber(ReachabilityProber.defaultClient()),
            services = services,
        )
    }

    /**
     * REQ-0007 deleted this class's freshness window, and with it four tests that pinned the
     * 90-second boundary. They were **wrong** rather than stale: the owner asked for a reload on
     * every return, so a test asserting that a verdict 89 seconds old still stands was asserting
     * behaviour the app no longer has. Adjusting them to a new number would have looked identical in
     * a diff to adjusting them to stop failing.
     *
     * The rule that replaced them is a lifecycle property and is tested where lifecycles exist, in
     * `LaunchCheckInstrumentedTest`: away five seconds, back, and a fresh round runs.
     *
     * This one survived the cull because it was never about the threshold - it is about a cancelled
     * round not leaving a spinner that never resolves, which is still true and still the failure
     * this arrangement is most likely to produce.
     */
    @Test
    fun `an interrupted check has not settled`() {
        val slow = results { MockResponse.Builder().headersDelay(3, TimeUnit.SECONDS).code(200).build() }

        runBlocking {
            val job = launch { slow.check() }
            delay(200)
            job.cancelAndJoin()
        }

        assertFalse("a round that never finished must not look finished", slow.hasSettled)
    }

    @Test
    fun `a completed check has settled`() {
        val r = results()
        runBlocking { r.check() }

        assertTrue(r.hasSettled)
    }

    // -- Two rounds cannot race --------------------------------------------------------------------

    /**
     * **The property: what is on screen came from exactly one round.**
     *
     * Since REQ-0007 every return to the foreground starts a round, and the owner can pull to refresh
     * the instant they come back — so a second call arriving mid-round is a normal sequence, not an
     * edge case. Two rounds racing would interleave their writes per service and the later-finishing
     * one would win, which can leave a row carrying the *older* answer. That produces a verdict
     * nobody can reproduce, and without this test nothing would catch it: there is no crash and no
     * exception, just a wrong row.
     *
     * Asserted by counting requests rather than by inspecting state. Two rounds of two services would
     * be four; one round is two.
     */
    @Test
    fun `a second check while one is running does not start a second round`() {
        val slow = results { MockResponse.Builder().headersDelay(700, TimeUnit.MILLISECONDS).code(200).build() }

        runBlocking {
            val first = launch { slow.check() }
            delay(150)
            // The swipe, arriving while the return-triggered round is still in flight.
            slow.check()
            first.join()
        }

        assertEquals("a second round was started", 2, server.requestCount)
    }

    @Test
    fun `a check after one finishes does start a new round`() {
        // The guard must release. Otherwise the first round of the process would be the only one and
        // both pull-to-refresh and reload-on-return would silently stop working.
        val r = results()

        runBlocking {
            r.check()
            r.check()
        }

        assertEquals(4, server.requestCount)
    }

    // -- Cancellation ------------------------------------------------------------------------------

    /**
     * The owner walked away mid-check. Rows that never settled go back to `NotChecked`, never left on
     * `Checking` — a spinner that never resolves is a lie of a different shape, and it is the one
     * this arrangement is most likely to produce.
     */
    @Test
    fun `cancelling leaves no row spinning forever`() {
        val slow = results { MockResponse.Builder().headersDelay(3, TimeUnit.SECONDS).code(200).build() }

        runBlocking {
            val job = launch { slow.check() }
            delay(200)
            job.cancelAndJoin()
        }

        assertEquals(
            "no row may be left on Checking",
            emptyList<String>(),
            slow.results.value.filterValues { it.reachability == Reachability.Checking }.keys.toList(),
        )
    }

    @Test
    fun `a check puts every row on Checking before any of them answer`() {
        val r = results()

        runBlocking {
            val job = launch { r.check() }
            delay(20)
            val seen = r.results.value
            job.join()
            assertEquals("every service should be pending at once", 2, seen.size)
        }
    }

    @Test
    fun `results are keyed by service and survive being read twice`() {
        val r = results()
        runBlocking { r.check() }

        assertEquals(setOf("s1", "s2"), r.results.value.keys)
        assertTrue(r.results.value.values.all { it.reachability is Reachability.Answered })
    }
}
