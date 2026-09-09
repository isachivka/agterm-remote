package dev.isachivka.bewareofsugar.ui.module

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.isachivka.bewareofsugar.MainActivity
import dev.isachivka.bewareofsugar.ui.home.TAG_OPEN_SETTINGS
import dev.isachivka.bewareofsugar.ui.home.moduleTileTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The settings button opens a screen that exists, and the launcher shows the whole registry.
 *
 * ### What this file used to be
 *
 * `ModulesSettingsInstrumentedTest`, five tests about turning a module off: that the switch reached
 * the store, that the launcher and its subtitle followed, that the choice survived the Activity being
 * thrown away, and that the footer named the way back. Four of those went with the feature on
 * 2026-07-31 — they proved hiding works, and hiding is gone.
 *
 * **The fifth did not, and that is why this file still exists.** `theSettingsButtonOpensAScreenThatExists`
 * is about wiring, not about hiding: a header button whose route resolves to nothing is a defect this
 * app has shipped before. Deleting the file wholesale would have taken that coverage with it, quietly,
 * and nothing would have gone red.
 *
 * The `@After` that put every module back is gone with the store it wrote to. Nothing here mutates
 * anything now, so there is no shared preferences file for one run to leave dirty for the next.
 */
@RunWith(AndroidJUnit4::class)
class SettingsInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theSettingsButtonOpensAScreenThatExists() {
        compose.onNodeWithTag(TAG_OPEN_SETTINGS).performClick()

        // The heading, and the pairing section that is now the whole page rather than one part of it.
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Laptop").assertIsDisplayed()
    }

    /**
     * **Every module in the registry is on the launcher, and there is no way to remove one.**
     *
     * The owner's request, in their own words: *"Все модули которые есть в приложении должны
     * отображаться на главной странице."* Asserted against the registry rather than against a number,
     * so adding a module cannot leave this passing while the launcher is short of one.
     */
    @Test
    fun theLauncherShowsTheWholeRegistry() {
        ModuleRegistry.all.forEach { entry ->
            compose.onNodeWithTag(moduleTileTag(entry.id)).assertExists()
        }
    }
}
