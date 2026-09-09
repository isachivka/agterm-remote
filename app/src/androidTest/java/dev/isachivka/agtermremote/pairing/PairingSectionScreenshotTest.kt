package dev.isachivka.agtermremote.pairing

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.isachivka.agtermremote.ui.theme.AppTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Every pairing state, as an image.
 *
 * The section is stateless and takes [PairingState] as a value precisely so this is possible without
 * a camera, a laptop or a paired phone. The state that matters is Comparing: two rows of hex the
 * owner has to check against another screen, which is a legibility decision and legibility is settled
 * by looking.
 */
@RunWith(AndroidJUnit4::class)
class PairingSectionScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private val certificate = ByteArray(385) { ((it * 13 + 7) % 256).toByte() }
    private val fingerprint = Fingerprint.rows(Fingerprint.of(certificate))
    private val profile = ConnectionProfile(StreamKind.DirectTcp, "laptop.example", 51820, certificate)

    /** Deliberately different from the laptop's, so a screen showing one twice is visible. */
    private val phoneFingerprint = listOf("1111 2222 3333 4444", "5555 6666 7777 8888")

    private fun capture(name: String, state: PairingState) {
        compose.setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PairingSection(
                        state = state,
                        camera = CameraAccess.Ready, onScan = {}, onChoose = {}, onConfirm = {}, onReject = {}, onUnpair = {},
                        onSendCertificate = {}, onHandoverDone = {}, onReplaceIdentity = {},
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "screenshots",
        ).apply { mkdirs() }
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("no screenshot written to ${file.absolutePath}", file.length() > 0)
    }

    @Test fun unpaired() = capture("pairing-1-unpaired", PairingState.NotPaired)

    /** The screen the owner would have needed on 2026-07-29 and did not have. */
    @Test fun identityCannotSign() =
        capture("pairing-6-identity-cannot-sign", PairingState.IdentityCannotSign(phoneFingerprint))
    @Test fun notACode() = capture("pairing-2-not-a-code", PairingState.NotAPairingCode)
    @Test fun comparing() = capture("pairing-3-comparing", PairingState.Comparing(profile, fingerprint))

    /**
     * The comparison screen renders [PairingAddress]'s exact output, not a string of its own.
     *
     * The bridge face will show the same address beside the QR, and nothing at runtime checks that
     * the two agree — so a hand-rolled string here would diverge silently and the owner would compare
     * two renderings of one address, correctly, and get the wrong answer. This fails if anyone
     * reformats it at the call site.
     *
     * **NOT RUN as of 2026-08-09.** There is no device: the owner's phone is not ours to touch and no
     * emulator is authorised. It is written now so that it runs the moment one is, and its absence is
     * recorded as a verification hole rather than papered over.
     */
    @Test
    fun comparingShowsTheAddressItIsAboutToTrust() {
        compose.setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PairingSection(
                        state = PairingState.Comparing(profile, fingerprint),
                        camera = CameraAccess.Ready, onScan = {}, onChoose = {}, onConfirm = {}, onReject = {}, onUnpair = {},
                        onSendCertificate = {}, onHandoverDone = {}, onReplaceIdentity = {},
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        compose.onNodeWithTag(TAG_ADDRESS).assertTextEquals(PairingAddress.of(profile))
    }
    @Test fun paired() =
        capture("pairing-4-paired", PairingState.Paired(profile, fingerprint, phoneFingerprint))

    // The two states that separate holding a profile from being able to use it. Both are reachable
    // here without a laptop, a camera or a screen lock, which is the point of keeping PairingSection
    // stateless.
    @Test fun halfDone() = capture(
        "pairing-5-half-done",
        PairingState.PhoneHalfOutstanding(profile, fingerprint, phoneFingerprint),
    )

    @Test fun noScreenLock() = capture("pairing-6-no-screen-lock", PairingState.NoScreenLock)
}
