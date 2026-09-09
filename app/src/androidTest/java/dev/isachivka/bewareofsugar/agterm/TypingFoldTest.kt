package dev.isachivka.bewareofsugar.agterm

import androidx.compose.ui.test.assertIsDisplayed
// No import for assertDoesNotExist: it is a MEMBER of SemanticsNodeInteraction, not an extension in
// this package, so importing it is an unresolved reference rather than a redundant line. Importing it
// is what failed compileDebugAndroidTestKotlin here on 2026-08-21; HomeScreenUiTest calls it with no
// import at all, which is the working shape.
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.isachivka.bewareofsugar.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The ellipsis, and the two claims REQ-0028 rests on that a JVM test cannot make.
 *
 * ### What this is for
 *
 * `KeyRowsTest` asserts the *lists* — seven on the bar, eight behind the fold, nothing deleted. It
 * cannot assert that the folded keys are actually off the screen, or that pressing one of them still
 * reaches `onSendKey` with the name the bridge expects. That is composition, and it needs a device.
 *
 * ### What it cannot see, and it is the part the owner will judge
 *
 * **How much taller the terminal got.** The whole feature is 104dp given back to `TerminalBox`, and
 * that number is arithmetic over a row height rather than something asserted here — this suite runs
 * no measurement of the bar against a real screen, and `ci.yml` runs no emulator, so **these tests
 * have never executed anywhere.** They exist so the contract is written down and so they run the day
 * an emulator does.
 */
@RunWith(AndroidJUnit4::class)
class TypingFoldTest {

    @get:Rule
    val compose = createComposeRule()

    private val pressed = mutableListOf<String>()

    private fun bar() {
        compose.setContent {
            AppTheme {
                TypingBar(
                    state = TypingState.Composing(),
                    onDraftChange = {},
                    onSendText = {},
                    onSendKey = { pressed += it },
                    onPickFile = {},
                )
            }
        }
    }

    /**
     * **Folded is the state the bar opens in**, because that is the state the height claim is about.
     * A bar that remembered itself open would give the terminal its 104dp back only sometimes.
     */
    @Test
    fun the_folded_keys_are_not_on_screen_until_the_ellipsis_is_pressed() {
        bar()

        compose.onNodeWithTag("${TAG_TYPING_KEY}escape").assertIsDisplayed()
        compose.onNodeWithTag(TAG_TYPING_FOLD).assertIsDisplayed()
        compose.onNodeWithTag("${TAG_TYPING_KEY}up").assertDoesNotExist()

        compose.onNodeWithTag(TAG_TYPING_FOLD).performClick()

        compose.onNodeWithTag("${TAG_TYPING_KEY}up").assertIsDisplayed()
        compose.onNodeWithTag("${TAG_TYPING_KEY}escape").assertIsDisplayed()
    }

    /**
     * And back again — a fold that only opens is a row that grew permanently on the second day.
     */
    @Test
    fun pressing_the_ellipsis_again_puts_them_away() {
        bar()

        compose.onNodeWithTag(TAG_TYPING_FOLD).performClick()
        compose.onNodeWithTag("${TAG_TYPING_KEY}linestart").assertIsDisplayed()

        compose.onNodeWithTag(TAG_TYPING_FOLD).performClick()
        compose.onNodeWithTag("${TAG_TYPING_KEY}linestart").assertDoesNotExist()
    }

    /**
     * **A folded key still sends its own name.** The fold is a layout decision; if it changed what
     * reaches the bridge it would be a deletion wearing a fold's clothes, and `keys.Key` would refuse
     * whatever came instead with the reason four layers away from the phone.
     */
    @Test
    fun a_key_from_under_the_fold_sends_the_name_the_bridge_knows() {
        bar()

        compose.onNodeWithTag(TAG_TYPING_FOLD).performClick()
        compose.onNodeWithTag("${TAG_TYPING_KEY}up").performClick()

        assertEquals(listOf("up"), pressed)
    }
}
