package dev.isachivka.bewareofsugar.reachability

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the last check found.
 *
 * Held here rather than on the screen, because REQ-0006 needs the answer to exist before the owner
 * opens the module. Nothing here starts work by itself: [check] runs when something calls it, and the
 * only two callers are the owner bringing the app forward and the owner pressing refresh.
 *
 * **Nothing is persisted, ever.** A verdict is a statement about the network the phone was on when it
 * was made, and yesterday's verdict on today's network is the exact lie this feature exists to
 * prevent — worse than saying nothing, because it looks like an answer.
 *
 * REQ-0007 removed the freshness window this class used to hold. The owner asked for a reload on
 * every return from the background, so there is no threshold left to compare against — and with the
 * threshold went the clock, the timestamp and the question `isStale()` answered. What remains decides
 * nothing about *when*; it only runs a round and holds the answer.
 */
class ReachabilityResults(
    private val prober: ReachabilityProber = ReachabilityProber(),
    private val services: List<HomeService> = ServiceRegistry.remote,
) {

    private val _results = MutableStateFlow<Map<String, Probe>>(emptyMap())
    val results: StateFlow<Map<String, Probe>> = _results.asStateFlow()

    /** True once a round has finished, so the module screen can tell "nothing yet" from "nothing available". */
    val hasSettled: Boolean get() = _results.value.values.any {
        it.reachability != Reachability.NotChecked && it.reachability != Reachability.Checking
    }

    /** True while a round is in flight. Guards against two rounds racing - see [check]. */
    private val running = AtomicBoolean(false)

    /**
     * Runs one round of checks, replacing whatever is held.
     *
     * Suspends until every service has settled. The caller owns the scope, which is the whole design:
     * cancel the caller and the calls stop.
     *
     * **A second call while a round is in flight does nothing, and that is a real case rather than a
     * theoretical one.** Since REQ-0007 every return to the foreground starts a round, and the owner
     * can pull to refresh the instant they come back. Two rounds racing would interleave their writes
     * and the later-finishing one would win per service — so a row could end up showing the *older*
     * verdict, which is the one thing this app may not do.
     *
     * Returning rather than queueing is deliberate: the caller wanted fresh results and fresh results
     * are already arriving. Queueing would run a redundant second round on the owner's mobile data to
     * produce the same answer, and the pull-to-refresh indicator follows the round in flight either
     * way, so the gesture still reads as doing something.
     */
    suspend fun check() {
        if (!running.compareAndSet(false, true)) return
        try {
            round()
        } finally {
            running.set(false)
        }
    }

    private suspend fun round() {
        // Every row moves to Checking together, so a slow service reads as pending rather than as a
        // stale verdict that has not caught up yet.
        _results.value = services.associate { it.id to Probe(Reachability.Checking) }

        try {
            prober.checkAll(services).collect { result ->
                _results.update { it + (result.service.id to result.probe) }
            }
        } catch (e: CancellationException) {
            // The owner walked away mid-check. Rows that never settled go back to NotChecked rather
            // than being left on Checking: a spinner that never resolves is a lie of a different
            // shape, and it is the one this arrangement is most likely to produce.
            _results.update { held ->
                held.mapValues { (_, probe) ->
                    if (probe.reachability == Reachability.Checking) Probe(Reachability.NotChecked) else probe
                }
            }
            throw e
        }
    }
}
