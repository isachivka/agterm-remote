package dev.isachivka.bewareofsugar.agterm

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.isachivka.bewareofsugar.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The overpull as a gesture, REQ-0029 — the half `TerminalPullTest` cannot reach.
 *
 * ### What is asserted here and not there
 *
 * `TerminalPullTest` owns every decision the gesture makes; it needs no screen and it runs on every
 * pull request. What needs a composition is the wiring: that a terminal with somewhere to scroll
 * consumes the drag and the indicator never appears, and that a terminal at its limit passes the
 * leftover through to the key.
 *
 * ### It has never run
 *
 * `ci.yml` starts no emulator, so this compiles on every push and executes nowhere. **Above all it
 * cannot tell anyone how the gesture FEELS** — whether 56dp of travel is a reach or a twitch, whether
 * the icon is noticed before the threshold, whether an overpull fires while the owner meant to scroll.
 * Those are the questions the release actually turns on, and the only instrument for them is a thumb.
 */
@RunWith(AndroidJUnit4::class)
class TerminalPullGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<String>()

    /** Long enough that the vertical scroller has somewhere to go. */
    private val tall = (1..400).joinToString("\n") { "line $it" }

    /** Short enough that the view is at the top limit and the bottom limit at once. */
    private val short = "one line"

    private fun terminal(text: String) {
        compose.setContent {
            AppTheme {
                TerminalBox(
                    text = text,
                    modifier = Modifier.fillMaxSize(),
                    onPageKey = { sent += it },
                )
            }
        }
    }

    /**
     * **Ordinary reading must never page the laptop.** The scroller consumes the whole drag, so
     * nothing reaches the overpull and the indicator is never composed at all.
     */
    @Test
    fun scrolling_a_long_terminal_sends_no_key() {
        terminal(tall)

        compose.onNodeWithTag(TAG_TERMINAL_TEXT).performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertEquals("a plain scroll paged the owner's laptop", emptyList<String>(), sent)
        compose.onNodeWithTag(TAG_TERMINAL_PULL).assertDoesNotExist()
    }

    /**
     * And the case that is ordinary rather than exceptional: a Claude screen that fits, where both
     * limits are hit at once and only the DIRECTION of travel can name a key. Finger up is PgUp.
     */
    @Test
    fun overpulling_a_short_terminal_upward_sends_page_up() {
        terminal(short)

        compose.onNodeWithTag(TAG_TERMINAL_TEXT).performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertEquals(listOf(TerminalPull.PAGE_UP), sent)
    }
}
