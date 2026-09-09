package dev.isachivka.agtermremote.pairing

import kotlinx.coroutines.runBlocking
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * **That the gate is REACHED**, which is a different claim from the one `EnrolGateTest` makes.
 *
 * `EnrolGateTest` proves the gate refuses when it is asked. That is worth nothing on its own: the gate
 * call was replaced with a direct `Enrollment.enroll` and **every test in this module still passed**,
 * because the property lived in an arrangement and only the piece was pinned. This file pins the
 * arrangement.
 *
 * ### The instrument is a real socket, and it is controlled
 *
 * "Nothing was opened" is asserted by a `ServerSocket` on loopback that counts what reaches it, and
 * `Enrollment` — the only thing in this module that opens a socket or writes the store — is **not**
 * injectable here. A fake in its place would make the assertion a statement about the fake.
 *
 * The counter is only meaningful if it can count, so the same path is driven a second time with a key
 * that WILL sign, and that run has to reach the socket. Without that control this file would pass just
 * as happily against a payload pointing nowhere.
 */
class EnrolWiringTest {

    @get:Rule val directory = TemporaryFolder()

    private val certificate = HeldCertificate.Builder().commonName("this phone").build().certificate

    private lateinit var server: ServerSocket
    private val reached = AtomicInteger()

    private fun listening(): EnrollPayload {
        server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    // Accepted and dropped. What the far end does afterwards does not matter: the
                    // question this file asks is whether anything arrived at all.
                    server.accept().close()
                    reached.incrementAndGet()
                } catch (e: Exception) {
                    return@thread
                }
            }
        }
        return EnrollPayload(
            host = "127.0.0.1",
            port = server.localPort,
            scheme = StreamKind.DirectTcp,
            fingerprint = ByteArray(32),
            token = ByteArray(32),
            expiryUnix = 4_000_000_000,
        )
    }

    @After
    fun stop() {
        if (::server.isInitialized) server.close()
    }

    private fun store() = PairedLaptop(directory.root)

    /**
     * **The assertion the round exists for.** A key that will not sign, on the real path, reaching no
     * socket and storing nothing — so nothing is spent here and nothing is pinned on the Mac.
     */
    @Test
    fun `a key that will not sign opens no socket on the real path`() = runBlocking {
        val payload = listening()
        val store = store()

        val result = enrolThisPhone(
            payload = payload,
            store = store,
            deviceName = "a test",
            identity = { certificate },
            signing = { SigningState.Unusable },
        )

        assertEquals(EnrollResult.Refused(PairingOutcome.KEY_CANNOT_SIGN), result)
        assertEquals("something dialled the address on the code", 0, reached.get())
        assertNull("a refused pairing must store nothing", store.read())
    }

    /**
     * **The control, and this file is worth nothing without it.**
     *
     * The same wiring with a key that signs has to reach the socket. If it did not, the zero above
     * would be a fact about the fixture rather than about the gate — which is how a test that asserts
     * an absence passes for the wrong reason.
     */
    @Test
    fun `a key that signs reaches the socket, so the counter above means something`() = runBlocking {
        val payload = listening()

        val result = enrolThisPhone(
            payload = payload,
            store = store(),
            deviceName = "a test",
            identity = { certificate },
            signing = { SigningState.Ready },
        )

        assertTrue("nothing reached the address, so this file's instrument is broken", reached.get() > 0)
        // And it did not pair: the far end is a socket that accepts and hangs up. What matters is
        // that the refusal came from the exchange rather than from the gate.
        assertNotEquals(EnrollResult.Refused(PairingOutcome.KEY_CANNOT_SIGN), result)
    }

    /**
     * A keystore that will not mint is refused on the real path too, and it does not reach the socket
     * either — the identity is read before anything is dialled, which is the other half of the order.
     */
    @Test
    fun `an identity that will not mint opens no socket on the real path`() = runBlocking {
        val payload = listening()

        val result = enrolThisPhone(
            payload = payload,
            store = store(),
            deviceName = "a test",
            identity = { throw java.security.GeneralSecurityException("no key") },
            signing = { SigningState.Ready },
        )

        assertEquals(EnrollResult.Refused(PairingOutcome.NO_IDENTITY), result)
        assertEquals("something dialled the address on the code", 0, reached.get())
    }
}
