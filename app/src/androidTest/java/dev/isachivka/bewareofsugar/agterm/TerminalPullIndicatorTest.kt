package dev.isachivka.bewareofsugar.agterm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.isachivka.bewareofsugar.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **The checkmark must not move the label. That is the whole of this file.**
 *
 * The owner asked for two things at once and they pull against each other: a mark before the text
 * (*"перед текстом добавим иконку галочки"*) and the text staying where it is (*"он у тебя сейчас
 * центрован, вот это надо сохранить"*).
 *
 * The obvious implementation satisfies the first and quietly breaks the second — reserve a slot for
 * the icon and toggle its visibility, and nothing jumps, but the label sits half an icon right of
 * centre from then on, for ever. That is a defect nobody notices in review because the diff looks
 * correct and the screenshot looks fine.
 *
 * So the property is asserted against **measured bounds in one composition**, toggling only `armed`.
 * Not two runs, not two screenshots: the same node, before and after, to the pixel.
 *
 * ### It has never executed
 *
 * `ci.yml` starts no emulator, so this compiles on every push and runs nowhere — which is exactly the
 * gap this test is written into rather than out of. What was checked instead is structural and is in
 * `PullIndicator`: the layout reports `layout(text.width, text.height)`, a size that does not mention
 * the tick, and the tick is placed at a negative x outside those bounds. One line carries the
 * guarantee, and this is the executable form of it for the day an emulator exists.
 */
@RunWith(AndroidJUnit4::class)
class TerminalPullIndicatorTest {

    @get:Rule
    val compose = createComposeRule()

    private val tag = "pull_indicator_under_test"

    @Test
    fun the_checkmark_does_not_move_the_label_by_a_pixel() {
        var armed by mutableStateOf(false)
        compose.setContent {
            AppTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    PullIndicator(
                        label = "PgUp",
                        armed = armed,
                        alpha = 1f,
                        modifier = Modifier.align(Alignment.TopCenter).testTag(tag),
                    )
                }
            }
        }

        val withoutTick = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

        armed = true
        compose.waitForIdle()
        val withTick = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

        // All four edges, and they are DpRect's own members - `width`/`height` are extension
        // properties needing an import, and four edges say the same thing with nothing to import.
        assertEquals("the tick moved the label's left edge", withoutTick.left, withTick.left)
        assertEquals("the tick moved the label's top edge", withoutTick.top, withTick.top)
        assertEquals("the tick moved the label's right edge", withoutTick.right, withTick.right)
        assertEquals("the tick moved the label's bottom edge", withoutTick.bottom, withTick.bottom)
    }

    /**
     * And back again, because a layout that is stable on the way in can still settle differently on the
     * way out — the same asymmetry that made the alpha bug show only when he pulled back below the
     * threshold.
     */
    @Test
    fun removing_the_checkmark_puts_nothing_back_where_it_was_not() {
        var armed by mutableStateOf(true)
        compose.setContent {
            AppTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    PullIndicator(
                        label = "PgDn",
                        armed = armed,
                        alpha = 1f,
                        modifier = Modifier.align(Alignment.BottomCenter).testTag(tag),
                    )
                }
            }
        }

        val withTick = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

        armed = false
        compose.waitForIdle()
        val withoutTick = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

        assertEquals(withTick.left, withoutTick.left)
        assertEquals(withTick.right, withoutTick.right)
    }
}
