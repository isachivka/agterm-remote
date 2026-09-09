package dev.isachivka.bewareofsugar.wire

import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * The inner half, against a real TLS server on the JVM.
 *
 * A real handshake and real records, so the buffer handling is genuinely exercised. What these cannot
 * cover is the hardware keystore, which iteration 1 proves separately - the identity is injected here
 * for exactly that reason, while the trust decision is not injectable at all.
 */
class TlsDriverTest {

    private var server: SSLServerSocket? = null

    @After fun tearDown() { server?.close() }

    private val bridge = HeldCertificate.Builder().commonName("bridge").build()
    private val phone = HeldCertificate.Builder().commonName("phone").build()

    private fun keyManager(held: HeldCertificate): KeyManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("id", held.keyPair.private, CHARS, arrayOf(held.certificate))
        }
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(store, CHARS)
        return factory.keyManagers.first()
    }

    /** A TLS server that echoes, requiring a client certificate. */
    private fun startServer(): Int {
        val certs = HandshakeCertificates.Builder()
            .heldCertificate(bridge)
            .addTrustedCertificate(phone.certificate)
            .build()
        val socket = (certs.sslSocketFactory().let {
            javax.net.ssl.SSLContext.getInstance("TLSv1.3").apply {
                init(certs.keyManager.let { km -> arrayOf<KeyManager>(km) }, arrayOf(certs.trustManager), null)
            }
        }).serverSocketFactory.createServerSocket(0) as SSLServerSocket
        socket.needClientAuth = true
        server = socket
        thread(isDaemon = true) {
            runCatching {
                while (true) {
                    val client = socket.accept() as SSLSocket
                    thread(isDaemon = true) {
                        runCatching {
                            val buf = ByteArray(8192)
                            while (true) {
                                val n = client.inputStream.read(buf)
                                if (n <= 0) break
                                client.outputStream.write(buf, 0, n)
                                client.outputStream.flush()
                            }
                        }
                    }
                }
            }
        }
        return socket.localPort
    }

    /**
     * Delivers at most [chunk] bytes per read.
     *
     * This is what makes the straddle test measure a boundary rather than a round trip: with a small
     * chunk every TLS record arrives split, which is precisely the BUFFER_UNDERFLOW path.
     */
    private class Chunking(private val delegate: InputStream, private val chunk: Int) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            delegate.read(b, off, minOf(len, chunk))
    }

    private fun connect(port: Int, chunk: Int? = null, pinned: X509Certificate = bridge.certificate):
        Pair<TlsDriver, Socket> {
        val socket = Socket("127.0.0.1", port)
        val input = chunk?.let { Chunking(socket.inputStream, it) } ?: socket.inputStream
        return TlsDriver.open(input, socket.outputStream, pinned, keyManager(phone)) to socket
    }

    private fun roundTrip(driver: TlsDriver, payload: ByteArray): ByteArray {
        driver.output.write(payload)
        driver.output.flush()
        val got = ByteArray(payload.size)
        var read = 0
        while (read < payload.size) {
            val n = driver.input.read(got, read, payload.size - read)
            if (n <= 0) break
            read += n
        }
        assertEquals("short read", payload.size, read)
        return got
    }

    // --- The straddle case, built so it proves what it claims ------------------------------------

    /**
     * **Step one: a payload that does NOT cross a boundary, with the transport unchunked.**
     *
     * Run first and deliberately, so that when the crossing case passes we know it is measuring the
     * boundary rather than the round trip. A straddle test that has never been shown to pass on the
     * easy case proves only that TLS works at all.
     */
    @Test
    fun `a small payload with no boundary crossing round trips`() {
        val port = startServer()
        val (driver, socket) = connect(port)
        socket.use {
            val payload = ByteArray(200) { (it * 7).toByte() }
            assertTrue(payload.contentEquals(roundTrip(driver, payload)))
        }
    }

    /**
     * **Step two: the same round trip with every TLS record split across transport reads.**
     *
     * A 37-byte chunk is far smaller than any record, so every single one arrives in pieces and the
     * engine reports BUFFER_UNDERFLOW repeatedly. That is the exact path a buffer bug lives on, and
     * getting it wrong stalls rather than fails.
     */
    @Test
    fun `a payload whose records cross transport boundaries still round trips`() {
        val port = startServer()
        val (driver, socket) = connect(port, chunk = 37)
        socket.use {
            val payload = ByteArray(200) { (it * 7).toByte() }
            assertTrue(payload.contentEquals(roundTrip(driver, payload)))
        }
    }

    /** Larger than one TLS record in both directions, still with split delivery. */
    @Test
    fun `a payload larger than a TLS record survives split delivery both ways`() {
        val port = startServer()
        val (driver, socket) = connect(port, chunk = 37)
        socket.use {
            // Comfortably past the 16 KiB TLS record maximum, so the engine must produce and consume
            // several records for one write.
            val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
            assertTrue(payload.contentEquals(roundTrip(driver, payload)))
        }
    }

    // --- The identity is wrong, at the type level ------------------------------------------------

    @Test
    fun `a bridge certificate that is not the pinned one is refused as NotPinned`() {
        val port = startServer()
        val other = HeldCertificate.Builder().commonName("somebody else").build()

        val failure = runCatching { connect(port, pinned = other.certificate) }.exceptionOrNull()

        assertEquals(WireFailure.NotPinned, (failure as? WireException)?.failure)
    }

    /**
     * **The one byte-exactness alone would miss.**
     *
     * PKIX does not validate a trust anchor's own dates, and a pinned self-signed certificate is its
     * own anchor - so an expired one would be accepted forever, NotAfter would be decorative, and
     * nothing would force rotation. It fails silently in the good direction, which is why nothing
     * would reveal it.
     *
     * Distinguishable from NotPinned because the remedies differ: mint a new certificate on the
     * laptop, not re-pair the phone.
     */
    @Test
    fun `an expired pinned certificate is refused as CertificateExpired, not as NotPinned`() {
        val expiredTrust = PinnedTrust(bridge.certificate) { Date(System.currentTimeMillis() + TEN_YEARS) }

        val failure = runCatching {
            expiredTrust.checkServerTrusted(arrayOf(bridge.certificate), "EC")
        }.exceptionOrNull()

        assertEquals(
            "an expired pin must name its own remedy",
            WireFailure.CertificateExpired,
            (failure as? PinnedTrustRefusal)?.failure,
        )
    }

    @Test
    fun `a certificate presented alongside others is refused`() {
        val other = HeldCertificate.Builder().commonName("filler").build()
        val trust = PinnedTrust(bridge.certificate)

        val failure = runCatching {
            trust.checkServerTrusted(arrayOf(bridge.certificate, other.certificate), "EC")
        }.exceptionOrNull()

        assertEquals(WireFailure.NotPinned, (failure as? PinnedTrustRefusal)?.failure)
    }

    /**
     * **A refusal is thrown in the type the interface documents.**
     *
     * `X509TrustManager.checkServerTrusted` documents `CertificateException`. This threw a
     * `WireException`, which is not one — so the provider wrapped an exception of a type it was not
     * told to expect, and the driver recovered the reason by walking a cause chain that no provider
     * promises to preserve. That works on the JVM provider these tests run against. The app runs on
     * Conscrypt, where a dropped or re-wrapped cause would collapse NotPinned and CertificateExpired
     * into "the laptop is not answering" — about a laptop that answered with the wrong certificate.
     *
     * A defect that passes in CI and fails on the one device nobody can instrument is the exact reason
     * a fake-`Socket` driver was rejected. This asserts the type rather than the behaviour that
     * happened to follow from it.
     */
    @Test
    fun `a refusal is a CertificateException, which is what the interface documents`() {
        val other = HeldCertificate.Builder().commonName("somebody else").build()

        val failure = runCatching {
            PinnedTrust(bridge.certificate).checkServerTrusted(arrayOf(other.certificate), "EC")
        }.exceptionOrNull()

        assertTrue(
            "JSSE is documented to expect CertificateException; anything else invites the provider " +
                "to treat a rejected peer as an internal error. Was ${failure?.javaClass?.name}",
            failure is CertificateException,
        )
    }

    /**
     * **The verdict is readable without an exception at all.**
     *
     * This is the mechanism the driver uses, and the reason it is provider-independent: a field on an
     * object `open` already holds crosses no handshake machinery. The cause chain is the fallback.
     */
    @Test
    fun `the trust manager records its verdict where no provider can lose it`() {
        val other = HeldCertificate.Builder().commonName("somebody else").build()
        val trust = PinnedTrust(bridge.certificate)

        assertEquals("nothing judged yet", null, trust.verdict)
        runCatching { trust.checkServerTrusted(arrayOf(other.certificate), "EC") }

        assertEquals(WireFailure.NotPinned, trust.verdict)
    }

    /** The same, for the failure byte-exactness alone would miss. */
    @Test
    fun `an expiry verdict is recorded too, and stays distinct from NotPinned`() {
        val trust = PinnedTrust(bridge.certificate) { Date(System.currentTimeMillis() + TEN_YEARS) }

        runCatching { trust.checkServerTrusted(arrayOf(bridge.certificate), "EC") }

        assertEquals(WireFailure.CertificateExpired, trust.verdict)
    }

    /** Client-side only. Being asked to judge a client is a state that cannot occur, so it is loud. */
    @Test
    fun `being asked to judge a client fails loudly rather than quietly`() {
        val failure = runCatching {
            PinnedTrust(bridge.certificate).checkClientTrusted(arrayOf(phone.certificate), "EC")
        }.exceptionOrNull()

        assertTrue("must not pass silently", failure is IllegalStateException)
    }

    private companion object {
        val CHARS = charArrayOf()
        const val TEN_YEARS = 10L * 365 * 24 * 60 * 60 * 1000
    }
}
