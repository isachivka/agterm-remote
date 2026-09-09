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
 * The app opens on the terminal, settings is reachable from it, and back comes home.
 *
 * Deliberately thin. Everything about *how* back behaves is a pure function and is covered in
 * `BackStackTest` in milliseconds. What only a device can answer is the three things below: that the
 * app's root destination really is the terminal, that the header's control is wired to the settings
 * screen at all, and that `rememberSaveable` round-trips the stack through a Bundle rather than
 * merely appearing to in a unit test.
 *
 * **This replaces two files that went with the module grid.** They drove navigation through the
 * launcher, the update screen and the token screen, none of which exist. The coverage they were
 * actually buying - that a header button opens a destination that resolves, and that the system's
 * back button reaches the stack - is what is here.
 *
 * The terminal is not paired on a fresh emulator, so it opens on its failure card rather than a
 * session list. That is the state under test on purpose: it is the first thing a new owner sees, and
 * it is precisely the state in which reaching pairing matters.
 */
@RunWith(AndroidJUnit4::class)
class NavigationInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theAppOpensOnTheTerminalAndSettingsIsOneTapAway() {
        compose.onNodeWithTag(TAG_OPEN_SETTINGS).assertIsDisplayed()

        compose.onNodeWithTag(TAG_OPEN_SETTINGS).performClick()

        // The heading, and the pairing section that is the whole page rather than one part of it.
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Laptop").assertIsDisplayed()
    }

    @Test
    fun systemBackFromSettingsReturnsToTheTerminal() {
        compose.onNodeWithTag(TAG_OPEN_SETTINGS).performClick()
        compose.onNodeWithText("Settings").assertIsDisplayed()

        Espresso.pressBack()

        compose.onNodeWithTag(TAG_OPEN_SETTINGS).assertIsDisplayed()
    }

    /**
     * The half `BackStackTest` cannot reach: the stack is saved as a list of route strings, and
     * whether those survive a Bundle is a question about the framework rather than about the
     * encoding. Recreating the Activity mid-journey checks the whole stack came back, not just the
     * screen on top - pressing back afterwards has to land on the terminal, which is only true if
     * the entry underneath was restored too.
     */
    @Test
    fun aRecreationKeepsTheWholeStackAndNotJustTheTopOfIt() {
        compose.onNodeWithTag(TAG_OPEN_SETTINGS).performClick()
        compose.onNodeWithText("Settings").assertIsDisplayed()

        compose.activityRule.scenario.recreate()

        compose.onNodeWithText("Settings").assertIsDisplayed()

        Espresso.pressBack()
        compose.onNodeWithTag(TAG_OPEN_SETTINGS).assertIsDisplayed()
    }
}
