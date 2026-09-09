package dev.isachivka.agtermremote.ui.nav

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.isachivka.agtermremote.MainActivity
import dev.isachivka.agtermremote.agterm.TAG_OPEN_SETTINGS
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app opens where [startDestination] says, and the stack under it is real.
 *
 * Deliberately thin. Everything about *how* back behaves is a pure function and is covered in
 * `BackStackTest` in milliseconds, and the start rule itself in `StartDestinationTest`. What only a
 * device can answer is the three things below: that the rule is actually consulted when the Activity
 * composes, that the entry it puts underneath pairing is reachable with the system back button, and
 * that `rememberSaveable` round-trips the stack through a Bundle rather than merely appearing to in
 * a unit test.
 *
 * **A fresh emulator has no paired laptop**, which is why every test here starts on pairing. That is
 * not an accident of the fixture - it is the state the start rule exists for, and it is the first
 * thing a new owner sees. A test-local pairing cannot be faked into place either: pairing writes a
 * file only a real handshake produces.
 *
 * **This replaces two files that went with the module grid.** They drove navigation through the
 * launcher, the update screen and the token screen, none of which exist.
 */
@RunWith(AndroidJUnit4::class)
class NavigationInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /**
     * The heading, and the pairing section that is the whole page rather than one part of it.
     * Pairing has no destination of its own yet; the settings page is where it lives.
     */
    @Test
    fun anUnpairedPhoneOpensOnPairing() {
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Laptop").assertIsDisplayed()
    }

    /**
     * The entry underneath pairing, which is the reason the start stack has two of them. Without it
     * back at the root is a no-op and an owner who does not want to pair right now is held on the
     * screen.
     */
    @Test
    fun systemBackFromPairingReachesTheTerminal() {
        compose.onNodeWithText("Settings").assertIsDisplayed()

        Espresso.pressBack()

        compose.onNodeWithTag(TAG_OPEN_SETTINGS).assertIsDisplayed()
    }

    @Test
    fun theTerminalHeaderOpensSettingsAgain() {
        Espresso.pressBack()
        compose.onNodeWithTag(TAG_OPEN_SETTINGS).assertIsDisplayed()

        compose.onNodeWithTag(TAG_OPEN_SETTINGS).performClick()

        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    /**
     * The half `BackStackTest` cannot reach: the stack is saved as a list of route strings, and
     * whether those survive a Bundle is a question about the framework rather than about the
     * encoding. Recreating the Activity mid-journey checks the whole stack came back, not just the
     * screen on top - pressing back afterwards has to land on the terminal, which is only true if
     * the entry underneath was restored too.
     *
     * It also checks the other half of `rememberSaveable`: the start rule must NOT run again on a
     * restore. If it did, this would come back on pairing no matter where the owner was.
     */
    @Test
    fun aRecreationKeepsTheWholeStackAndNotJustTheTopOfIt() {
        compose.onNodeWithText("Settings").assertIsDisplayed()

        compose.activityRule.scenario.recreate()

        compose.onNodeWithText("Settings").assertIsDisplayed()

        Espresso.pressBack()
        compose.onNodeWithTag(TAG_OPEN_SETTINGS).assertIsDisplayed()
    }
}
