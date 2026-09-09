package dev.isachivka.bewareofsugar.update

import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.isachivka.bewareofsugar.MainActivity
import dev.isachivka.bewareofsugar.ui.home.TAG_UPDATES_ENTRY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The parts that need a real Activity: the secure-window flag, and what survives a rotation.
 */
@RunWith(AndroidJUnit4::class)
class TokenScreenActivityTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val typed = "not-a-real-token"

    /** Home -> Updates -> Token, which is where the token screen lives from iteration 2 on. */
    private fun openTokenScreen() {
        compose.onNodeWithTag(TAG_UPDATES_ENTRY).performClick()
        compose.onNodeWithTag(TAG_OPEN_TOKEN).performClick()
        compose.onNodeWithTag(TAG_FIELD).assertIsDisplayed()
    }

    private fun secureFlag(): Int = compose.activity.window.attributes.flags and
        WindowManager.LayoutParams.FLAG_SECURE

    @Test
    fun theTokenScreenIsNotTheFirstThingTheOwnerSees() {
        // REQ-0003 wants updates quiet and ignorable: the greeting is what opens, and the token
        // screen is somewhere you go.
        compose.onNodeWithTag(TAG_FIELD).assertDoesNotExist()
        compose.onNodeWithTag(TAG_UPDATES_ENTRY).assertIsDisplayed()
    }

    @Test
    fun theScreenIsExcludedFromScreenshotsAndRecents() {
        assertEquals("the greeting is ordinary", 0, secureFlag())

        openTokenScreen()
        assertNotEquals("FLAG_SECURE must be set while the token screen shows", 0, secureFlag())
    }

    @Test
    fun theFlagIsClearedOnTheWayOut() {
        openTokenScreen()
        assertNotEquals(0, secureFlag())

        // Back from the token screen lands on the update screen it was opened from.
        compose.onNodeWithTag(TAG_BACK).performClick()

        compose.onNodeWithTag(TAG_CHECK_NOW).assertIsDisplayed()
        assertEquals("and no longer than that", 0, secureFlag())
    }

    /**
     * Rotating must not bounce the owner back to the greeting, and must not lose what they were
     * halfway through pasting. The typed text is masked, so the observable proof that it survived is
     * that Save is still enabled — which only happens for non-blank input.
     */
    @Test
    fun aRotationKeepsTheScreenAndWhatWasTyped() {
        openTokenScreen()
        compose.onNodeWithTag(TAG_FIELD).performTextInput(typed)
        compose.onNodeWithTag(TAG_SAVE).assertIsEnabled()

        compose.activityRule.scenario.recreate()

        compose.onNodeWithTag(TAG_FIELD).assertIsDisplayed()
        compose.onNodeWithTag(TAG_SAVE).assertIsEnabled()
    }
}
