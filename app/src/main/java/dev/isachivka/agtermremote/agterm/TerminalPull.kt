package dev.isachivka.agtermremote.agterm

import kotlin.math.abs
import kotlin.math.min

/**
 * Paging the laptop's terminal by overpulling the phone's, as a decision that can be tested without a
 * screen.
 *
 * ### Where this comes from
 *
 * `PgUp` and `PgDn` were two of the eight cells on the key bar, and it had just been established that
 * the bar's width is the scarce thing. The owner's replacement, in his words: the terminal *travels a
 * little further than it should under the finger, the way pull-to-refresh does*; an icon fades in
 * inside the padding that opens up; release at full opacity sends the key, release short of it sends
 * nothing and the terminal settles back.
 *
 * ### It arms itself, and that is a property of nested scroll rather than a rule written here
 *
 * A `NestedScrollConnection` is only offered the delta its child did **not** consume. A terminal that
 * can still scroll consumes everything, so there is nothing to accumulate until the scroll is already
 * at its limit. Ordinary reading can therefore never trigger this, and no "am I at the end" test is
 * written anywhere — the absence of one is the point.
 *
 * ### DIRECTION, which is settled and is not to be improved
 *
 * **Finger moving UP sends PgUp. Finger moving DOWN sends PgDn.**
 *
 * The other reading — top edge means PgUp, continuing the scroll you were already making — was put to
 * the owner and he chose this one, twice, and asked for it a third time after v0.15.0 shipped it
 * backwards.
 *
 * **v0.15.0 did the opposite of this paragraph, and the paragraph was not the thing that was wrong.**
 * The requirement was right; the premise underneath it — which sign of leftover a rising finger
 * produces — was read out of the nested-scroll API and never measured. See [isUpward], which now
 * carries the measurement and the date.
 *
 * ### Both limits at once, which is the ordinary case here
 *
 * A Claude Code screen usually fits with nothing to scroll, so the view is at the top limit and the
 * bottom limit simultaneously and neither edge can name a key. **The sign of travel decides, never the
 * edge** — which is why nothing in this file asks which end it is at, and why the short-screen case
 * needs no branch of its own. It is the general rule, and the scrollable case is the special one.
 */
object TerminalPull {

    /** The key names, which are the bridge's own. `TerminalPullTest` asserts they are the bar's too. */
    const val PAGE_UP = "pageup"
    const val PAGE_DOWN = "pagedown"

    /**
     * How much of the finger's leftover travel the terminal actually moves.
     *
     * Less than one on purpose: content that tracks the finger exactly at a limit reads as a broken
     * scroller rather than as a gesture, and the resistance is what makes it feel like something being
     * stretched.
     *
     * ### 0.3 since 2026-08-22, down from 0.5, and this is the lever chosen against false triggers
     *
     * The owner reported a high rate of false triggers. The cause is inherent rather than
     * a defect — he flicks to scroll, the flick runs out of content mid-gesture, and the remainder of
     * his own movement becomes pull. The only lever against that is distance.
     *
     * **Resistance rather than [THRESHOLD_TRAVEL_DP], and the choice matters.** Both lengthen the
     * finger distance; only this one changes how the gesture FEELS while it happens. Raising the
     * threshold would leave the content sliding as freely as it does now and simply make the slide
     * longer — which still reads as a loose scroller that happens to need more of it. Lowering
     * resistance makes the terminal push back: the same movement buys less travel, so overshoot from a
     * flick converts into much less progress, and the stiffness is itself the signal that this is a
     * deliberate act rather than a scroll continuing.
     *
     * It also leaves the gap the label sits in exactly the size it was, so nothing about the layout
     * moves.
     *
     * **A guess awaiting his thumb.** Nothing but his thumb has ever reported on this number.
     */
    const val RESISTANCE = 0.3f

    /**
     * The travel at which the icon reaches full opacity and the key is armed, in dp.
     *
     * **This is the distance the TERMINAL moves, not the distance the FINGER does, and the second one
     * is what a thumb reports on.** The finger travels `THRESHOLD_TRAVEL_DP / RESISTANCE`:
     *
     * | | terminal moves | finger moves |
     * |---|---|---|
     * | v0.15.0 and v0.15.1 | 56dp | **112dp** — reported as false-triggering |
     * | now | 56dp | **187dp** |
     *
     * The gap between those two columns is exactly why this shipped too light: the constant names the
     * first and he feels the second. Unchanged at 56 — [RESISTANCE] is what moved, for the reason
     * written there.
     */
    const val THRESHOLD_TRAVEL_DP = 56

    /**
     * **Which sign means the finger went UP — and this was established by a thumb, not by reading.**
     *
     * Positive. The owner measured it on his own phone on **2026-08-22**, running v0.15.0: he pulled
     * upward and his laptop received `pagedown`. Given [released] is a pure function that returns
     * `pagedown` only for a non-negative pull, an upward finger must produce a POSITIVE leftover in
     * this path. That is the whole derivation, and it is the only measurement this question has ever
     * had.
     *
     * **The previous value was the opposite, and it came from reading the nested-scroll API.** It was
     * asserted by a test, described in a requirement, reviewed, and shipped, and every one of those
     * was a restatement of the same unmeasured premise. One pull of a real finger outranked all of it.
     * **Documentation is not measurement.**
     *
     * A named function rather than an inline `< 0`, so there is exactly one place where this fact
     * lives and exactly one thing to change if a future platform version moves it.
     */
    fun isUpward(pull: Float): Boolean = pull > 0f

    /** Adds one frame's unconsumed delta. Positive y is downward, which is Compose's own convention. */
    fun accumulate(pull: Float, leftover: Float): Float = moved(pull, leftover)

    /**
     * **Winding and unwinding are ONE rule, read in both directions.**
     *
     * The gesture shipped with only the winding half — `onPostScroll` accumulated and nothing ever
     * subtracted — so reversing the drag before release could not bring the pull back down. The
     * moment the finger turned round the child had somewhere to scroll again, consumed the whole
     * delta, and this connection was handed zero. The number climbed and would not come down.
     *
     * Both directions now go through this one addition. Two functions that agree about winding and
     * unwinding today are two that can disagree later, and the symptom is the bug that was just
     * shipped.
     */
    private fun moved(pull: Float, by: Float): Float = pull + by

    /** How far the terminal has moved, given how far the finger has. */
    fun travel(pull: Float): Float = pull * RESISTANCE

    /**
     * What a delta arriving BEFORE the child sees it does to an existing pull.
     *
     * The missing half of the two-sided nested-scroll pattern. While a pull is standing, a delta
     * pushing back toward zero must be spent unwinding it first, and only the remainder passed down —
     * otherwise the terminal stays displaced while the content scrolls underneath it.
     *
     * [Unwound.consumed] is what the caller must claim; zero means "not my delta, let it through".
     */
    fun unwind(pull: Float, delta: Float): Unwound {
        // Nothing wound, or nothing arriving: this connection has no business in the gesture.
        if (pull == 0f || delta == 0f) return Unwound(pull, 0f)
        // Same direction as the pull is winding it FURTHER, which is onPostScroll's job and not this
        // one. Claiming it here would take the delta twice.
        if (isUpward(pull) == isUpward(delta)) return Unwound(pull, 0f)
        // A reversal larger than the pull unwinds it exactly to zero and hands the rest down, so the
        // terminal settles and the content scrolls in the same gesture rather than in two.
        val consumed = if (abs(delta) >= abs(pull)) -pull else delta
        return Unwound(moved(pull, consumed), consumed)
    }

    /** The result of [unwind]: where the pull now stands, and how much of the delta was spent. */
    data class Unwound(val pull: Float, val consumed: Float)

    /**
     * **Everything the pull says about itself, as ONE value.**
     *
     * The key that will fire, and the end of the screen the indicator belongs at. They were three
     * separate expressions over the same sign — the key in [released], the label and the alignment in
     * `TerminalBox` — and three expressions that agree today are three that can drift. The gesture
     * shipped with the label naming one direction and a different key leaving, which is precisely
     * that drift.
     *
     * [atTop] is derived from [travel] rather than from [isUpward], deliberately: the indicator
     * belongs in the gap the pull opened, and the gap's end is decided by which way the content moved.
     * Tying it to the key instead would put the label at the end the content did not vacate.
     */
    fun pulling(pull: Float): Pulling = Pulling(
        key = if (isUpward(pull)) PAGE_UP else PAGE_DOWN,
        atTop = travel(pull) > 0f,
    )

    /** What the indicator shows and what the release will send — see [pulling]. */
    data class Pulling(val key: String, val atTop: Boolean)

    /**
     * The icon's opacity, 0 to 1, and the same number that decides whether the key is armed.
     *
     * **One function for both**, so the icon cannot reach full opacity while the release does nothing —
     * the owner's rule is literally *"release at 100 and the key is sent"*, which is only true if the
     * thing he can see and the thing that decides are the same number.
     */
    fun progress(pull: Float, thresholdPx: Float): Float =
        if (thresholdPx <= 0f) 0f else min(1f, abs(travel(pull)) / thresholdPx)

    /**
     * What to send when the finger lifts: a key name, or null for "nothing happened".
     *
     * Null rather than a no-op key, because "the owner pulled and changed their mind" is a real
     * outcome and the caller has to animate back rather than send something harmless.
     */
    /**
     * **Whether letting go now would send the key — the one boolean this gesture has.**
     *
     * It was not obvious at which moment letting go would send the key. Alpha is a ramp and
     * a ramp has no line in it; a person watching something fade in cannot see the instant it crossed.
     * So the label turns green, and green is driven from HERE.
     *
     * **The same function decides the colour and the keystroke.** Two thresholds that agree today
     * disagree later, and the symptom would be a green label that does nothing — which is the same
     * argument that put alpha and arming on one number, one round of reports ago.
     */
    fun armed(pull: Float, thresholdPx: Float): Boolean = progress(pull, thresholdPx) >= 1f

    fun released(pull: Float, thresholdPx: Float): String? =
        // **Through armed() and pulling(), so neither the moment nor the key is decided twice.**
        if (!armed(pull, thresholdPx)) null else pulling(pull).key
}
