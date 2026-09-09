package dev.isachivka.agtermremote.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.isachivka.agtermremote.agterm.BridgeConnection
import dev.isachivka.agtermremote.agterm.BridgeRefused
import dev.isachivka.agtermremote.pairing.EnrollCodec
import dev.isachivka.agtermremote.pairing.EnrollResult
import dev.isachivka.agtermremote.pairing.Enrollment
import dev.isachivka.agtermremote.pairing.PairedLaptop
import dev.isachivka.agtermremote.pairing.PairingAddress
import dev.isachivka.agtermremote.pairing.PhoneIdentity
import dev.isachivka.agtermremote.pairing.SigningState
import dev.isachivka.agtermremote.ui.settings.LaptopSection
import dev.isachivka.agtermremote.ui.settings.SettingsScreen
import dev.isachivka.agtermremote.ui.settings.TAG_ADDRESS
import dev.isachivka.agtermremote.ui.settings.TAG_ADDRESS_SAVE
import dev.isachivka.agtermremote.ui.theme.AppTheme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **The property the settings screen exists for, proven against a bridge that actually moved.**
 *
 * A unit test can pass this while the product fails, and that is not a hypothetical: everything about
 * the address change is a file write, and a file write says nothing about whether the pinned
 * certificate still satisfies a handshake at the new address. So this pairs against a real bridge,
 * the bridge is moved to a different port, the address is changed **through the real settings screen**,
 * and the API is reached again — with no re-pairing anywhere in it.
 *
 * ### Two phases, two runs of the instrumentation, one app install
 *
 * The bridge cannot be moved from inside a test: it is a process on the host. So the two halves are
 * separate `@Test` methods, selected one at a time by `scripts/address-change-end-to-end.sh`, which
 * moves the bridge in between. **The app is installed once and never reinstalled**, which is what
 * makes this honest: the pairing under test is the one phase one left on the device, in the app's own
 * files and in the hardware keystore, exactly as an owner's would be overnight.
 *
 * ### Skipped, never passed, when there is no bridge
 *
 * Both phases are `assumeTrue` on their instrumentation argument, and a skip shows as a skip. This
 * repository has been bitten repeatedly by a test that reported success by not executing; a green
 * tick on an end-to-end claim that never ran is that defect with a better disguise.
 */
@RunWith(AndroidJUnit4::class)
class AddressChangeTest {

    @get:Rule val compose = createComposeRule()

    private val dir: File
        get() = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            PairedLaptop.DIRECTORY,
        )

    /**
     * **Phase one: pair, at the address the code carries.**
     *
     * Deliberately leaves everything behind — the stored laptop and the keystore key — because that is
     * the input to phase two. Nothing is cleared on the way out.
     */
    @Test
    fun pairsAgainstTheBridgeAtTheAddressOnTheCode() {
        val text = InstrumentationRegistry.getArguments().getString(CODE)
        assumeTrue("no pairing code; see scripts/address-change-end-to-end.sh", text != null)

        val payload = EnrollCodec.decodeText(text!!)
            ?: throw AssertionError("the supplied pairing code did not decode")

        // A fresh identity, so what the bridge pins is minted by this run.
        PhoneIdentity.clear()
        val identity = PhoneIdentity.certificate()
        val store = PairedLaptop(dir).apply { clear() }

        val result = Enrollment.enroll(payload, identity, "an emulator", store)
        assertTrue("the bridge did not pair this phone: $result", result is EnrollResult.Paired)
        assertEquals(SigningState.Ready, PhoneIdentity.signingState())

        val stored = store.read()!!
        assertEquals(payload.host, stored.host)
        assertEquals(payload.port, stored.port)
        reachTheApi("the address it paired at")
    }

    /**
     * **Phase two: the bridge has moved, and the phone follows it without re-pairing.**
     *
     * The address is typed into the real text field on the real screen and stored by the real Save
     * button. What is asserted afterwards is the whole requirement, in three parts:
     *
     *  1. the pinned certificate is unchanged, byte for byte;
     *  2. this phone's own key is the same key — nothing minted a new identity;
     *  3. the API answers at the new address, over a handshake that both of those had to satisfy.
     *
     * The third is the one no unit test can make. A stored profile that pairs nowhere looks identical
     * to one that pairs perfectly until something opens a socket with it.
     */
    @Test
    fun changingTheAddressReachesTheMovedBridgeWithoutRePairing() {
        val moved = InstrumentationRegistry.getArguments().getString(MOVED)
        assumeTrue("no moved address; see scripts/address-change-end-to-end.sh", moved != null)

        val store = PairedLaptop(dir)
        val before = store.read()
        assertNotNull("phase one did not leave a pairing behind", before)
        val pinnedBefore = before!!.bridgeCertificate
        val phoneBefore = PhoneIdentity.existing()
        assertNotNull("phase one did not leave a key behind", phoneBefore)

        val settings = LaptopSettings(store, PhoneIdentity::clear, PhoneIdentity::signingState)
        compose.setContent {
            AppTheme {
                SettingsScreen(
                    onBack = {},
                    pairingSection = { LaptopSection(settings = settings, store = store) },
                )
            }
        }

        compose.onNodeWithTag(TAG_ADDRESS).performTextClearance()
        compose.onNodeWithTag(TAG_ADDRESS).performTextInput(moved!!)
        compose.onNodeWithTag(TAG_ADDRESS_SAVE).performClick()
        compose.waitForIdle()

        val after = store.read()!!
        assertEquals("the screen did not store the address", moved, PairingAddress.of(after))
        assertArrayEquals(
            "the pinned certificate moved, which is the one thing this must never do",
            pinnedBefore,
            after.bridgeCertificate,
        )
        assertArrayEquals(
            "this phone minted a new key, so the Mac would no longer know it",
            phoneBefore!!.encoded,
            PhoneIdentity.existing()!!.encoded,
        )

        reachTheApi("the address the owner typed")
    }

    /**
     * One connection, as the identity already pinned, to whatever the store now says.
     *
     * `BridgeRefused` is accepted: agterm itself may not be answering on the machine running this,
     * which is a different fact from the one under test. The bridge having replied **in the
     * application protocol** means the handshake completed — which is only possible if the pinned
     * certificate matched and this phone's key proved possession.
     */
    private fun reachTheApi(what: String) {
        BridgeConnection.open(PairedLaptop(dir).read()!!, PhoneIdentity.keyManager()).use { connection ->
            try {
                connection.sessions()
            } catch (refused: BridgeRefused) {
                assertTrue("the bridge answered at $what, which is what this asserts", true)
            }
        }
    }

    private companion object {
        const val CODE = "pairingCode"
        const val MOVED = "movedAddress"
    }
}
