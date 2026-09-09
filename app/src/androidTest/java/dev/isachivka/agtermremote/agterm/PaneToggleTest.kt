package dev.isachivka.agtermremote.agterm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.isachivka.agtermremote.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pane toggle.
 *
 * ### What this file used to assert, and why the assertion turned around
 *
 * It asserted that a session with no second pane has **no toggle at all**, on the absent-not-disabled
 * ruling. The owner then specified that tapping right should CREATE the pane when there is none.
 *
 * Absent-not-disabled is untouched — it is a rule about controls that can do nothing. This control can
 * always do something on any session, so the condition it was gated on stopped existing. The tests
 * that proved its absence now prove its presence, and they are the same tests pointed the other way.
 *
 * ### What only a composition can answer
 *
 * `PaneTest` owns the wire words and the toggle's arithmetic, and runs on every pull request. What
 * needs a screen is that the control is composed at all, and that pressing it asks — a claim about a
 * node existing and a lambda firing.
 *
 * ### It has never executed
 *
 * `ci.yml` starts no emulator, so this compiles on every push and runs nowhere. **It cannot say
 * whether the lit half reads as "you are here"** — a judgement only the owner's eye can make.
 */
@RunWith(AndroidJUnit4::class)
class PaneToggleTest {

    @get:Rule
    val compose = createComposeRule()

    private fun session(splitPane: Boolean) = BridgeSession(
        id = "11111111-1111-4111-8111-111111111111",
        workspaceId = "w",
        workspace = "ws",
        name = "build",
        title = "",
        active = true,
        splitPane = splitPane,
    )

    /** The screen's full argument list, so a test can vary the one thing it is about. */
    @Composable
    private fun Screen(splitPane: Boolean, shown: Pane = Pane.Left, onTogglePane: () -> Unit = {}) {
        val one = session(splitPane)
        AgtermScreen(
            state = AgtermUiState.Sessions(listOf(one), watching = one, screen = "$ ls\nfile"),
            onRefresh = {}, onOpen = {}, onCloseSession = {},
            onFitToPhone = { _, _, _ -> }, onRestoreWindow = {},
            fit = FitState(enabled = null, columns = 0),
            typing = TypingState.Closed,
            onOpenTyping = {}, onCloseTyping = {}, onDraftChange = {}, onSendText = {}, onSendKey = {},
            onPickFile = {}, onDisconnect = {}, onPair = {}, onOpenSettings = {},
            paneShown = shown,
            onTogglePane = onTogglePane,
        )
    }

    private fun screen(splitPane: Boolean, onTogglePane: () -> Unit = {}, shown: Pane = Pane.Left) {
        compose.setContent { AppTheme { Screen(splitPane, shown, onTogglePane) } }
    }

    /**
     * **THE REVERSAL, and the test the old build fails.** A session with no second pane still gets the
     * control, because tapping it is how a second pane comes into being.
     */
    @Test
    fun a_session_with_no_second_pane_still_has_the_toggle() {
        screen(splitPane = false)

        compose.onNodeWithTag(TAG_PANE_TOGGLE).assertIsDisplayed()
    }

    /** And a session that already has one, which was never in doubt. */
    @Test
    fun a_session_with_a_second_pane_has_the_toggle() {
        screen(splitPane = true)

        compose.onNodeWithTag(TAG_PANE_TOGGLE).assertIsDisplayed()
    }

    /**
     * Pressing it ASKS, and the request carries no pane.
     *
     * **This is the shape change, not a rename.** The callback used to be `(Pane) -> Unit` and the
     * control decided which pane it was moving to — which made the press an instruction the screen had
     * already carried out. Going right may have to create a pane first and may fail, so the press is a
     * request now and [paneShown] moves only when the laptop has confirmed. A control that named its
     * own destination here would light before the pane existed.
     */
    @Test
    fun pressing_it_asks_without_naming_a_pane() {
        var asked = 0

        screen(splitPane = false, shown = Pane.Left, onTogglePane = { asked++ })
        compose.onNodeWithTag(TAG_PANE_TOGGLE).performClick()

        assertEquals("a press on the left must ask once", 1, asked)
    }

    /** And from the other side, because a toggle that only works one way is a bug this project shipped once. */
    @Test
    fun pressing_it_while_on_the_right_also_asks() {
        var asked = 0

        screen(splitPane = true, shown = Pane.Right, onTogglePane = { asked++ })
        compose.onNodeWithTag(TAG_PANE_TOGGLE).assertIsOn()
        compose.onNodeWithTag(TAG_PANE_TOGGLE).performClick()

        assertEquals(1, asked)
    }

    /**
     * **The control does not move itself.** Pressing it while the state stays put leaves the icon
     * where it was — the property the whole failure path rests on.
     *
     * `IconToggleButton` is a controlled component here: `checked` comes from [paneShown] and the
     * press does not set it. If somebody reintroduced local state to "feel responsive", this fails —
     * and the owner would see the right half lit over a pane the phone is not reading.
     */
    @Test
    fun a_press_that_the_laptop_does_not_confirm_leaves_the_icon_alone() {
        var asked = 0
        // `shown` is fixed at Left, standing in for a laptop that never confirmed.
        screen(splitPane = false, shown = Pane.Left, onTogglePane = { asked++ })

        compose.onNodeWithTag(TAG_PANE_TOGGLE).performClick()
        compose.waitForIdle()

        assertEquals(1, asked)
        // Off is the left pane - see PaneToggle, where `checked` means the right one.
        compose.onNodeWithTag(TAG_PANE_TOGGLE).assertIsOff()
    }

    /**
     * The session changing underneath does not take the control away any more, in the same
     * composition. It used to: a split closing on the Mac removed it.
     */
    @Test
    fun the_toggle_survives_the_second_pane_going_away() {
        var splitPane by mutableStateOf(true)
        compose.setContent { AppTheme { Screen(splitPane) } }
        compose.onNodeWithTag(TAG_PANE_TOGGLE).assertIsDisplayed()

        splitPane = false
        compose.waitForIdle()

        compose.onNodeWithTag(TAG_PANE_TOGGLE).assertIsDisplayed()
    }
}
