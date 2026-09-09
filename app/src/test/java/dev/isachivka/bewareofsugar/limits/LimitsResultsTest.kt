package dev.isachivka.bewareofsugar.limits

import dev.isachivka.bewareofsugar.agterm.BridgeRefused
import dev.isachivka.bewareofsugar.wire.WireException
import dev.isachivka.bewareofsugar.wire.WireFailure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * What one request does to the state, for every way the request can end — and when the launcher's own
 * trigger is allowed to start one.
 */
class LimitsResultsTest {

    private var now = Instant.parse("2026-09-06T10:00:00Z")
    private fun snapshot(at: Instant = now) = LimitsSnapshot(
        at,
        ProviderLimits.Windows(listOf(LimitWindow(WindowKind.SevenDay, 75, null))),
        ProviderLimits.Windows(listOf(LimitWindow(WindowKind.SevenDay, 11, null))),
    )

    @Test
    fun `a good answer is held, and fresh travels as asked`() = runBlocking {
        val asked = mutableListOf<Boolean>()
        val results = LimitsResults({ fresh -> asked += fresh; snapshot() }, { now })
        results.refresh(fresh = false)
        results.refresh(fresh = true)
        assertEquals(LimitsState.Held(snapshot()), results.state.value)
        assertEquals(listOf(false, true), asked)
    }

    @Test
    fun `the old bridge's two sentences are both too old`() = runBlocking {
        for (reason in listOf("unknown verb", "malformed request")) {
            val results = LimitsResults({ throw BridgeRefused(reason) }, { now })
            results.refresh(fresh = true)
            assertEquals(reason, LimitsState.BridgeTooOld, results.state.value)
        }
    }

    @Test
    fun `another refusal keeps the bridge's words and the last numbers`() = runBlocking {
        var fail = false
        val results = LimitsResults(
            { if (fail) throw BridgeRefused("this bridge was started without a limits reader") else snapshot() },
            { now },
        )
        results.refresh(false)
        fail = true
        results.refresh(true)
        assertEquals(
            LimitsState.Refused("this bridge was started without a limits reader", snapshot()),
            results.state.value,
        )
    }

    @Test
    fun `a laptop that does not answer keeps the last numbers`() = runBlocking {
        var fail = false
        val results = LimitsResults({ if (fail) throw WireException(WireFailure.CannotReach) else snapshot() }, { now })
        results.refresh(false)
        fail = true
        results.refresh(true)
        assertEquals(LimitsState.Unreachable(snapshot()), results.state.value)
    }

    @Test
    fun `a socket that breaks mid-exchange is unreachable too`() = runBlocking {
        val results = LimitsResults({ throw IOException("reset by peer") }, { now })
        results.refresh(false)
        assertEquals(LimitsState.Unreachable(null), results.state.value)
    }

    @Test
    fun `not paired is its own state`() = runBlocking {
        val results = LimitsResults({ throw WireException(WireFailure.NotPaired) }, { now })
        results.refresh(false)
        assertEquals(LimitsState.NotPaired, results.state.value)
    }

    @Test
    fun `refreshIfStale asks once, then not again until thirty minutes pass`() = runBlocking {
        var calls = 0
        val results = LimitsResults({ calls++; snapshot() }, { now })
        results.refreshIfStale()
        results.refreshIfStale()
        assertEquals(1, calls)
        now = now.plusSeconds(29 * 60)
        results.refreshIfStale()
        assertEquals(1, calls)
        now = now.plusSeconds(2 * 60)
        results.refreshIfStale()
        assertEquals(2, calls)
    }

    @Test
    fun `refreshIfStale asks again after a failure`() = runBlocking {
        var calls = 0
        val results = LimitsResults({ calls++; throw WireException(WireFailure.CannotReach) }, { now })
        results.refreshIfStale()
        results.refreshIfStale()
        assertEquals(2, calls)
    }

    @Test
    fun `staleness is measured from the bridge's fetched_at, not from when the phone asked`() = runBlocking {
        var calls = 0
        // The bridge served a cached answer already 25 minutes old.
        val results = LimitsResults({ calls++; snapshot(at = now.minusSeconds(25 * 60)) }, { now })
        results.refreshIfStale()
        now = now.plusSeconds(6 * 60)
        results.refreshIfStale()
        assertEquals(2, calls)
    }
}
