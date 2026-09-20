package dev.isachivka.agtermremote.agterm

/**
 * What the phone MEASURES about its own terminal, and nothing else.
 *
 * ### There is no arithmetic here any more, and that is the point
 *
 * This object used to turn a screen width into a column count: screen dp, minus a padding constant
 * kept in step with [TerminalBox] by hand, divided by `CHARACTER_WIDTH_DP = 10.84` — a figure taken
 * from Menlo's advance ratio **at 18sp**, never once checked against what the owner's phone renders,
 * and left behind by the step-down to 16sp, where the phone actually draws 9.78dp. A guess built on a
 * guess, and the bridge cached against the result, so **every layout change we shipped silently
 * discarded their calibration.** Three attempts at this feature failed on it.
 *
 * `columnsThatFit` and `request` are DELETED rather than left beside the new path. A formula that
 * still compiles is a formula someone will use, and a replaced thing survives until it is gone from
 * the source — see finding 5e, where a design changed in conversation, was approved, was described
 * three times, and went on running because nobody grepped for what it replaced.
 *
 * ### What happens instead
 *
 * The phone sends two things it MEASURED — the terminal box's laid-out width and the width of one
 * character in the font that actually draws it — and the laptop answers with the column count its
 * terminal really rendered. The count is a RESULT, not an input the phone invents.
 *
 * What is left here is two constants the phone reports for the record, and a bound.
 */
object FitToPhone {

    /**
     * Kept in step with `TerminalBox`, which owns the layout this describes.
     *
     * The control measures a character in THIS size and sends the result, so this moving is what makes
     * a font change reach the bridge's cache key at all - see the key in internal/resize.
     */
    const val TERMINAL_FONT_SIZE_SP = 16

    /**
     * The HORIZONTAL padding only, and it takes part in no calculation.
     *
     * The box measures its own width with the padding already removed. This survives so the phone can
     * tell the bridge which margin a calibration was taken under, and the bridge stores it beside the
     * fit so a human reading that file can see why a cache key changed rather than inferring it from
     * a number that moved.
     */
    const val TERMINAL_HORIZONTAL_PADDING_DP = 4

    /** Mirrors `internal/resize`. A terminal narrower than this is not a terminal. */
    const val MIN_COLUMNS = 20

    /** Mirrors `internal/resize`. A sanity bound, not a measurement. */
    const val MAX_COLUMNS = 400

    /**
     * The HEIGHT a fit asks for while "Colours through zmx" is on, in rows.
     *
     * Not a measurement, and it could not be one: the phone cannot see the laptop's window, and
     * agterm's own resize is clamped to the screen's visibleFrame, so a tall pane is impossible to
     * reach by making the window bigger. The height comes from the zmx daemon instead - the bridge
     * claims leadership of the daemon and holds its pty at this many rows - which is why the zmx
     * colour setting is the only thing that turns this on.
     *
     * **200 because it is far more than any phone shows and well under the bridge's refusal at 500.**
     * The point is not to fill the screen: it is that Claude Code, which draws its own scroller and
     * emits exactly the rows its pty has, renders 200 of them, so the phone can scroll back through
     * output that would otherwise never have been written. Higher would only cost bytes on every poll
     * for lines nobody reaches.
     */
    const val TALL_ROWS = 200
}
