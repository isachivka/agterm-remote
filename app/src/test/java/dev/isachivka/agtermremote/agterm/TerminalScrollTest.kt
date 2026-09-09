package dev.isachivka.agtermremote.agterm

import dev.isachivka.agtermremote.agterm.TerminalScroll.Scroll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The autoscroll policy, decided without a screen.
 *
 * The owner opened a session and landed at the top of the buffer, when what they came to read was the
 * newest output at the bottom. The fix is two rules, and the second one is the whole difference between
 * a feature and an annoyance:
 *
 *  - follow while they are at the bottom, because staying there is a state they chose;
 *  - **never yank the view while they are reading**, because dragging someone back down every two
 *    seconds is worse than not following at all.
 *
 * Held here rather than by care, and by unit tests rather than by an instrumented one: this is
 * arithmetic on two integers, and a rule that only fails on a device is a rule nobody re-checks.
 */
class TerminalScrollTest {

    /** A screenful of terminal, in pixels. Any plausible numbers - the policy is scale-free. */
    private val max = 2000

    @Test
    fun `at the bottom means at the end, within a hair`() {
        assertTrue(TerminalScroll.isAtBottom(offset = max, max = max))
        // A fling settles a pixel or two short, and a layout pass leaves fractional remainders.
        // Requiring equality would silently stop following for someone who is, to their eye, at the
        // bottom.
        assertTrue(TerminalScroll.isAtBottom(offset = max - TerminalScroll.BOTTOM_TOLERANCE_PX, max = max))
        assertFalse(TerminalScroll.isAtBottom(offset = max - TerminalScroll.BOTTOM_TOLERANCE_PX - 1, max = max))
    }

    /** The tolerance must not be able to swallow a line of text. Line height is 22sp. */
    @Test
    fun `the tolerance is smaller than a line`() {
        assertTrue(
            "a tolerance this large would treat a whole unread line as 'at the bottom'",
            TerminalScroll.BOTTOM_TOLERANCE_PX < 22,
        )
    }

    @Test
    fun `opening a session shows the bottom`() {
        // Unconditional, and it must stay so: the scroll state is one object shared by every session,
        // so anything conditional here opens the next session at the previous one's offset.
        assertEquals(Scroll.ToBottom, TerminalScroll.onSessionOpened())
    }

    @Test
    fun `new output keeps the newest line in view when they are following`() {
        val decision = TerminalScroll.onContentChanged(wasAtBottom = true, offset = max, newMax = max + 500)
        assertEquals(Scroll.ToBottom, decision)
    }

    /**
     * **The rule this feature lives or dies on.**
     *
     * They have scrolled up, which means they are reading. The poll replaces the whole screen every
     * couple of seconds, and each replacement must leave them exactly where they are.
     */
    @Test
    fun `new output does not move a reader who has scrolled up`() {
        val decision = TerminalScroll.onContentChanged(wasAtBottom = false, offset = 200, newMax = max + 500)
        assertEquals("a poll dragged the owner away from what they were reading", Scroll.None, decision)
    }

    /** And it must not move them on poll after poll after poll — the annoyance is cumulative. */
    @Test
    fun `a reader is left alone across many polls`() {
        var offset = 200
        repeat(30) { poll ->
            val decision = TerminalScroll.onContentChanged(
                wasAtBottom = TerminalScroll.isAtBottom(offset, max + poll * 40),
                offset = offset,
                newMax = max + poll * 40,
            )
            assertEquals("poll $poll moved a reader", Scroll.None, decision)
            offset = 200
        }
    }

    /**
     * Following resumes when they return to the bottom themselves — the same gesture that stopped it.
     * There is no separate "re-enable" anywhere, and there must not be: a mode the owner has to
     * remember they are in is a mode that surprises them.
     */
    @Test
    fun `following resumes when they scroll back down`() {
        assertEquals(Scroll.None, TerminalScroll.onContentChanged(wasAtBottom = false, offset = 200, newMax = max))
        // They scroll to the end themselves. The very next poll follows again.
        assertEquals(Scroll.ToBottom, TerminalScroll.onContentChanged(wasAtBottom = true, offset = max, newMax = max))
    }

    /**
     * **The shrink case.** A terminal that clears, or whose program redraws fewer lines, leaves the
     * offset past the end of the new content — the view looks at nothing, and blank space below the
     * last line is indistinguishable from empty output.
     *
     * Not a yank: what they were reading no longer exists.
     */
    @Test
    fun `a screen that got shorter than the offset comes back into view`() {
        val decision = TerminalScroll.onContentChanged(wasAtBottom = false, offset = 1800, newMax = 300)
        assertEquals(Scroll.ToBottom, decision)
    }

    /** But a shrink that still contains their position leaves them where they are. */
    @Test
    fun `a shrink that still holds their position does not move them`() {
        val decision = TerminalScroll.onContentChanged(wasAtBottom = false, offset = 200, newMax = 900)
        assertEquals(Scroll.None, decision)
    }

    // --- The keyboard -----------------------------------------------------------------------------

    /**
     * The owner opens the keyboard to type at the prompt at the bottom, and opening it is what hides
     * that prompt: the terminal really shrinks (adjustResize plus safeDrawingPadding), so the content
     * is unchanged while the maximum offset grows.
     */
    @Test
    fun `the keyboard puts a follower back at the bottom`() {
        assertEquals(Scroll.ToBottom, TerminalScroll.onBottomChromeAppeared(wasAtBottom = true))
    }

    /** And the same rule in the other direction: they may be opening the keyboard while reading back. */
    @Test
    fun `the keyboard does not move someone who had scrolled up`() {
        assertEquals(
            "opening a keyboard yanked a reader to the bottom",
            Scroll.None,
            TerminalScroll.onBottomChromeAppeared(wasAtBottom = false),
        )
    }

    /**
     * **The remembered value must survive the animation it exists to outlive.**
     *
     * Once the keyboard begins to take space the viewport is already shrinking, so anything measured
     * from then on describes the animation rather than where the owner was. One frame of updating past
     * that boundary overwrites the only record of the answer.
     */
    @Test
    fun `the memory freezes the moment the keyboard starts taking space`() {
        // They were at the bottom. The keyboard begins to appear, and the maximum starts growing -
        // which makes them look, frame by frame, like someone who had scrolled up.
        var remembered = true
        for (max in listOf(2100, 2300, 2600, 2900)) {
            remembered = TerminalScroll.rememberedAtBottom(
                previous = remembered, transitionInFlight = true, offset = 2000, max = max,
            )
        }
        assertTrue("the animation overwrote the value that describes the moment before it", remembered)
    }

    /**
     * **And it is a memory, not a latch.** While nothing is settling it follows them wherever they go -
     * including while the input bar is open and standing still, which is the case that produced the
     * yank: a scroll made with the bar up is the owner deciding where to look. A value stuck at true would yank a reader to the bottom on the next keyboard open - the
     * annoyance already ruled out, arriving by the back door.
     */
    @Test
    fun `the memory follows them up while nothing is settling`() {
        var remembered = true
        // They scroll up, keyboard closed.
        remembered = TerminalScroll.rememberedAtBottom(remembered, transitionInFlight = false, offset = 200, max = 2000)
        assertFalse("a stale true survived them scrolling away", remembered)

        // And back down again: following resumes with no separate re-enable.
        remembered = TerminalScroll.rememberedAtBottom(remembered, transitionInFlight = false, offset = 2000, max = 2000)
        assertTrue(remembered)
    }

    /** The settle bound is a ceiling on an observation, so it must outlast a real IME animation. */
    @Test
    fun `the settle bound outlasts a keyboard animation`() {
        // ~250ms at 60fps is 15 frames; at 120fps, 30. The bound is a backstop, not a target.
        assertTrue(TerminalScroll.SETTLE_FRAME_LIMIT >= 30)
    }

    /**
     * **The same rule for our own controls, which is how the owner hit it the second time.** Pressing
     * type brings the input bar up, it takes space at the bottom, and the prompt they were about to
     * type at goes under it. Nothing about the policy changes - only what was taking the space.
     */
    @Test
    fun `our own controls follow the same rule as the keyboard`() {
        assertEquals(Scroll.ToBottom, TerminalScroll.onBottomChromeAppeared(wasAtBottom = true))
        assertEquals(Scroll.None, TerminalScroll.onBottomChromeAppeared(wasAtBottom = false))

        // And the memory freezes for our controls exactly as it does for the IME: the flag is one
        // boolean, so there is no second path that could disagree with the first.
        var remembered = true
        for (max in listOf(2100, 2400, 2700)) {
            remembered = TerminalScroll.rememberedAtBottom(
                previous = remembered, transitionInFlight = true, offset = 2000, max = max,
            )
        }
        assertTrue("our own controls overwrote the memory the keyboard could not", remembered)
    }

    /** Settling must survive two things arriving in sequence, so one repeat is not enough. */
    @Test
    fun `settling waits for more than a single repeat`() {
        assertTrue(
            "a maximum measured between the controls and the keyboard is about to change again",
            TerminalScroll.SETTLE_STABLE_FRAMES >= 3,
        )
    }
}
