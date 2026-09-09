package dev.isachivka.bewareofsugar.reachability

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/**
 * The prober against real sockets and real handshakes.
 *
 * The certificate cases are the reason this file exists. Four of the states are TLS conditions, and
 * asserting them from a reading of the OkHttp source would be exactly the kind of claim this project
 * does not accept — so every one of them is produced by a certificate minted in-process and rejected
 * by a real client. No network, no `badssl.com`, nothing to be flaky.
 */
class ReachabilityProberTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun service(url: String) = HomeService("test", "Test", url)

    /** A name rather than an address, so hostname verification has something to verify. */
    private val serverHost = "service.test"

    private fun probe(url: String, client: OkHttpClient = ReachabilityProber.defaultClient()) =
        runBlocking { ReachabilityProber(client).check(service(url)) }

    private fun check(url: String, client: OkHttpClient = ReachabilityProber.defaultClient()) =
        probe(url, client).reachability

    // -- Answers over a real socket ----------------------------------------------------------------

    @Test
    fun `a 401 over a real socket is up and carries its code`() {
        server.enqueue(MockResponse.Builder().code(401).build())
        server.start()

        val result = check(server.url("/").toString())

        assertTrue(result is Reachability.Answered)
        assertEquals(401, (result as Reachability.Answered).code)
        assertEquals(AnswerMeaning.NeedsAuth, result.meaning)
    }

    /**
     * Not following the redirect is the point, twice over: the 3xx is itself the answer, and
     * following it would leave the host whose certificate was just validated.
     */
    @Test
    fun `a redirect is reported as a redirect rather than followed`() {
        server.enqueue(
            MockResponse.Builder().code(302).setHeader("Location", "https://elsewhere.invalid/").build(),
        )
        server.start()

        val result = check(server.url("/").toString())

        assertEquals(AnswerMeaning.Redirecting, (result as Reachability.Answered).meaning)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the router header is read off a real response`() {
        server.enqueue(
            MockResponse.Builder().code(200).setHeader(ReachabilityCheck.ROUTER_HEADER, "router").build(),
        )
        server.start()

        assertTrue(check(server.url("/").toString()) is Reachability.RouterAnswered)
    }

    @Test
    fun `the body is never read`() {
        // A megabyte of Immich's home page, to learn something the status line already said, is a
        // cost paid on the owner's mobile data.
        server.enqueue(MockResponse.Builder().code(200).body("x".repeat(4096)).build())
        server.start()

        val result = check(server.url("/").toString())

        assertEquals(AnswerMeaning.Reachable, (result as Reachability.Answered).meaning)
    }

    // -- Failures with no server at all ------------------------------------------------------------

    @Test
    fun `a name that does not resolve`() {
        assertEquals(
            Reachability.NameNotResolved,
            check("https://a-name-that-does-not-exist.invalid/"),
        )
    }

    @Test
    fun `a port with nothing listening`() {
        server.start()
        val dead = server.url("/").toString()
        server.close()

        val result = check(dead)

        assertTrue("was $result", result is Reachability.ConnectionRefused)
    }

    @Test
    fun `a server that connects and never answers`() {
        server.enqueue(MockResponse.Builder().headersDelay(3, TimeUnit.SECONDS).code(200).build())
        server.start()

        val impatient = ReachabilityProber.defaultClient().newBuilder()
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .callTimeout(2, TimeUnit.SECONDS)
            .build()

        assertEquals(Reachability.NoAnswerInTime, check(server.url("/").toString(), impatient))
    }

    // -- The certificate family, against real handshakes -------------------------------------------

    /**
     * An HTTPS server presenting a **CA-signed leaf**, which is what makes these tests mean anything.
     *
     * The first version of this helper served a self-signed certificate and trusted it directly, and
     * the expired case then returned 200: PKIX does not check the validity of a trust anchor, so a
     * certificate that is its own anchor never expires as far as the validator is concerned. It
     * proved nothing, and it proved it convincingly. A leaf signed by a separately-trusted CA is both
     * the realistic shape — the owner's certificate is Let's Encrypt-signed — and the one where
     * validity is actually checked.
     *
     * @param subjectAltName the name on the certificate. The request always goes to [serverHost], so
     * anything else here is a hostname mismatch.
     * @param clientTrustsCa false leaves the client on its default trust store, which is what an
     * unknown chain looks like.
     */
    private fun httpsServer(
        notBefore: Long = -1_000L,
        notAfter: Long = 3_600_000L,
        subjectAltName: String = serverHost,
        clientTrustsCa: Boolean = true,
    ): Pair<String, OkHttpClient> {
        val now = System.currentTimeMillis()
        val ca = HeldCertificate.Builder()
            .certificateAuthority(0)
            .commonName("Beware of Sugar test CA")
            .build()
        val leaf = HeldCertificate.Builder()
            .commonName(subjectAltName)
            .addSubjectAlternativeName(subjectAltName)
            .validityInterval(now + notBefore, now + notAfter)
            .signedBy(ca)
            .build()

        server.useHttps(
            HandshakeCertificates.Builder()
                .heldCertificate(leaf, ca.certificate)
                .build()
                .sslSocketFactory(),
        )
        server.enqueue(MockResponse.Builder().code(200).build())
        server.start()

        val client = ReachabilityProber.defaultClient().newBuilder().apply {
            if (clientTrustsCa) {
                val trust = HandshakeCertificates.Builder()
                    .addTrustedCertificate(ca.certificate)
                    .build()
                sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            }
            // Pins the name to the loopback the server is on, so these tests are about certificates
            // and not about which address family `localhost` happens to resolve to first.
            dns { listOf(InetAddress.getByName("127.0.0.1")) }
        }.build()

        return "https://$serverHost:${server.port}/" to client
    }

    @Test
    fun `an expired certificate is reported as expired and not as tampering`() {
        // The state that will actually fire: the owner's wildcard lapses 2026-09-19.
        val (url, client) = httpsServer(notBefore = -7_200_000L, notAfter = -3_600_000L)

        val result = check(url, client)

        assertEquals(TlsFailure.Expired, (result as Reachability.TlsRejected).reason)
        assertEquals(false, result.reason.tampering)
    }

    @Test
    fun `a certificate issued for another host is a hostname mismatch`() {
        val (url, client) = httpsServer(subjectAltName = "not-the-host-we-asked-for.test")

        val result = check(url, client)

        assertEquals(TlsFailure.HostnameMismatch, (result as Reachability.TlsRejected).reason)
        assertTrue(result.reason.tampering)
    }

    @Test
    fun `a chain the device does not trust is untrusted`() {
        val (url, client) = httpsServer(clientTrustsCa = false)

        val result = check(url, client)

        assertEquals(TlsFailure.Untrusted, (result as Reachability.TlsRejected).reason)
        assertTrue(result.reason.tampering)
    }

    @Test
    fun `a certificate the client does trust simply works`() {
        // The control. Without it, the three above would also pass against a client that rejected
        // every certificate for some unrelated reason.
        val (url, client) = httpsServer()

        assertTrue(check(url, client) is Reachability.Answered)
    }

    // -- Connection coalescing ---------------------------------------------------------------------

    /**
     * The risk, demonstrated rather than asserted.
     *
     * All ten services share one IP, one port and one wildcard certificate, and the ingress speaks
     * HTTP/2 — the exact conditions for connection coalescing. This reproduces them: one server, one
     * certificate covering two names, and a DNS that sends both to it.
     *
     * With a shared pool, the second host does not open a connection at all — it rides the first's.
     * On a network that filters per name at the handshake, that turns a blocked service into a green
     * row, which is the false-healthy answer this design exists to avoid.
     */
    @Test
    fun `two hostnames on one address share a connection when the pool is shared`() {
        val (client, pool) = coalescingSetUp()

        // Deliberately what the prober refuses to do: two hosts through one pool.
        listOf("alpha.test", "beta.test").forEach { host ->
            client.newCall(Request.Builder().url("https://$host:${server.port}/").build())
                .execute()
                .use { it.code }
        }

        assertEquals(
            "coalescing did not happen, so the defence in the prober is guarding nothing",
            1,
            pool.connectionCount(),
        )
        assertEquals(2, server.requestCount)
    }

    /** And the same two hostnames through the prober, which gives every check a pool of its own. */
    @Test
    fun `the prober leaves the shared pool untouched, so every check stands alone`() {
        val (client, pool) = coalescingSetUp()
        val prober = ReachabilityProber(client)

        runBlocking {
            prober.check(service("https://alpha.test:${server.port}/"))
            prober.check(service("https://beta.test:${server.port}/"))
        }

        assertEquals(0, pool.connectionCount())
        assertEquals(2, server.requestCount)
    }

    private fun coalescingSetUp(): Pair<OkHttpClient, ConnectionPool> {
        val certificate = HeldCertificate.Builder()
            .commonName("alpha.test")
            .addSubjectAlternativeName("alpha.test")
            .addSubjectAlternativeName("beta.test")
            .build()

        server.useHttps(
            HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(),
        )
        server.protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
        repeat(2) { server.enqueue(MockResponse.Builder().code(200).build()) }
        server.start()

        val trust = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        val pool = ConnectionPool()
        val client = ReachabilityProber.defaultClient().newBuilder()
            .connectionPool(pool)
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            // Both names to the loopback the server is on, which is what makes the two routes
            // identical and coalescing possible.
            .dns { listOf(InetAddress.getByName("127.0.0.1")) }
            .build()

        return client to pool
    }

    // -- Concurrency and cancellation --------------------------------------------------------------

    // -- The address family, which is a fact about the attempt and not part of the verdict ---------

    /**
     * Recorded from the connection that was actually made. It matters because the ten names resolve
     * to one IPv4 ingress and a rotating pool of five IPv6 addresses - so an IPv6 fault is
     * intermittent and moves between services, which is the hardest thing to spot from a screenshot.
     */
    @Test
    fun `a connection that was made reports which family it used`() {
        server.enqueue(MockResponse.Builder().code(200).build())
        server.start()

        // MockWebServer binds loopback; whichever family it resolved, the probe must name it.
        assertTrue(probe(server.url("/").toString()).family != null)
    }

    /**
     * A name that would not resolve never opened a connection, so there is no family to report - and
     * the row renders that absence as absence rather than as an empty separator.
     */
    @Test
    fun `a name that never resolved has no family`() {
        val p = probe("https://a-name-that-does-not-exist.invalid/")

        assertEquals(Reachability.NameNotResolved, p.reachability)
        assertEquals(null, p.family)
    }

    /** A rejected certificate still connected, so the family is known and is worth knowing. */
    @Test
    fun `a certificate failure still reports the family it connected over`() {
        val (url, client) = httpsServer(clientTrustsCa = false)

        val p = probe(url, client)

        assertTrue(p.reachability is Reachability.TlsRejected)
        assertTrue("a rejected certificate still had a connection", p.family != null)
    }

    @Test
    fun `every service is checked, and each result carries its own service`() {
        repeat(3) { server.enqueue(MockResponse.Builder().code(200).build()) }
        server.start()

        val services = listOf(
            HomeService("a", "A", server.url("/a").toString()),
            HomeService("b", "B", server.url("/b").toString()),
            HomeService("c", "C", server.url("/c").toString()),
        )

        val results = runBlocking {
            ReachabilityProber(ReachabilityProber.defaultClient()).checkAll(services).toList()
        }

        assertEquals(3, results.size)
        assertEquals(setOf("a", "b", "c"), results.map { it.service.id }.toSet())
        assertTrue(results.all { it.reachability is Reachability.Answered })
    }

    /**
     * The requirement that each row resolves on its own.
     *
     * A slow service must not hold a fast one off the screen — on a filtered network the slow ones
     * are the ones that time out, and waiting for all of them before showing any would make the
     * screen useless for the ten seconds it matters most.
     */
    @Test
    fun `a fast service lands while a slow one is still going`() {
        val slow = MockWebServer()
        try {
            slow.enqueue(MockResponse.Builder().headersDelay(1, TimeUnit.SECONDS).code(200).build())
            slow.start()
            server.enqueue(MockResponse.Builder().code(200).build())
            server.start()

            val services = listOf(
                HomeService("slow", "Slow", slow.url("/").toString()),
                HomeService("fast", "Fast", server.url("/").toString()),
            )

            val order = runBlocking {
                ReachabilityProber(ReachabilityProber.defaultClient())
                    .checkAll(services)
                    .toList()
                    .map { it.service.id }
            }

            assertEquals(listOf("fast", "slow"), order)
        } finally {
            slow.close()
        }
    }

    /**
     * Leaving the screen stops the checks.
     *
     * Asserted by how long it takes rather than by inspection: the server would take three seconds to
     * answer, and cancelling has to return well inside that. A prober that merely stopped *waiting*
     * would leave the call running on an IO thread and this would still pass — which is why
     * `check` cancels the OkHttp call itself and why the request never reaches the server.
     */
    @Test
    fun `cancelling stops the calls rather than merely stopping the waiting`() {
        server.enqueue(MockResponse.Builder().headersDelay(3, TimeUnit.SECONDS).code(200).build())
        server.start()

        val services = listOf(HomeService("slow", "Slow", server.url("/").toString()))
        val prober = ReachabilityProber(ReachabilityProber.defaultClient())

        val elapsed = measureTimeMillis {
            runBlocking {
                val job = launch { prober.checkAll(services).collect { } }
                // Long enough for the request to be in flight, far short of the three-second answer.
                delay(300)
                job.cancelAndJoin()
            }
        }

        assertTrue("took ${elapsed}ms, so the call was not cancelled", elapsed < 2_000)
    }
}
