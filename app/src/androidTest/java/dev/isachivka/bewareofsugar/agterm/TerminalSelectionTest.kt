package dev.isachivka.bewareofsugar.agterm

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.isachivka.bewareofsugar.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Selecting text in the terminal, and — the part worth testing — **still being able to scroll it.**
 *
 * ### What this is for
 *
 * The owner asked to copy text out of a session. The whole change is a `SelectionContainer` around
 * the terminal's `Text`, so Android's own selection takes over: their handles, their magnifier, their
 * menu, their Copy. Nothing of ours. REQ-0026.
 *
 * The question that had to be answered before building it was whether selection would take a gesture
 * the terminal needs, because the terminal scrolls on drag in **both** axes and selection also drags.
 * Read out of `foundation` itself, the touch-selection path waits on
 * `awaitLongPressOrCancellation` before it drags — so a plain drag is still a scroll. **These tests
 * are that claim, executable.**
 *
 * ### What they cannot see, and it is most of the feature
 *
 * A thumb on glass. Whether the handles are reachable with a fat finger on a monospace grid, whether
 * the magnifier helps, whether the system's copy confirmation appears — none of that is assertable
 * here, and this suite does not run in CI at all (`ci.yml` compiles instrumentation and runs no
 * emulator). It exists so the contract is written down and so it runs the day an emulator does.
 */
@RunWith(AndroidJUnit4::class)
class TerminalSelectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val screen = (1..80).joinToString("\n") { "line $it of output that is quite wide indeed" }

    private lateinit var vertical: ScrollState

    private fun content() {
        compose.setContent {
            vertical = androidx.compose.foundation.rememberScrollState()
            AppTheme {
                TerminalBox(
                    text = screen,
                    onMeasured = {},
                    modifier = Modifier.fillMaxSize(),
                    vertical = vertical,
                )
            }
        }
    }

    /**
     * **The gesture that must survive.** A plain drag is the terminal's own scroll, and selection must
     * not have taken it — a person reading output scrolls a hundred times for every time they copy.
     */
    @Test
    fun a_plain_drag_still_scrolls_the_terminal() {
        content()
        assertEquals("the test starts at the top or it proves nothing", 0, vertical.value)

        compose.onNodeWithTag(TAG_TERMINAL_TEXT).performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertTrue("a plain drag no longer scrolls: selection took it", vertical.value > 0)
    }

    /**
     * And the other half: a long press is NOT a scroll. If a long press moved the view, the gesture
     * that starts a selection would fight the thing the owner is reading.
     */
    @Test
    fun a_long_press_does_not_scroll_the_terminal() {
        content()

        compose.onNodeWithTag(TAG_TERMINAL_TEXT).performTouchInput { longClick() }
        compose.waitForIdle()

        assertEquals("a long press moved the terminal", 0, vertical.value)
    }
}
