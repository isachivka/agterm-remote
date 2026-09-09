package dev.isachivka.agtermremote.agterm

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import dev.isachivka.agtermremote.R
import dev.isachivka.agtermremote.ui.AppIcons
import dev.isachivka.agtermremote.ui.theme.AppTheme

/**
 * The session list, and the box.
 *
 * Two views in one destination: the list, and one session's screen. The box is [TerminalBox] and the
 * argument for its layout lives there — this file only decides when it is on screen.
 */
@Composable
fun AgtermScreen(
    state: AgtermUiState,
    onRefresh: () -> Unit,
    onOpen: (BridgeSession) -> Unit,
    onCloseSession: () -> Unit,
    onFitToPhone: (Int, Int, Boolean) -> Unit,
    onRestoreWindow: () -> Unit,
    fit: FitState,
    typing: TypingState,
    onOpenTyping: () -> Unit,
    onCloseTyping: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSendText: (String) -> Unit,
    onSendKey: (String) -> Unit,
    /** Types a command and then presses Return — the Claude button. */
    onRunMacro: (String) -> Unit = {},
    onPickFile: () -> Unit,
    onDisconnect: () -> Unit,
    /** The open session's draft. Beside [typing], not inside it; see TypingBar. */
    draft: String = "",
    onPair: () -> Unit,
    /**
     * Settings, which is where pairing lives.
     *
     * **This slot used to be `onBack`, and the swap is the whole of "the terminal is the app".** The
     * terminal is the root destination now, so there is no parent screen for a back arrow to lead to
     * - and pairing, which the deleted launcher used to reach through a tile, would otherwise be
     * reachable only by failing to sign.
     */
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    // Hoisted from the ViewModel so panning right and then rotating does not drop the owner back to
    // column zero - on the very gesture that gives them more columns to read.
    // The session list's remembered position, hoisted for the same reason as the terminal's - see
    // AgtermViewModel. Defaulted so a preview or a test that does not care reads exactly as before.
    listAnchor: ListPosition.Anchor? = null,
    onListAnchor: (ListPosition.Anchor?) -> Unit = {},
    // Which workspaces the owner has folded shut, hoisted for the same reason as the anchor - see
    // CollapsedWorkspaces. Defaulted so a preview or a test that does not care reads as it did before.
    /**
     * Which pane the terminal is showing, and the control that changes it.
     *
     * **[paneShown] is the same value the screen was READ from and the same one a keystroke goes
     * into.** It is not a display copy: `AgtermSessions` holds one field, both of its bridge calls
     * read it, and this renders it. A second source here would be the defect this feature was written
     * to fix, rebuilt in the UI.
     *
     * Defaulted so a preview or a test that does not care reads exactly as it did before.
     */
    /**
     * The column count a just-finished recalibration reported, or null.
     *
     * Defaulted so a preview or a test that does not care reads exactly as it did before.
     */
    recalibrated: Int? = null,
    paneShown: Pane = Pane.Left,
    /**
     * One tap, both directions. Going right may create a pane on the laptop; going left
     * never touches it. The screen does not know which happened and does not need to: it draws
     * [paneShown], which only moves once the laptop has confirmed the pane is there.
     */
    onTogglePane: () -> Unit = {},
    /**
     * Re-apply a fit the laptop already has, for the geometry now on screen.
     *
     * **Not a press.** It fires when the session or the pane changes, because the intent he stated is
     * *keep this terminal readable on this screen* rather than a one-shot action attached to a button.
     * A cached fit applies silently; a geometry the laptop has never measured produces one line and no
     * calibration, because a calibration is a visible hunt across a window he is not looking at.
     */
    onRefit: (Int, Int) -> Unit = { _, _ -> },
    closedWorkspaces: Set<String> = emptySet(),
    onClosedWorkspaces: (Set<String>) -> Unit = {},
    /**
     * The ADDRESS this phone dials, for the connection row. Empty when there is no pairing to read.
     *
     * **Not the laptop's name, because the phone does not hold one** — the pairing payload carries a
     * kind, a host, a port and a certificate. Drawn on their own screen and nowhere else: it is
     * redacted from `ConnectionProfile.toString()` on purpose, and it belongs in no log, no fixture
     * and no committed screenshot.
     */
    laptop: String = "",
    /** What the phone can honestly say about the connection right now — see [LinkNote]. */
    link: LinkNote = LinkNote.None,
    /** Creates a workspace. It arrives empty and its rename dialog opens on agterm's default name. */
    onCreateWorkspace: () -> Unit = {},
    /**
     * Creates a session in one workspace, by id.
     *
     * **This moves the owner's laptop** — agterm focuses what it creates. Measured, and said here
     * because a control whose cost is only in a commit message is a cost nobody reads.
     */
    onCreateSession: (String) -> Unit = {},
    /** The rename dialog's subject, or null when it is closed. */
    renaming: RenameRequest? = null,
    /** Opens the rename dialog on something that already exists — the long press. */
    onBeginRename: (RenameTarget) -> Unit = {},
    /** Commits the dialog. The label has passed [labelProblem]; the bridge checks it again anyway. */
    onRename: (RenameTarget, String) -> Unit = { _, _ -> },
    /** Dismisses it. **Nothing is undone** — a created thing keeps the default name it already has. */
    onCancelRename: () -> Unit = {},
    /**
     * Closes a session or deletes a workspace **and everything in it**. No undo.
     *
     * Reached only from the modal a long press opened, which is the owner's own guard — see
     * [RenameOrigin]. Nothing is removed from the list here; the row goes when the laptop says so.
     */
    onDelete: (RenameTarget) -> Unit = {},
    /** What the last create or rename did, for the notes surface. */
    mutation: MutationNote = MutationNote.None,
    onDismissMutation: () -> Unit = {},
    /** Puts the one note away, whichever of the two sources produced it. */
    onDismissNotice: () -> Unit = {},
    terminalVertical: ScrollState = rememberScrollState(),
    terminalHorizontal: ScrollState = rememberScrollState(),
) {
    val watching = (state as? AgtermUiState.Sessions)?.watching
    // The hierarchy, built once per state and read by the header control as well as by the list.
    // Two constructions of "the groups" would agree until the day one of them changed - the same
    // argument ListPosition makes about the keys.
    val groups = remember(state) {
        val listed = state as? AgtermUiState.Sessions
        // **The published workspace list is passed, not derived.** Without it an EMPTY workspace has
        // no heading at all, which is what the owner would get the moment they pressed + - see
        // BridgeWorkspace. Null when the bridge is too old to say, and the derivation is the fallback.
        groupByWorkspace(listed?.sessions.orEmpty(), listed?.workspaces)
    }
    // What the terminal box last measured itself to be. Zero until it has been laid out once, which is
    // why the toggle is disabled until then: asking the laptop to match a width nobody has measured is
    // the guess this attempt exists to delete.
    var boxWidthDp by remember { mutableStateOf(0.0) }
    // **Both numbers are MEASURED and neither becomes a column count here.**
    //
    // The box reports the width it was laid out at, after its own padding. The character is measured
    // from the very TextStyle TerminalBox draws with, so swapping the font changes the answer instead
    // of silently invalidating a constant written down elsewhere. Measured on the owner's phone the
    // character is 9.78dp.
    //
    // Taken at this level since the fit control needs it and so does the automatic re-apply,
    // and the measurement lives in TerminalFont so the instrumentation can assert THAT integer rather
    // than a second copy of the arithmetic.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val family = TerminalFontFamily
    val characterWidthMilliDp = remember(measurer, density, family) {
        characterWidthMilliDp(measurer, density, family)
    }
    // Hoisted so the reason can be drawn under the header row, where a sentence fits.
    var fitDisabledReason by remember { mutableStateOf<Int?>(null) }
    // Used when the type control is switched OFF: the bar goes away and the keyboard goes with it.
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current

    // **ONE definition of back, called by the arrow AND by the system gesture.** They used to be two:
    // the arrow knew an open session was a place to come back from, and the gesture did not, so the
    // gesture left the app from inside a session. Two functions that agree today disagree by next
    // week - see BackWithin, where the order lives as a pure function.
    val typingIsOpen = typing !is TypingState.Closed
    val goBack: () -> Unit = {
        when (BackWithin.action(typingOpen = typingIsOpen, inSession = watching != null)) {
            // The same cleanup the toggle performs. Back must not leave a keyboard hanging over a
            // bar that is no longer there.
            BackWithin.Action.CloseTyping -> {
                keyboard?.hide()
                focus.clearFocus(force = true)
                onCloseTyping()
            }
            BackWithin.Action.CloseSession -> onCloseSession()
            // Unreachable, and deliberately not wired to anything. The handler below is enabled only
            // while `handles` is true, and the arrow that also calls this is drawn only then - so
            // "leave the screen" is left to Android, where at the root of the app it means leaving
            // the app. A branch here would be a second, quieter way out of a screen that has one.
            BackWithin.Action.LeaveScreen -> Unit
        }
    }

    // **Enabled only when this screen has somewhere to go**, so back on the list still leaves the app
    // exactly as it does today. A handler that is always on strands the owner inside the app, which
    // is worse than the bug being fixed.
    BackHandler(enabled = BackWithin.handles(typingOpen = typingIsOpen, inSession = watching != null)) {
        goBack()
    }

    Column(modifier = modifier.fillMaxSize()) {
        // **One chrome row, down from four.** This was a back arrow on its own row, a title block with
        // a subtitle beneath it, and then a row for the width and typing controls - four rows spent
        // before a single line of terminal, on the screen whose entire complaint is that a phone is
        // short. Each removal used the same reasoning: the chrome was competing with the content it
        // exists to frame.
        //
        // The subtitle went with them while a session is open: the workspace name is context for
        // CHOOSING a session, and once one is open the owner knows which one they are in.
        //
        // The controls only appear with a session open, because that is the only state either of them
        // means anything in - there is no window to fit and nothing to type into while reading a list.
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // **Two buttons in one slot, and which one is drawn is the same question the back
            // gesture asks.** Inside a session, back means "out of the session" and the arrow is the
            // arrow - the same function the gesture calls, see goBack above. On the list there is
            // nothing within this screen to come back from, so an arrow would either do nothing or
            // close the app; the slot carries the way into Settings instead, which is the route the
            // deleted launcher used to provide and the only way to reach pairing before anything is
            // paired.
            //
            // Asked through `BackWithin.handles`, not through a second condition of its own, so the
            // button and the gesture cannot disagree about where the owner is.
            if (BackWithin.handles(typingOpen = typingIsOpen, inSession = watching != null)) {
                IconButton(
                    onClick = goBack,
                    modifier = Modifier.testTag(TAG_BACK),
                ) {
                    Icon(
                        painter = painterResource(AppIcons.ArrowBack),
                        contentDescription = stringResource(R.string.nav_back),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag(TAG_OPEN_SETTINGS),
                ) {
                    Icon(
                        painter = painterResource(AppIcons.Tune),
                        contentDescription = stringResource(R.string.settings_title),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            // **Workspace and session, in ONE row.** The owner asked to see the workspace in the
            // header of an open session; they also asked this morning for the chrome to stop eating
            // the screen, and a second line would take back the height that removal just gave them.
            //
            // **Which one gives way, and why.** A Row measures its unweighted children FIRST and hands
            // the weighted ones what is left - so the session name is unweighted and the workspace
            // carries the weight. The workspace ellipsises when the row is tight; the session name
            // does not, until nothing else is left to give. Losing the end of a workspace name is an
            // inconvenience. Losing which session you are in is a defect, and that asymmetry is the
            // whole reason for the modifiers below.
            //
            // Do not "fix" this by ellipsising whichever string is longer: that would drop the
            // session name exactly when it is long, which is when it is carrying the most meaning.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val workspace = watching?.workspace.orEmpty()
                if (watching != null && workspace.isNotEmpty()) {
                    Text(
                        text = workspace,
                        style = MaterialTheme.typography.bodyMedium,
                        // Subordinate on purpose: the session is what they are looking at, the
                        // workspace is where it lives.
                        color = AppTheme.colors.onSurfaceSubtle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // The weight is what makes this the part that shrinks.
                        modifier = Modifier.weight(1f, fill = false).testTag(TAG_HEADER_WORKSPACE),
                    )
                    Text(
                        text = " / ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.colors.onSurfaceSubtle,
                        maxLines = 1,
                    )
                }
                Text(
                    text = watching?.name ?: stringResource(R.string.agterm_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // **Fold every workspace, or open every one.** One control with two positions, and the
            // icon and the action are two readings of the SAME question - see CollapsedWorkspaces.press,
            // where that question is asked once so a button cannot show "expand" while doing "collapse".
            //
            // Only on the list, and only when there is something to fold.
            // **A workspace is created from the list header, beside collapse-all**, which is where the
            // owner asked for a control of its own. Unlike collapse-all it is shown even when the list is
            // empty - that is
            // precisely when they most need to make one.
            if (watching == null) {
                IconButton(
                    onClick = onCreateWorkspace,
                    modifier = Modifier.testTag(TAG_NEW_WORKSPACE),
                ) {
                    Icon(
                        painter = painterResource(AppIcons.Add),
                        contentDescription = stringResource(R.string.agterm_new_workspace_a11y),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (watching == null && groups.isNotEmpty()) {
                val ids = groups.map { it.id }
                val allClosed = CollapsedWorkspaces.allClosed(closedWorkspaces, ids)
                IconButton(
                    onClick = { onClosedWorkspaces(CollapsedWorkspaces.press(closedWorkspaces, ids)) },
                    modifier = Modifier.testTag(TAG_COLLAPSE_ALL),
                ) {
                    Icon(
                        painter = painterResource(if (allClosed) AppIcons.UnfoldMore else AppIcons.UnfoldLess),
                        contentDescription = stringResource(
                            if (allClosed) R.string.agterm_expand_all else R.string.agterm_collapse_all,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (watching != null) {
                // The owner's own idea, and the reason TerminalBox does not reflow: the laptop is told
                // how wide this phone is, and vim, htop and every table-printing program lay
                // themselves out for it. A control rather than something done on connect - their
                // window is theirs, and they are sitting at it.
                // **Always present, and this is a reversal.**
                //
                // It was composed only when the session had a second pane, on the absent-not-disabled
                // ruling. That ruling is unchanged and stopped applying here: a control is absent when
                // it can do NOTHING, and this one can always do something. There is always a left
                // pane, and a right one can always be brought into existence: if it is not there, it is
                // created and then shown.
                //
                // So there is no third state to draw and no screenshot to wait for. The control has
                // exactly the two pictures it has always had.
                //
                // Before Fit rather than after, because it changes what the terminal SHOWS, and Fit
                // changes how wide it is. The one that decides what you are looking at comes first.
                PaneToggle(shown = paneShown, onTogglePane = onTogglePane)
                FitWidthToggle(boxWidthDp, characterWidthMilliDp, fit, { fitDisabledReason = it }, onFitToPhone, onRestoreWindow)
                // **Typing is somewhere they go deliberately.** They are sending real commands to a
                // real machine, so the bar stays shut until this is pressed. An input that is always
                // focused is one a pocket can type into.
                // **A toggle, and an icon, to match the control beside it.** The owner asked for a
                // switch - press to bring the bar up, press again to put it away - and for
                // it to look like the fit control rather than being the one text button in a row of
                // icons. Two controls in one header that behave differently teach that neither can
                // be trusted to behave like the other.
                val typingOpen = typing !is TypingState.Closed
                IconToggleButton(
                    checked = typingOpen,
                    onCheckedChange = {
                        if (it) {
                            onOpenTyping()
                        } else {
                            // **Putting the bar away takes the keyboard with it.** Otherwise the
                            // phone is left with a keyboard hanging over a terminal that has nothing
                            // to type into - which is the complaint they already made once about
                            // dismissal leaving focus behind.
                            keyboard?.hide()
                            focus.clearFocus(force = true)
                            onCloseTyping()
                        }
                    },
                    modifier = Modifier.testTag(TAG_TYPING_OPEN),
                ) {
                    Icon(
                        painter = painterResource(
                            if (typingOpen) AppIcons.KeyboardOn else AppIcons.KeyboardOff,
                        ),
                        contentDescription = stringResource(
                            if (typingOpen) R.string.agterm_typing_close else R.string.agterm_typing_open,
                        ),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        // **The reason the width control is dark, if it is.** Under the row rather than beside it:
        // the header has no room for a sentence, and this must be readable rather than clipped.
        val reason = fitDisabledReason
        if (watching != null && reason != null) {
            Text(
                text = stringResource(reason),
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceSubtle,
                modifier = Modifier
                    .padding(start = FitToPhone.TERMINAL_HORIZONTAL_PADDING_DP.dp, end = 12.dp)
                    .testTag(TAG_FIT_DISABLED_REASON),
            )
        }

        // **The connection row replaced the subtitle.** *"Sessions on your laptop"* said what the
        // screen already showed; this row says the two things it did not - that something is
        // connected, and to what - and it carries the Disconnect control that used to be a line of
        // text pretending to be a button.
        //
        // Drawn only on the list, and only when there are sessions: it is context for CHOOSING one.
        if (watching == null && state is AgtermUiState.Sessions) {
            ConnectionRow(laptop = laptop, sessions = state.sessions.size, onDisconnect = onDisconnect)
        }

        when (state) {
            AgtermUiState.Loading -> Loading()
            AgtermUiState.Disconnected ->
                Failure(R.string.agterm_disconnected, onRefresh, R.string.agterm_action_connect)
            AgtermUiState.NotPaired -> Failure(R.string.agterm_failure_not_paired, onPair, R.string.agterm_action_pair)
            // The bridge's own reason when it gave one, and the generic line only when it did not.
            // A refusal that HAS a reason should say it - the same principle as passing the
            // arithmetic through on an impossible fit.
            // **The button says what it does.** `onRefresh` re-lists the sessions and puts the owner
            // back on the list; it does not retry the read that failed, and for every refusal on this
            // path retrying would fail identically — a session that is gone stays gone, and one the
            // Mac has not opened stays unopened until the Mac opens it. It said "Try again" until
            // 2026-08-12, which promised a retry and delivered an abandonment.
            is AgtermUiState.Refused ->
                if (state.reason.isEmpty()) {
                    Failure(R.string.agterm_failure_refused, onRefresh, R.string.agterm_action_back_to_sessions)
                } else {
                    Failure(
                        message = state.reason,
                        onAction = onRefresh,
                        action = R.string.agterm_action_back_to_sessions,
                        detail = state.detail,
                    )
                }
            is AgtermUiState.Failed ->
                Failure(
                    message = copyFor(state.failure),
                    // An identity failure offers pairing, not retry: retrying a certificate that will
                    // never be accepted teaches the owner that this app's buttons are decorative.
                    onAction = if (isIdentityFailure(state.failure)) onPair else onRefresh,
                    action = if (isIdentityFailure(state.failure)) {
                        R.string.agterm_action_pair
                    } else {
                        R.string.agterm_action_retry
                    },
                )

            is AgtermUiState.Sessions ->
                if (watching != null) {
                    // **Where the view looks, decided by TerminalScroll and performed here.**
                    //
                    // The owner opened a session and landed at the TOP, when what they came to read is
                    // the newest output at the bottom. Two effects, because the two events ask
                    // different questions and one of them must not be answered on every poll.
                    //
                    // Keyed on the session id: opening always goes to the bottom. The scroll state is
                    // ONE object shared by every session - see AgtermViewModel, where it is hoisted so
                    // a rotation does not lose the reading position - so without this the next session
                    // opens at the previous one's offset, a number from a buffer it never had.
                    LaunchedEffect(watching.id) {
                        if (TerminalScroll.onSessionOpened() == TerminalScroll.Scroll.ToBottom) {
                            // The new text has not been laid out yet, so maxValue is still the old
                            // one. Waiting a frame is what makes this the bottom of THIS session
                            // rather than the last position of the previous one.
                            withFrameNanos { }
                            terminalVertical.scrollTo(terminalVertical.maxValue)
                        }
                    }

                    // **Where they were before the keyboard, remembered continuously.**
                    //
                    // Updated on every frame the IME is not taking space, and frozen the moment it
                    // starts to - see TerminalScroll.rememberedAtBottom for why sampling this when
                    // the keyboard appears would answer the wrong question. It follows them up as
                    // well as down, so a reader is never yanked to the bottom by opening a keyboard.
                    var atBottomBeforeKeyboard by remember { mutableStateOf(true) }

                    /**
                     * A PgUp is out and the page it brings back should land at its bottom.
                     *
                     * Holds the scroll offset at the moment the key was sent, which is how "he has not
                     * touched it since" is detected: if the offset has moved, he is steering and the
                     * intent is dropped rather than fought. Null means no PgUp is outstanding.
                     */
                    var pageUpLanding by remember { mutableStateOf<Int?>(null) }

                    /**
                     * Every key the owner sends, with the PgUp landing armed on the way past.
                     *
                     * **Wrapped in one place so the gesture and the folded button behave identically.**
                     * They are the same keystroke and there is no reason for the seam to depend on
                     * which control produced it - and two call sites arming this separately is how one
                     * of them stops doing it.
                     */
                    val sendKeyAndLand: (String) -> Unit = { key ->
                        if (key == TerminalPull.PAGE_UP) pageUpLanding = terminalVertical.value
                        onSendKey(key)
                    }
                    // **The inset is READ inside the flow, never captured.** `ime` and `density` are
                    // stable handles; the pixel value is fetched on every change, so the loop observes
                    // the keyboard instead of remembering what it was when the coroutine started. The
                    // first version passed the Int and was frozen at zero for the life of the effect -
                    // see TerminalScroll.trackAtBottom.
                    val ime = WindowInsets.ime
                    val density = LocalDensity.current
                    // **Our own controls count as taking space, exactly as the keyboard does.** The
                    // owner hit this twice: the IME first, then the typing bar. Same viewport change,
                    // so it feeds the same policy rather than a second one that can disagree.
                    //
                    // Held through rememberUpdatedState so the flow READS it. `typing` is a parameter,
                    // and a lambda closing over it would capture the value at launch - the frozen-Int
                    // defect from the keyboard fix, arriving one file later.
                    val chromeOpen = rememberUpdatedState(typing !is TypingState.Closed)
                    // **Two facts, kept apart.** As one OR'd boolean this was a LaunchedEffect key that
                    // could not change when the SECOND event arrived: the bar set it true, and the
                    // keyboard opening left it true, so the effect never ran for the keyboard and
                    // nothing scrolled. The owner reported exactly that split - the header control
                    // works, the Android keyboard does not. See TerminalScroll.BottomChrome.
                    val bottomChrome = TerminalScroll.BottomChrome(
                        keyboard = ime.getBottom(density) > 0,
                        bar = chromeOpen.value,
                    )

                    // **Is the viewport MOVING, not is there chrome on screen** - and DERIVED here in
                    // composition rather than raised inside the effect that handles it.
                    //
                    // Raised by the effect, it would rise too late: the inset moves during composition,
                    // layout writes a new maximum, and the memory's flow can collect from that
                    // mid-animation viewport before the effect body has been dispatched at all. One
                    // collect in that window overwrites the memory with "not at the bottom" for someone
                    // who was, and the policy then correctly declines to scroll - the original bug,
                    // arriving through a race that no pure-function test can see.
                    //
                    // Derived from the pair, it is true from the instant composition sees a new one.
                    // The effect below only LOWERS it, by recording what it finished settling for.
                    var settledChrome by remember { mutableStateOf(bottomChrome) }
                    // Held in state so the flow READS it rather than capturing the Boolean this
                    // composition happened to compute - the frozen-value defect, one file later again.
                    val chromeSettling = rememberUpdatedState(TerminalScroll.settling(bottomChrome, settledChrome))

                    LaunchedEffect(terminalVertical, ime, density) {
                        TerminalScroll.trackAtBottom(
                            // Read inside the flow, never captured - the frozen-Int defect this
                            // signature exists to prevent.
                            transitionInFlight = { chromeSettling.value },
                            offset = { terminalVertical.value },
                            max = { terminalVertical.maxValue },
                        ) { atBottomBeforeKeyboard = it }
                    }

                    // **After the keyboard has finished taking its space, not when it starts.**
                    //
                    // Scrolling to a maximum computed against the old height puts them NEAR the
                    // bottom rather than at it, which looks like the bug half-fixed. So this waits
                    // until the maximum is the same twice running - the terminal is the only thing
                    // that knows when it has stopped changing - bounded by SETTLE_FRAME_LIMIT so a
                    // stuck layout cannot wait for ever. Same shape as resizeAndSettle in the bridge.
                    // Keyed on the PAIR, so (false,true) and (true,true) are different values and each
                    // arrival gets its own run of this one effect. One effect, not two: two that agree
                    // today disagree by next week.
                    LaunchedEffect(bottomChrome) {
                        // Nothing on screen to settle for, but this pair is still the one we are now
                        // settled at - recording it is what lowers the flag.
                        if (!bottomChrome.anyPresent) {
                            settledChrome = bottomChrome
                            return@LaunchedEffect
                        }
                        // The memory stops moving for the length of THIS transition, and resumes after
                        // it - so a scroll between the bar arriving and the keyboard arriving is the
                        // owner deciding where to look, and is remembered as such.
                        try {
                        // **Settled means the maximum held across SETTLE_STABLE_FRAMES, not that it
                        // repeated once.** Pressing type can bring up the controls AND the keyboard
                        // one after the other, and a maximum measured in the gap between them is one
                        // that is about to change again - scrolling to it lands near the bottom
                        // rather than at it, which is the bug looking half-fixed.
                        awaitStableMax(terminalVertical)
                        if (TerminalScroll.onBottomChromeAppeared(atBottomBeforeKeyboard) ==
                            TerminalScroll.Scroll.ToBottom
                        ) {
                            terminalVertical.scrollTo(terminalVertical.maxValue)
                        }
                        } finally {
                            // In a finally because cancellation is the normal exit: the next event
                            // replaces this effect mid-settle. Recording the pair we settled at is what
                            // lowers the flag, and a transition left unrecorded would freeze the memory
                            // for the rest of the session.
                            settledChrome = bottomChrome
                        }
                    }

                    // Keyed on the text: the poll replaces the whole screen every couple of seconds.
                    // **`wasAtBottom` is read before the frame wait, deliberately** - the question is
                    // whether they were following when this arrived, not whether the new content
                    // happens to leave them near its end.
                    LaunchedEffect(state.screen) {
                        // **A PgUp lands at the BOTTOM of the page it brought back**, so that
                        // scrolling up reads seamlessly. The bottom of the earlier screen is the line
                        // immediately before the top of where he just was, so landing there continues
                        // the text without a seam. PgDn needs nothing: it is already handled by
                        // `wasAtBottom` below.
                        //
                        // **What identifies "the render the PgUp caused" - and it does not, exactly.**
                        // Nothing on the wire says which screen a keystroke produced. This takes the
                        // first change after the key went out, which a poll carrying unrelated output
                        // could also satisfy. Three things bound the damage, because a wrong jump
                        // while he is reading is worse than no jump:
                        //
                        //   - the intent is ONE SHOT and armed only by a pageup we sent;
                        //   - it is dropped if he has scrolled since (the offset moved), so it can
                        //     never yank a view he is already steering;
                        //   - it expires (see the effect below), so a pageup that changed nothing
                        //     cannot make some later, unrelated poll jump him.
                        val landing = pageUpLanding
                        if (landing != null && terminalVertical.value == landing) {
                            pageUpLanding = null
                            // Settled rather than one frame: a max measured mid-relayout lands NEAR
                            // the bottom, which is this feature looking half-fixed.
                            awaitStableMax(terminalVertical)
                            terminalVertical.scrollTo(terminalVertical.maxValue)
                            return@LaunchedEffect
                        }
                        pageUpLanding = null

                        val wasAtBottom = TerminalScroll.isAtBottom(
                            terminalVertical.value, terminalVertical.maxValue,
                        )
                        withFrameNanos { }
                        val decision = TerminalScroll.onContentChanged(
                            wasAtBottom = wasAtBottom,
                            offset = terminalVertical.value,
                            newMax = terminalVertical.maxValue,
                        )
                        if (decision == TerminalScroll.Scroll.ToBottom) {
                            terminalVertical.scrollTo(terminalVertical.maxValue)
                        }
                    }

                    // The intent expires. Without this, a pageup whose screen never changed would
                    // leave the flag standing until some unrelated poll collected it - a jump with no
                    // gesture behind it, minutes later, which is the worst version of this feature.
                    LaunchedEffect(pageUpLanding) {
                        if (pageUpLanding != null) {
                            delay(PAGE_UP_LANDING_WINDOW_MS)
                            pageUpLanding = null
                        }
                    }

                    // What the fit is doing, above the box and outside what it measures.
                    FitNote(fit)

                    // **The note floats OVER the terminal and takes nothing from it.** The box's
                    // measured width is half the bridge's fit cache key, so a card that pushed it
                    // narrower would silently discard every calibration the owner has.
                    Box(modifier = Modifier.weight(1f)) {
                        TerminalBox(
                            text = state.screen,
                            styled = state.screenStyled,
                            onMeasured = { boxWidthDp = it },
                            modifier = Modifier.fillMaxSize().testTag(TAG_BOX),
                            vertical = terminalVertical,
                            horizontal = terminalHorizontal,
                            // **The same callback the key bar's own PgUp and PgDn use.** The overpull
                            // replaced two buttons with a gesture; it did not add a second way for a
                            // keystroke to reach the laptop, and the folded buttons still work.
                            onPageKey = sendKeyAndLand,
                        )
                        // **A recovery says its piece and goes.** "Reconnecting" stays until it is
                        // resolved, because it describes something still happening; "reconnected"
                        // describes a moment that has passed, and a card about it sitting over the
                        // terminal for the rest of the evening would be chrome, not news.
                        // One surface, two sources, precedence decided in one pure function.
                        // **The session screen passes `mutation` now** - the pane toggle gave it one to
                        // report. Before this it had no mutation of its own and left the argument
                        // at its default.
                        // **The switch re-applies, and this is where it is triggered**.
                        //
                        // Keyed on the session, the pane, AND on having a measurement: `boxWidthDp`
                        // starts at zero and is filled by the box's own layout, so an effect keyed
                        // only on the session would fire once with nothing to send and never again.
                        //
                        // **It is issued alongside the first poll rather than before it.** `watch`
                        // starts polling the moment he taps a row, and this composable does not run
                        // until that has happened - so the resize and the first screen read are in
                        // flight together. Whether the first frame lands before or after the window
                        // moves is a race, and the owner's eye settles it: if it reads as a reflow
                        // arriving under him, holding the first read until the resize returns is a
                        // one-line change.
                        val canRefit = boxWidthDp > 0 && characterWidthMilliDp > 0
                        LaunchedEffect(watching.id, paneShown, canRefit) {
                            if (canRefit) onRefit(boxWidthDp.toInt(), characterWidthMilliDp)
                        }
                        val notice = noticeFor(link, typing, mutation, recalibrated)
                        LaunchedEffect(notice) {
                            if (notice != null && expires(notice)) {
                                delay(NoticeVisibleMs)
                                onDismissNotice()
                            }
                        }
                        NoticeCard(notice = notice, onDismiss = onDismissNotice)
                    }
                    // Below the terminal, not inside it. The draft is never terminal content.
                    TypingBar(
                        state = typing,
                        draft = draft,
                        onDraftChange = onDraftChange,
                        onSendText = onSendText,
                        onSendKey = sendKeyAndLand,
                        onRunMacro = onRunMacro,
                        onPickFile = onPickFile,
                    )
                } else {
                    // **The notes surface, on this screen too.** It was drawn only over the terminal,
                    // so a create or rename that failed had nowhere to be said - and a failed create
                    // leaves the list looking exactly as it did, which is indistinguishable from a
                    // press that was ignored. One surface, more sources: the same rule that moved the
                    // typing report here in the first place.
                    Box(modifier = Modifier.weight(1f)) {
                        // **Pull down to refresh what is on screen.** The owner asked for the standard
                        // pattern - the loader is drawn out and everything reloads - and this is the
                        // Material3 component for it, so the gesture means one thing in this app.
                        //
                        // On the LIST only. The open session has its own scroll in both axes and polls
                        // every tick anyway; a pull there would fight the terminal and duplicate a
                        // refresh that is already happening.
                        //
                        // `isRefreshing` is DERIVED from the fetch rather than set beside it, and
                        // `onRefresh` is the same lambda the failure screens' retry uses - so a pull
                        // and an arrival are literally one call, not two that agree today.
                        PullToRefreshBox(
                            isRefreshing = (state as? AgtermUiState.Sessions)?.refreshing == true,
                            onRefresh = onRefresh,
                            modifier = Modifier.testTag(TAG_LIST_PULL_REFRESH),
                        ) {
                            SessionList(
                                groups, onOpen, onRefresh, listAnchor, onListAnchor,
                                closedWorkspaces, onClosedWorkspaces, onCreateSession, onBeginRename,
                            )
                        }
                        val notice = noticeFor(link, typing, mutation)
                        LaunchedEffect(notice) {
                            if (notice != null && expires(notice)) {
                                delay(NoticeVisibleMs)
                                onDismissMutation()
                                onDismissNotice()
                            }
                        }
                        NoticeCard(notice = notice) {
                            onDismissMutation()
                            onDismissNotice()
                        }
                        // Drawn here so it is inside the list screen and nowhere near the terminal.
                        renaming?.let { request ->
                            RenameDialog(
                                request = request,
                                onConfirm = onRename,
                                // **The anchor is re-pointed BEFORE the delete is dispatched**, while
                                // the grouping still knows which group held the row. Afterwards the
                                // listing has no record of it and nothing could say. See
                                // ListPosition.afterDelete.
                                onDelete = { target ->
                                    onListAnchor(ListPosition.afterDelete(listAnchor, target, groups))
                                    onDelete(target)
                                },
                                onCancel = onCancelRename,
                            )
                        }
                    }
                }
        }
    }
}

/**
 * The terminal's width, as one control with two positions.
 *
 * **Fit and Undo were two text buttons on a row of their own.** That said the window had two
 * independent things that could be done to it; it has one, and it is either narrowed or it is not. As
 * a toggle it also fits in the header, which is what removed the fourth chrome row.
 *
 * The width is measured, not guessed: [FitToPhone] turns dp into columns with the same font metric
 * TerminalBox draws with, and `FitToPhoneTest` fails if those two files stop agreeing.
 *
 * The column count is read from the live configuration, so it is the one THIS screen has right now: a
 * phone in landscape holds nearly twice as many, and a number fixed at build time would be wrong in
 * one orientation while looking right in both.
 */
@Composable
private fun FitWidthToggle(
    boxWidthDp: Double,
    characterWidthMilliDp: Int,
    fit: FitState,
    onWhyDisabled: (Int?) -> Unit,
    onFitToPhone: (Int, Int, Boolean) -> Unit,
    onRestoreWindow: () -> Unit,
) {
    // **Both numbers are MEASURED and neither is turned into a column count here.**
    //
    // The box reports the width it was laid out at, after its own padding. The character is measured
    // from the very TextStyle TerminalBox draws with, so swapping the font changes the answer instead
    // of silently invalidating a constant written down elsewhere.
    //
    // They are SENT. The laptop answers with the count its terminal really rendered, and that answer
    // is what the description below shows. What this replaces: screen width minus a padding constant,
    // divided by 10.84 - Menlo's advance ratio at 18sp, never checked against this screen and left
    // behind by the step-down to 16. Two guesses in series, cached against, so every layout edit we
    // shipped discarded the calibration. Measured on the owner's phone the character is 9.78dp.
    // **The measurement moved up one level and nothing about it changed**. It is taken in
    // [AgtermScreen] now because the automatic re-apply needs the same number and computing it twice
    // would be two copies of one measurement, which is the shape ruled against.

    // **Why the control is off, in the owner's words, when it is off.**
    //
    // Today they pressed a dead button three times. It was disabled because the bridge could not
    // transmit "off" - see api.Response.FitEnabled - and NOTHING ANYWHERE SAID SO. Every refusal that
    // happens on the phone is invisible to the laptop's log, and those cost the most time.
    //
    // Costs nothing to ignore, one glance to read: a line under the control, only while it is
    // disabled, naming the specific reason rather than "unavailable".
    // **TWO conditions, and until 2026-08-26 they were one.**
    //
    // The comment below this used to say it correctly and the code did not honour it: *"nothing to
    // send until the box has measured itself once, and nothing to TOGGLE until the laptop has said
    // which way it currently is."* Two different preconditions, collapsed into one `enabled` that
    // `combinedClickable` applies to the tap and the hold alike.
    //
    // So the recalibration died with the toggle. **The escape hatch was disabled in precisely the
    // state it exists for**: something has gone wrong, replies are not landing, `_fit` is still at its
    // initial `enabled = null` — which is also where `release()` puts it — and the one control that
    // could break the deadlock is grey. The owner reached for it on 2026-08-25 with a stuck fit and
    // found nothing there.
    //
    // The hold needs NOTHING from the laptop's current answer. It does not toggle; it means "measure
    // it again anyway", and the only thing it requires is that this phone knows its own two numbers.
    val measured = boxWidthDp > 0 && characterWidthMilliDp > 0
    val canToggle = measured && fit.enabled != null

    // The line under the control. It named a reason the control was DEAD; now it names what is still
    // possible, because in the state it appears in something always is.
    val whyDisabled = when {
        !measured -> R.string.agterm_fit_why_unmeasured
        !canToggle -> R.string.agterm_fit_why_no_answer
        else -> null
    }

    onWhyDisabled(whyDisabled)

    // **A pill with the word on it, filled while it is on.** It was an icon and nothing else, which
    // is two guesses asked of the owner at once - what the glyph means, and whether it is currently
    // pressed. The design puts the word beside the glyph and fills the container when the fit is in
    // force, so both questions are answered by looking.
    val on = fit.enabled == true
    // **Live whenever the hold is possible**, not whenever the tap is. A control that looks dead but
    // answers a long press is a gesture nobody will ever find.
    val enabled = measured
    Surface(
        shape = PillShape,
        color = if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = when {
            on -> MaterialTheme.colorScheme.onSecondaryContainer
            enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> AppTheme.colors.onSurfaceSubtle
        },
        modifier = Modifier
            .height(40.dp)
            .testTag(TAG_FIT_TO_PHONE)
            // **A long press forces a recalibration.** Not a new gesture: this app already
            // teaches hold-to-do-the-deliberate-thing for rename and delete, and this is the same
            // shape - the tap is the ordinary thing, the hold says measure it again anyway.
            //
            // It is here rather than on Surface's onClick because Surface has no long press.
            .combinedClickable(
                // The HOLD's precondition, which is the weaker of the two. See `measured` above.
                enabled = enabled,
                onLongClickLabel = stringResource(R.string.agterm_fit_recalibrate_a11y),
                onLongClick = { onFitToPhone(boxWidthDp.toInt(), characterWidthMilliDp, true) },
                onClick = {
                    when {
                        !canToggle ->
                            // **The tap does the one thing that IS possible rather than nothing.**
                            // With no answer from the laptop there is no state to toggle between, so
                            // a tap that did nothing would be the dead button this project keeps
                            // refusing to ship. Asking again is well defined in every state, and the
                            // line under the control says that is what will happen.
                            onFitToPhone(boxWidthDp.toInt(), characterWidthMilliDp, true)
                        on -> onRestoreWindow()
                        else -> onFitToPhone(boxWidthDp.toInt(), characterWidthMilliDp, false)
                    }
                },
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painter = painterResource(if (on) AppIcons.FitWidthOn else AppIcons.FitWidthOff),
                // The count lives in the description, because the label cannot carry it and a screen
                // reader is the only place it can still be said.
                contentDescription = stringResource(
                    if (on) R.string.agterm_fit_undo_a11y else R.string.agterm_fit_a11y,
                    // The count the LAPTOP reported, never one computed here. Zero until it answered.
                    fit.columns,
                ),
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.agterm_fit_label),
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = TerminalFontFamily),
            )
        }
    }
}

/**
 * What the fit is doing right now, in one line under the header.
 *
 * **Everything here was observed.** The count is the one the laptop measured and sent back — never one
 * computed on this side, which is the guess three attempts at this feature died on. With the setting
 * off, the line describes the window rather than predicting how anything will wrap.
 *
 * Nothing is drawn before the laptop has answered: `enabled == null` means not answered, and the
 * disabled-reason line above already says so in those words.
 *
 * **Drawn ABOVE `TerminalBox`, outside the node it measures.** The design puts it inside the scrolling
 * content, which would slide it away when the owner pans — and, far worse, would put it inside the
 * width that is half the bridge's fit cache key.
 */
@Composable
private fun FitNote(fit: FitState) {
    val text = when {
        fit.enabled == null -> null
        fit.enabled == true && fit.columns > 0 ->
            stringResource(R.string.agterm_fit_note_on, fit.columns)
        // Fitted, but the count has not arrived on this reply. Saying "0 columns" would be a number
        // nobody measured; saying nothing about the count is the honest half of the same sentence.
        fit.enabled == true -> stringResource(R.string.agterm_fit_note_on_unmeasured)
        else -> stringResource(R.string.agterm_fit_note_off)
    } ?: return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = FitToPhone.TERMINAL_HORIZONTAL_PADDING_DP.dp,
                end = 12.dp,
                top = 4.dp,
                bottom = 6.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(if (fit.enabled == true) AppIcons.FitWidthOn else AppIcons.FitWidthOff),
            contentDescription = null,
            tint = AppTheme.colors.onSurfaceSubtle,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = AppTheme.colors.onSurfaceSubtle,
            modifier = Modifier.testTag(TAG_FIT_NOTE),
        )
    }
}

/**
 * The one note, floating over the top of the terminal.
 *
 * See [Notice] for what may appear here and what may not. In short: this surface carries what the app
 * WATCHED HAPPEN — the connection dropping and coming back, and the outcome of a keystroke — and
 * nothing about what any session is doing. The mock's second card is the agent's state and this app
 * cannot observe it.
 *
 * **One occupant, and the precedence lives in [noticeFor] rather than here.** Two sources feeding one
 * place is exactly where an ordering accident turns into a rule nobody wrote down.
 *
 * Overlaid in a [Box] rather than placed in the column, so it takes no width and no height from the
 * terminal. The fit cache key is the terminal's measured width; a note that pushed the box narrower
 * would silently invalidate every calibration the owner has.
 */
@Composable
private fun NoticeCard(notice: Notice?, onDismiss: () -> Unit) {
    if (notice == null) return
    val title: Int
    val body: Int
    val glyph: Int
    val container: Color
    val content: Color
    // **The one note whose body carries a number.** Null for every other, so the format call below is
    // the same expression in both cases rather than a second branch that could drift from the first.
    var bodyCount: Int? = null
    when (notice) {
        Notice.Reconnecting -> {
            title = R.string.agterm_link_reconnecting_title
            body = R.string.agterm_link_reconnecting_body
            glyph = AppIcons.Lan
            container = MaterialTheme.colorScheme.primaryContainer
            content = MaterialTheme.colorScheme.onPrimaryContainer
        }
        Notice.Reconnected -> {
            title = R.string.agterm_link_reconnected_title
            body = R.string.agterm_link_reconnected_body
            glyph = AppIcons.Lan
            container = AppTheme.colors.successContainer
            content = AppTheme.colors.onSuccessContainer
        }
        Notice.Sent -> {
            title = R.string.agterm_notice_sent_title
            body = R.string.agterm_typing_sent
            glyph = AppIcons.Schedule
            container = MaterialTheme.colorScheme.surfaceContainerHigh
            content = MaterialTheme.colorScheme.onSurfaceVariant
        }
        // **The recalibration reporting itself.** Neutral colours: it is news, not an alarm
        // and not a success worth a green card - the owner asked it to measure and it measured.
        //
        // The COUNT is the whole point. It is the one fact that says what the recalibration concluded,
        // and without it "measured again" is indistinguishable from "nothing happened" on a screen
        // where the terminal may not have moved at all.
        is Notice.Measured -> {
            title = R.string.agterm_notice_measured_title
            body = R.string.agterm_notice_measured_body
            bodyCount = notice.columns
            glyph = AppIcons.FitWidthOn
            container = MaterialTheme.colorScheme.surfaceContainerHigh
            content = MaterialTheme.colorScheme.onSurfaceVariant
        }
        // **Neutral, deliberately.** The bridge took the keystroke and the laptop showed nothing,
        // which is ordinary - a password prompt, a command still running. Moving this report onto a
        // card must not quietly promote it to an alarm, so it draws in the same colours as Sent and
        // emphatically not in the error ones.
        Notice.NoChange -> {
            title = R.string.agterm_notice_no_change_title
            body = R.string.agterm_typing_no_change
            glyph = AppIcons.CheckCircle
            container = MaterialTheme.colorScheme.surfaceContainerHigh
            content = MaterialTheme.colorScheme.onSurfaceVariant
        }
        // The keystroke failure, and one of three drawn as failures.
        Notice.Failed -> {
            title = R.string.agterm_notice_failed_title
            body = R.string.agterm_typing_failed
            glyph = AppIcons.Error
            container = MaterialTheme.colorScheme.errorContainer
            content = MaterialTheme.colorScheme.onErrorContainer
        }
        // **These two name the ACTION, because that is what the screen cannot show.** A create that
        // failed leaves the list exactly as it was, so silence is indistinguishable from a phone that
        // ignored the press - and the owner presses again. They are separate cases rather than one
        // "that did not work" because the owner pressed a specific button.
        Notice.CreateFailed -> {
            title = R.string.agterm_notice_create_failed_title
            body = R.string.agterm_notice_create_failed_body
            glyph = AppIcons.Error
            container = MaterialTheme.colorScheme.errorContainer
            content = MaterialTheme.colorScheme.onErrorContainer
        }
        Notice.RenameFailed -> {
            title = R.string.agterm_notice_rename_failed_title
            body = R.string.agterm_notice_rename_failed_body
            glyph = AppIcons.Error
            container = MaterialTheme.colorScheme.errorContainer
            content = MaterialTheme.colorScheme.onErrorContainer
        }
        // The row is still on screen because nothing here closes optimistically, so this note is what
        // tells them the press did not take rather than that the app ignored it.
        Notice.DeleteFailed -> {
            title = R.string.agterm_notice_delete_failed_title
            body = R.string.agterm_notice_delete_failed_body
            glyph = AppIcons.Error
            container = MaterialTheme.colorScheme.errorContainer
            content = MaterialTheme.colorScheme.onErrorContainer
        }
        // The icon is still on the left because nothing here moves it optimistically, so this note is
        // what tells him the tap did not take rather than that the app ignored it.
        Notice.PaneFailed -> {
            title = R.string.agterm_notice_pane_failed_title
            body = R.string.agterm_notice_pane_failed_body
            glyph = AppIcons.Error
            container = MaterialTheme.colorScheme.errorContainer
            content = MaterialTheme.colorScheme.onErrorContainer
        }
        // **One line, where the other fit notes live**. This used to replace the whole
        // screen with the bridge's own sentence about probe widths.
        Notice.FitRefused -> {
            title = R.string.agterm_notice_fit_refused_title
            body = R.string.agterm_notice_fit_refused_body
            glyph = AppIcons.Error
            container = MaterialTheme.colorScheme.errorContainer
            content = MaterialTheme.colorScheme.onErrorContainer
        }
        // **Not an error, and drawn like one would be a lie**. Nothing went wrong: this
        // session's shape has simply never been measured, and he is being invited to press.
        Notice.NeedsFit -> {
            title = R.string.agterm_notice_needs_fit_title
            body = R.string.agterm_notice_needs_fit_body
            glyph = AppIcons.Error
            container = MaterialTheme.colorScheme.surfaceVariant
            content = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .background(container, NoteShape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag(TAG_NOTICE),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(glyph),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.labelLarge,
                color = content,
            )
            Text(
                text = bodyCount?.let { stringResource(body, it) } ?: stringResource(body),
                style = MaterialTheme.typography.bodySmall,
                color = content.copy(alpha = 0.75f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(28.dp).testTag(TAG_NOTICE_DISMISS),
        ) {
            Icon(
                painter = painterResource(AppIcons.Close),
                contentDescription = stringResource(R.string.agterm_link_dismiss),
                tint = content.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * That something is connected, and to what.
 *
 * **The address, never a name.** The mock says `MacBook-Pro-Igor`; the phone holds no name for the
 * laptop, only the host it dials — see the [AgtermScreen] parameter. Drawing a name would be
 * inventing a fact about the owner's machine, which is the rule [copyFor] has always held.
 *
 * The dot is green because this composable is only reached from [AgtermUiState.Sessions] — the laptop
 * answered, and that is an observation rather than an assumption. Every other state draws its own
 * screen instead of this row.
 */
@Composable
private fun ConnectionRow(laptop: String, sessions: Int, onDisconnect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(AppTheme.colors.success, CircleShape)
                .testTag(TAG_CONNECTED_DOT),
        )
        Text(
            // Monospace, like the workspace and session names: this is a machine address.
            //
            // Two strings rather than one with the address left empty. Formatting a blank in and
            // trimming the separator off afterwards is copy assembled by cutting punctuation off
            // other copy, and it breaks the moment a translator moves the separator.
            text = if (laptop.isEmpty()) {
                pluralStringResource(R.plurals.agterm_connected_count, sessions, sessions)
            } else {
                pluralStringResource(R.plurals.agterm_connected, sessions, laptop, sessions)
            },
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = TerminalFontFamily),
            color = AppTheme.colors.onSurfaceSubtle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag(TAG_CONNECTED_TO),
        )
        // **A button that looks like one.** This was a line of blue text pretending to be a control,
        // which is the same complaint as the Fit toggle being an icon nobody could read.
        OutlinedButton(
            onClick = onDisconnect,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.heightIn(min = 36.dp).testTag(TAG_DISCONNECT),
        ) {
            Text(
                text = stringResource(R.string.agterm_action_disconnect),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * The session list: one card per workspace, folding shut on a tap.
 *
 * ### One LazyColumn item per key, and that is load-bearing
 *
 * The design draws a card containing a header and its rows, which would naturally be ONE item. It is
 * not built that way, because the owner's reading position is remembered per ROW — see [ListPosition]
 * — and a card-per-item would coarsen that to per-workspace. So the card is drawn by the rows
 * themselves: the header carries the top corners, the last session carries the bottom ones, and the
 * gap between cards is padding outside the background rather than a spacer item.
 *
 * **Every item's key comes from `ListPosition.listKeys`, and nothing else is ever added to this
 * list.** That was not true before this screen was redrawn: an unkeyed Disconnect row sat at index 0
 * while the keys began at the first heading, so every index derived here was one out — the anchor
 * recorded the row below the one at the top, and restoring put them one row above it. Moving that row
 * out of the list, which the design asks for anyway, removes the whole class of mistake rather than
 * correcting the offset.
 */
@Composable
private fun SessionList(
    groups: List<WorkspaceGroup>,
    onOpen: (BridgeSession) -> Unit,
    onRefresh: () -> Unit,
    listAnchor: ListPosition.Anchor?,
    onListAnchor: (ListPosition.Anchor?) -> Unit,
    closed: Set<String>,
    onClosed: (Set<String>) -> Unit,
    onCreateSession: (String) -> Unit,
    onBeginRename: (RenameTarget) -> Unit,
) {
    if (groups.isEmpty()) {
        Failure(R.string.agterm_empty, onRefresh, R.string.agterm_action_retry)
        return
    }
    // The rows on screen, and separately the LISTING - the same function, called twice. See
    // ListPosition: the listing is what the restore is keyed on precisely because folding a group
    // does not change it.
    val keys = ListPosition.listKeys(groups, closed)
    val listing = ListPosition.listKeys(groups, emptySet())
    val listState = rememberLazyListState()

    // **Put them back where they were, once, when the LISTING appears or changes shape.** A listing
    // that changed is a different list to restore into, and re-running the lookup is how a row that
    // moved is followed rather than lost.
    //
    // **Keyed on the listing and never on the visible rows.** Collapsing changes what is on screen, so
    // keying this on `keys` would re-run the restore on every fold and scroll the list under the
    // owner's thumb - a control that fights the memory beside it.
    LaunchedEffect(listing) {
        val target = ListPosition.restore(listAnchor, groups, closed) ?: return@LaunchedEffect
        listState.scrollToItem(target.index, target.offset)
    }

    // And remember where they are, continuously, by KEY. Read inside snapshotFlow rather than
    // captured - the frozen-value defect this project met four times in one evening.
    LaunchedEffect(listState, keys) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                val key = keys.getOrNull(index)
                onListAnchor(if (key == null) null else ListPosition.Anchor(key, offset))
            }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag(TAG_LIST),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        // **The hierarchy agterm already holds, shown rather than flattened.** Grouped by workspace
        // IDENTITY - see groupByWorkspace for why not by name and emphatically not by adjacency - and
        // in agterm's own order at both levels. Recomputed from this listing every time rather than
        // remembered, so a tree that changes shape is followed rather than cached.
        for (group in groups) {
            val open = group.id !in closed
            item(key = ListPosition.headingKey(group.id)) {
                WorkspaceHeader(
                    group = group,
                    open = open,
                    closed = closed,
                    onToggle = { onClosed(CollapsedWorkspaces.toggle(closed, group.id)) },
                    onNewSession = { onCreateSession(group.id) },
                    onRename = { onBeginRename(RenameTarget.Workspace(group.id)) },
                )
            }
            if (!open) continue
            itemsIndexed(group.sessions, key = { _, session -> session.id }) { index, session ->
                SessionRow(
                    session = session,
                    last = index == group.sessions.lastIndex,
                    onOpen = { onOpen(session) },
                    onRename = { onBeginRename(RenameTarget.Session(session.id)) },
                )
            }
        }
    }
}

/**
 * The one rename dialog, reached four ways: after creating either kind, and by long-pressing either.
 *
 * ### What it does when the name is wrong
 *
 * **It stays open, with what they typed still in it**, and says the SHAPE of the problem. A dialog
 * that closed and reported the failure somewhere else would lose their text and make them retype it
 * to find out what was wrong with it.
 *
 * The check is [labelProblem], which is the phone's copy of the bridge's `keys.Label`. This is not the
 * security boundary — the bridge is, and it checks again — this is so the owner learns before a round
 * trip to their laptop.
 *
 * **No message contains the name.** It is in the field in front of them; the copy names the shape and
 * nothing else, which is the same rule that keeps names out of every log and every error here.
 *
 * ### Cancelling undoes nothing
 *
 * A create that vanished when its dialog was dismissed would be worse than one with a dull name, so
 * cancel leaves whatever agterm called it.
 */
@Composable
private fun RenameDialog(
    request: RenameRequest,
    onConfirm: (RenameTarget, String) -> Unit,
    onDelete: (RenameTarget) -> Unit,
    onCancel: () -> Unit,
) {
    // Seeded from the request and keyed on it, so opening the dialog on a DIFFERENT thing resets the
    // field. Keyed on the target rather than the name: a rename that failed and is retried is the same
    // subject, and wiping their second attempt back to the old name would be the same defect as
    // closing on them.
    var draft by remember(request.target) { mutableStateOf(request.currentName) }
    val problem = labelProblem(draft)

    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag(TAG_RENAME_DIALOG),
        title = { Text(stringResource(R.string.agterm_rename_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    isError = problem != null,
                    label = { Text(stringResource(R.string.agterm_rename_field)) },
                    // Monospace, like every other name on this screen: these are terminal session and
                    // workspace labels, and a proportional font here would be the app restyling them.
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = TerminalFontFamily),
                    modifier = Modifier.fillMaxWidth().testTag(TAG_RENAME_FIELD),
                )
                // Shown only once there is something to say. An error under an untouched field greets
                // the owner by telling them off for a name they have not written yet - and the field
                // opens holding a valid one.
                if (problem != null && draft.isNotEmpty()) {
                    Text(
                        text = when (problem) {
                            LabelProblem.Empty -> stringResource(R.string.agterm_rename_empty)
                            LabelProblem.TooLong ->
                                stringResource(R.string.agterm_rename_too_long, MaxLabelRunes)
                            LabelProblem.ControlCharacter -> stringResource(R.string.agterm_rename_control)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                // **Delete lives here, and this is the owner's own design.**
                //
                // A long press, then this press: the two deliberate acts ARE the confirmation, so there
                // is no confirmation dialog anywhere in this feature.
                //
                // It is drawn INSIDE the body rather than beside Rename in the button row, and that is
                // deliberate: the destructive action does not sit where a confirm button sits, and it
                // is not the default. See RenameOrigin for why it is absent after a create.
                if (request.mayDelete) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))
                    TextButton(
                        onClick = { onDelete(request.target) },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.testTag(TAG_RENAME_DELETE),
                    ) {
                        // **The count is the whole point of this copy.** agterm takes a workspace's
                        // sessions with it silently - measured - and the list is behind this modal, so
                        // this is the only place the owner can learn what the press costs.
                        Text(
                            // **The zero case is decided in Kotlin, not by the plural.** English has
                            // only `one` and `other`, so a `zero` item is never selected and a count
                            // of nothing would read "and its 0 sessions". See deleteCopyFor.
                            text = when (val copy = deleteCopyFor(request.target, request.sessionCount)) {
                                DeleteCopy.EmptyWorkspace ->
                                    stringResource(R.string.agterm_delete_workspace_empty)
                                is DeleteCopy.WorkspaceWithSessions -> pluralStringResource(
                                    R.plurals.agterm_delete_workspace,
                                    copy.count,
                                    copy.count,
                                )
                                DeleteCopy.Session -> stringResource(R.string.agterm_delete_session)
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                // Disabled rather than failing on press: a button that can only report a refusal is a
                // control that teaches the owner not to trust the others.
                enabled = problem == null,
                onClick = { onConfirm(request.target, draft) },
                modifier = Modifier.testTag(TAG_RENAME_CONFIRM),
            ) {
                Text(stringResource(R.string.agterm_rename_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.agterm_rename_cancel)) }
        },
    )
}

/**
 * A workspace's header: the chevron, the name, the attention dot, the count.
 *
 * The whole row is the control. A chevron small enough to look right is too small to hit, and a header
 * that is only partly tappable teaches the owner to aim.
 */
@Composable
private fun WorkspaceHeader(
    group: WorkspaceGroup,
    open: Boolean,
    closed: Set<String>,
    onToggle: () -> Unit,
    onNewSession: () -> Unit,
    onRename: () -> Unit,
) {
    // Read here rather than inside the semantics blocks below: those lambdas are not composable, and
    // a label fetched outside one is a label that exists when the block runs.
    val attentionLabel = stringResource(R.string.agterm_group_attention)
    val countLabel = stringResource(R.string.agterm_group_count_a11y, group.sessions.size)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            // A folded group is the whole card, so it is rounded on all four corners and carries the
            // gap to the next one. An open group's card continues into its rows.
            .padding(bottom = if (open) 0.dp else 12.dp)
            .clip(if (open) CardTopShape else CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            // The design lifts an OPEN header a shade above the card it sits on. `#252118` is not in
            // the palette and its nearest role is; four near-duplicate hexes
            // did not join ui/theme.
            .then(if (open) Modifier.background(MaterialTheme.colorScheme.surfaceContainer) else Modifier)
            // **Long press renames, tap folds.** One dialog reached from four places rather than a
            // rename flow of its own - see RenameRequest.
            .combinedClickable(
                onClickLabel = stringResource(
                    if (open) R.string.agterm_workspace_collapse else R.string.agterm_workspace_expand,
                ),
                onLongClickLabel = stringResource(R.string.agterm_rename_workspace_a11y),
                onLongClick = onRename,
                onClick = onToggle,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(TAG_WORKSPACE_HEADING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(AppIcons.ExpandMore),
            // The row already says what it does, through onClickLabel. A description here would have
            // a screen reader announce the arrow as well as the action.
            contentDescription = null,
            tint = AppTheme.colors.onSurfaceSubtle,
            modifier = Modifier.size(22.dp).rotate(if (open) 0f else -90f),
        )
        Text(
            // A workspace with no name is still a workspace, so it gets OUR label rather than an
            // empty heading or something pretending to be a name. A session is never dropped because
            // its heading was hard to write.
            text = group.name.ifEmpty { stringResource(R.string.agterm_workspace_unnamed) },
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = TerminalFontFamily),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Whether this is drawn at all is showsAttention's decision, not this file's - a rule inside a
        // composable can only be checked by running a UI test on a device.
        if (showsAttention(group, closed)) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .semantics { contentDescription = attentionLabel }
                    .testTag(TAG_GROUP_ATTENTION),
            )
        }
        // **A folded group still counts its sessions.** Folding hides rows, never facts - the same
        // rule that stops groupByWorkspace ever dropping one.
        Text(
            text = group.sessions.size.toString(),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = TerminalFontFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, BadgeShape)
                .padding(horizontal = 9.dp, vertical = 3.dp)
                .semantics { contentDescription = countLabel }
                .testTag(TAG_GROUP_COUNT),
        )
        // **Its own clickable, and that is the whole point.** The header row is a control already, so
        // a `+` that did not take its own tap would fold the group instead of creating anything — the
        // Switch-inside-toggleable defect, and the owner would find it in one press.
        //
        // `IconButton` installs its own clickable, which consumes the tap rather than letting it reach
        // the row behind. Held by a unit test rather than by this paragraph.
        IconButton(
            onClick = onNewSession,
            modifier = Modifier.size(28.dp).testTag(TAG_NEW_SESSION),
        ) {
            Icon(
                painter = painterResource(AppIcons.Add),
                contentDescription = stringResource(R.string.agterm_new_session_a11y),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** One session: what it is doing, what it is called, and what it is doing it to. */
@Composable
private fun SessionRow(session: BridgeSession, last: Boolean, onOpen: () -> Unit, onRename: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = if (last) 12.dp else 0.dp)
            .clip(if (last) CardBottomShape else RectangleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = 8.dp, end = 8.dp, bottom = if (last) 8.dp else 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RowShape)
                // Tap opens, long press renames — the same pair as the workspace header, so the
                // gesture means one thing everywhere on this list.
                .combinedClickable(
                    onLongClickLabel = stringResource(R.string.agterm_rename_session_a11y),
                    onLongClick = onRename,
                    onClick = onOpen,
                )
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusDot(session.status)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // Monospace here too: these are terminal session names, and a proportional font
                    // would be the first place the app started restyling the owner's terminal.
                    text = session.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = TerminalFontFamily),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The workspace name is the heading now, so repeating it here would say the same
                // thing twice on every row. A session with no title simply has no second line.
                if (session.title.isNotEmpty()) {
                    Text(
                        text = session.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.onSurfaceSubtle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * What agterm says the session is doing, as one dot.
 *
 * **Exhaustive with no `else`**, like [copyFor]: a fifth state must stop the build rather than fall
 * through to whichever glyph the default happens to name.
 *
 * [SessionStatus.Idle] is a plain circle drawn with a Box rather than a glyph, and that is the point
 * of it — there is no icon for "nothing is happening", and any glyph put there would assert something.
 */
@Composable
private fun StatusDot(status: SessionStatus) {
    val label = stringResource(
        when (status) {
            SessionStatus.Running -> R.string.agterm_status_running
            SessionStatus.NeedsYou -> R.string.agterm_status_needs_you
            SessionStatus.Done -> R.string.agterm_status_done
            SessionStatus.Idle -> R.string.agterm_status_idle
        },
    )
    val glyph = when (status) {
        SessionStatus.Running -> AppIcons.PlayArrow
        SessionStatus.NeedsYou -> AppIcons.FrontHand
        SessionStatus.Done -> AppIcons.CheckCircle
        SessionStatus.Idle -> null
    }
    val tint = when (status) {
        SessionStatus.Running, SessionStatus.Done -> AppTheme.colors.success
        SessionStatus.NeedsYou -> MaterialTheme.colorScheme.primary
        SessionStatus.Idle -> AppTheme.colors.onSurfaceSubtle
    }
    // The same 16dp box either way, so the name beside it sits on the same line whatever the state -
    // a row that shifts by three pixels when a session finishes is a list that shimmers while it polls.
    Box(
        modifier = Modifier.padding(top = 3.dp).size(16.dp).testTag(TAG_STATUS_DOT),
        contentAlignment = Alignment.Center,
    ) {
        if (glyph == null) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(tint, CircleShape)
                    .semantics { contentDescription = label },
            )
        } else {
            Icon(painter = painterResource(glyph), contentDescription = label, tint = tint)
        }
    }
}

@Composable
private fun Loading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(modifier = Modifier.padding(32.dp))
    }
}

@Composable
private fun Failure(message: Int, onAction: () -> Unit, action: Int) =
    Failure(stringResource(message), onAction, action)

/**
 * The same screen, given words rather than a resource.
 *
 * **For text that came from the BRIDGE**, which writes its refusals to be read by the person at the
 * keyboard. Everything this app says on its own behalf is still a string resource; this overload
 * exists so a reason the laptop supplied can be shown instead of a sentence that describes something
 * else.
 */
@Composable
private fun Failure(message: String, onAction: () -> Unit, action: Int, detail: String = "") {
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag(TAG_FAILURE),
        )
        Button(
            onClick = onAction,
            modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
        ) {
            Text(stringResource(action))
        }
        // **Findable, and not the message.** The far end's own sentence, below the button, in the
        // smallest type on the screen and a colour that recedes. When the owner reports a problem
        // this is what they read out; when they just want their session it is not what they are made
        // to read. The bug this answers had it as the entire screen.
        if (detail.isNotEmpty()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp).testTag(TAG_FAILURE_DETAIL),
            )
        }
    }
}

/**
 * The card corners, in one place.
 *
 * A workspace's card is drawn by its rows rather than by a container - see [SessionList] - so the
 * radius appears on the header and on the last session. Two literals in two files is how a corner
 * ends up 24 on one row and 20 on the next.
 */
private val CardShape = RoundedCornerShape(24.dp)
private val PillShape = RoundedCornerShape(20.dp)
private val NoteShape = RoundedCornerShape(18.dp)
private val CardTopShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
private val CardBottomShape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
private val RowShape = RoundedCornerShape(16.dp)
private val BadgeShape = RoundedCornerShape(9.dp)

internal const val TAG_DISCONNECT = "agterm_disconnect"
internal const val TAG_BACK = "agterm_back"

/** The way into Settings, in the slot the back arrow occupies inside a session. */
const val TAG_OPEN_SETTINGS = "agterm_open_settings"
internal const val TAG_LIST = "agterm_list"
/** The workspace heading rows, so a test can count the structure without reading any name. */
internal const val TAG_WORKSPACE_HEADING = "agterm_workspace_heading"
/** The control that folds every workspace shut, and opens them again. */
internal const val TAG_COLLAPSE_ALL = "agterm_collapse_all"
internal const val TAG_NEW_WORKSPACE = "agterm_new_workspace"
internal const val TAG_NEW_SESSION = "agterm_new_session"
internal const val TAG_RENAME_DIALOG = "agterm_rename_dialog"
internal const val TAG_RENAME_FIELD = "agterm_rename_field"
internal const val TAG_RENAME_CONFIRM = "agterm_rename_confirm"
internal const val TAG_RENAME_DELETE = "agterm_rename_delete"

/** Pull-to-refresh, on the session list only — never on the open session. */
internal const val TAG_LIST_PULL_REFRESH = "agterm_list_pull_refresh"
/** A group's session count, which a folded group still shows. */
internal const val TAG_GROUP_COUNT = "agterm_group_count"
/** The dot on a folded group holding a session that is waiting on the owner. */
internal const val TAG_GROUP_ATTENTION = "agterm_group_attention"
/** One session's state dot. */
internal const val TAG_STATUS_DOT = "agterm_status_dot"
/** The connection row's live dot and its address line. */
internal const val TAG_CONNECTED_DOT = "agterm_connected_dot"
internal const val TAG_CONNECTED_TO = "agterm_connected_to"
/** The one-line note under the header saying what the fit is doing. */
/**
 * Which pane the phone is showing, and the control that changes it.
 *
 * ### It is agterm's own picture, and that is the whole design
 *
 * A rounded window with one half solid and the other hollow. **The owner recognises it from his
 * laptop**, where agterm draws the same small pane indicator with the active half lit, and the phone
 * showing him that picture is most of the value.
 * Measured off his screenshot rather than approximated; see the drawables' headers.
 *
 * ### What it replaced, and why that was wrong
 *
 * A segmented `[ Left | Right ]` pair, 116dp of the header. The complaint was not the words but the
 * size: it covered the session name.
 *
 * The pair was chosen over a pictogram on a prediction that a small glyph would not read — reasoned
 * from him having rejected a low-contrast armed state days earlier. **The inference was right about
 * him and wrong about the drawing.** agterm's version is solid against hollow, which is presence
 * against absence, not two shades — the same property he asked for when he asked for a checkmark. It
 * reads at this size precisely because it is not a shading difference. Nobody looked at what agterm
 * already did before choosing.
 *
 * ### An icon control, sized like its neighbours
 *
 * An `IconToggleButton` like the keyboard control beside it, so the row is icons and one pill rather
 * than two pills and an icon. **48dp against the pair's 116dp: the session name gets 68dp back**, and
 * is now 48dp worse off than before this feature existed rather than 116dp.
 *
 * ### Never absent
 *
 * It was composed only for a session that already had a second pane. Two rulings moved it: the first
 * corrected WHICH condition that was, and then the owner removed the condition altogether by
 * specifying that tapping right creates the pane when there is none.
 *
 * **Absent-not-disabled is untouched.** That ruling is about controls which can do nothing. This one
 * can always do something, in both directions, on every session — so there is no state in which it
 * would be decoration, and no third picture to draw.
 */
@Composable
private fun PaneToggle(
    shown: Pane,
    onTogglePane: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconToggleButton(
        // Checked means the right pane, arbitrarily but consistently - what a screen reader announces
        // comes from the description below, which names the pane rather than a checkbox state.
        checked = shown == Pane.Right,
        // **The requested pane is not passed up, and that is the point.** This used to send
        // `if (it) Right else Left`, which made the press an instruction. It is a request now: going
        // right may have to create a pane first, and [shown] moves only once the laptop says one is
        // there. A control that set its own state here would light before the pane existed.
        onCheckedChange = { onTogglePane() },
        modifier = modifier.testTag(TAG_PANE_TOGGLE),
    ) {
        Icon(
            painter = painterResource(
                if (shown == Pane.Left) AppIcons.PaneLeft else AppIcons.PaneRight,
            ),
            // **Says which pane it IS showing and what pressing does**, because the glyph carries the
            // state and a screen reader cannot see it. Naming only the destination would be the
            // chevron ambiguity again, in the one place nobody would notice it.
            contentDescription = stringResource(
                if (shown == Pane.Left) R.string.agterm_pane_left_a11y else R.string.agterm_pane_right_a11y,
            ),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The pane toggle. **Always composed**, on every session — the instrumented
 * test that asserted its absence is now the test that asserts it is there whatever the session holds.
 *
 * One tag, because the control is one icon now. The segmented pair it replaced had a tag per half.
 */
const val TAG_PANE_TOGGLE = "agterm_pane_toggle"
internal const val TAG_FIT_NOTE = "agterm_fit_note"
/** The one note over the terminal, and the control that puts it away. */
internal const val TAG_NOTICE = "agterm_notice"
internal const val TAG_NOTICE_DISMISS = "agterm_notice_dismiss"
/** The workspace half of an open session's header row. */
internal const val TAG_HEADER_WORKSPACE = "agterm_header_workspace"
internal const val TAG_BOX = "agterm_box"
const val TAG_FIT_DISABLED_REASON = "agterm_fit_disabled_reason"
const val TAG_FIT_TO_PHONE = "agterm_fit_to_phone"
const val TAG_RESTORE_WINDOW = "agterm_restore_window"
internal const val TAG_FAILURE = "agterm_failure"
internal const val TAG_FAILURE_DETAIL = "agterm_failure_detail"

/// The terminal's text itself, inside the scrollers and inside the selection container. Tagged so a
/// test can long-press the TEXT rather than the box around it — the box is what scrolls, the text is
/// what a finger selects, and hitting the wrong one would be a test that passes while measuring the
/// viewport.
internal const val TAG_TERMINAL_TEXT = "agterm_terminal_text"


/**
 * Waits until the scroll's maximum stops moving, or gives up.
 *
 * **A maximum measured in the gap between two relayouts is one that is about to change**, and scrolling
 * to it lands NEAR the bottom rather than at it - the bug looking half-fixed. Extracted when the PgUp
 * landing needed the same wait the keyboard path already did: two copies of this loop would drift, and
 * the drift would be invisible because both versions still scroll roughly to the end.
 */
private suspend fun awaitStableMax(scroll: ScrollState) {
    var last = -1
    var stable = 0
    var frames = 0
    while (frames < TerminalScroll.SETTLE_FRAME_LIMIT && stable < TerminalScroll.SETTLE_STABLE_FRAMES) {
        withFrameNanos { }
        val max = scroll.maxValue
        stable = if (max == last) stable + 1 else 0
        last = max
        frames++
    }
}

/**
 * How long a PgUp landing stays armed.
 *
 * Long enough for the laptop to redraw and the next poll to carry it; short enough that a pageup which
 * changed nothing cannot have some later, unrelated screen collected against it. A jump with no gesture
 * behind it is worse than no jump at all.
 */
private const val PAGE_UP_LANDING_WINDOW_MS = 3000L
