package dev.isachivka.agtermremote.ui.nav

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.isachivka.agtermremote.MainActivity
import dev.isachivka.agtermremote.agterm.TAG_OPEN_SETTINGS
import dev.isachivka.agtermremote.pairing.PairedLaptop
import dev.isachivka.agtermremote.pairing.TAG_FAILURE_REASON
import dev.isachivka.agtermremote.pairing.TAG_PAIR
import dev.isachivka.agtermremote.pairing.TAG_PASTE_FIELD
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **A phone that has just paired is on the terminal, without being restarted.**
 *
 * The first person to pair on real hardware did it twice and both times sat on the settings screen
 * afterwards, with sessions appearing only after the app was killed. The unit tests around pairing
 * are green because none of them is the app: this one is, from the first frame to the terminal's own
 * top bar, against a bridge built from this tree. It needs a pairing code as an instrumentation
 * argument, minted by whatever started that bridge; with none it is skipped and says so.
 *
 * Run it through `scripts/paired-navigation-end-to-end.sh`, which clears the app's data first so the
 * app opens on the pairing screen, grants the camera so no system dialog covers the field, and
 * forwards the bridge's port into the emulator.
 */
@RunWith(AndroidJUnit4::class)
class PairedNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun pairingLandsOnTheTerminalWithoutARestart() {
        val code = InstrumentationRegistry.getArguments().getString("pairingCode")
        assumeTrue("no pairing code; see scripts/paired-navigation-end-to-end.sh", code != null)

        // Unpaired at launch, so the app opened on the pairing screen with no terminal beneath it -
        // the case that had nowhere to go.
        compose.onNodeWithText("Settings").assertIsDisplayed()
        // Both sit below the viewfinder in a scrolling column; a click at their coordinates without
        // scrolling lands on whatever is on screen there instead, and the test then waits on nothing.
        compose.onNodeWithTag(TAG_PASTE_FIELD).performScrollTo().performTextInput(code!!)
        compose.onNodeWithTag(TAG_PAIR).performScrollTo().performClick()

        // The terminal's own control, which no other screen carries. "Terminal" as a word is also a
        // section heading on the settings screen, so it proves nothing.
        // Either the terminal, or the pairing screen's own verdict - and the verdict is what the
        // failure message carries, so a red run says what the phone said rather than "timed out".
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodesWithTag(TAG_OPEN_SETTINGS).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag(TAG_FAILURE_REASON).fetchSemanticsNodes().isNotEmpty()
        }
        val refused = compose.onAllNodesWithTag(TAG_FAILURE_REASON).fetchSemanticsNodes()
        if (refused.isNotEmpty()) {
            val said = refused.first().config.getOrNull(SemanticsProperties.Text)
                ?.joinToString { it.text }
            throw AssertionError("the phone did not pair: $said")
        }
        compose.onNodeWithTag(TAG_OPEN_SETTINGS).assertIsDisplayed()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull("the terminal is up but nothing is stored", PairedLaptop(context).read())
    }
}
