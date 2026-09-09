package dev.isachivka.bewareofsugar.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.isachivka.bewareofsugar.BuildConfig
import dev.isachivka.bewareofsugar.MainActivity
import dev.isachivka.bewareofsugar.ui.module.ModuleRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The launcher, on a real device, launched the way the owner launches it.
 *
 * Replaces `GreetingUiTest`. One of its two tests comes with it unchanged in substance: the version
 * has to be legible on the phone itself, because REQ-0002's acceptance ends with the owner opening
 * the app and seeing that it is now the new version, and they have no laptop to check with. On the
 * launcher that lives in the Updates tile's subtitle. The other test asserted "Hello world", which
 * no longer exists.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theLauncherOpensOnEveryModule() {
        ModuleRegistry.all.forEach { module ->
            compose.onNodeWithTag(moduleTileTag(module.id)).assertIsDisplayed()
        }
    }

    /**
     * Carried over from `GreetingUiTest`. Matched as a substring against `BuildConfig` so it cannot
     * drift from the build it is testing.
     */
    @Test
    fun theInstalledVersionIsLegibleWithoutALaptop() {
        compose
            .onNodeWithText(BuildConfig.VERSION_NAME, substring = true)
            .assertIsDisplayed()
    }

    /**
     * **The subtitle stopped counting on 2026-07-31, and the tag kept a narrowed subject.**
     *
     * It asserted `2 of 10 modules live`, which was worth saying while eight of the ten tiles were
     * illustrative shapes. With only real modules left that ratio's halves are necessarily equal, so
     * the line says `homelab` and this asserts that instead — REQ-0012 Decision 3.
     *
     * The tag is kept rather than deleted with the count, because the line is still there and still
     * worth knowing is displayed. A tag that quietly disappears takes its assertion with it and
     * nothing goes red.
     */
    @Test
    fun theSubtitleNamesTheApp() {
        compose.onNodeWithTag(TAG_HOME_SUBTITLE).assertIsDisplayed()
        compose.onNodeWithText("homelab", substring = true).assertIsDisplayed()
    }

    /**
     * REQ-0003, still true after the restyle: the updater is quiet. With no token stored there is
     * nothing to say, and nothing is said - no line, no dialog, nothing to dismiss.
     */
    @Test
    fun thereIsNoUpdateLineWithNothingToReport() {
        compose.onNodeWithTag(TAG_UPDATE_BANNER).assertDoesNotExist()
    }

    /**
     * **Every tile opens a screen, and comes back.**
     *
     * Three tests lived here about the placeholder: that tapping a module said it was not built, that
     * both ways out of that dead end worked, and that each tile led to its own module rather than to
     * whichever one the grid happened to key on. The first two went with the placeholder on
     * 2026-07-31; the third's intent survives it and is what this is.
     *
     * It walks the registry rather than naming ids, so a module added without a screen fails here as
     * well as in `ModuleRegistryTest` — `Screen.screenFor` returns null for one, and the launcher's
     * tap handler does nothing, which would leave the subtitle on screen and fail the first assert.
     */
    @Test
    fun everyTileOpensAScreenAndComesBack() {
        ModuleRegistry.all.forEach { module ->
            compose.onNodeWithTag(moduleTileTag(module.id)).performClick()

            // Somewhere else: whatever that module's screen is, it is not the launcher.
            compose.onNodeWithTag(TAG_HOME_SUBTITLE).assertDoesNotExist()

            Espresso.pressBack()
            compose.onNodeWithTag(TAG_HOME_SUBTITLE).assertIsDisplayed()
        }
    }
}
