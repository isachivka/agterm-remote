package dev.isachivka.bewareofsugar.reachability

import dev.isachivka.bewareofsugar.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resumeWithException

/**
 * Which address family a connection actually used.
 *
 * **Deliberately not part of [Reachability].** The classifier decides what happened; this is a fact
 * about how the attempt was made, and letting it into the state model would mean a verdict could
 * depend on it. `ReachabilityCheck` never sees this and `ProbeTest` asserts the separation.
 *
 * It exists because of a correction from REQ-0006: the ten names resolve to one IPv4 ingress and a
 * rotating pool of five IPv6 addresses, two returned per query, with the pair itself varying between
 * queries. So an IPv6 fault is intermittent *and* moves between services - the hardest signature to
 * recognise from a screenshot, and the reason a row that says which path it took turns "Immich was
 * different" into "Immich went over IPv6 and the others did not".
 */
enum class AddressFamily { IPv4, IPv6 }

/**
 * A verdict, and the facts about how it was reached.
 *
 * @param family null when no connection was attempted - a name that would not resolve has no family,
 * and the row must render that absence as absence rather than as an empty separator.
 */
data class Probe(val reachability: Reachability, val family: AddressFamily? = null)

/** One service and what happened to it. */
data class ServiceResult(val service: HomeService, val probe: Probe) {
    val reachability: Reachability get() = probe.reachability
}

/**
 * Asks each service whether it is there, and never asks it who it is.
 *
 * The app authenticates to nothing: this sends an unauthenticated `GET` and reads the status line.
 * Whatever the answer is, the interpretation belongs to [ReachabilityCheck], which does no IO — so
 * everything worth arguing about is a unit test and this class is the part that touches a socket.
 */
class ReachabilityProber(
    private val client: OkHttpClient = defaultClient(),
    private val nowNanos: () -> Long = System::nanoTime,
) {

    /**
     * Every service at once, each row landing as it resolves.
     *
     * A `Flow` rather than a list of results, because the requirement is that the screen never waits
     * for the slowest: a service that answers in 40 ms should be on screen while another is still
     * timing out. Cancelling collection — which is what leaving the screen does — cancels every call
     * still in flight.
     */
    fun checkAll(services: List<HomeService>): Flow<ServiceResult> = channelFlow {
        // channelFlow closes the channel once this block and every coroutine it launched have
        // finished, which is exactly the wanted behaviour: results arrive in whatever order they
        // land, and the flow completes when the last one does.
        services.forEach { service ->
            launch { send(ServiceResult(service, check(service))) }
        }
    }

    /**
     * One service.
     *
     * The body is never read. The status line and one header are the whole answer, and reading a
     * megabyte of Immich's home page to learn something the first line already said would be a cost
     * paid on the owner's mobile data.
     */
    suspend fun check(service: HomeService): Probe {
        val recorder = StageRecorder()
        val call = client.newBuilder()
            // A connection pool of this call's own, and this is the least obvious line in the file.
            //
            // All ten services resolve to one IP on one port behind one wildcard certificate, and
            // the ingress speaks HTTP/2 - all three measured. Those are exactly the conditions for
            // HTTP/2 connection coalescing: OkHttp will happily carry a request for
            // navidrome.<domain> over a connection it already opened for immich.<domain>, because
            // the certificate covers both and the route is identical. That is correct behaviour for
            // a client trying to be fast, and ruinous for a client trying to measure.
            //
            // Coalesced, nine of the ten would perform no DNS lookup, open no connection and send no
            // SNI - they would inherit the first row's verdict. On the filtered network this app is
            // built for, where blocking is per-name and happens at the handshake, that turns a
            // blocked service into a green row: the false-healthy answer that the RouterAnswered
            // state also exists to prevent.
            //
            // A private pool per call is what makes each row an independent measurement. It costs
            // ten handshakes instead of one, which is the price of the answer being true.
            .connectionPool(ConnectionPool())
            .eventListener(recorder)
            .build()
            .newCall(
                Request.Builder()
                    .url(service.url)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build(),
            )

        val started = nowNanos()

        return try {
            call.await().use { response ->
                Probe(
                    ReachabilityCheck.of(
                        code = response.code,
                        routerAnswered = response.header(ReachabilityCheck.ROUTER_HEADER) != null,
                        latencyMillis = millisSince(started),
                    ),
                    recorder.family,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A cancelled call surfaces from OkHttp as an IOException, not as a CancellationException
            // - so without this, walking away from the screen would classify ten aborted calls as
            // failures and hand the caller a screenful of red on the way out.
            coroutineContext.ensureActive()
            Probe(ReachabilityCheck.of(e, recorder.stage, millisSince(started)), recorder.family)
        }
    }

    /**
     * Awaits the call, and cancels the socket when the coroutine is cancelled.
     *
     * This is `enqueue` and not `execute` for one reason, and it was a test that produced it rather
     * than foresight. The first version blocked on `execute()` inside `Dispatchers.IO` and registered
     * `job.invokeOnCompletion { call.cancel() }` — which never ran in time, because a job whose body
     * is blocked in a socket read does not *complete* when it is cancelled; it completes when the
     * read returns. Leaving the screen took as long as the slowest service, which is precisely the
     * behaviour the requirement forbids, and it looked correct.
     *
     * `invokeOnCancellation` fires at cancellation rather than at completion, which is the difference
     * that matters. It also stops holding an IO thread per service for the duration.
     */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                // Closes the body if the continuation was cancelled between the callback and the
                // resumption, which would otherwise leak the connection.
                continuation.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                // A cancelled call reports failure too. Resuming an already-cancelled continuation
                // would be an error, and the failure is not one worth reporting anyway.
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        })
    }

    private fun millisSince(startedNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(nowNanos() - startedNanos)

    companion object {
        private val USER_AGENT = "BewareOfSugar/${BuildConfig.VERSION_NAME}"

        /**
         * Settings chosen so the client reports what happened rather than tries to succeed — with one
         * exception, which is the most interesting line here.
         *
         * **`retryOnConnectionFailure(true)`**, reversing what PLAN-0005 proposed. The plan argued
         * for `false` on the grounds that "a retried failure reports the second attempt", and that
         * description of the flag is wrong: it does not govern retrying the same failing operation,
         * it governs whether OkHttp tries the *other addresses a name resolves to*.
         *
         * That matters here rather than in the abstract. All ten services publish an AAAA record as
         * well as an A record, and the two families do not go to the same place — the ten share a
         * single IPv4 ingress and have ten distinct IPv6 addresses. On a dual-stack mobile network
         * the phone will generally try IPv6 first, so with route fallback disabled a working service
         * whose IPv6 path is broken would be reported unreachable while the owner's browser, which
         * does fall back, opens it. A diagnostic that is less capable than the browser it is advising
         * is a diagnostic that lies, which is the one thing this screen may not do.
         *
         * The cost is accepted and is small: when every route fails the reported failure is the last
         * one tried rather than the first, and the latency includes the attempts that failed — which
         * is also what the browser pays.
         *
         * **`followRedirects(false)`** and **`followSslRedirects(false)`** — a 3xx is an answer, and
         * three of the ten services answer that way. Following one would also leave the host whose
         * certificate was just validated, which is the single thing this check is built on.
         *
         * **`callTimeout`** — bounds the whole call rather than one phase of it, so a service that
         * connects and then dribbles cannot hold a row open past the screen's ten seconds.
         *
         * Certificate validation is left exactly as OkHttp ships it. Nothing here or anywhere else in
         * this app installs a trust manager or a hostname verifier, in any build type.
         *
         * No logging interceptor, for the same reason as `GitHubApi.kt:98`.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // Set explicitly, though it matches OkHttp's default, because PLAN-0005 called for the
            // opposite and a reader comparing the two should find the disagreement here.
            .retryOnConnectionFailure(true)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        private const val CONNECT_TIMEOUT_SECONDS = 4L
        private const val READ_TIMEOUT_SECONDS = 4L

        /**
         * Six seconds, so that ten concurrent checks settle inside the ten the requirement allows
         * even when every one of them times out.
         */
        private const val CALL_TIMEOUT_SECONDS = 6L
    }
}

/**
 * Records how far a call got, so that a timeout can be told apart from a timeout.
 *
 * A `SocketTimeoutException` says nothing about which phase gave up, and the phase is the whole
 * diagnosis — never connected is a blocked port, connected but never secured is a blocked handshake,
 * secured and then silent is a service that stopped answering. The alternative is matching on the
 * exception's message text, which is control flow on prose.
 *
 * Advances only. OkHttp fires `connectEnd` *after* `secureConnectEnd` for an HTTPS call, so an
 * unguarded assignment would walk the stage backwards at the last moment and report a completed
 * handshake as an incomplete one.
 */
private class StageRecorder : EventListener() {

    @Volatile
    var stage: CallStage = CallStage.Starting
        private set

    /**
     * The family of the address this attempt used, or null if no connection was ever attempted.
     *
     * Recorded at `connectStart` rather than `connectEnd`, because the diagnostic value is in which
     * path was *taken* - a refused connection and a rejected certificate both have a family, and
     * both are cases where knowing it matters. Only a name that would not resolve has none.
     */
    @Volatile
    var family: AddressFamily? = null
        private set

    private fun advanceTo(next: CallStage) {
        if (next.ordinal > stage.ordinal) stage = next
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) =
        advanceTo(CallStage.NameResolved)

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        family = when (inetSocketAddress.address) {
            is Inet6Address -> AddressFamily.IPv6
            else -> AddressFamily.IPv4
        }
    }

    // TCP is established by the time TLS begins, so this marks both.
    override fun secureConnectStart(call: Call) = advanceTo(CallStage.Handshaking)

    override fun secureConnectEnd(call: Call, handshake: Handshake?) = advanceTo(CallStage.Secured)

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) = advanceTo(CallStage.Connected)

    override fun connectionAcquired(call: Call, connection: Connection) =
        advanceTo(CallStage.Secured)

    override fun requestHeadersEnd(call: Call, request: Request) =
        advanceTo(CallStage.AwaitingResponse)
}
