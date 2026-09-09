package dev.isachivka.agtermremote.pairing

import dev.isachivka.agtermremote.wire.ByteStream
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * The one exchange in this project that runs before anything is pinned, against a real TLS server.
 *
 * ### What this covers and what it deliberately does not
 *
 * A real handshake, real ALPN, the real JSON, the real trust decision, and the real store. What it
 * does not carry is the HTTP upgrade and the WebSocket framing under it: those are
 * `WebSocketStream`'s, they are the same bytes the terminal already speaks, and they are proven
 * against the actual Go bridge rather than against a second Kotlin imitation of it. A fake that
 * reimplements the far end is only ever evidence about the fake.
 *
 * So the transport is injected here as a plain socket, and the layer above it — everything this file
 * is about — is the real code.
 */
class EnrollmentTest {

    @get:Rule val folder = TemporaryFolder()

    private val bridge = HeldCertificate.Builder().commonName("a bridge").build()
    private val someoneElse = HeldCertificate.Builder().commonName("somebody else").build()
    private val phone = HeldCertificate.Builder().commonName("agterm-remote phone").build()

    private var running: FakeBridge? = null

    @After fun tearDown() { running?.close() }

    private fun store() = PairedLaptop(File(folder.root, PairedLaptop.DIRECTORY))

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun payloadFor(
        fake: FakeBridge,
        fingerprintOf: HeldCertificate = bridge,
        host: String = "127.0.0.1",
        port: Int = fake.port,
    ) = EnrollPayload(
        host = host,
        port = port,
        fingerprint = sha256(fingerprintOf.certificate.encoded),
        token = ByteArray(32) { (it + 1).toByte() },
        expiryUnix = 4_102_444_800L,
    )

    private fun enrol(
        payload: EnrollPayload,
        deviceName: String = "a phone",
        store: PairedLaptop = store(),
    ): EnrollResult = Enrollment.enroll(
        payload = payload,
        identity = phone.certificate,
        deviceName = deviceName,
        store = store,
    ) { address -> SocketStream(address) }

    /** The reply a bridge that accepted the token sends. */
    private fun accepted(): (JSONObject) -> String = {
        JSONObject()
            .put("ok", true)
            .put("certificate", Base64.getEncoder().encodeToString(bridge.certificate.encoded))
            .put("fingerprint", Fingerprint.of(phone.certificate))
            .put("name", "a phone")
            .toString()
    }

    /**
     * **The forty bytes.** Every refusal the bridge can produce is this exact line, with no cause in
     * it, and the test uses the literal rather than a builder so a change to either side shows up
     * here.
     */
    private fun refused(): (JSONObject) -> String = { """{"ok":false,"error":"enrolment refused"}""" }

    @Test
    fun `a bridge that accepts the token pairs this phone`() {
        val fake = start(accepted())
        val payload = payloadFor(fake)
        val store = store()

        val result = enrol(payload, store = store)

        val paired = result as EnrollResult.Paired
        assertArrayEquals(bridge.certificate.encoded, paired.bridgeCertificate.encoded)
        assertEquals(Fingerprint.of(phone.certificate), paired.fingerprint)

        val stored = store.read()
        assertNotNull("a successful enrolment is what pairs this phone", stored)
        assertEquals(payload.host, stored!!.host)
        assertEquals(payload.port, stored.port)
        assertArrayEquals(
            "the exact bytes behind the fingerprint off the code, not the fingerprint",
            bridge.certificate.encoded,
            stored.bridgeCertificate,
        )
    }

    /**
     * The connection declares what it is for in the handshake, and it offers exactly one protocol.
     *
     * Offering two would hand the bridge the choice, and the bridge's own rule is that a caller
     * offering the enrolment protocol while a window is open gets enrolment whatever else it asked
     * for. One protocol per connection is what keeps that from ever being a surprise.
     */
    @Test
    fun `the handshake asks for enrolment and for nothing else`() {
        val fake = start(accepted())

        enrol(payloadFor(fake))

        assertEquals(listOf("agterm/enroll-1"), fake.clientOffered)
        assertEquals("agterm/enroll-1", fake.negotiated)
    }

    /** The four fields the bridge accepts, and no fifth — it refuses a field it does not know. */
    @Test
    fun `the request is the four fields the bridge parses`() {
        val fake = start(accepted())
        val payload = payloadFor(fake)

        enrol(payload, deviceName = "a phone")

        val request = fake.request!!
        assertEquals(setOf("verb", "token", "certificate", "name"), request.keys().asSequence().toSet())
        assertEquals("enroll", request.getString("verb"))
        assertArrayEquals(payload.token, Base64.getDecoder().decode(request.getString("token")))
        assertArrayEquals(
            phone.certificate.encoded,
            Base64.getDecoder().decode(request.getString("certificate")),
        )
        assertEquals("a phone", request.getString("name"))
    }

    /** One line in, one line out, then the connection closes. A second request is never sent. */
    @Test
    fun `exactly one line is sent`() {
        val fake = start(accepted())

        enrol(payloadFor(fake))

        assertEquals(1, fake.linesRead)
    }

    /**
     * **The whole security argument for showing a token on a screen.**
     *
     * The fingerprint is checked during the handshake, so a laptop that is not the one in the code
     * never sees the token, never sees this phone's certificate, and never learns that a pairing was
     * attempted at all beyond a failed handshake.
     */
    @Test
    fun `a wrong fingerprint fails before anything is sent`() {
        val fake = start(accepted())
        val store = store()

        val result = enrol(payloadFor(fake, fingerprintOf = someoneElse), store = store)

        assertTrue("this is not unreachable: a machine answered", result is EnrollResult.NotTheLaptopInTheCode)
        assertNull("the token must never reach a laptop that is not in the code", fake.request)
        assertEquals(0, fake.linesRead)
        assertNull(store.read())
    }

    /**
     * **A spent or expired code is the common failure, and it must not be reported as a dead laptop.**
     *
     * With no window open the bridge offers `agterm/api-1` alone, so a phone offering `enroll-1`
     * alone is refused during ALPN negotiation — before any certificate is presented, and therefore
     * before `FingerprintTrust` is ever consulted. Everything the four-armed result type exists to
     * separate collapses at that point unless the arm is chosen by WHERE the failure happened.
     *
     * The transport opened. Something is listening at that address, it completed an HTTP upgrade, and
     * it then declined to speak the protocol this code asks for. "Your laptop is not reachable" is
     * false about all three, and it sends the owner to their router when the remedy is a new code.
     */
    @Test
    fun `a code the laptop will not accept is refused rather than called unreachable`() {
        val fake = startWithTheWindowShut()
        val store = store()

        val result = enrol(payloadFor(fake), store = store)

        assertTrue(
            "a laptop that answered and declined the protocol is not unreachable, it refused: $result",
            result is EnrollResult.Refused,
        )
        assertEquals(Enrollment.REFUSED, (result as EnrollResult.Refused).reason)
        assertNull(store.read())
    }

    /**
     * The other half of the arm, and the one that keeps the fix from being "call everything refused".
     * Nothing answered at all, so there is nothing to have refused.
     */
    @Test
    fun `a refusal is not claimed when the transport never opened`() {
        val store = store()
        val closed = start(accepted())
        val port = closed.port
        closed.close()

        val result = enrol(payloadFor(closed, port = port), store = store)

        assertTrue("nothing answered, so nothing refused: $result", result is EnrollResult.Unreachable)
    }

    @Test
    fun `a refused enrolment pins nothing`() {
        val fake = start(refused())
        val store = store()

        val result = enrol(payloadFor(fake), store = store)

        assertTrue(result is EnrollResult.Refused)
        assertNull("a refusal must leave this phone exactly as unpaired as it was", store.read())
    }

    /**
     * A refusal says nothing about why, and this asserts that the phone does not pretend otherwise.
     * The reason it reports is its own sentence; nothing is parsed out of the reply.
     */
    @Test
    fun `a refusal reports no cause it was not given`() {
        val withACause = start { """{"ok":false,"error":"the token expired at 12:04"}""" }

        val result = enrol(payloadFor(withACause)) as EnrollResult.Refused

        assertFalse(result.reason.contains("12:04"))
    }

    /**
     * The bridge's reply carries the DER behind the fingerprint. If it carries anything else, the
     * fingerprint the owner compared authenticated a certificate the phone is not about to pin, and
     * the next connection would fail with nothing pointing here.
     */
    @Test
    fun `a reply carrying a different certificate pins nothing`() {
        val fake = start {
            JSONObject()
                .put("ok", true)
                .put("certificate", Base64.getEncoder().encodeToString(someoneElse.certificate.encoded))
                .put("fingerprint", Fingerprint.of(phone.certificate))
                .toString()
        }
        val store = store()

        val result = enrol(payloadFor(fake), store = store)

        assertTrue(result is EnrollResult.Refused)
        assertNull(store.read())
    }

    /**
     * The receipt. The reply's fingerprint is what the bridge actually STORED, so one that is not
     * this phone's means the certificate arrived mangled — and the phone learns it here rather than
     * as a handshake failure it cannot explain on the next connection.
     */
    @Test
    fun `a receipt for another phone pins nothing`() {
        val fake = start {
            JSONObject()
                .put("ok", true)
                .put("certificate", Base64.getEncoder().encodeToString(bridge.certificate.encoded))
                .put("fingerprint", Fingerprint.of(someoneElse.certificate))
                .toString()
        }
        val store = store()

        val result = enrol(payloadFor(fake), store = store)

        assertTrue(result is EnrollResult.Refused)
        assertNull(store.read())
    }

    /**
     * **The decoder accepts a host the dialler must not.** `EnrollCodec` validates the host as UTF-8
     * and nothing more, matching the Go side byte for byte, so a NUL, a space or a slash decodes into
     * a perfectly ordinary-looking payload. Port 0 decodes too.
     *
     * Those are not addresses. Refused here, where the address is used, rather than in the decoder,
     * where refusing would put the two implementations of one wire format out of step.
     */
    @Test
    fun `an address that is not one is never dialled`() {
        val notAddresses = listOf(
            EnrollPayload("host name", 8443, StreamKind.DirectTcp, ByteArray(32), ByteArray(32), 0),
            EnrollPayload("", 8443, StreamKind.DirectTcp, ByteArray(32), ByteArray(32), 0),
            EnrollPayload(" ", 8443, StreamKind.DirectTcp, ByteArray(32), ByteArray(32), 0),
            EnrollPayload("laptop.example/../..", 8443, StreamKind.DirectTcp, ByteArray(32), ByteArray(32), 0),
            EnrollPayload("laptop.example\nhost", 8443, StreamKind.DirectTcp, ByteArray(32), ByteArray(32), 0),
            EnrollPayload("evil@laptop.example", 8443, StreamKind.DirectTcp, ByteArray(32), ByteArray(32), 0),
            EnrollPayload("laptop.example", 0, StreamKind.DirectTcp, ByteArray(32), ByteArray(32), 0),
        )

        for (payload in notAddresses) {
            var dialled = false
            val result = Enrollment.enroll(
                payload = payload,
                identity = phone.certificate,
                deviceName = "a phone",
                store = store(),
            ) { dialled = true; throw AssertionError("must not dial") }

            assertFalse("dialled ${payload.host}:${payload.port}", dialled)
            assertTrue(result is EnrollResult.Unreachable)
        }
    }

    /** A laptop that is not there is unreachable, and nothing is stored. */
    @Test
    fun `a laptop that does not answer is unreachable`() {
        val store = store()
        val closed = start(accepted())
        val port = closed.port
        closed.close()

        val result = enrol(payloadFor(closed, port = port), store = store)

        assertTrue(result is EnrollResult.Unreachable)
        assertNull(store.read())
    }

    private fun start(reply: (JSONObject) -> String): FakeBridge =
        FakeBridge(bridge, reply).also { running = it }

    /** The same laptop with its pairing window shut: it offers the API protocol and nothing else. */
    private fun startWithTheWindowShut(): FakeBridge =
        FakeBridge(bridge, accepted(), offers = "agterm/api-1").also { running = it }

    /**
     * A TLS server speaking the enrolment protocol: one ALPN entry, no client certificate, one line
     * in and one line out.
     *
     * It is not a second implementation of the bridge and must not become one. What it exists to do
     * is present a certificate and record what arrived.
     */
    private class FakeBridge(
        held: HeldCertificate,
        private val reply: (JSONObject) -> String,
        /**
         * What this bridge offers, and it is **one** protocol, exactly as the far end is.
         *
         * `enroll.ServerConfigFor` picks a whole configuration per connection: with a window open it
         * offers `agterm/enroll-1` alone, and with the window shut it offers `agterm/api-1` alone.
         * A phone arriving with a spent code therefore meets a server whose single protocol is not
         * the one it asked for, and the handshake dies on `no_application_protocol` before a
         * certificate is looked at. That is the state this fixture exists to reproduce.
         */
        private val offers: String = ENROL,
    ) : Closeable {

        @Volatile var request: JSONObject? = null
        @Volatile var negotiated: String? = null
        @Volatile var clientOffered: List<String>? = null
        @Volatile var linesRead: Int = 0

        private val server: SSLServerSocket

        val port: Int get() = server.localPort

        init {
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setKeyEntry("id", held.keyPair.private, CHARS, arrayOf(held.certificate))
            }
            val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            factory.init(keys, CHARS)
            val context = SSLContext.getInstance("TLSv1.3").apply {
                init(factory.keyManagers, null, null)
            }
            server = context.serverSocketFactory
                .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            thread(isDaemon = true) { serve() }
        }

        private fun serve() {
            while (!server.isClosed) {
                val socket = try {
                    server.accept() as SSLSocket
                } catch (e: Exception) {
                    return
                }
                thread(isDaemon = true) { exchange(socket) }
            }
        }

        private fun exchange(socket: SSLSocket) {
            socket.use {
                try {
                    socket.setHandshakeApplicationProtocolSelector { _, offered ->
                        clientOffered = offered.toList()
                        // null is the platform's "nothing in common", which fails the handshake the
                        // way Go's does rather than quietly negotiating no protocol at all.
                        offered.firstOrNull { p -> p == offers }
                    }
                    socket.startHandshake()
                    negotiated = socket.applicationProtocol
                    val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                    val line = reader.readLine() ?: return
                    linesRead++
                    val parsed = JSONObject(line)
                    request = parsed
                    socket.outputStream.write((reply(parsed) + "\n").toByteArray(Charsets.UTF_8))
                    socket.outputStream.flush()
                } catch (e: Exception) {
                    // A refused handshake lands here. Nothing to answer with: the point of the test
                    // that exercises it is that the exchange never began.
                }
            }
        }

        override fun close() {
            server.close()
        }

        private companion object {
            const val ENROL = "agterm/enroll-1"

            /** An in-memory keystore that is never written anywhere, so it is protected by nothing. */
            val CHARS = charArrayOf()
        }
    }

    /**
     * The transport seam: a plain socket, so the fake bridge above can be a plain TLS server.
     *
     * It is handed the URL the payload derived, and takes the address back out of it. That is not
     * ceremony: parsing what production actually passes is what makes the test cover the derivation
     * rather than route around it - `theUrlIsWhatTheTransportIsHanded` asserts the scheme in it.
     */
    private class SocketStream(url: String) : ByteStream {
        private val socket: Socket

        init {
            val authority = url.substringAfter("://").substringBefore("/")
            val host = authority.substringBeforeLast(':').removeSurrounding("[", "]")
            val port = authority.substringAfterLast(':').toInt()
            socket = Socket(host, port)
        }

        override val input: InputStream get() = socket.getInputStream()
        override val output: OutputStream get() = socket.getOutputStream()
        override fun close() = socket.close()
    }
}
