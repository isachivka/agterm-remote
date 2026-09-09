package dev.isachivka.bewareofsugar.ui.nav

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.isachivka.bewareofsugar.MainActivity
import dev.isachivka.bewareofsugar.ui.home.TAG_UPDATES_ENTRY
import dev.isachivka.bewareofsugar.update.TAG_CHECK_NOW
import dev.isachivka.bewareofsugar.update.TAG_FIELD
import dev.isachivka.bewareofsugar.update.TAG_OPEN_TOKEN
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That the back stack is actually wired to the screens, and that it survives the system throwing the
 * Activity away.
 *
 * Deliberately thin. Everything about *how* back behaves — going where you already are, returning to
 * a screen already behind you, never growing past the number of destinations — is a pure function
 * and is covered in `BackStackTest` in milliseconds. Duplicating that here would buy nothing and
 * cost thirty seconds a case. What only a device can answer is the two things below: that the
 * system's back button reaches the stack at all, and that `rememberSaveable` really round-trips it
 * through a Bundle rather than merely appearing to in a unit test.
 */
@RunWith(AndroidJUnit4::class)
class NavigationInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun systemBackFromUpdatesReturnsToTheLauncher() {
        compose.onNodeWithTag(TAG_UPDATES_ENTRY).performClick()
        compose.onNodeWithTag(TAG_CHECK_NOW).assertIsDisplayed()

        Espresso.pressBack()

        compose.onNodeWithTag(TAG_UPDATES_ENTRY).assertIsDisplayed()
    }

    /** The deepest journey the app currently has, unwound one screen at a time. */
    @Test
    fun systemBackUnwindsTheWholeJourney() {
        compose.onNodeWithTag(TAG_UPDATES_ENTRY).performClick()
        compose.onNodeWithTag(TAG_OPEN_TOKEN).performClick()
        compose.onNodeWithTag(TAG_FIELD).assertIsDisplayed()

        Espresso.pressBack()
        compose.onNodeWithTag(TAG_CHECK_NOW).assertIsDisplayed()

        Espresso.pressBack()
        compose.onNodeWithTag(TAG_UPDATES_ENTRY).assertIsDisplayed()
    }

    /**
     * The half `BackStackTest` cannot reach: the stack is saved as a list of route strings, and
     * whether those actually survive a Bundle is a question about the framework rather than about
     * the encoding. Rotating in the middle of the journey checks the whole stack came back, not
     * just the screen on top — pressing back afterwards has to land on Updates, which is only true
     * if the entry underneath was restored too.
     */
    @Test
    fun aRotationKeepsTheWholeStackAndNotJustTheTopOfIt() {
        compose.onNodeWithTag(TAG_UPDATES_ENTRY).performClick()
        compose.onNodeWithTag(TAG_OPEN_TOKEN).performClick()
        compose.onNodeWithTag(TAG_FIELD).assertIsDisplayed()

        compose.activityRule.scenario.recreate()

        compose.onNodeWithTag(TAG_FIELD).assertIsDisplayed()

        Espresso.pressBack()
        compose.onNodeWithTag(TAG_CHECK_NOW).assertIsDisplayed()

        Espresso.pressBack()
        compose.onNodeWithTag(TAG_UPDATES_ENTRY).assertIsDisplayed()
    }
}
