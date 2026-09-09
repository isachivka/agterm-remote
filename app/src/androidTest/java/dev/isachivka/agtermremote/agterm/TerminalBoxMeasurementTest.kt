package dev.isachivka.agtermremote.agterm

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * What [TerminalBox] reports as its width, and what it refuses to report.
 *
 * ### The defect this holds shut
 *
 * On 2026-07-30 the owner pressed the fit control and their laptop window went to full screen width.
 * The bridge log was honest all the way through:
 *
 * ```
 * width: asked 159 columns from a 1706dp box at 10.7dp per character
 * width: calibrated, 159 columns now in effect
 * ```
 *
 * 159 × 10.7 = 1701dp. The phone had reported the width of the TEXT, because the measurement sat
 * below `horizontalScroll` in the modifier chain and observed the node the scroller measures with
 * unbounded width. Their phone is about 440dp. Each press made the window wider, which made the
 * content wider, which made the next press wider still — a loop that reported success every time.
 *
 * ### Why a test and not a careful line
 *
 * The obvious repair is to move the measurement above the scrollers. That fixes today and leaves the
 * next refactor free to move it back, with no failure until an owner presses a button a week later.
 * So the measurement is taken from a CONSTRAINT, and this file is the part that keeps it there:
 * [insideAScrollerItRefusesToGuess] puts the whole composable inside a `horizontalScroll` — the exact
 * mistake, applied from the outside where a modifier chain cannot defend against it — and asserts it
 * answers zero rather than a number.
 */
class TerminalBoxMeasurementTest {

    @get:Rule val compose = createComposeRule()

    /** Wide enough to be unmistakable, and far wider than any phone. */
    private val screen = (1..40).joinToString("\n") { "x".repeat(400) }

    @Test
    fun itReportsTheViewportAndNotTheContent() {
        var measured = -1.0
        compose.setContent {
            Box(modifier = Modifier.width(PARENT_DP.dp)) {
                TerminalBox(text = screen, onMeasured = { measured = it })
            }
        }
        compose.waitForIdle()

        // The parent less the padding on both sides, and nothing to do with the 400-character lines
        // it was handed. Before this change the same content produced roughly 400 × the character
        // width, which is how a 440dp phone came to ask for 159 columns.
        assertEquals(PARENT_DP.toDouble() - 2 * TERMINAL_PADDING, measured, 0.5)
    }

    @Test
    fun insideAScrollerItRefusesToGuess() {
        var measured = -1.0
        compose.setContent {
            // The defect, imposed from OUTSIDE the composable, where no modifier order inside it can
            // help. This is what a later refactor looks like.
            Box(modifier = Modifier.width(PARENT_DP.dp).horizontalScroll(rememberScrollState())) {
                TerminalBox(text = screen, onMeasured = { measured = it })
            }
        }
        compose.waitForIdle()

        // Zero, which `AgtermScreen` reads as unmeasured: the control goes dead with a reason on the
        // owner's screen. A wrong number here is a resize of their window that every log calls a
        // success, and that is the trade this assertion exists to make.
        assertEquals(0.0, measured, 0.0)
    }

    /**
     * **The redesign's two new surfaces must take nothing from the box.**
     *
     * design/v1 adds a line saying what the fit is doing and a card that floats over the terminal when
     * the connection drops and comes back. The measured width is half the bridge's fit cache key, so
     * either of them taking width would silently re-key every calibration the owner has — the exact
     * cost `FitToPhone` records for three earlier attempts, arriving through a layout change instead
     * of through arithmetic.
     *
     * The note is drawn ABOVE the box and the card is drawn OVER it, and this is what says so from
     * outside: the same parent, the same content, with an overlay present, must measure the same.
     */
    @Test
    fun anOverlayOverTheBoxTakesNoWidthFromIt() {
        var alone = -1.0
        var overlaid = -1.0
        compose.setContent {
            Box(modifier = Modifier.width(PARENT_DP.dp)) {
                TerminalBox(text = screen, onMeasured = { alone = it })
            }
            Box(modifier = Modifier.width(PARENT_DP.dp)) {
                TerminalBox(text = screen, onMeasured = { overlaid = it })
                // Stands in for LinkNoteCard: a full-width card in the same Box, over the terminal.
                Box(modifier = Modifier.width(PARENT_DP.dp))
            }
        }
        compose.waitForIdle()

        assertEquals(alone, overlaid, 0.0)
        assertEquals(PARENT_DP.toDouble() - 2 * TERMINAL_PADDING, overlaid, 0.5)
    }

    private companion object {
        const val PARENT_DP = 440
        /** Mirrors `TerminalBox`'s own horizontal inset; it is private there, and deliberately so. */
        const val TERMINAL_PADDING = 4.0
    }
}
