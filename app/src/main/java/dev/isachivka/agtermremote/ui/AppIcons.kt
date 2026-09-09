package dev.isachivka.agtermremote.ui

import androidx.annotation.DrawableRes
import dev.isachivka.agtermremote.R

/**
 * Every icon the design uses, bundled rather than depended on.
 *
 * `androidx.compose.material:material-icons-extended` was the obvious alternative and was measured
 * rather than assumed: the artifact is 35,720,998 bytes, release builds here are not minified so it
 * would ship whole into an `.apk` that is currently 24.2 MB, the Compose BOM has it frozen at 1.7.8
 * while `compose-ui` moved to 1.11.4, and unpacking its 11,105 classes shows `deployed_code` and
 * `hard_drive` are simply not in it. The design is drawn in Material *Symbols*, which has moved on
 * from Material *Icons* — so 35 MB would have bought 24 of these 26 and left two to hand-author
 * anyway. See PLAN-0004.
 *
 * Named for the glyph rather than for the use, deliberately. `Back` and `Key` would read better at
 * the call site right up until the moment somebody has to check one against the design, and the
 * design speaks in glyph names.
 *
 * Rendered together by `AppIconGridPreview`, which is how 26 hand-converted paths get checked: a
 * wrong viewport or a dropped path is obvious in a grid and nearly invisible one file at a time.
 */
object AppIcons {

    // Navigation and chrome.
    @DrawableRes val ArrowBack = R.drawable.ic_arrow_back
    @DrawableRes val ChevronRight = R.drawable.ic_chevron_right
    @DrawableRes val ExpandMore = R.drawable.ic_expand_more
    @DrawableRes val ExpandLess = R.drawable.ic_expand_less
    @DrawableRes val Tune = R.drawable.ic_tune

    // The terminal's width toggle. **Hand-drawn rather than converted** - Material Symbols has no
    // glyph for "the laptop's terminal is at this phone's width", and the two files say so at the top.
    // Converted from Material Symbols, unlike the two below it.
    @DrawableRes val AttachFile = R.drawable.ic_attach_file

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
    @DrawableRes val UnfoldLess = R.drawable.ic_unfold_less
    @DrawableRes val UnfoldMore = R.drawable.ic_unfold_more

    /** Both create controls, REQ-0011. What is created is said by the label, not by the glyph. */
    @DrawableRes val Add = R.drawable.ic_add

    /**
     * The Claude logo, REQ-0013 — Anthropic's own mark, converted from their SVG.
     *
     * **The only glyph here that carries its own colour.** Every other icon in this file is white and
     * tinted at the call site; this one has #D97757 baked in at the owner's word — *"оставлять
     * оранжевым прямо чтобы был как оригинальный"* — so wherever it is drawn, the tint must be left
     * unspecified or it will be silently repainted. See the drawable's header.
     */
    @DrawableRes val Claude = R.drawable.ic_claude

    /**
     * OpenAI's mark, for the Codex half of the limits tile — REQ-0045. Monochrome and tinted like the
     * Material glyphs, unlike [Claude]: the owner asked for that one to keep its colour and said
     * nothing about this one, and a white knot on this palette is what OpenAI's own dark mode draws.
     */
    @DrawableRes val Codex = R.drawable.ic_codex

    // The open session: Send on the input bar, and the connection notes over the terminal with the
    // control that puts one away.
    @DrawableRes val ArrowUpward = R.drawable.ic_arrow_upward
    @DrawableRes val Close = R.drawable.ic_close
    @DrawableRes val Lan = R.drawable.ic_lan

    // The update check and the download.
    @DrawableRes val SystemUpdateAlt = R.drawable.ic_system_update_alt
    @DrawableRes val Schedule = R.drawable.ic_schedule
    @DrawableRes val Sync = R.drawable.ic_sync
    @DrawableRes val CheckCircle = R.drawable.ic_check_circle
    @DrawableRes val DownloadDone = R.drawable.ic_download_done

    // The token screen.
    @DrawableRes val Key = R.drawable.ic_key
    @DrawableRes val KeyOff = R.drawable.ic_key_off
    @DrawableRes val VerifiedUser = R.drawable.ic_verified_user
    @DrawableRes val NoPhotography = R.drawable.ic_no_photography
    @DrawableRes val VisibilityOff = R.drawable.ic_visibility_off
    @DrawableRes val Help = R.drawable.ic_help
    @DrawableRes val Error = R.drawable.ic_error

    // The launcher.
    @DrawableRes val Extension = R.drawable.ic_extension

    /**
     * The eight modules, which are illustrative and stay that way — REQ-0004. These are the only
     * icons here that do not correspond to anything the app can currently do.
     */
    @DrawableRes val Dns = R.drawable.ic_dns
    @DrawableRes val DeployedCode = R.drawable.ic_deployed_code
    @DrawableRes val Router = R.drawable.ic_router
    @DrawableRes val HardDrive = R.drawable.ic_hard_drive
    @DrawableRes val Movie = R.drawable.ic_movie
    @DrawableRes val Lightbulb = R.drawable.ic_lightbulb
    @DrawableRes val Backup = R.drawable.ic_backup
    @DrawableRes val Terminal = R.drawable.ic_terminal

    /**
     * The overpull's armed marker, REQ-0029 — the only glyph here that is **hand-authored rather than
     * converted**, and its header says so.
     *
     * It replaced a colour. The owner: *"давай не цвет, давай перед текстом добавим иконку галочки"*.
     */
    @DrawableRes val Check = R.drawable.ic_check

    /**
     * The split-pane indicator, REQ-0032 — the only glyphs here **derived from another app's toolbar**.
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

    /** REQ-0005's module. The only glyph here that belongs to a tile which does something. */
    @DrawableRes val NetworkCheck = R.drawable.ic_network_check

    /** Glyph name to drawable, for the preview grid and for the test that counts them. */
    val all: List<Pair<String, Int>> = listOf(
        "arrow_back" to ArrowBack,
        "chevron_right" to ChevronRight,
        "expand_more" to ExpandMore,
        "expand_less" to ExpandLess,
        "tune" to Tune,
        "play_arrow" to PlayArrow,
        "front_hand" to FrontHand,
        "unfold_less" to UnfoldLess,
        "unfold_more" to UnfoldMore,
        "arrow_upward" to ArrowUpward,
        "close" to Close,
        "lan" to Lan,
        "system_update_alt" to SystemUpdateAlt,
        "schedule" to Schedule,
        "sync" to Sync,
        "check_circle" to CheckCircle,
        "download_done" to DownloadDone,
        "key" to Key,
        "key_off" to KeyOff,
        "verified_user" to VerifiedUser,
        "no_photography" to NoPhotography,
        "visibility_off" to VisibilityOff,
        "help" to Help,
        "error" to Error,
        "extension" to Extension,
        "dns" to Dns,
        "deployed_code" to DeployedCode,
        "router" to Router,
        "hard_drive" to HardDrive,
        "movie" to Movie,
        "lightbulb" to Lightbulb,
        "backup" to Backup,
        "terminal" to Terminal,
        "network_check" to NetworkCheck,
        "claude" to Claude,
        "check" to Check,
        "pane_left" to PaneLeft,
        "pane_right" to PaneRight,
    )
}
