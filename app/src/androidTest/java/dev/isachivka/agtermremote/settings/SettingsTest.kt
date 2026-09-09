package dev.isachivka.agtermremote.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.isachivka.agtermremote.pairing.ConnectionProfile
import dev.isachivka.agtermremote.pairing.Fingerprint
import dev.isachivka.agtermremote.pairing.PairedLaptop
import dev.isachivka.agtermremote.pairing.PhoneIdentity
import dev.isachivka.agtermremote.pairing.SigningState
import dev.isachivka.agtermremote.pairing.StreamKind
import dev.isachivka.agtermremote.ui.settings.LaptopSection
import dev.isachivka.agtermremote.ui.settings.PhoneKeySection
import dev.isachivka.agtermremote.ui.settings.SettingsScreen
import dev.isachivka.agtermremote.ui.settings.TAG_ADDRESS
import dev.isachivka.agtermremote.ui.settings.TAG_ADDRESS_SAVE
import dev.isachivka.agtermremote.ui.settings.TAG_CANCEL
import dev.isachivka.agtermremote.ui.settings.TAG_FINGERPRINT
import dev.isachivka.agtermremote.ui.settings.TAG_REPLACE_KEY
import dev.isachivka.agtermremote.ui.settings.TAG_UNPAIR
import dev.isachivka.agtermremote.ui.theme.AppTheme
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The settings screen, driven as a person drives it.
 *
 * ### Why this is instrumented and not a JVM test of `LaptopSettings`
 *
 * `LaptopSettingsTest` already covers every rule without a device, and it would keep passing if the
 * Save button were wired to nothing. **This is the wiring test**: the address travels from a real text
 * field through a real button into a real file, and unpairing reaches the real hardware keystore —
 * which is the half that cannot be faked, because `PhoneIdentity` has no seam and deliberately none:
 * a fake in its place would make *the key is gone* a statement about the fake.
 */
@RunWith(AndroidJUnit4::class)
class SettingsTest {

    @get:Rule val compose = createComposeRule()

    private val dir: File
        get() = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            PairedLaptop.DIRECTORY,
        )

    /**
     * Not a real certificate, and it does not need to be: nothing on this screen parses it. What is
     * asserted is that the bytes do not move, and arbitrary bytes prove that better than a
     * well-formed certificate would — a codec that quietly re-encoded would be invisible against one.
     */
    private val laptopCertificate = ByteArray(385) { ((it * 17 + 5) % 256).toByte() }

    @Before
    fun clean() = wipe()

    @After
    fun tearDown() = wipe()

    private fun wipe() {
        PairedLaptop(dir).clear()
        PhoneIdentity.clear()
    }

    /**
     * A paired phone: a laptop in the store, and **a real key in the hardware keystore.**
     *
     * The laptop half is a stored profile rather than an enrolment against a bridge — that run is
     * `AddressChangeTest`, which needs a process this runner cannot start. The phone half is the real
     * thing, because `assertNull(PhoneIdentity.existing())` below is only worth asserting about a key
     * the keystore actually minted.
     */
    private fun pairWithFake(host: String = "laptop.example", port: Int = 8443) {
        PhoneIdentity.certificate()
        PairedLaptop(dir).write(
            ConnectionProfile(StreamKind.DirectTcp, host, port, laptopCertificate),
        )
    }

    /**
     * @param laptopSection false leaves the Laptop section off the screen entirely.
     *
     * Only the key-replacement test passes false, and the reason is worth stating: an unpaired phone
     * draws the scanner, the scanner asks for the camera on arrival, and **a system permission dialog
     * over the test's own window takes the composition out of the tree** — the run then fails with
     * *no compose hierarchies found*, which says nothing about the section under test. Pairing the
     * fixture would hide it too, but a phone whose key will not sign is precisely a phone the gate
     * refused to pair, so that fixture would be a state this app cannot be in.
     */
    private fun openSettings(
        key: SigningState = SigningState.Ready,
        onDiscard: () -> Unit = PhoneIdentity::clear,
        laptopSection: Boolean = true,
    ) {
        val store = PairedLaptop(dir)
        val settings = LaptopSettings(store, onDiscard, { key })
        compose.setContent {
            AppTheme {
                SettingsScreen(
                    onBack = {},
                    pairingSection = if (laptopSection) {
                        ({ LaptopSection(settings = settings, store = store) })
                    } else {
                        null
                    },
                    phoneSection = phoneSection(settings),
                )
            }
        }
    }

    private fun phoneSection(settings: LaptopSettings): (@Composable () -> Unit)? =
        if (settings.keyIsUnusable) ({ PhoneKeySection(settings = settings) }) else null

    private fun setAddress(text: String) {
        compose.onNodeWithTag(TAG_ADDRESS).performTextClearance()
        compose.onNodeWithTag(TAG_ADDRESS).performTextInput(text)
        compose.onNodeWithTag(TAG_ADDRESS_SAVE).performClick()
        compose.waitForIdle()
    }

    // --- The two behaviours the brief names --------------------------------------------------------

    /**
     * **Changing the address keeps the pinned laptop, byte for byte.**
     *
     * Spec §5.5. The pinned identity is independent of where that laptop happens to answer: an owner
     * whose address changed must not have to re-pair, and must certainly not have their trust reset
     * by editing a text field.
     */
    @Test
    fun editingTheAddressKeepsThePinnedLaptop() {
        pairWithFake()
        val before = PairedLaptop(dir).read()!!.bridgeCertificate

        openSettings()
        setAddress("example.test:9443")

        val after = PairedLaptop(dir).read()!!
        assertEquals("example.test", after.host)
        assertEquals(9443, after.port)
        assertArrayEquals(before, after.bridgeCertificate)
    }

    /**
     * **Unpairing clears both halves**, and it takes one confirmation to get there.
     *
     * The second press is the confirmation, and it is only reachable because the first one replaced
     * the button with it — the two are never on screen together, which is what stops a double tap
     * from being an unpairing.
     */
    @Test
    fun unpairClearsBothTheLaptopAndThePhoneKey() {
        pairWithFake()
        assertNotNull("the fixture did not mint a key", PhoneIdentity.existing())

        openSettings()
        compose.onNodeWithText("Unpair").performClick()
        compose.onNodeWithText("Unpair").performClick() // the confirmation
        compose.waitForIdle()

        assertNull(PairedLaptop(dir).read())
        assertNull(PhoneIdentity.existing())
    }

    // --- What holds the two of them up -------------------------------------------------------------

    /** The receipt: the fingerprint of what this phone pinned, in the grouping the Mac prints. */
    @Test
    fun theFingerprintOfThePinnedLaptopIsShownInFull() {
        pairWithFake()
        openSettings()

        val rows = Fingerprint.rows(Fingerprint.of(laptopCertificate))
        assertEquals("two rows of eight, matching bridgecert", 2, rows.size)
        rows.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithTag(TAG_FINGERPRINT).assertIsDisplayed()
    }

    /**
     * **One press destroys nothing**, and the trigger is gone while the question is on screen. If a
     * dialog floated over the button instead, both would be in the hierarchy at once and this
     * assertion could not be written at all.
     */
    @Test
    fun theConfirmationCanBeBackedOutOfWithNothingLost() {
        pairWithFake()
        openSettings()

        compose.onNodeWithText("Unpair").performClick()
        compose.onNodeWithTag(TAG_UNPAIR).assertDoesNotExist()
        compose.onNodeWithTag(TAG_CANCEL).performClick()
        compose.waitForIdle()

        assertNotNull(PairedLaptop(dir).read())
        assertNotNull(PhoneIdentity.existing())
        compose.onNodeWithTag(TAG_UNPAIR).assertIsDisplayed()
    }

    /**
     * **The replacement for a key that will not sign is not on a healthy phone's screen at all.**
     *
     * Not disabled, not hidden behind a scroll: the section is not rendered and its heading is not
     * drawn. A destructive control that is always present is one somebody eventually presses.
     */
    @Test
    fun aHealthyPhoneIsNeverOfferedTheKeyReplacement() {
        pairWithFake()
        openSettings(key = SigningState.Ready)

        compose.onNodeWithTag(TAG_REPLACE_KEY).assertDoesNotExist()
        compose.onNodeWithText("This phone").assertDoesNotExist()
    }

    /**
     * And a phone whose key will never sign gets it — behind the same one confirmation, and with the
     * cost written out.
     *
     * The key state is injected because the emulator's keystore will not mint an unusable key on
     * demand: the state that produces it is a key minted by a build from before `DIGEST_NONE` was in
     * the spec. What is real here is the deletion, which goes to the actual keystore.
     */
    @Test
    fun aKeyThatWillNotSignCanBeReplacedFromThisScreen() {
        PhoneIdentity.certificate()
        openSettings(key = SigningState.Unusable, laptopSection = false)

        compose.onNodeWithText("This phone").assertIsDisplayed()
        compose.onNodeWithTag(TAG_REPLACE_KEY).performClick()
        compose.onNodeWithText(
            LaptopSettings.Destruction.ReplaceKey.warning,
        ).assertIsDisplayed()
        compose.onNodeWithText("Replace the key").performClick()
        compose.waitForIdle()

        assertNull("the key the owner asked to replace is still there", PhoneIdentity.existing())
    }

    /**
     * **The camera route out, proven to lead somewhere.**
     *
     * A permanently refused camera used to leave the owner with a sentence and nothing to press. The
     * copy is one half of the fix; the other half is that the intent behind the button actually
     * resolves on the device — a button that opens nothing is the same defect wearing a fix's
     * clothes.
     */
    @Test
    fun theCameraSettingsRouteResolvesToARealScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))

        assertTrue(
            "nothing on this device answers the app's own settings screen",
            intent.resolveActivity(context.packageManager) != null,
        )
    }
}
