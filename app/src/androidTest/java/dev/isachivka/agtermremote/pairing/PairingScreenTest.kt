package dev.isachivka.agtermremote.pairing

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.isachivka.agtermremote.ui.theme.AppTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The pairing screen: five states, two ways in, and one path out of both.
 *
 * ### Why the golden vector, and how it gets onto the device
 *
 * The text typed into the paste field is a real pairing code from
 * `wire/enroll-payload-vectors.json` - the same bytes the Go encoder is held to produce. A code
 * invented here would prove the screen can move a string around; this proves it moves a string the
 * other half of the project actually mints.
 *
 * The repository is not on the phone, so `app/build.gradle.kts` copies the vector files into this
 * test's Java resources at build time, exactly as `wire/README.md` prescribes for a test on a device.
 * **The copy is produced by the build on every run and is not tracked**, so it cannot drift and
 * cannot be edited by hand - which is the whole reason a `cp` into `src/` is forbidden.
 *
 * ### What the second test is really asserting
 *
 * Not that a pasted code works. That the pasted code and the scanned code **are the same path**: the
 * screen hands the viewfinder the identical callback the Pair button uses, so there is nowhere for a
 * second decoder, a second trim, or a second set of rules to live. The predecessor of this screen had
 * exactly that - one decoder for the file route and, in effect, another for the lens - and the one
 * facing the lens was the one that was wrong.
 */
@RunWith(AndroidJUnit4::class)
class PairingScreenTest {

    @get:Rule val compose = createComposeRule()

    /**
     * The longest version 2 code in the shared vectors. Longest because capacity is what a real symbol
     * runs out of, and because a paste field that mishandles length does so at the end.
     */
    private fun goldenVectorText(): String {
        val stream = javaClass.getResourceAsStream("/$VECTORS")
            ?: error("$VECTORS is not in the test's resources; see the copyWireVectors task")
        val array = JSONArray(stream.bufferedReader().use { it.readText() })
        return (0 until array.length())
            .map { array.getJSONObject(it) }
            .filter { it.getInt("version") == EnrollCodec.VERSION }
            .map { it.getString("text") }
            .maxBy { it.length }
    }

    /**
     * A stand-in for the camera, wired to **the callback the screen would hand the real viewfinder**.
     *
     * There is no camera in a Compose test rule and there does not need to be one: what this file can
     * prove is that the two routes converge, and that they converge is a fact about the screen. The
     * camera itself is proven by running it - see `docs/pairing.md`.
     */
    @Composable
    private fun FakeViewfinder(onText: (String) -> Unit) {
        TextButton(onClick = { onText(goldenVectorText()) }, modifier = Modifier.testTag(FAKE_SCAN)) {
            Text("scan")
        }
    }

    private fun show(flow: PairingFlow) {
        compose.setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val scope = rememberCoroutineScope()
                    PairingSection(
                        state = flow.state,
                        onCode = { text -> scope.launch { flow.onCode(text) } },
                        onRetry = flow::retry,
                        modifier = Modifier.padding(16.dp),
                        viewfinder = { onText -> FakeViewfinder(onText) },
                    )
                }
            }
        }
    }

    /** A flow that never finishes enrolling, so [PairingUi.Working] can be observed. */
    private fun blockedFlow(recorded: MutableList<EnrollPayload>): PairingFlow {
        val forever = CompletableDeferred<EnrollResult>()
        return PairingFlow { payload ->
            recorded += payload
            forever.await()
        }
    }

    private fun pastePairAndWaitToFail(flow: PairingFlow) {
        compose.onNodeWithTag(TAG_PASTE_FIELD).performTextInput(goldenVectorText())
        compose.onNodeWithText(PAIR).performClick()
        compose.waitUntil { flow.state is PairingUi.Failed }
    }

    // --- The three behaviours -------------------------------------------------------------------

    /**
     * A refused camera is not a dead end. The paste field is the route the spec requires precisely
     * because the payload is base64 text - it can be sent over any channel the owner already has.
     */
    @Test
    fun showsThePasteFieldWhenTheCameraIsDenied() {
        val flow = PairingFlow { EnrollResult.Refused("unused") }.apply { cameraUnavailable() }
        show(flow)

        compose.onNodeWithText(PASTE_INSTEAD).assertIsDisplayed()
        compose.onNodeWithTag(TAG_PASTE_FIELD).assertIsDisplayed()
    }

    /**
     * **The same landing point, from both routes.** The payload the scanner produces and the payload
     * the paste field produces are compared, and the screen is what routed both.
     */
    @Test
    fun aPastedCodeEnrolsTheSameWayAScannedOneDoes() {
        val recorded = mutableListOf<EnrollPayload>()
        val flow = blockedFlow(recorded)
        show(flow)

        compose.onNodeWithTag(TAG_PASTE_FIELD).performTextInput(goldenVectorText())
        compose.onNodeWithText(PAIR).performClick()
        compose.waitUntil { flow.state is PairingUi.Working }

        // Back to the scanner, and in through the lens instead.
        flow.retry()
        compose.waitUntil { flow.state is PairingUi.Scanning }
        compose.onNodeWithTag(FAKE_SCAN).performClick()
        compose.waitUntil { flow.state is PairingUi.Working }

        assertEquals("both routes must reach the enrolment", 2, recorded.size)
        assertEquals("a pasted code must enrol as the scanned one does", recorded[0], recorded[1])
    }

    /** A failure that only reports is the defect; every one of them names the next step. */
    @Test
    fun aFailureSaysWhatToDoNext() {
        val reason = "Your Mac did not answer. Check it is awake, then scan the code again."
        val flow = PairingFlow { EnrollResult.Refused(reason) }
        show(flow)

        pastePairAndWaitToFail(flow)

        compose.onNodeWithText(reason).assertIsDisplayed()
        compose.onNodeWithText(TRY_AGAIN).assertIsDisplayed()
    }

    // --- What happens after the failure ---------------------------------------------------------

    /** Try again puts the owner back where they can act, rather than on a dead screen. */
    @Test
    fun tryAgainReturnsToTheScanner() {
        val flow = PairingFlow { EnrollResult.Refused("no.") }
        show(flow)
        pastePairAndWaitToFail(flow)

        compose.onNodeWithText(TRY_AGAIN).performClick()

        compose.waitUntil { flow.state is PairingUi.Scanning }
    }

    /** A refused camera stays refused: Try again must not offer a viewfinder there is none of. */
    @Test
    fun tryAgainAfterARefusedCameraReturnsToThePasteField() {
        val flow = PairingFlow { EnrollResult.Refused("no.") }.apply { cameraUnavailable() }
        show(flow)
        pastePairAndWaitToFail(flow)

        compose.onNodeWithText(TRY_AGAIN).performClick()

        compose.waitUntil { flow.state is PairingUi.NeedsCamera }
        compose.onNodeWithTag(TAG_PASTE_FIELD).assertIsDisplayed()
    }

    /**
     * **Rubbish in the paste field is a sentence, not a crash and not silence.** The field is the one
     * place in this application a person can put arbitrary text, and nothing may reach the enrolment
     * from it that the codec has not accepted.
     */
    @Test
    fun textThatIsNotACodeNeverReachesTheEnrolment() {
        val flow = PairingFlow { error("a string the codec refused reached the enrolment") }
        show(flow)

        compose.onNodeWithTag(TAG_PASTE_FIELD).performTextInput("not a pairing code at all")
        compose.onNodeWithText(PAIR).performClick()
        compose.waitUntil { flow.state is PairingUi.Failed }

        assertTrue(
            "a refusal must carry a sentence",
            (flow.state as PairingUi.Failed).reason.isNotBlank(),
        )
        compose.onNodeWithText(TRY_AGAIN).assertIsDisplayed()
    }

    // --- Every state, as an image ---------------------------------------------------------------

    @Test fun scanningIsAnImage() = capture("pairing-1-scanning", PairingUi.Scanning)

    @Test fun needsCameraIsAnImage() = capture("pairing-2-needs-camera", PairingUi.NeedsCamera)

    @Test fun workingIsAnImage() = capture("pairing-3-working", PairingUi.Working)

    @Test fun failedIsAnImage() = capture("pairing-4-failed", PairingUi.Failed(Enrollment.REFUSED))

    /** The fingerprint is the one thing on this screen a person reads character by character. */
    @Test
    fun pairedIsAnImage() = capture(
        "pairing-5-paired",
        PairingUi.Paired(
            "1111 2222 3333 4444 5555 6666 7777 8888 9999 AAAA BBBB CCCC DDDD EEEE FFFF 0000",
        ),
    )

    private fun capture(name: String, state: PairingUi) {
        compose.setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PairingSection(
                        state = state,
                        onCode = {},
                        onRetry = {},
                        modifier = Modifier.padding(16.dp),
                        viewfinder = { Box(modifier = Modifier.testTag(TAG_VIEWFINDER)) },
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

    private companion object {
        const val VECTORS = "enroll-payload-vectors.json"
        const val FAKE_SCAN = "fake-scan"

        // The labels a person reads, spelled here rather than resolved from resources: what this
        // asserts is that the screen SAYS them, and reading them out of the same file the screen
        // reads them from would assert nothing at all.
        const val PAIR = "Pair"
        const val TRY_AGAIN = "Try again"
        const val PASTE_INSTEAD = "Paste the code instead"
    }
}
