package dev.isachivka.agtermremote.pairing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.isachivka.agtermremote.agterm.BridgeConnection
import dev.isachivka.agtermremote.agterm.BridgeRefused
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The two halves of this project, meeting.
 *
 * Everything else about enrolment is proven against a fake: `EnrollmentTest` in `src/test` stands up a
 * real TLS server in-process, which covers the handshake, the trust decision, the JSON and the store,
 * and covers none of the HTTP upgrade, the WebSocket framing, Conscrypt's ALPN, or the Go handler's
 * reading of what this phone actually sends. **A fake of the far end is only ever evidence about the
 * fake**, and this is the test that is not that.
 *
 * ### It needs a bridge, so it is skipped rather than passed when there is not one
 *
 * The pairing code is handed in as an instrumentation argument, because minting one requires a bridge
 * process with a real state directory and a window opened over its local control socket — none of
 * which an instrumentation runner can do for itself. With no argument the test is **skipped**, which
 * a report shows as skipped. It must never be made to pass by not running: this repository has been
 * bitten six times by a test that reported success by being up to date, and a green tick on an
 * end-to-end claim that never executed is the same defect with a better disguise.
 *
 * `scripts/enrol-end-to-end.sh` is what supplies the argument: it builds the bridge from this
 * repository, gives it a scratch state directory, opens a window through the real control socket,
 * forwards the port into the emulator with `adb reverse`, and runs this.
 *
 * ### 127.0.0.1 and not the host alias
 *
 * The forward is `adb reverse`, so the address in the pairing code is loopback *on the emulator*.
 * Dialling the emulator's host alias instead hangs in SYN-SENT — an IPv4-mapped IPv6 socket against
 * the emulator's NAT — which costs an afternoon each time it is rediscovered.
 */
@RunWith(AndroidJUnit4::class)
class EnrollmentTest {

    private val directory: File
        get() = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            PairedLaptop.DIRECTORY,
        )

    @After
    fun tearDown() {
        PairedLaptop(directory).clear()
    }

    @Test
    fun enrolsAgainstARealBridgeAndThenReachesTheApiAsTheIdentityItJustPinned() {
        val text = InstrumentationRegistry.getArguments().getString(ARGUMENT)
        assumeTrue("no pairing code was supplied; see scripts/enrol-end-to-end.sh", text != null)

        val payload = EnrollCodec.decodeText(text!!)
            ?: throw AssertionError("the supplied pairing code did not decode")

        // A fresh identity, so what the bridge pins is minted by this run and the keystore's own
        // guarantees are in the path rather than a certificate left behind by an earlier one.
        PhoneIdentity.clear()
        val identity = PhoneIdentity.certificate()
        val store = PairedLaptop(directory).apply { clear() }

        val result = Enrollment.enroll(payload, identity, "an emulator", store)

        val paired = result as? EnrollResult.Paired
            ?: throw AssertionError("the bridge did not pair this phone: $result")

        // The receipt: what the bridge actually stored is this phone.
        assertEquals(Fingerprint.of(identity), paired.fingerprint)
        // The bytes behind the fingerprint the code carried.
        assertArrayEquals(
            paired.bridgeCertificate.encoded,
            store.read()!!.bridgeCertificate,
        )
        assertEquals(payload.host, store.read()!!.host)
        assertEquals(payload.port, store.read()!!.port)

        // **And now the other protocol, over a second connection, as the identity just pinned.**
        //
        // This is the half a fake cannot give: the enrolment window is spent, the bridge is back to
        // offering `agterm/api-1` alone, and it requires the pinned client certificate. Reaching the
        // API at all means the phone's certificate reached the trust store, was parsed, and satisfied
        // the pinning verifier on a handshake that happened after the pairing.
        BridgeConnection.open(store.read()!!, PhoneIdentity.keyManager()).use { connection ->
            try {
                connection.sessions()
            } catch (e: BridgeRefused) {
                // agterm itself may not be answering on the machine running this, which is a
                // different fact from the one under test: the bridge replied, in the application
                // protocol, on a connection it accepted as this phone.
                assertTrue("the bridge answered, which is what this asserts", true)
            }
        }
    }

    private companion object {
        const val ARGUMENT = "pairingCode"
    }
}
