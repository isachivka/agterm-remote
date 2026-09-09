package dev.isachivka.bewareofsugar.reachability

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress

/**
 * The same four certificate verdicts, on the platform the owner actually runs.
 *
 * `ReachabilityProberTest` proves these on the JVM, and that is not the same claim. Android does not
 * use the JVM's certificate validator: the JVM wraps a rejection in
 * `sun.security.validator.ValidatorException` and Android's Conscrypt raises its own
 * `CertificateException` subclass, so the two platforms report the same rejected certificate through
 * different exception chains. `ReachabilityCheck` deliberately keys on `java.security.cert` types
 * that exist on both — and "deliberately keys on types that should work on both" is a claim, not a
 * result, until it is run on a device.
 *
 * The duplication with the JVM test is the point rather than an oversight: the thing under test here
 * is whether the two platforms agree.
 */
@RunWith(AndroidJUnit4::class)
class TlsClassificationInstrumentedTest {

    private lateinit var server: MockWebServer
    private val serverHost = "service.test"

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

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
            dns { listOf(InetAddress.getByName("127.0.0.1")) }
        }.build()

        return "https://$serverHost:${server.port}/" to client
    }

    private fun check(url: String, client: OkHttpClient): Reachability =
        runBlocking { ReachabilityProber(client).check(HomeService("test", "Test", url)).reachability }

    /**
     * **Android cannot tell an expired certificate from an untrusted one, and this test exists to
     * pin that rather than to hide it.**
     *
     * The JVM reports expiry precisely: the chain carries a `CertificateExpiredException` and
     * `ReachabilityCheck` returns [TlsFailure.Expired]. `ReachabilityProberTest` proves that, and it
     * proves it for a platform the owner does not use.
     *
     * On Android the same server produces, measured on API 37:
     *
     * ```
     * SSLHandshakeException: java.security.cert.CertPathValidatorException:
     *     Trust anchor for certification path not found.
     *   -> CertificateException: (same message)
     *     -> CertPathValidatorException, getReason() == UNSPECIFIED
     * ```
     *
     * No `CertificateExpiredException`, and no `BasicReason.EXPIRED` to key on. Android's trust
     * manager discards a certificate that is outside its validity window *while building the path*,
     * so what surfaces is a path it could not complete — the same exception, with the same reason and
     * the same message, that an unknown chain produces.
     *
     * The consequence is not cosmetic and is why this is flagged rather than absorbed: the PM's
     * ruling on the error colours rests on `Expired` being quiet, precisely because the owner's
     * wildcard lapses **2026-09-19** and will produce this state. As things stand the phone would
     * classify that as [TlsFailure.Untrusted], which carries `tampering = true` — telling the owner
     * something is intercepting their connection when their certificate merely ran out. That is the
     * exact failure the ruling exists to prevent, arriving by a route neither of us anticipated.
     *
     * **Ruled 2026-07-27: the app stops claiming the distinction rather than building machinery to
     * recover it.** Recovering it would need the app to record the certificate that was presented,
     * which means a custom trust manager — and the precision that buys is one clause in one sentence,
     * against a permanent cost: nobody reading this codebase later can tell a delegating recorder
     * from a permissive one without reading it line by line. A security property that holds only
     * while nobody misreads it is weaker than one that depends on nothing.
     *
     * So [TlsFailure.Untrusted] stays loud, and its copy names both causes and tells the owner to
     * check the certificate's date. REQ-0005 carries the required wording.
     *
     * This test asserts what Android actually does. If a future platform version starts reporting
     * expiry properly, it fails — which is the notification that the copy can be narrowed again.
     */
    @Test
    fun anExpiredCertificateIsIndistinguishableFromAnUntrustedOneOnAndroid() {
        val (url, client) = httpsServer(notBefore = -7_200_000L, notAfter = -3_600_000L)

        val result = check(url, client)

        assertEquals(TlsFailure.Untrusted, (result as Reachability.TlsRejected).reason)
    }

    @Test
    fun aCertificateForAnotherHostIsAMismatchOnAndroidToo() {
        val (url, client) = httpsServer(subjectAltName = "not-the-host-we-asked-for.test")

        val result = check(url, client)

        assertEquals(TlsFailure.HostnameMismatch, (result as Reachability.TlsRejected).reason)
        assertTrue(result.reason.tampering)
    }

    @Test
    fun anUnknownChainIsUntrustedOnAndroidToo() {
        val (url, client) = httpsServer(clientTrustsCa = false)

        val result = check(url, client)

        assertEquals(TlsFailure.Untrusted, (result as Reachability.TlsRejected).reason)
        assertTrue(result.reason.tampering)
    }

    @Test
    fun aTrustedCertificateStillWorksOnAndroid() {
        // The control. Without it the three above would pass against a device that rejected every
        // certificate for some unrelated reason.
        val (url, client) = httpsServer()

        assertTrue(check(url, client) is Reachability.Answered)
    }
}
