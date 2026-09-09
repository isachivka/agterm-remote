package dev.isachivka.bewareofsugar.limits

import android.content.Context
import dev.isachivka.bewareofsugar.agterm.BridgeConnection
import dev.isachivka.bewareofsugar.agterm.BridgeRefused
import dev.isachivka.bewareofsugar.pairing.PairedLaptop
import dev.isachivka.bewareofsugar.pairing.PhoneIdentity
import dev.isachivka.bewareofsugar.wire.WireException
import dev.isachivka.bewareofsugar.wire.WireFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The limits the tile shows, and the one request that fills them — REQ-0045.
 *
 * The same shape as `ReachabilityResults`: it holds a state, it runs when called and never by itself,
 * and it persists nothing. The caller owns the scope, so leaving the app cancels the request.
 *
 * [fetch] is a function rather than a connection so the tests stand in a lambda; production builds it
 * with [overBridge], which opens the paired connection for one exchange and closes it.
 */
class LimitsResults(
    private val fetch: (fresh: Boolean) -> LimitsSnapshot,
    private val clock: () -> Instant = Instant::now,
) {

    private val _state = MutableStateFlow<LimitsState>(LimitsState.Idle)
    val state: StateFlow<LimitsState> = _state.asStateFlow()

    private val running = AtomicBoolean(false)

    /**
     * One request. A second call while one is in flight does nothing — a long press during an update
     * is already getting what it asked for, and the tile's own guard says the same thing.
     */
    suspend fun refresh(fresh: Boolean) {
        if (!running.compareAndSet(false, true)) return
        val last = _state.value.shown
        _state.value = LimitsState.Fetching(last)
        try {
            val snapshot = withContext(Dispatchers.IO) { fetch(fresh) }
            _state.value = LimitsState.Held(snapshot)
        } catch (e: CancellationException) {
            // The owner walked away mid-request. Back to what was held rather than a spinner that
            // never resolves.
            _state.value = last?.let { LimitsState.Held(it) } ?: LimitsState.Idle
            throw e
        } catch (e: BridgeRefused) {
            _state.value = when (e.reason) {
                // An older bridge: no verb by that name, or - on a long press, which sends `fresh` -
                // a key it does not know. Both mean the same thing to the owner, which is that the
                // Mac's half needs updating. See BridgeConnection.limits.
                "unknown verb", "malformed request" -> LimitsState.BridgeTooOld
                else -> LimitsState.Refused(e.reason, last)
            }
        } catch (e: WireException) {
            _state.value =
                if (e.failure == WireFailure.NotPaired) LimitsState.NotPaired else LimitsState.Unreachable(last)
        } catch (e: IOException) {
            _state.value = LimitsState.Unreachable(last)
        } finally {
            running.set(false)
        }
    }

    /**
     * The launcher's own trigger: ask only when nothing is held or what is held is older than
     * [STALE_AFTER]. Thirty minutes on the phone mirrors thirty on the Mac, so the two do not disagree
     * about staleness. Any state other than a held snapshot is stale by definition — a failure is
     * retried on the next return to the foreground rather than remembered.
     */
    suspend fun refreshIfStale() {
        val held = (_state.value as? LimitsState.Held)?.snapshot
        if (held != null && Duration.between(held.fetchedAt, clock()) < STALE_AFTER) return
        refresh(fresh = false)
    }

    companion object {
        val STALE_AFTER: Duration = Duration.ofMinutes(30)

        /**
         * The production fetch: the paired laptop, one exchange, closed. Null from [PairedLaptop] is
         * the unpaired state, and it is reported as that rather than as a laptop that did not answer.
         */
        fun overBridge(connect: () -> BridgeConnection?): (Boolean) -> LimitsSnapshot = { fresh ->
            val connection = connect() ?: throw WireException(WireFailure.NotPaired)
            connection.use { it.limits(fresh) }
        }

        /** The same connection `AgtermHost` builds, so pairing has one reader. */
        fun connectPaired(context: Context): () -> BridgeConnection? = {
            PairedLaptop(context).read()?.let { BridgeConnection.open(it, PhoneIdentity.keyManager()) }
        }
    }
}
