package dev.isachivka.agtermremote.ui

import androidx.annotation.DrawableRes
import dev.isachivka.agtermremote.R

/**
 * Every icon this app draws, bundled rather than depended on.
 *
 * `androidx.compose.material:material-icons-extended` was the obvious alternative and was measured
 * rather than assumed: the artifact is 35,720,998 bytes, release builds here are not minified so it
 * would ship whole, the Compose BOM has it frozen at 1.7.8 while `compose-ui` moved to 1.11.4, and
 * the design is drawn in Material *Symbols*, which has moved on from Material *Icons* - so it would
 * have bought most of this list and left the rest to hand-author anyway.
 *
 * Named for the glyph rather than for the use, deliberately. `Back` and `Key` would read better at
 * the call site right up until somebody has to check one against the design, and the design speaks
 * in glyph names.
 *
 * ### Every entry here is drawn by a screen, and that is enforced
 *
 * Twenty-two entries were deleted when the launcher, the updater, the limits tile and the car screen
 * went. Nothing failed: their drawables stayed in the `.apk`, [all] kept listing them, and the test
 * over [all] pinned a COUNT, so the orphans were exactly as green as the icons in use. One of them
 * was a third-party logo shipping inside an application that no longer draws it.
 *
 * `AppIconsTest` on the JVM now walks the app's own sources and asserts the three sets agree: what is
 * declared here, what a screen references, and what is on disk under `res/drawable`. A glyph whose
 * last caller is deleted fails that test rather than quietly riding along.
 */
object AppIcons {

    // Navigation and chrome. `Tune` is the way into Settings from the terminal's header.
    @DrawableRes val ArrowBack = R.drawable.ic_arrow_back
    @DrawableRes val ExpandMore = R.drawable.ic_expand_more
    @DrawableRes val Tune = R.drawable.ic_tune

    /** Attaching a file to what is being typed. Converted from Material Symbols. */
    @DrawableRes val AttachFile = R.drawable.ic_attach_file

    // The terminal's width toggle. **Hand-drawn rather than converted** - Material Symbols has no
    // glyph for "the laptop's terminal is at this phone's width", and the two files say so at the top.
    @DrawableRes val FitWidthOn = R.drawable.ic_fit_width_on
    @DrawableRes val FitWidthOff = R.drawable.ic_fit_width_off

    // The type control, drawn on the same 960 grid as the fit pair so the two toggles in one header
    // read as one family rather than as two borrowed icon sets.
    @DrawableRes val KeyboardOn = R.drawable.ic_keyboard_on
    @DrawableRes val KeyboardOff = R.drawable.ic_keyboard_off

    // The session list's state dots, and the control that folds every workspace shut.
    //
    // **`check_circle` is reused for a finished session and no fourth dot exists**: an idle session
    // draws a plain circle with a Box, because a glyph for "nothing is happening" would be a glyph
    // asserting something.
    @DrawableRes val PlayArrow = R.drawable.ic_play_arrow
    @DrawableRes val FrontHand = R.drawable.ic_front_hand
    @DrawableRes val CheckCircle = R.drawable.ic_check_circle
    @DrawableRes val UnfoldLess = R.drawable.ic_unfold_less
    @DrawableRes val UnfoldMore = R.drawable.ic_unfold_more

    /** Both create controls. What is created is said by the label, not by the glyph. */
    @DrawableRes val Add = R.drawable.ic_add

    /**
     * The Claude logo — Anthropic's own mark, converted from their SVG.
     *
     * **The only glyph here that carries its own colour.** Every other icon in this file is white and
     * tinted at the call site; this one has #D97757 baked in at the owner's word, who asked for it to
     * stay orange rather than be repainted - so wherever it is drawn, the tint must be left
     * unspecified. See the drawable's header.
     *
     * It is here because a key on the typing bar draws it. OpenAI's mark used to sit beside it for a
     * screen this app no longer has, and it went with that screen: an unused third-party logo inside
     * a shipped `.apk` is a liability with no upside.
     */
    @DrawableRes val Claude = R.drawable.ic_claude

    // The open session: Send on the input bar, and the connection notes over the terminal with the
    // control that puts one away.
    @DrawableRes val ArrowUpward = R.drawable.ic_arrow_upward
    @DrawableRes val Close = R.drawable.ic_close
    @DrawableRes val Lan = R.drawable.ic_lan

    /** A wait that is being ridden out, on the connection note. */
    @DrawableRes val Schedule = R.drawable.ic_schedule

    /** Something the owner has to act on: a refusal, or an identity that cannot sign. */
    @DrawableRes val Error = R.drawable.ic_error

    /**
     * The overpull's armed marker — the only glyph here that is **hand-authored rather than
     * converted**, and its header says so.
     *
     * It replaced a colour: the owner asked for a checkmark before the text instead.
     */
    @DrawableRes val Check = R.drawable.ic_check

    /**
     * The split-pane indicator — the only glyphs here **derived from another app's toolbar**.
     *
     * agterm draws this picture on the Mac and the owner reads it instantly; the phone showing him the
     * same one is most of the control's value. Measured from his screenshot rather than approximated —
     * see the drawables' headers for the numbers.
     *
     * Two files rather than one mirrored at the call site: a mirrored drawable would depend on the
     * layout direction, and this picture is about which half of a WINDOW is lit, not about reading
     * order.
     */
    @DrawableRes val PaneLeft = R.drawable.ic_pane_left

    @DrawableRes val PaneRight = R.drawable.ic_pane_right

    /**
     * Glyph name to drawable, for the preview grid and for the tests.
     *
     * **This is the complete list, and `AppIconsTest` fails if it is not.** It used to be a subset -
     * seven declared glyphs were missing from it - which meant the grid that exists to be LOOKED AT
     * silently did not draw seven of the icons a reviewer was looking for.
     */
    val all: List<Pair<String, Int>> = listOf(
        "arrow_back" to ArrowBack,
        "expand_more" to ExpandMore,
        "tune" to Tune,
        "attach_file" to AttachFile,
        "fit_width_on" to FitWidthOn,
        "fit_width_off" to FitWidthOff,
        "keyboard_on" to KeyboardOn,
        "keyboard_off" to KeyboardOff,
        "play_arrow" to PlayArrow,
        "front_hand" to FrontHand,
        "check_circle" to CheckCircle,
        "unfold_less" to UnfoldLess,
        "unfold_more" to UnfoldMore,
        "add" to Add,
        "claude" to Claude,
        "arrow_upward" to ArrowUpward,
        "close" to Close,
        "lan" to Lan,
        "schedule" to Schedule,
        "error" to Error,
        "check" to Check,
        "pane_left" to PaneLeft,
        "pane_right" to PaneRight,
    )
}
