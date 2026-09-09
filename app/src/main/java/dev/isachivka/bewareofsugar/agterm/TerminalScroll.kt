package dev.isachivka.bewareofsugar.agterm

import androidx.compose.runtime.snapshotFlow

/**
 * Where the terminal should be looking, as a decision that can be tested without a screen.
 *
 * ### The owner's ask, and why it is not the special case they described
 *
 * *"if it is claude session i want to see bottom of terminal (autoscroll) after open session"*. They
 * named the case they hit; the rule is general. **There is no honest way to detect a claude session** -
 * nothing on the wire says so, and guessing from the text would be inventing a fact about their
 * machine. The bottom is where the newest output is in every terminal, so opening at the top is wrong
 * for all of them and this applies to all of them.
 *
 * ### The two rules that decide whether this is a feature or an annoyance
 *
 * **Follow while they are at the bottom.** Staying there is a state they chose by not scrolling away,
 * so new output keeps the newest line in view.
 *
 * **Never yank the view while they are reading.** If they have scrolled up they are reading something,
 * and dragging them back down on a two-second poll is the single behaviour that makes autoscroll worse
 * than no autoscroll. Following resumes when they come back to the bottom themselves - the same
 * gesture that stopped it.
 *
 * The decision is a pure function of two numbers and a fact, so it is unit-tested rather than argued
 * for in a comment. Compose's part is to supply the numbers and perform the result.
 */
object TerminalScroll {

    /**
     * How near the end still counts as "at the bottom", in pixels.
     *
     * **Not zero, and the reason is that the offset is rarely exactly the maximum.** A fling settles a
     * pixel or two short, and a layout pass can leave a fractional remainder; requiring equality would
     * silently stop following for someone who is, to their eye, at the bottom. Small enough that it
     * cannot swallow a line of text - the terminal's line height is 22sp, several times this.
     */
    const val BOTTOM_TOLERANCE_PX = 4

    /** Whether the view is at the end of the content, within [BOTTOM_TOLERANCE_PX]. */
    fun isAtBottom(offset: Int, max: Int): Boolean = offset >= max - BOTTOM_TOLERANCE_PX

    /**
     * What to do when the poll has replaced the screen text.
     *
     * [wasAtBottom] is sampled BEFORE the new content is laid out, because that is the question being
     * asked: were they following when this arrived? Reading it afterwards would ask whether the new,
     * longer content happens to leave them near its end, which is a different question with a
     * different answer on every poll.
     */
    fun onContentChanged(wasAtBottom: Boolean, offset: Int, newMax: Int): Scroll = when {
        // They were following. Keep the newest line in view.
        wasAtBottom -> Scroll.ToBottom
        // **The screen got SHORTER and their offset is now past the end.** A terminal that clears, or
        // whose program redraws fewer lines, leaves the view looking at nothing. This is not a yank:
        // the content they were reading is gone, and the alternative is blank space below the last
        // line with no way to tell it is not simply empty output.
        offset > newMax -> Scroll.ToBottom
        // They are reading. Leave them alone - this is the branch the whole feature lives or dies on.
        else -> Scroll.None
    }

    /**
     * What to do when a session is opened.
     *
     * Always the bottom, and **it is not conditional on where the last session was left.** The scroll
     * state is one object shared by every session (see `AgtermViewModel`), so without this the second
     * session opens at the first one's offset - a number that means nothing in a buffer it did not
     * come from.
     */
    fun onSessionOpened(): Scroll = Scroll.ToBottom

    /**
     * What to do when something has taken space at the bottom - the keyboard, or our own controls.
     *
     * The owner: *"when i focus to keyboard it overlap claude input. it means if terminal on phone
     * scrolled bottom and user open keyboard torether with opening we should scroll it again"*. They
     * open the keyboard **because** they want to type at the prompt at the bottom, and opening it is
     * what hides that prompt.
     *
     * **The trigger is generalised because the defect was.** The owner hit it twice: first with the
     * IME, then with our own typing bar - *"когда нажимаю type, снизу появляются наши контролы и
     * оверлапят часть Клода"*. Same viewport change, different thing taking the space, so it is the
     * same rule rather than a second piece of scrolling logic that can disagree with this one.
     *
     * The terminal really does shrink rather than being covered - `windowSoftInputMode` is
     * `adjustResize` and `MainActivity` pads the root with `safeDrawingPadding`, which includes the
     * IME - so the content is unchanged while the viewport gets shorter and the maximum offset GROWS.
     * Someone sitting exactly at the bottom is suddenly a keyboard's height above it.
     *
     * **The rule is the same one, in both directions.** If they had scrolled up, they are left exactly
     * where they are: opening a keyboard while reading something further back is the same annoyance
     * ruled out for polls, arriving by a different door.
     */
    fun onBottomChromeAppeared(wasAtBottom: Boolean): Scroll =
        if (wasAtBottom) Scroll.ToBottom else Scroll.None

    /**
     * What to remember about where they were, updated whenever no bottom-chrome transition is in
     * flight.
     *
     * ### Why this is remembered rather than sampled when the keyboard appears
     *
     * **A value sampled at the wrong moment answers a different question than the one being asked** -
     * the failure this project has now been bitten by three times. Observing the IME becoming visible
     * happens INSIDE a ~250ms animation, by which point the viewport has already partly shrunk and the
     * maximum has already partly grown. Asking "are they at the bottom" then answers *no* for someone
     * who was, the policy correctly declines to scroll, and the bug survives - while every test passes,
     * because the policy is not what is wrong.
     *
     * ### It freezes for a TRANSITION, and never for a STATE
     *
     * The reason the freeze exists is that a viewport in mid-animation gives a false reading: the
     * offset and the maximum are both moving and nothing sampled from them means anything. **That is a
     * property of the animation, not of the bar being open.**
     *
     * This used to freeze for as long as any bottom chrome was PRESENT, which meant the memory stopped
     * following the owner the entire time the input bar was up. The cost was exact and would have
     * shipped the moment the keyboard path started working: at the bottom, press Type, scroll up to
     * read, tap the field - and the memory still says "at the bottom", so opening the keyboard yanks
     * them down to a bottom they had deliberately left. That yank is unreachable today only by luck,
     * because the keyboard never triggers anything at all.
     *
     * So each of the two events freezes the memory for its own settle and releases it afterwards, and
     * between them - and after both - the memory tracks. Once the bar has settled, a scroll is the
     * owner deciding where to look, and a memory that ignores it is remembering something they have
     * since changed their mind about.
     *
     * ### And it is a memory, not a latch
     *
     * While nothing is settling it follows them wherever they go, including up. A value stuck at true
     * would yank a reader to the bottom the moment they opened the keyboard - the annoyance already
     * ruled out, arriving by the back door.
     */
    fun rememberedAtBottom(previous: Boolean, transitionInFlight: Boolean, offset: Int, max: Int): Boolean =
        if (transitionInFlight) previous else isAtBottom(offset, max)

    /**
     * What is taking space at the bottom, as TWO facts rather than one.
     *
     * ### The boolean that could not say "and the other one as well"
     *
     * This was `ime.getBottom(density) > 0 || chromeOpen.value`, used directly as a `LaunchedEffect`
     * key. A `LaunchedEffect` re-runs when its key CHANGES, so:
     *
     *  - the owner presses the header control, the bar appears, `false -> true`, the effect fires and
     *    the view follows the bottom. **This is the half they reported as working.**
     *  - the keyboard then opens while the bar is already up, `true -> true`, **the key does not
     *    change, the effect never runs, and nothing scrolls.** This is the half they reported as
     *    broken: *"когда открывается андроид клава - это сломано"*.
     *
     * One boolean OR-ing two independent events cannot express *the second one also happened*. So the
     * events are kept apart, and the pair is what the screen keys on - `(false, true)` and
     * `(true, true)` are different values, so each arrival gets its own run of the same effect.
     *
     * The OR survives as [anyPresent], for deciding whether there is anything to settle for. **It is
     * never an effect key and never gates the memory** - collapsing them again is the defect.
     */
    data class BottomChrome(
        /** The Android keyboard's inset. */
        val keyboard: Boolean,
        /** Our own input bar. */
        val bar: Boolean,
    ) {
        val anyPresent: Boolean get() = keyboard || bar
    }

    /**
     * Whether a bottom-chrome transition is in flight: the chrome on screen is not the chrome we last
     * finished settling for.
     *
     * ### Why this is derived rather than raised by the effect that handles it
     *
     * The obvious shape is for the settle loop to set a flag when it starts and clear it when it ends.
     * **That flag rises where the change is HANDLED, and the change is OBSERVED earlier** - the IME
     * inset moves during composition, the viewport is re-laid-out with a new maximum, and the memory's
     * flow can collect from that new maximum before the effect body has been dispatched at all.
     *
     * One collect in that window is the whole original defect back again: the memory is overwritten
     * from a viewport that is already mid-animation, records *not at the bottom* for someone who was,
     * and the policy then correctly declines to scroll. It would look exactly like the bug the owner
     * reported, and no pure-function test could see it.
     *
     * Derived from the pair, the flag is true from the instant composition sees a new one - the same
     * pass that read the inset - so there is no window in which it can still be false. The effect only
     * ever LOWERS it, by recording what it finished settling for. **It cannot be raised late, by
     * construction**, which is the same move as keeping the two events apart in the first place.
     */
    fun settling(current: BottomChrome, settled: BottomChrome): Boolean = current != settled

    /**
     * How many frames to wait for the IME animation to stop changing the viewport.
     *
     * **A bound on an observation, not a guess at someone else's animation.** The wait ends when the
     * maximum offset is the same twice running - the terminal is the only thing that knows when it has
     * stopped changing - and this is only the ceiling that stops a stuck layout waiting for ever. The
     * same shape as `resizeAndSettle` in the bridge, and for the same reason: a fixed delay tuned
     * until it looked right is a number that breaks on the next device.
     */
    const val SETTLE_FRAME_LIMIT = 60

    /**
     * How many consecutive identical maximums count as settled.
     *
     * **Three, not two, and pressing type is why.** That press can bring up our controls AND the
     * keyboard, arriving one after the other - so a maximum that repeats once can be a maximum
     * measured in the gap between them, which is about to change again. Waiting for it to hold across
     * three frames costs two frames and removes a whole class of "scrolled to the wrong bottom".
     */
    const val SETTLE_STABLE_FRAMES = 3

    /**
     * Keeps [rememberedAtBottom] up to date, observing the inset rather than remembering it.
     *
     * ### The defect this shape exists to prevent
     *
     * The first version of this loop read the IME inset in the composable and used the resulting `Int`
     * inside `snapshotFlow`. The flow re-read the scroll offset and maximum - those were snapshot state
     * read inside the block - but the inset was **a captured number, taken when the coroutine started
     * and therefore almost always zero**. So the "has the keyboard begun to take space" test was a
     * constant false, the memory updated all the way through the IME animation, and a follower was
     * recorded as someone who had scrolled up. The prompt stayed hidden, and every unit test passed,
     * because the policy was not what was wrong.
     *
     * A value sampled at the wrong moment answers a different question; a value CAPTURED answers a
     * question from the past for ever. This signature is the fix: the three inputs arrive as functions,
     * so they are read inside the flow, on every change, and a caller cannot pass a number that has
     * already stopped being true.
     *
     * [transitionInFlight] is *is the viewport moving right now*, not *is there chrome on screen* - see
     * [rememberedAtBottom] for what that distinction cost.
     */
    suspend fun trackAtBottom(
        transitionInFlight: () -> Boolean,
        offset: () -> Int,
        max: () -> Int,
        initial: Boolean = true,
        onChanged: (Boolean) -> Unit,
    ) {
        var remembered = initial
        snapshotFlow { Triple(transitionInFlight(), offset(), max()) }
            .collect { (settling, currentOffset, currentMax) ->
                remembered = rememberedAtBottom(remembered, settling, currentOffset, currentMax)
                onChanged(remembered)
            }
    }

    /** The instruction Compose carries out. */
    enum class Scroll {
        /** Leave the view exactly where it is. */
        None,

        /** Put the newest line in view. */
        ToBottom,
    }
}
