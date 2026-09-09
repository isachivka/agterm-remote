package dev.isachivka.bewareofsugar.agterm

import dev.isachivka.bewareofsugar.wire.WireException
import dev.isachivka.bewareofsugar.pairing.SigningState
import dev.isachivka.bewareofsugar.wire.WireFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * What the screen is currently showing.
 *
 * A sealed type rather than a bag of nullable fields, so "listed but also failed" cannot be
 * represented — the screen has to be in exactly one of these.
 */
/**
 * What a rename is about to act on.
 *
 * **A pair of distinct types rather than an id plus a boolean**, because "rename this id, and it is a
 * workspace" has a fourth state — an id of one kind with the flag of the other — that means renaming
 * something nobody chose. Two cases cannot be crossed, and the `when` that dispatches them is
 * exhaustive.
 */
sealed interface RenameTarget {
    data class Workspace(val id: String) : RenameTarget
    data class Session(val id: String) : RenameTarget
}

/**
 * The command the Claude button runs, REQ-0013.
 *
 * The owner asked for it by name: *"когда я нажимаю на эту кнопку - вводится команда claude_yolo и
 * нажимается enter"*. It is **their own shell alias** — this app does not know what it does, does not
 * check it, and does not invent variations of it. On 2026-09-05 the alias changed and so did this:
 * *"кнопка claude_yolo → её нужно переделать в просто claude, так как я сделал другой алиас"*.
 *
 * A named constant rather than a literal in a composable, so the one thing this button actually sends
 * is readable without opening a UI file, and so it cannot be quietly edited into something else while
 * looking like layout.
 */
const val CLAUDE_COMMAND = "claude"

/**
 * The Return that follows it.
 *
 * Named because it is a key from the bridge's closed allowlist rather than free text — `keys.Key`
 * refuses anything outside that map — and because `"enter"` appearing twice, once here and once in
 * the key rows, is two places that could drift.
 */
const val ENTER_KEY = "enter"

/**
 * The pause between a draft landing and the Return that follows it — REQ-0046, the owner's *"с
 * небольшой задержкой между событиями"*. Long enough for the far end to have taken the text before the
 * key arrives; short enough that the pair still reads as one press.
 */
const val ENTER_AFTER_TEXT_MS = 150L

/** How long after the last edit a draft is written to disk. Short, because the whole point is a pocket. */
const val DRAFT_WRITE_DELAY_MS = 300L

/**
 * How the rename modal was opened, which decides whether it may also destroy.
 *
 * ### Why this is a case and not a `deletable: Boolean`
 *
 * The owner's design: *"чтобы удалить нужно будет сделать длинное нажатие и затем нажать удалить - мы
 * тогда confirmation не нужен"*. **The two deliberate acts ARE the confirmation**, which is why there
 * is no confirm dialog anywhere in this feature.
 *
 * But the modal has four entry points and only two are long presses — creating a workspace or a
 * session also opens it, so the owner can name what they just made. **On that path the deliberate
 * gesture never happened**: one tap of `+`, then a mis-tap where Delete sits, and something is
 * destroyed by two ordinary presses. So Delete is offered for [LongPress] and withheld for [Created],
 * and the cost of withholding it is nil — the thing just made can be deleted a second later by
 * long-pressing it, which is the gesture they designed.
 *
 * A boolean would let "opened by a create" and "may be deleted" drift apart the first time somebody
 * set one without the other. Two cases cannot.
 */
enum class RenameOrigin { Created, LongPress }

/**
 * The rename modal's subject: what is being renamed, what it is called now, and how it was opened.
 *
 * [currentName] is what the modal opens on, and it is **read from the listing** rather than from the
 * reply to a create — agterm's creates answer with an id and nothing else. One source, so the modal
 * and the row behind it cannot disagree.
 *
 * [sessionCount] is how many sessions a workspace holds, and it exists for one reason: **agterm takes
 * them all, silently.** Measured 2026-07-31 — deleting a workspace with two sessions in it removed
 * both and answered ok, with no warning of any kind. The list is behind the modal, so this is the
 * only place the owner can learn what a delete costs. Zero for a session, which has no sessions inside
 * it.
 */
data class RenameRequest(
    val target: RenameTarget,
    val currentName: String,
    val origin: RenameOrigin,
    val sessionCount: Int = 0,
) {
    /** Delete is offered only when a long press opened this. See [RenameOrigin]. */
    val mayDelete: Boolean get() = origin == RenameOrigin.LongPress
}

/**
 * Which sentence the Delete button says.
 *
 * ### Why this is a function and not a `pluralStringResource` call
 *
 * The plural had an `<item quantity="zero">` reading *"Delete this empty workspace"*, and **English
 * never selects it.** CLDR gives English exactly two plural categories, `one` and `other`, so a count
 * of zero falls through to `other` and the button reads *"Delete workspace and its 0 sessions"* — the
 * exact string the resource's own comment called a bug.
 *
 * That case is reachable in one gesture: the owner closes the last session in a workspace, then
 * long-presses its header. It is also where a half-created workspace lands, since REQ-0012 keeps one
 * whose first session failed.
 *
 * **A resource that is silently never selected is the kind of green nothing checks** — lint raised
 * neither `UnusedQuantity` nor `ImpliedQuantity` for it. So the zero case is decided here, in Kotlin,
 * where a test can pin it, and the plural is left to do only what a plural can do.
 */
sealed interface DeleteCopy {

    /** A workspace with nothing in it. Its own sentence, because a plural cannot say this in English. */
    data object EmptyWorkspace : DeleteCopy

    /** A workspace and the [count] sessions that go with it — one or more, never zero. */
    data class WorkspaceWithSessions(val count: Int) : DeleteCopy

    /** A session, which takes nothing with it. */
    data object Session : DeleteCopy
}

/** See [DeleteCopy]. Pure, so the zero case is a test rather than a screenshot. */
fun deleteCopyFor(target: RenameTarget, sessionCount: Int): DeleteCopy = when (target) {
    is RenameTarget.Session -> DeleteCopy.Session
    is RenameTarget.Workspace ->
        if (sessionCount <= 0) DeleteCopy.EmptyWorkspace
        else DeleteCopy.WorkspaceWithSessions(sessionCount)
}

sealed interface AgtermUiState {

    /** No laptop has been paired. The remedy is pairing, not retrying, so the screen says so. */
    data object NotPaired : AgtermUiState

    data object Loading : AgtermUiState

    /**
     * The owner disconnected on purpose.
     *
     * Distinct from every failure: nothing went wrong, and the copy must not apologise for something
     * they asked for. It exists because the owner asked for a control rather than only a timer - a
     * bound they cannot see is not agency.
     */
    data object Disconnected : AgtermUiState

    /** The list. [watching] is the session whose screen is open, if any. */
    data class Sessions(
        val sessions: List<BridgeSession>,
        /**
         * agterm's own workspace list, or null when the bridge did not publish one.
         *
         * Carried so an EMPTY workspace has a heading — the grouping cannot invent one from sessions
         * that do not exist. See [BridgeWorkspace].
         */
        val workspaces: List<BridgeWorkspace>? = null,
        val watching: BridgeSession? = null,
        val screen: String = "",
        /** [screen] carries SGR sequences and wants [Sgr] before it is drawn. See [ScreenText.Text.styled]. */
        val screenStyled: Boolean = false,
        val stale: Boolean = false,
        /**
         * A listing is being fetched while this list is on screen — the pull-to-refresh spinner.
         *
         * **Part of the state rather than a boolean beside it**, because the type could not otherwise
         * say *a list is showing AND a fetch is in flight*, and that is exactly the fact the indicator
         * shows. `HomeScreen` learned this the expensive way: *"a second boolean would be a second
         * source of truth for one fact - they would disagree the first time a round was cancelled, and
         * the indicator would spin over a screen that had stopped."*
         *
         * **[refreshNow] is its only writer.** Set as a fetch starts, cleared by the reply that
         * replaces the list, and cleared again on the way out of a failure — so nothing else can
         * disagree with it and no spinner can outlive the fetch it describes.
         */
        val refreshing: Boolean = false,
    ) : AgtermUiState

    /** A transport failure the copy branches on. */
    data class Failed(val failure: WireFailure) : AgtermUiState

    /**
     * The laptop answered and refused.
     *
     * Its own state rather than a [WireFailure], because it is the one failure where the machine was
     * definitely reached — and the copy for "not answering" would be false about it.
     */
    data class Refused(
        /**
         * The bridge's own words, or empty when it gave none.
         *
         * **Shown to the owner.** It is written to be read by the person at the keyboard - the
         * impossible-fit refusal spells out its arithmetic, and a resize that cannot proceed says
         * which measurement was missing. Rendering every refusal as "could not list your sessions"
         * described nothing that happened: on 2026-07-31 the owner pressed fit, the laptop answered,
         * the failure had nothing to do with listing, and the screen said it did.
         *
         * This is the bridge's text, not a diagnosis this app invented - the same rule as everywhere
         * else here. The app still cannot say WHY agterm did something; it can quote the bridge.
         */
        val reason: String = "",
        /**
         * The FAR END's own words, when [reason] is a sentence the bridge wrote instead.
         *
         * Shown small, under the button, and never as the message. On 2026-08-12 the owner's whole
         * Terminal screen read `failed to read surface buffer` — agterm describing its internals,
         * relayed verbatim, with nothing else on the screen. Deleting that string entirely would be
         * the opposite mistake: it is the fastest route to what happened when they report a problem.
         *
         * So: our sentence is the message, this is the evidence, and the two are not the same size on
         * screen. Empty whenever [reason] is already the far end's own words.
         */
        val detail: String = "",
    ) : AgtermUiState
}

/**
 * Whether the session LIST is what the owner is looking at right now.
 *
 * ### Why this exists, and why it is a predicate rather than a call site
 *
 * The list used to refresh exactly once per ViewModel — `LaunchedEffect(model)` in `AgtermHost` — so
 * the owner's report was precisely right: *"список сессий сейчас обновляется только когда я выхожу на
 * home и возвращаюсь"*. **Returning from an open session remounts nothing**: `stopWatching` sets
 * `watching = null` on the same state and the list is drawn again from the listing it already held, so
 * what they came back to was as old as the moment they left. Leaving to Home and returning worked only
 * because that destroys the ViewModel.
 *
 * So the trigger is the transition INTO this predicate, which makes entry and the return from a
 * session the same event and leaves no second call site to forget.
 *
 * ### [AgtermUiState.Loading] counts as the list, and that is load-bearing
 *
 * Not a nicety. Without it the first composition sees no list, never fetches, and nothing ever reaches
 * [AgtermUiState.Sessions] — a deadlock by construction. It also means `Loading → Sessions` does not
 * flip the answer, so arriving fetches once rather than twice.
 *
 * ### The loop this must not create
 *
 * An effect keyed on derived state loops forever if its own fetch flips the key. It does not:
 * `Sessions → Sessions(refreshing = true) → Sessions` is true throughout, so a refresh cannot
 * re-trigger itself. `ListVisibilityTest` asserts those transitions as pairs rather than trusting a
 * reading of the effect.
 */
fun showsList(state: AgtermUiState): Boolean = when (state) {
    // The list, still arriving.
    AgtermUiState.Loading -> true
    // The list, unless the terminal is over it.
    is AgtermUiState.Sessions -> state.watching == null
    // A failure, pairing, or their own disconnect: each carries its own action, and refreshing
    // underneath one would fight the button the owner is being asked to press.
    AgtermUiState.NotPaired,
    AgtermUiState.Disconnected,
    is AgtermUiState.Failed,
    is AgtermUiState.Refused,
    -> false
}

/**
 * Holds the connection and the polling loop.
 *
 * ### What is kept, and where
 *
 * The selection, the screen text and the connection live here, in memory, and die with the process.
 * The owner's DRAFTS are the exception since REQ-0046: one per session, held here while the process
 * lives and written to disk through [DraftStore] shortly after every edit, so a prompt typed on the
 * phone survives a key press, a session switch and the process being killed in a pocket.
 *
 * There used to be a paragraph here saying nothing may be persisted or logged, citing REQ-0009. The
 * owner never set that rule; it was withdrawn on 2026-09-06 (REQ-0046). Failures still carry a
 * [WireFailure] rather than a message, because a type is what the copy branches on.
 */
class AgtermSessions(
    private val connect: () -> BridgeConnection?,
    /**
     * What this phone's key can do right now, asked before anything opens a socket.
     *
     * A state rather than a Boolean because the two ways of not being able to sign have different
     * remedies: an absent owner is asked, an unusable key must be replaced. See [SigningState].
     *
     * Defaults to Ready so tests that are not about authentication do not have to care.
     */
    private val keyState: () -> SigningState = { SigningState.Ready },
    private val scope: CoroutineScope,
    /**
     * Where the blocking work runs.
     *
     * Injectable so tests are deterministic rather than racing a thread hop - a test that waits for a
     * state change to appear from another dispatcher is a test that passes on a fast machine and gets
     * re-run on a slow one, which is how a real failure gets filed as a flake.
     */
    private val io: CoroutineContext = Dispatchers.IO,
    /** Injectable for the same reason as [io]: a test that really waits two seconds per poll is a test people stop running. */
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    /**
     * Whether to ask for the screen with its colours — [StyledScreenStore], read on EVERY poll rather
     * than once at watch time, so a flip in Settings shows on the next reply. Off by default so tests
     * and callers that do not care read exactly as before.
     */
    private val styled: () -> Boolean = { false },
    /**
     * Where drafts go between processes — REQ-0046. Null keeps them in memory only, which is what a
     * test wants and what the preview gets.
     */
    private val drafts: DraftStore? = null,
    private val draftWriteDelayMs: Long = DRAFT_WRITE_DELAY_MS,
    /** The pause between a text landing and the Return that follows it. Zero in tests that count calls. */
    private val enterAfterTextMs: Long = ENTER_AFTER_TEXT_MS,
) {

    /**
     * **The pane the owner is looking at, and the only place it is decided — REQ-0032.**
     *
     * ### One value, two questions
     *
     * What the screen SHOWS and what a keystroke GOES INTO are the same fact, and they were not. agterm
     * resolves an absent pane differently per command — a read gets the on-screen half, a keystroke
     * gets primary — so a split session showed one and typed into the other, silently, into the
     * terminal the owner was not looking at.
     *
     * Two mechanisms keep that shut, and neither is a rule anyone maintains:
     *
     *  - `BridgeConnection.screen` and `.type` take a pane as a **required** argument with no default,
     *    so the compiler refuses a call that leaves one of them to guess;
     *  - every one of those calls in this file goes through [readingScreen] or [typingInto], which are
     *    the only two expressions that name this property. Six call sites reading one field is a rule;
     *    two accessors over one field is a shape.
     *
     * ### It resets when the session does
     *
     * Opening a session starts on [Pane.Left]. A pane carried over from the session before means
     * nothing in a buffer it did not come from — the same reasoning `TerminalScroll.onSessionOpened`
     * applies to a scroll offset.
     *
     * ### This paragraph used to claim more than the code did, and that is the whole of REQ-0034
     *
     * It said a session **whose split has gone** also resets to [Pane.Left]. That is true of a pane
     * DESTROYED — `session split close` on the Mac — because the next read is refused with `no split
     * pane` and the handler below drops to Left. It was false of a pane merely COLLAPSED, which
     * refuses nothing: the pane still exists, still reads, and the reset never fires.
     *
     * So the sentence was accurate about the case somebody measured and load-bearing for the case
     * nobody did. **The same shape as the fit button describing two preconditions and enforcing one.**
     * Prose that is right about what was checked is not evidence about what was not — and here it
     * described the trap as already handled, which is why nobody went looking for it.
     */
    private val _pane = MutableStateFlow(Pane.Left)

    /**
     * Which pane he was reading in each session, so coming back returns him to it — REQ-0042, ONE.
     *
     * ### Why this exists now and did not before
     *
     * The phone used to show a pane; it now MAXIMIZES the pane it shows, so only one is on screen at a
     * time and re-entering a session is a CHOICE. Making that choice arbitrarily would put him in the
     * terminal he was not reading, every time, on a screen where the other one is no longer visible
     * beside it.
     *
     * ### On the phone, not on the bridge
     *
     * Per-session UI state, next to `watching`, which this class already holds. Keeping it here costs
     * no wire field, no bridge state and nothing to reconcile after a reconnect. **In memory only** —
     * the same as `lastWatched`, and for the same reason: it describes this run of the app.
     *
     * ### A remembered Right for a session that no longer has a right pane
     *
     * There is already a path for that and this AGREES with it rather than adding a second: when a read
     * is refused with `no split pane` the pane drops to Left, and [forgetPane] is called at that same
     * point. Nothing new checks anything, and the case where the listing already knows — [watch] is
     * handed the row it is opening — is resolved before anything reaches the wire.
     */
    private val paneMemory = mutableMapOf<String, Pane>()

    /** Records where he is, so [watch] can put him back. Left is stored too: it is an answer. */
    private fun rememberPane(sessionId: String, pane: Pane) {
        paneMemory[sessionId] = pane
    }

    /**
     * Drops a session's memory, called from the ONE place that already handles a vanished pane.
     *
     * Separate from [rememberPane] because forgetting is not storing Left: a session whose pane was
     * destroyed has no remembered side, and writing Left here would be indistinguishable from him
     * having chosen it.
     */
    private fun forgetPane(sessionId: String) {
        paneMemory.remove(sessionId)
    }

    /** What the toggle draws. The SAME field the two accessors read — see [pane]. */
    val paneShown: StateFlow<Pane> = _pane.asStateFlow()

    private val pane: Pane get() = _pane.value

    /** Reads a screen from the pane the owner is looking at. See [pane]; do not inline it. */
    private fun BridgeConnection.readingScreen(sessionId: String, digest: String?) =
        screen(sessionId, pane, digest = digest, styled = styled())

    /** Types into the pane the owner is looking at. See [pane]; do not inline it. */
    private fun BridgeConnection.typingInto(
        sessionId: String,
        text: String? = null,
        key: String? = null,
        paste: String? = null,
    ) = type(sessionId, pane, text = text, key = key, paste = paste)

    /**
     * Shows the other pane, which changes what is read AND what is typed, because they are one value.
     *
     * **One button, two states — REQ-0035.** The owner: *"если сессия есть мы её показываем, если её
     * нет мы её создаём и потом показываем"*.
     *
     * ### The two directions are still not symmetrical, and REQ-0042 did not make them so
     *
     * Going back to the LEFT pane is local and instant **for what the phone shows**. Every session has
     * that pane, reading it cannot fail, and the icon moves at once.
     *
     * Going to the RIGHT may mean starting a shell on his laptop. So that direction asks first, acts,
     * and confirms — three steps, each of which can stop the icon from moving.
     *
     * ### Both directions now maximize the pane on his Mac
     *
     * *"при переключении мы держим их фуллскрин"*. The maximize is the LAST step in both, and in the
     * left direction it deliberately happens AFTER the icon has moved: what the phone reads is already
     * correct, and his Mac's layout catching up a round trip later is not something the icon should
     * wait on. A failure there earns a note and keeps his terminal — the part he asked for happened.
     *
     * ### Why it asks the laptop instead of reading the session it already holds
     *
     * `watching.splitPane` is **a snapshot from the moment the session was opened.** `watching` is
     * written in exactly two places — [watch] sets it, [stopWatching] clears it — and the poll only
     * ever copies `screen` and `stale`. So a split created or destroyed on the Mac while this screen
     * is open is invisible here, and a tap decided on that value would create a second pane for a
     * session that already has one, or claim one exists when it does not.
     *
     * **This is not a defect being routed around**, and it is deliberately not fixed by making the
     * poll carry pane existence: the screen reply is one `session.text` call, and telling it about
     * panes would mean a tree round trip on every poll — REQ-0008 measured that polling is where this
     * app's cost lives, and 95% of those polls convey nothing. A tap is a human action a few times an
     * hour. **It can afford a question the poll cannot.**
     *
     * ### The icon moves last, and only on the laptop's word
     *
     * Nothing here sets [Pane.Right] because a call returned ok. It is set when a listing fetched
     * AFTER the attempt says the pane is there. A create that succeeded into a pane something else
     * closed a moment later leaves the icon where it was and puts a note up, rather than lighting the
     * right half over a screen the phone cannot read.
     */
    fun togglePane() {
        val watching = (_state.value as? AgtermUiState.Sessions)?.watching ?: return
        // Local, instant, and unconditional: the left pane is the one every session has, so what the
        // phone SHOWS moves now and the laptop's layout follows.
        if (pane == Pane.Right) {
            _pane.value = Pane.Left
            rememberPane(watching.id, Pane.Left)
            scope.launch { maximize(watching.id, Pane.Left, initiated = true) }
            return
        }
        scope.launch {
            // Cleared first, so a note from a previous attempt is not read as a report about this one.
            _mutation.value = MutationNote.None

            // **Ask before acting.** A null here is a link that could not answer, which withConnection
            // has already turned into a Failed or Refused screen; adding a note on top would be this
            // app diagnosing over the bridge's own words.
            when (paneExistsNow(watching.id)) {
                null -> return@launch
                true -> Unit
                false -> {
                    if (!withConnection { it.openPane(watching.id) }) {
                        _mutation.value = MutationNote.PaneFailed
                        return@launch
                    }
                    // **Confirmed from the laptop, not from the fact that the call returned ok.**
                    // agterm's reply carries the session id whether it created, revealed or did
                    // nothing, so it cannot be read as evidence that a pane is now there.
                    if (paneExistsNow(watching.id) != true) {
                        _mutation.value = MutationNote.PaneFailed
                        return@launch
                    }
                }
            }
            if (!maximize(watching.id, Pane.Right, initiated = true)) return@launch
            _pane.value = Pane.Right
            rememberPane(watching.id, Pane.Right)
        }
    }

    /**
     * Shows one pane at the full width of the terminal area on his Mac — REQ-0042, TWO.
     *
     * Returns whether it worked, so the caller can decide what to do about a failure. **The two callers
     * decide differently and that is the point**: a tap he made is owed an answer, and a session switch
     * is not — the REQ-0041 rule, carried here as a parameter rather than as care.
     *
     * ### It is never sent to a session with no split
     *
     * agterm refuses that with `session has no split`, measured. A session with one pane is ALREADY
     * showing it at full width, so sending anyway would turn the commonest case there is into a
     * refusal — and on the initiated path that refusal takes his terminal away, which is exactly the
     * shape REQ-0037 had to undo. The two callers each establish that a second pane exists before
     * calling: the toggle by asking the laptop, the switch by reading the row it was handed.
     */
    private suspend fun maximize(sessionId: String, pane: Pane, initiated: Boolean): Boolean {
        val worked = if (initiated) {
            withConnection { it.showPane(sessionId, pane) }
        } else {
            // **Silent on every failure**, both handlers empty: he tapped a row in a list, so the
            // bridge's words may not reach the screen and may not replace it either.
            withConnection(onRefusedContent = {}, onRefused = {}) { it.showPane(sessionId, pane) }
        }
        if (!worked && initiated) _mutation.value = MutationNote.PaneFailed
        return worked
    }

    /**
     * Whether this session has a right-hand pane **right now**, or null if the laptop did not answer.
     *
     * Reads the listing and takes one field out of it. It deliberately does NOT write the fresh row
     * into [state]: the header's name and title are what the owner opened, and quietly swapping the
     * session object underneath an open screen is a change nobody asked this feature to make.
     */
    private suspend fun paneExistsNow(sessionId: String): Boolean? {
        var answer = false
        // **The two nulls are not the same and must not collapse into one.** A link that did not
        // answer is null here; a session the listing does not mention is `false`, which sends the
        // caller on to ask for a pane and get the bridge's own `no such session` back. That is a
        // sentence the owner can act on, and it beats this function quietly deciding nothing happened.
        val answered = withConnection { bridge ->
            answer = bridge.sessions().sessions.firstOrNull { it.id == sessionId }?.splitPane == true
        }
        return if (answered) answer else null
    }

    private val _state = MutableStateFlow<AgtermUiState>(AgtermUiState.Loading)
    val state: StateFlow<AgtermUiState> = _state.asStateFlow()

    /**
     * Whether a listing is being fetched right now, and the guard that keeps it to one.
     *
     * **Owned by [refreshNow] and read by nothing else.** Deliberately not derived from
     * [AgtermUiState.Sessions.refreshing], which is the same fact rendered for the owner: the state
     * lags by a coroutine dispatch, so a pull arriving before that write would slip past a check on it
     * and start a duplicate round trip.
     */
    private var fetching = false

    /**
     * The input bar, held apart from [state] on purpose.
     *
     * A draft is not terminal content and must never travel with it — see [TypingState]. Keeping them
     * in separate flows means there is no struct in which the owner's typing and the laptop's output
     * are neighbours, so a later edit cannot casually render one as the other.
     */
    private val _typing = MutableStateFlow<TypingState>(TypingState.Closed)
    val typing: StateFlow<TypingState> = _typing.asStateFlow()

    /**
     * The draft of the session on screen — REQ-0046.
     *
     * **Separate from [typing] on purpose.** While the draft was a field of `Composing`, every
     * transition out of that state — a key sent, a report shown, a report dismissed — came back with
     * an empty one, so Esc or an overpull into PgUp wiped what the owner had typed. Here it is touched
     * by exactly three things: the owner editing it, a text send clearing it, and a send that did not
     * happen putting it back.
     */
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    /** Every session's draft, by id, for as long as the process lives. Read from [drafts] on first open. */
    private val heldDrafts = mutableMapOf<String, String>()

    /** The pending write of the draft being edited; each edit replaces it, so a burst of typing is one write. */
    private var draftWriter: Job? = null

    /**
     * Whether the laptop's window is currently narrowed to this phone.
     *
     * **This app's belief, not an observation**, and the distinction is the same one that runs
     * through this file: the bridge holds the restore point and the window is on another machine that
     * the owner can resize themselves. So it is what WE last asked for, it resets when the connection
     * drops, and it is used for a toggle's position rather than for any decision.
     */

    /**
     * What the phone can honestly say about the connection — see [LinkNote].
     *
     * **Written at two points that already existed**, and nothing else in this file moved for it: the
     * branch that decides a failure is transient and schedules a quiet retry, and the success path.
     * The retry ladder, the healer and every state transition here are behaviour the owner has
     * confirmed working, so this had to be readable as "a value is written here" rather than as a
     * change to when anything happens.
     */
    private val _link = MutableStateFlow<LinkNote>(LinkNote.None)
    val link: StateFlow<LinkNote> = _link.asStateFlow()

    /** What the last create or rename did, for the notes surface. See [MutationNote]. */
    private val _mutation = MutableStateFlow(MutationNote.None)
    val mutation: StateFlow<MutationNote> = _mutation.asStateFlow()

    /** The rename dialog's subject, or null when it is closed. See [RenameRequest]. */
    private val _renaming = MutableStateFlow<RenameRequest?>(null)
    val renaming: StateFlow<RenameRequest?> = _renaming.asStateFlow()

    /**
     * Whether anything has failed transiently since the last success.
     *
     * **This is what makes "reconnected" an observation rather than a greeting.** Without it, the
     * first successful connection of the session would announce a recovery from nothing.
     */
    private var recovering = false

    private var connection: BridgeConnection? = null
    private var poller: Job? = null

    /**
     * Retries while the failure screen is showing. One at a time, cancelled by [release].
     *
     * See Reconnect: a deploy restarts the bridge for about two seconds, and the owner should not have
     * to notice that it fixed itself.
     */
    private var healer: Job? = null

    /**
     * The session the owner had open, so a heal puts them back in it rather than in the list.
     *
     * In memory only; it is cleared with the rest of the state by [release].
     */
    private var lastWatched: BridgeSession? = null

    /**
     * Fetches the list. Safe to call repeatedly; the owner's refresh is the same path, and so is the
     * refresh after a create or a rename.
     *
     * # Why this no longer blanks a list that is already showing
     *
     * It set [AgtermUiState.Loading] unconditionally. That was invisible while the only caller was
     * screen entry, where there is nothing on screen to lose — and wrong the moment a mutation used
     * the same path, because the rename dialog would close onto an empty screen for a round trip.
     *
     * **That reads as failure exactly as much as a stale name does**, which is the defect this refresh
     * exists to avoid. So the list stays on screen and is replaced when the new one lands.
     *
     * No new state was needed to say this. The distinction is already in the type: `Sessions` means
     * there is a list in front of the owner and every other state means there is not, so the rule is
     * *do not blank what is showing* rather than a flag saying which caller we are. That is also the
     * right behaviour for re-entering the screen, so there is still exactly one path.
     */
    /**
     * **Returns the [Job], so a caller that needs the round to be OVER can wait for it.**
     *
     * Nothing in the app waits — the UI reads state as it changes, which is the point of a state
     * flow. A test does need it, and the reason is a defect this returned nothing while hiding: the
     * screen leaves `Loading` *inside* the fetch, and the recovery note is written afterwards, so
     * "the state stopped being Loading" is not "the refresh has finished". A test that used the
     * first as a barrier for the second raced it — see `docs/qa/flaky-tests.md`, 2026-08-02.
     */
    fun refresh(): Job = scope.launch { refreshNow() }

    /**
     * [refresh]'s body, as something a caller can WAIT for.
     *
     * A create has to read the default name agterm chose, and that name is only in the listing — the
     * create reply carries an id and nothing else. So the mutation path needs the refresh to have
     * finished before it looks, which `scope.launch` cannot express.
     *
     * **One body, two entry points**, rather than a second fetch beside the first: two fetches is two
     * chances to disagree about what the laptop just said.
     */
    private suspend fun refreshNow() {
        // **A second fetch while one is outstanding is a NO-OP, not a queued round.**
        //
        // The owner can pull to refresh while a create's own refresh is still in flight. That pull is
        // answered by the round already asking the same question rather than by a duplicate - the same
        // answer HomeScreen's launch check gives, so the two screens behave alike. Nothing is
        // cancelled: the reply that is coming is the reply they wanted.
        if (fetching) return
        fetching = true
        try {
            // Loading is a state for "nothing to show yet", and only then.
            val showing = _state.value as? AgtermUiState.Sessions
            if (showing == null) {
                _state.value = AgtermUiState.Loading
            } else {
                // The spinner, over the list they are already looking at - which stays put, per
                // REQ-0012: a refresh must not blank a list that is showing.
                _state.value = showing.copy(refreshing = true)
            }
            withConnection {
                val listing = it.sessions()
                // **The setting is adopted from the LIST reply, before the owner touches anything.**
                //
                // It arrived only on a response to `resize`, and the toggle is gated on having seen
                // it - so learning the state required pressing the button, and pressing required
                // knowing the state. A deadlock by construction, and the owner pressed a control that
                // was doing exactly what it was told across three builds.
                // **Entering the screen reads the setting from the SAME place the poll does**: the
                // bridge's own flag, which `api.Handle` puts on every reply. A different verb, one
                // source. That matters because leaving and returning is the owner's current workaround
                // for a stale toggle - if this read from anywhere else, the workaround and the poll
                // could disagree, and the state they trust most would be the one we had not corrected.
                _fit.value = FitState(enabled = listing.fitEnabled, columns = listing.fitColumns)
                _state.value = AgtermUiState.Sessions(listing.sessions, workspaces = listing.workspaces)
            }
        } finally {
            fetching = false
            // **The spinner never outlives the fetch, whichever way the fetch ended.**
            //
            // A failure usually replaces the list with Failed or Refused, so there is no Sessions left
            // to carry a spinner and the owner is looking at the failure and its retry. When a list IS
            // still showing - a failure that did not move the screen, or a cancellation - this is what
            // stops the indicator spinning over a screen that has stopped, which is the exact defect
            // HomeScreen's comment describes and this project keeps meeting.
            (_state.value as? AgtermUiState.Sessions)
                ?.takeIf { it.refreshing }
                ?.let { _state.value = it.copy(refreshing = false) }
        }
    }

    /**
     * Creates a workspace, then reports what it made so the screen can offer to name it.
     *
     * **The name is not chosen here and is not sent.** agterm picks a default; the owner renames it if
     * they want to, and cancelling that dialog leaves the default rather than undoing the create —
     * a create that silently vanished would be worse than a dull name.
     */
    fun createWorkspace() {
        var made = ""
        mutate(
            onFailure = MutationNote.CreateFailed,
            // **Two calls, and the second is what makes the first worth anything.** The owner:
            // *"если я создаю workspace, то в нём сразу делай одну сессию, потому что сам по себе он
            // не имеет смысла"*.
            //
            // REQ-0011 refused exactly this shape - two socket calls with no transaction, where a
            // failure between them leaves an empty workspace the bridge could not describe and was not
            // allowed to delete. **Both halves of that objection are now false.** The wire publishes
            // workspaces, so an empty one is visible; and REQ-0012 lets the owner delete it. A
            // half-failure now leaves something they can see and remove.
            block = {
                made = it.createWorkspace()
                it.createSession(made)
            },
            // The rename opens on the WORKSPACE rather than the session, because that is the thing the
            // button they pressed is named after.
            afterRefresh = { askToName(RenameTarget.Workspace(made), RenameOrigin.Created) },
        )
    }

    /**
     * Creates a session in one workspace.
     *
     * **This moves the owner's laptop**: agterm focuses what it creates and offers no flag to stop it,
     * so their Mac jumps to the new session. Measured, and recorded rather than discovered.
     */
    fun createSession(workspaceId: String) {
        var made = ""
        mutate(
            onFailure = MutationNote.CreateFailed,
            block = { made = it.createSession(workspaceId) },
            afterRefresh = { askToName(RenameTarget.Session(made), RenameOrigin.Created) },
        )
    }

    /** Renames a workspace, if the name is one this phone will send. See [dispatchRename]. */
    fun renameWorkspace(workspaceId: String, label: String) {
        dispatchRename(label) { it.renameWorkspace(workspaceId, cleanLabel(label)) }
    }

    /** Renames a session. See [renameWorkspace]. */
    fun renameSession(sessionId: String, label: String) {
        dispatchRename(label) { it.renameSession(sessionId, cleanLabel(label)) }
    }

    /**
     * The gate every rename passes, and the reason it is here rather than only on the dialog's button.
     *
     * **A name this phone refuses is a LOCAL refusal.** It opens no socket, produces no note, and does
     * not touch the screen's state — it simply leaves the dialog open with what the owner typed still
     * in it, which is the only way they can fix it without retyping.
     *
     * The dialog's confirm button is already disabled for these, so in practice this never fires. That
     * is exactly why it is here: a disabled button is a property of one composable, checkable only on a
     * device, and this project has no working emulator. This is the same rule stated where the JVM can
     * hold it — `a name the phone rejects never reaches the connection` does precisely that.
     *
     * **Closing the dialog is decided here too**, and that is the other half. It used to be the host's
     * job, unconditionally, right after calling this — so a refusal would have closed the dialog and
     * discarded their text no matter what this function decided.
     */
    private fun dispatchRename(label: String, block: (BridgeConnection) -> Unit) {
        if (labelProblem(label) != null) return
        _renaming.value = null
        mutate(onFailure = MutationNote.RenameFailed, block = block)
    }

    /**
     * Opens the rename dialog on something that already exists — the owner's long press.
     *
     * The same entry the two creates use once their thing exists, so there is one dialog reached four
     * ways rather than a create flow and a rename flow that could drift.
     */
    fun beginRename(target: RenameTarget) {
        askToName(target, RenameOrigin.LongPress)
    }

    /**
     * Closes one of the owner's sessions. **Destroys their work and there is no undo.**
     *
     * Nothing disappears optimistically: the modal shuts, the list refreshes, and the row goes when
     * the laptop says it is gone. A refusal leaves the row where it is and raises a note, which is the
     * only honest outcome — a row removed on the strength of a request that failed would tell them
     * their session is gone when it is still running.
     */
    fun closeSession(sessionId: String) {
        _renaming.value = null
        mutate(
            onFailure = MutationNote.DeleteFailed,
            block = { it.closeSession(sessionId) },
        )
    }

    /**
     * Deletes one of the owner's workspaces **and every session inside it**.
     *
     * agterm takes them silently — measured — so the modal that leads here is where the count was
     * shown. By this point the owner has read it and pressed Delete.
     */
    fun deleteWorkspace(workspaceId: String) {
        _renaming.value = null
        mutate(
            onFailure = MutationNote.DeleteFailed,
            block = { it.deleteWorkspace(workspaceId) },
        )
    }

    /** Closes the rename dialog. Cancelling leaves the name exactly as it is — nothing is undone. */
    fun cancelRename() {
        _renaming.value = null
    }

    /**
     * Raises the dialog, with the name **read from the listing**.
     *
     * A create answers with an id and nothing else, so the default label agterm chose is only knowable
     * from the tree it just published. Reading it from the same listing that draws the row means the
     * dialog and the row behind it cannot disagree — and it is why [mutate] refreshes before this runs.
     *
     * A target that is not in the listing raises nothing. That happens when the laptop changed under
     * us between the create and the refresh, and an empty dialog whose OK button addresses something
     * that may not exist is worse than no dialog.
     */
    private fun askToName(target: RenameTarget, origin: RenameOrigin) {
        val listed = _state.value as? AgtermUiState.Sessions ?: return
        val name = when (target) {
            is RenameTarget.Workspace ->
                listed.workspaces?.firstOrNull { it.id == target.id }?.name
                    ?: listed.sessions.firstOrNull { it.workspaceId == target.id }?.workspace
            is RenameTarget.Session -> listed.sessions.firstOrNull { it.id == target.id }?.name
        } ?: return
        // **Counted from the listing, at the moment the modal opens.** A workspace's delete takes its
        // sessions with it silently, so this number is the whole of what the owner is told - and a
        // count carried from anywhere but the listing behind the modal could disagree with it.
        val sessions = when (target) {
            is RenameTarget.Workspace -> listed.sessions.count { it.workspaceId == target.id }
            is RenameTarget.Session -> 0
        }
        _renaming.value = RenameRequest(target, name, origin, sessions)
    }

    /**
     * The one path every create and rename takes.
     *
     * Two things it guarantees that four separate copies would not.
     *
     * **A mutation refreshes the list itself rather than waiting for a poll.** A dialog that closes
     * onto the old name for a poll interval reads as *it did not work*, and the owner presses again.
     * The refresh is [refresh] — the same call the owner's own pull uses and the same one screen entry
     * uses — not a second fetch that could drift from it.
     *
     * **A failure names the action, and does not try to outrank the screen.** [withConnection] already
     * turns a dead link into [AgtermUiState.Failed] and a refusal into [AgtermUiState.Refused], and
     * that is not changed here: those are the same states typing and file-sending produce, and a
     * mutation is not more important than the connection. What they do not say is WHICH button the
     * owner pressed, so the note carries that and nothing else.
     *
     * The note is therefore visible exactly when the list still is. When the screen has gone to a
     * refusal it is showing the bridge's own words, which are more specific than anything this could
     * add — see [AgtermUiState.Refused], which exists because a refusal about one thing used to be
     * reported as a failure of another.
     */
    private fun mutate(
        onFailure: MutationNote,
        block: (BridgeConnection) -> Unit,
        /** Runs once the refreshed listing is in [state], so it can read what the laptop now holds. */
        afterRefresh: () -> Unit = {},
    ) {
        scope.launch {
            // Cleared first: a note left over from the previous attempt sitting above a new one is a
            // report about something that already finished.
            _mutation.value = MutationNote.None
            // Named, because `withConnection` now takes the content-refusal handler first and a
            // positional lambda would silently become that instead of the work.
            val worked = withConnection(block = block)
            if (!worked) _mutation.value = onFailure

            // **The list is refreshed whether it worked or not, and that is REQ-0012 Decision 10.**
            //
            // Creating a workspace is two calls now - the workspace, then its first session - and if
            // the second fails the first has still happened. Returning early would leave that
            // workspace off the screen: real on the laptop, invisible on the phone, and the owner
            // pressing + again to make another.
            //
            // **Nothing is rolled back.** The bridge could delete it, and must not: a delete nobody
            // pressed a button for is the bridge destroying something on its own initiative, which is
            // a different and worse thing than the owner deciding to. So the workspace stays, the note
            // says the create did not finish, and it is on screen where a long press removes it.
            //
            // For every other caller this costs one listing that would have happened on the next poll
            // anyway, and it buys the same guarantee: after any attempt, the screen shows what the
            // laptop actually holds rather than what we assumed it holds.
            refreshNow()

            if (worked) afterRefresh()
        }
    }

    /** Dismisses the create/rename note. The card's own control, same as every other note. */
    fun dismissMutationNote() {
        _mutation.value = MutationNote.None
    }

    /**
     * Opens one session's screen and starts polling it.
     *
     * Polled rather than pushed because the transport has no output event — REQ-0008 established that
     * nothing fires when a pane draws, so the choice is between polling and a screen that is a
     * photograph. The digest is what makes it affordable: 95% of idle polls, measured, move no text at
     * all, and the reply to an unchanged screen carries no body.
     */
    fun watch(session: BridgeSession) {
        poller?.cancel()
        // Remembered so a heal can put them back in it - see startHealing. In memory only.
        lastWatched = session
        val listed = (_state.value as? AgtermUiState.Sessions)?.sessions ?: return
        // **A pane does not carry across sessions, but it is remembered PER session** — REQ-0042.
        //
        // It used to start at Left unconditionally, on the reasoning TerminalScroll.onSessionOpened
        // applies to a scroll offset: a value from a different buffer means nothing here. That is
        // still true of a pane carried over from ANOTHER session. It is not true of this session's
        // own, which is where he was reading last time he was in it — and it matters more now that
        // only one pane is on screen at a time.
        //
        // **A remembered Right is checked against the row being opened before it is used.** `split`
        // on that row is the listing the phone just refreshed, so a pane destroyed on the Mac
        // resolves to Left here, for free, before anything reaches the wire. When the row is stale
        // the read refusal below catches it and forgets the memory, which is the path that already
        // existed.
        val remembered = paneMemory[session.id]
        val opening = if (remembered == Pane.Right && session.splitPane) Pane.Right else Pane.Left
        _pane.value = opening
        _state.value = AgtermUiState.Sessions(listed, watching = session)
        // This session's own draft, from memory or from disk - never the previous session's.
        _draft.value = heldDrafts.getOrPut(session.id) { drafts?.read(session.id).orEmpty() }

        // **Showing a pane maximizes it, and a session switch is a showing** — REQ-0042, and the same
        // reading of "при переключении" that REQ-0041 gave the re-applied fit.
        //
        // Only when a second pane exists: a session with one pane is already that pane at full width,
        // and agterm refuses the call outright for it. Silent either way — he tapped a row, and
        // nothing on this path may put words in front of him or take his terminal away.
        //
        // **It does not create.** The toggle may start a shell because that is his gesture; coming
        // back to a session may not, which is the rule IntentApplyIfKnown holds for the fit.
        if (session.splitPane) {
            scope.launch { maximize(session.id, opening, initiated = false) }
        }

        poller = scope.launch {
            var digest: String? = null
            while (isActive) {
                val done = withConnection { bridge ->
                    val update = bridge.readingScreen(session.id, digest)
                    // **The poll is allowed to be the authority because the bridge owns the setting and
                    // this app only renders it.** The reply is a REPORT, not a request: it says what is
                    // in force on the laptop, which survives reconnects, restarts and this app being
                    // killed. Anyone tempted to reintroduce a local flag here - to avoid a flicker, to
                    // feel responsive - would be creating a second source of truth for a value only the
                    // other end can know, which is the bug this replaced.
                    adoptFit(update.fit)
                    when (val reply = update.text) {
                        is ScreenText.Text -> {
                            digest = reply.digest
                            // Only if this is still the session on screen. Cancelling the job does
                            // not unwind a poll that is already past its last suspension point, so
                            // without this a reply in flight when the owner closes the session lands
                            // AFTER stopWatching cleared the text - putting their terminal's contents
                            // back into memory under a state that says nothing is being watched.
                            update {
                                if (it.watching?.id == session.id) {
                                    it.copy(screen = reply.body, screenStyled = reply.styled, stale = false)
                                } else {
                                    it
                                }
                            }
                            // The screen moved, so an outstanding keystroke has been acknowledged in
                            // the only way this design can acknowledge one: the laptop sent something
                            // back.
                            _typing.value = Typing.onScreenSettled(_typing.value, changed = true)
                        }
                        // Unchanged is NOT an empty screen. Leaving the text alone is the whole
                        // point of the digest; writing "" here would blank the box every poll that
                        // conveyed nothing, which is most of them.
                        // Unchanged is NOT nothing where typing is concerned: it is the honest
                        // answer that the bridge took the keystroke and the laptop showed no output.
                        // Ordinary - a busy shell, a password prompt - and it must be said rather
                        // than left as silence.
                        ScreenText.Unchanged ->
                            _typing.value = Typing.onScreenSettled(_typing.value, changed = false)
                    }
                }
                if (!done) return@launch
                delay(pollIntervalMs)
            }
        }
    }

    /**
     * Types [text] into the session on screen, or presses a named [key].
     *
     * **The typing state is the caller's window into what is known**, and the transitions are in
     * [Typing]. It moves to Sent immediately, and only a poll can resolve it — into Composing if the
     * screen actually changed, or into NoChange if the bridge took the keystroke and the laptop
     * showed nothing. Those are different facts and the copy says different things.
     */
    /**
     * Types a command and then presses Return — the Claude button, REQ-0013.
     *
     * ### Two calls, ordered, and the second only if the first landed
     *
     * The asymmetry is the entire safety argument. A failure between them leaves the command sitting
     * **unsent** on the owner's prompt: visible, harmless, and one press of the Return they already
     * have. Sending Return after a failed text presses it on **whatever was already there** — a
     * half-typed command, a prompt waiting on a confirmation — which is the one outcome this must not
     * be able to produce.
     *
     * "Landed" means the bridge accepted it, which is what this app can observe. It does not mean the
     * laptop's screen changed; that is what the *No change on screen* note is for, and this reports
     * through the same [TypingState] as everything else.
     *
     * ### It adds no capability
     *
     * Both calls go through the same `type` verb the input box uses, so `keys.Text` checks the text
     * exactly as it checks anything typed by hand — and refuses a newline, which is why the Return is
     * a separate call rather than a `\n` smuggled into the string. The Return is the `enter` key,
     * already in the bridge's allowlist and already on the bar two rows up. No new verb, no new
     * permission.
     */
    fun runMacro(text: String) {
        val watching = (_state.value as? AgtermUiState.Sessions)?.watching ?: return
        // Nothing about WHAT was typed is kept - the same rule as type(), and the reason
        // TypingState.Sent carries no payload.
        _typing.value = Typing.sending()
        scope.launch {
            if (!withConnection { it.typingInto(watching.id, text = text) }) {
                _typing.value = Typing.failed()
                return@launch
            }
            delay(enterAfterTextMs)
            if (!withConnection { it.typingInto(watching.id, key = ENTER_KEY) }) {
                _typing.value = Typing.failed()
            }
        }
    }

    fun type(text: String? = null, key: String? = null) {
        val watching = (_state.value as? AgtermUiState.Sessions)?.watching ?: return
        if (text == null && key == null) return

        // **Return with a draft in the field types the draft first** — REQ-0046. The owner: *"если я
        // нажимаю enter и у меня в поле ввода что-то есть то мы сначала отправляем текст а потом уже
        // отправляем enter с небольшой задержкой между событиями"*. Return with an empty field is the
        // bare key it always was.
        if (text == null && key == ENTER_KEY) {
            val pending = heldDrafts[watching.id].orEmpty()
            if (pending.isNotEmpty()) {
                sendText(watching.id, pending, thenReturn = true)
                return
            }
        }
        if (text != null && key == null) {
            sendText(watching.id, text, thenReturn = false)
            return
        }

        // A bare key. **The draft is not touched**: pressing Esc, an arrow, or overpulling the terminal
        // into PgUp is not a statement about what the owner was composing, and it used to wipe it.
        _typing.value = Typing.sending()
        scope.launch {
            val sent = withConnection { it.typingInto(watching.id, text, key) }
            if (!sent && _typing.value !is TypingState.Composing) _typing.value = Typing.failed()
        }
    }

    /**
     * Sends text, clearing the draft at the moment of sending and putting it back if the send did not
     * happen.
     *
     * The draft is cleared HERE rather than when the reply lands: text sitting in a field that has
     * already been sent is the one arrangement that makes double-sending feel natural. While the send
     * is in flight the text lives in this function's parameter and nowhere else.
     *
     * **A send that did not happen leaves the text where it was — refused OR failed.** REQ-0017 granted
     * this for a refusal only; REQ-0046 extends it to a connection that dropped, because from the
     * owner's side both are the same event: they pressed send and their words went nowhere. On a
     * refusal the notice says why; on a failure the report does.
     *
     * [thenReturn] presses Return after the text landed, with [ENTER_AFTER_TEXT_MS] between the two,
     * and never when the text did not land.
     */
    private fun sendText(sessionId: String, text: String, thenReturn: Boolean) {
        clearDraft(sessionId)
        _typing.value = Typing.sending()
        scope.launch {
            var refused = false
            val sent = withConnection(
                // The bridge's own words are deliberately dropped on the floor: they name byte
                // offsets and our internal key vocabulary. What the owner reads is written on the
                // phone from what the phone can see - see Typing.refused.
                onRefusedContent = {
                    refused = true
                    restoreDraft(sessionId, text)
                    _typing.value = Typing.refused(text)
                },
            ) {
                // **A draft with a line break in it is a PASTE, and everything else is unchanged.**
                // REQ-0017: the owner pasted a message out of a chat app and the newline was refused,
                // correctly, because typing one is a Return they did not press. Pasting one is a
                // different act, so it goes as a different field and the bridge puts it between the
                // bracketed paste markers.
                //
                // Decided from the text itself, never from a guess about what is running at the far
                // end. Bracketed paste is honoured by Claude Code, by a shell, by vim and by things
                // nobody here has thought of; Shift+Enter would have needed us to know which.
                if (text.contains('\n')) {
                    it.typingInto(sessionId, key = null, paste = text)
                } else {
                    it.typingInto(sessionId, text, null)
                }
            }
            if (!sent) {
                if (!refused) {
                    restoreDraft(sessionId, text)
                    _typing.value = Typing.failed()
                }
                return@launch
            }
            if (thenReturn) {
                delay(enterAfterTextMs)
                if (!withConnection { it.typingInto(sessionId, key = ENTER_KEY) }) {
                    _typing.value = Typing.failed()
                }
            }
        }
    }

    private fun watchingId(): String? = (_state.value as? AgtermUiState.Sessions)?.watching?.id

    /**
     * Whether [sessionId] is the session the field belongs to right now. The watched one, or - while a
     * refusal has replaced the screen and a heal is about to put him back - the one he was in.
     */
    private fun fieldBelongsTo(sessionId: String): Boolean =
        (watchingId() ?: lastWatched?.id) == sessionId

    /** The draft is gone: from the field, from memory, and from disk. */
    private fun clearDraft(sessionId: String) {
        heldDrafts.remove(sessionId)
        if (fieldBelongsTo(sessionId)) _draft.value = ""
        draftWriter?.cancel()
        scope.launch { withContext(io) { drafts?.write(sessionId, "") } }
    }

    /** The draft is back, exactly as it was sent, because the send did not happen. */
    private fun restoreDraft(sessionId: String, text: String) {
        heldDrafts[sessionId] = text
        if (fieldBelongsTo(sessionId)) _draft.value = text
        scheduleWrite(sessionId, text)
    }

    private fun scheduleWrite(sessionId: String, text: String) {
        draftWriter?.cancel()
        draftWriter = scope.launch {
            delay(draftWriteDelayMs)
            withContext(io) { drafts?.write(sessionId, text) }
        }
    }

    /**
     * Sends the picked files, in order, and puts each path the bridge chose **into the draft** as it
     * lands. One file was the whole of this until REQ-0048; now it is the list of one.
     *
     * **Nothing is sent to the shell, and that is the point.** The owner presses Enter themselves.
     * Choosing a file must never execute a command — the same reasoning that keeps the bar shut until
     * they open it.
     *
     * Each path is appended to whatever they were already composing, so `cat ` followed by a pick
     * reads the way they meant it, and three picks read `cat /a /b /c`. A separating space goes in
     * when the draft does not already end with one - see [appendPath].
     *
     * **One file per round trip, and the draft grows after each.** A single `withConnection` around
     * the whole batch would retry the whole batch after a transient failure, sending again what had
     * already landed; and a failure on the third file would then say nothing about the two that did
     * land. So each file has its own attempt, its path goes into the draft before the next one
     * starts, and the first failure ends the run with the earlier paths kept. The owner sees exactly
     * the paths that exist on his laptop.
     *
     * The draft is untouched by a file going up: it is neither sent nor cleared, and a refusal leaves
     * it exactly where it was - REQ-0017 fixed the version that threw it away.
     */
    fun sendFiles(files: List<Pair<String, ByteArray>>) {
        if (files.isEmpty()) return
        val sessionId = watchingId()
        var draft = sessionId?.let { heldDrafts[it] }.orEmpty()
        _typing.value = Typing.sending()
        scope.launch {
            for ((name, content) in files) {
                var path = ""
                val ok = withConnection(
                    onRefusedContent = { _typing.value = Typing.refusedFile() },
                ) { path = it.sendFile(name, content) }
                if (ok && path.isNotEmpty()) {
                    draft = appendPath(draft, path)
                    if (sessionId != null) restoreDraft(sessionId, draft)
                    continue
                }
                if (_typing.value !is TypingState.Composing) {
                    // A refusal has already put them back with their draft; anything else is the old
                    // failure, which says only that it did not land.
                    _typing.value = Typing.failed()
                }
                return@launch
            }
            _typing.value = Typing.open()
        }
    }

    /** Opens the input bar. Deliberate: the owner pressed something. */
    fun openTyping() { _typing.value = Typing.open() }

    /** Closes it, discarding any outstanding report - that was about a keystroke, not the session. */
    fun closeTyping() { _typing.value = Typing.close() }

    /**
     * The owner put the note away, whichever source produced it.
     *
     * **One control for one surface.** The card does not say which of the two sources it came from and
     * the owner should not have to know - so this clears both, and whichever was not showing was
     * already clear.
     *
     * It does not clear [recovering]: whether something failed is a fact about the connection, and
     * dismissing a card is not a statement about it. Dismissing "reconnecting" and then recovering
     * should still say so.
     *
     * The typing half goes back to composing rather than closing the bar - see Typing.dismissReport.
     */
    fun dismissNotice() {
        _link.value = LinkNote.None
        _typing.value = Typing.dismissReport(_typing.value)
        // Cleared here rather than on a timer of its own, so every note on that one surface goes away
        // by the same route and none can outlive the others.
        _recalibrated.value = null
        // **And the mutation note, since REQ-0035 put one on this screen.** Without this the pane note
        // has no way out: the session screen's auto-expire runs through here, so a note this route did
        // not clear would sit over the terminal until the session was closed - which is precisely the
        // outliving the sentence above rules out. The list screen clears the same field through
        // dismissMutationNote and is unaffected.
        _mutation.value = MutationNote.None
    }

    /**
     * The owner edited the draft. It goes to the field now, to memory now, and to disk shortly — and
     * any notice about a previous send goes away, because this is a different one.
     */
    fun editDraft(draft: String) {
        val sessionId = watchingId() ?: return
        heldDrafts[sessionId] = draft
        _draft.value = draft
        _typing.value = Typing.edited(_typing.value)
        scheduleWrite(sessionId, draft)
    }

    /**
     * Asks the laptop to make its terminal [columns] wide, so the programs re-render themselves.
     *
     * **On request only, never on connect.** The owner's window is theirs; an app that resized it the
     * moment the phone appeared would be moving something on a machine they are sitting at. See
     * [restoreWindow] for the way back, which the same screen offers.
     *
     * The poll loop is left running: the next tick reads the re-rendered screen, which is the point.
     */
    fun fitToPhone(boxWidthDp: Int, characterWidthMilliDp: Int, recalibrate: Boolean = false) {
        val watching = (_state.value as? AgtermUiState.Sessions)?.watching ?: return
        scope.launch {
            val ok = withConnection(
                // **A fit the laptop declines is a note, not a new screen** - REQ-0037. Without this
                // the refusal falls to the branch that drops a healthy socket and replaces his
                // terminal with the bridge's sentence about probe widths. The reason is deliberately
                // NOT rendered: it is arithmetic addressed to the log, and putting it in front of him
                // is the defect REQ-0017 removed once already.
                onRefusedContent = { _mutation.value = MutationNote.FitRefused },
            ) {
                // What comes back is the count the LAPTOP measured. Held so the screen shows a number
                // that was observed rather than one this app computed.
                // **What the BRIDGE says, adopted whole.** No local flag is set here - see FitState.
                // **The pane comes from the same field the screen and the keystroke read** — REQ-0036.
                // The fit is FOR the pane he is looking at, so it must be told which one, and it must
                // be told by the one value that already decides where a read and a press go. A second
                // source for "which pane" is the REQ-0032 defect waiting to be reintroduced in a third
                // place.
                val answer = it.resize(
                    watching.id, pane, boxWidthDp, characterWidthMilliDp,
                    FitToPhone.TERMINAL_HORIZONTAL_PADDING_DP,
                    recalibrate,
                )
                _fit.value = answer
                // **On the ANSWER, not on anything looking different** - REQ-0033. A recalibration
                // that lands on the same count changes nothing on screen, so a report keyed on the
                // picture moving would confirm success only in the cases the owner could already see.
                // The event is that we asked and the laptop replied.
                if (recalibrate) _recalibrated.value = answer.columns
                answer
            }
            // Nothing is assumed on failure: the value stays whatever the laptop last said.
            @Suppress("UNUSED_EXPRESSION") ok
        }
    }

    /**
     * The setting as the LAPTOP holds it. `enabled` is null until it has answered, and that is shown
     * as "not known yet" rather than as off - see FitState for the bug that rule exists to prevent.
     */
    /**
     * The column count a just-finished RECALIBRATION reported, or null — REQ-0033.
     *
     * **Set only when the owner forced one**, never on an ordinary fit. A note on every press would be
     * chatter about a thing he can already see happen; this exists because the deliberate act is the
     * one with no visible result of its own — a recalibration that lands on the same count changes
     * nothing on screen.
     *
     * Cleared by [dismissNotice] like every other note, and by the screen's own timer through it.
     */
    private val _recalibrated = MutableStateFlow<Int?>(null)
    val recalibrated: StateFlow<Int?> = _recalibrated.asStateFlow()

    private val _fit = MutableStateFlow(FitState(enabled = null, columns = 0))
    val fit: StateFlow<FitState> = _fit.asStateFlow()

    /**
     * Takes the laptop's answer, and **structurally cannot turn a non-answer into "off"**.
     *
     * A reply that does not carry the flag - an older bridge, a shape we have not met - decodes as
     * null, and null means NOT ANSWERED. Writing it through as false would unpress a button whose fit
     * is still applied, inviting the owner to press it again and re-apply something already in force:
     * the inverse of the bug being fixed here, and worse than it.
     *
     * Same shape as the `omitempty` bool that could not say "off" and left the control dead all
     * morning. That one was a wire that could not express a state; this is a renderer that must not
     * invent one. Held by `ThePollNeverUnpressesAButtonItWasNotToldAbout`.
     */
    private fun adoptFit(reported: FitState) {
        if (reported.enabled == null) return
        _fit.value = reported
    }

    /**
     * Re-applies a fit the laptop already has, for the geometry now on screen — REQ-0041.
     *
     * ### The intent, in his words
     *
     * *"keep this terminal readable on this screen"* — not a one-shot action attached to a press.
     * Switching sessions changes the geometry, because a split session's pane is a fraction of the
     * window and an unsplit one is the whole of it; switching panes changes it too. So both count.
     *
     * ### Three rules, and the third is the one that is easy to get wrong
     *
     *  - **A cached entry applies silently.** He has already answered that question for that geometry
     *    and asking again would be asking twice.
     *  - **A missing entry does NOT calibrate.** A calibration is a visible hunt across his window, and
     *    one starting because he tapped a row in a list is a surprise arriving from a machine he is not
     *    looking at. One press is a smaller cost than a window that moves on its own.
     *  - **ONLY [Refit.NeedsFit] PUTS ANYTHING ON SCREEN.** A refusal, a dropped link, a bridge too old
     *    to understand the request, a session that vanished — every one of them is silent. He did not
     *    press anything, so there is nothing to tell him about. Held by `RefitTest`, because the
     *    tempting bug here is the sympathetic one: a link dropping mid-switch feels worth mentioning,
     *    and mentioning it puts a note in front of him for an action he never took.
     *
     * ### Off means nothing happens, and "off" has three states
     *
     * `enabled` is true, false, or **null for not-answered**. Only true acts. Treating null as on would
     * resize his window off the back of a reply that never came — the inverse of [adoptFit]'s rule and
     * the same lesson.
     */
    fun refit(boxWidthDp: Int, characterWidthMilliDp: Int) {
        if (_fit.value.enabled != true) return
        val watching = (_state.value as? AgtermUiState.Sessions)?.watching ?: return
        scope.launch {
            // **Silent on every failure**, which is why the refusal handler does nothing at all: the
            // automatic path may not put the bridge's words in front of him for something he did not
            // ask for. The screen keeps whatever it was showing.
            // **Silent on every failure.** Both handlers do nothing: the automatic path may not put
            // the bridge's words in front of him, and may not take his terminal away either, for
            // something he did not ask for. The screen keeps whatever it was showing.
            withConnection(onRefusedContent = {}, onRefused = {}) { bridge ->
                when (val answer = bridge.refit(
                    watching.id, pane, boxWidthDp, characterWidthMilliDp,
                    FitToPhone.TERMINAL_HORIZONTAL_PADDING_DP,
                )) {
                    // The laptop's own answer, adopted whole - the same rule as every other reply.
                    is Refit.Applied -> adoptFit(answer.fit)
                    Refit.NeedsFit -> _mutation.value = MutationNote.NeedsFit
                }
            }
        }
    }

    /** Puts the owner's window back, geometry and zoom, as it was before the first resize. */
    fun restoreWindow() {
        scope.launch {
            // Cleared even if the call fails. A toggle stuck in the fitted position after a failed
            // restore would offer the owner nothing but the action that just did not work.
            withConnection { _fit.value = it.restoreWindow() }
        }
    }

    /** Back to the list. Stops the polling immediately rather than at the next tick. */
    fun stopWatching() {
        poller?.cancel()
        poller = null
        // They left on purpose, so a heal must not drag them back into a session they closed.
        lastWatched = null
        update { it.copy(watching = null, screen = "", stale = false) }
    }

    /** The owner's own disconnect. Closes the connection and says so, without calling it a failure. */
    fun disconnect() {
        release()
        _state.value = AgtermUiState.Disconnected
    }

    /** Called when the screen goes away. Frees the connection rather than holding one in the background. */
    fun release() {
        // **Back to "not known yet", never to "off".** The setting lives on the laptop and survives
        // this connection; asserting off here would be inventing an answer about their window, which
        // is the second-source-of-truth bug this whole change removes. The next reply says what it is.
        _fit.value = FitState(enabled = null, columns = 0)
        // A note is about the connection this screen was holding. Keeping it would greet the owner on
        // their next visit with news about a socket that no longer exists.
        _link.value = LinkNote.None
        // Same rule for a create or rename that failed: it is news about a button pressed on a screen
        // that is going away.
        _mutation.value = MutationNote.None
        recovering = false
        poller?.cancel()
        poller = null
        // The retry loop must not outlive the screen: a healer left running would reconnect in the
        // background for a UI nobody is looking at.
        healer?.cancel()
        healer = null
        lastWatched = null
        runCatching { connection?.close() }
        connection = null
    }

    /**
     * Runs [block] against a live connection, turning every failure into a state.
     *
     * Returns false when the caller should stop — a failed poll must not keep retrying against a
     * laptop that has told us the identity is wrong, and a tight loop of failing handshakes on a
     * metered connection is its own defect.
     */
    /**
     * [onRefusedContent] is how a caller says *"and if the laptop refuses what I sent, that is mine
     * to explain"* — REQ-0017.
     *
     * **The default throws nothing away and changes no screen**: without a handler a content refusal
     * is simply a false return, exactly as any other failure, minus the connection drop it never
     * deserved. Callers that can put the owner back where they were pass one.
     */
    private suspend fun withConnection(
        onRefusedContent: (String) -> Unit = {},
        /**
         * Handles a plain refusal WITHOUT touching the screen — REQ-0041, and only the automatic
         * re-apply passes one.
         *
         * **The default is unchanged and stays unchanged**: a refusal drops the connection and puts the
         * bridge's words on the screen, because the owner pressed something and is owed an answer.
         *
         * On the automatic path he pressed nothing. A bridge too old to understand `cached_only`
         * refuses the whole request — `Decode` disallows unknown fields — and without this that
         * refusal would replace his terminal with an error screen because he tapped a row in a list.
         * That is the fit refusal taking his screen, one layer up, and REQ-0037 had to undo it once.
         */
        onRefused: (() -> Unit)? = null,
        block: (BridgeConnection) -> Unit,
    ): Boolean = attemptWithConnection(block, onRefusedContent, onRefused, attempt = 0)

    /**
     * One attempt, and the retry that follows a transient failure.
     *
     * [attempt] indexes [Reconnect.QUIET_RETRY_DELAYS_MS]. Recursive rather than a loop so the whole
     * body - including the key pre-flight, which must run again after a dropped connection - is
     * repeated exactly as it was the first time.
     */
    private suspend fun attemptWithConnection(
        block: (BridgeConnection) -> Unit,
        onRefusedContent: (String) -> Unit,
        onRefused: (() -> Unit)?,
        attempt: Int,
    ): Boolean =
        withContext(io) {
            try {
                // The key first, before any socket exists to be blamed for it. This is the guard that
                // keeps the owner's complaint 5 from coming back: a phone that cannot use its own key
                // must never report that the laptop is not answering.
                if (connection == null) {
                    when (keyState()) {
                        SigningState.Ready -> Unit
                        // A key that can never sign. There is nothing to wait for and nothing to
                        // prompt for - the remedy is a new identity, which the owner asks for on the
                        // pairing screen.
                        SigningState.Unusable -> {
                            _state.value = AgtermUiState.Failed(WireFailure.IdentityUnusable)
                            return@withContext false
                        }
                        // No key at all. Fall through and let connect() answer - which says NotPaired
                        // when no laptop is stored. Reporting an identity failure here is what told a
                        // brand-new owner their key was broken before telling them there was nothing
                        // to connect to.
                        SigningState.Absent -> Unit
                    }
                }
                val bridge = connection ?: connect() ?: run {
                    _state.value = AgtermUiState.NotPaired
                    return@withContext false
                }
                connection = bridge
                block(bridge)
                // Something worked. If something had failed on the way here, that is a recovery the
                // owner watched happen with no explanation - see LinkNote.
                if (recovering) {
                    recovering = false
                    _link.value = LinkNote.Reconnected
                }
                true
            } catch (e: WireException) {
                dropConnection()
                // **A bridge that is restarting is not an error screen.** See Reconnect: a refused
                // connection, or a router with nothing behind it, is what a two-second deploy looks
                // like from here, and it heals on its own. Anything about identity goes straight to
                // the screen - retrying a security boundary would turn it into a spinner.
                if (Reconnect.isTransient(e.failure) && attempt < Reconnect.QUIET_RETRY_DELAYS_MS.size) {
                    // **The quiet phase stops being silent.** Waiting one out is what the phone is
                    // doing, and until now the owner saw a frozen screen and no account of the gap.
                    // The note says that and nothing about why - from here a restarting bridge, a
                    // sleeping laptop and a lost network are the same silence.
                    recovering = true
                    _link.value = LinkNote.Reconnecting
                    delay(Reconnect.QUIET_RETRY_DELAYS_MS[attempt])
                    return@withContext attemptWithConnection(block, onRefusedContent, onRefused, attempt + 1)
                }
                _state.value = AgtermUiState.Failed(e.failure)
                // Keep trying on its own once the screen is up, so it heals without a tap.
                startHealing(e.failure)
                false
            } catch (e: ContentRefused) {
                // **The connection is fine and the screen stays exactly where it is.** REQ-0017.
                //
                // This branch is the whole fix for what the owner saw: a complaint about their pasted
                // text used to fall into the case below, which drops a healthy socket and replaces
                // the terminal, the session list and the input bar with a full-page error carrying
                // our internal key names. Nothing about the laptop was wrong; one request was.
                //
                // No dropConnection, no state change, no retry: retrying would send the identical
                // bytes and get the identical answer. The caller is handed the bridge's words and
                // decides what the owner reads - which is never this string.
                onRefusedContent(e.reason)
                false
            } catch (e: BridgeRefused) {
                // **A caller that took responsibility for the refusal keeps the screen** — REQ-0041.
                //
                // Checked before the pane fallback and before anything is dropped, because a caller
                // that opted out of the screen has opted out of all of it: the owner pressed nothing,
                // so there is no answer owed to him and no reason to tear down a healthy socket.
                if (onRefused != null) {
                    onRefused()
                    return@withContext false
                }
                // **The pane went away underneath us — fall back rather than keep asking for it.**
                //
                // The pane can be DESTROYED on the Mac between one poll and the next, and every
                // request after that names a pane the laptop no longer has. Dropping to Left makes
                // the next poll succeed; leaving the pane alone would make it fail forever and read
                // as the connection being broken, which it is not.
                //
                // **Destroyed, not collapsed** — the distinction REQ-0034 turns on. A collapsed pane
                // refuses nothing, so this never fires for it, which is correct: it still reads, and
                // the toggle above it is now present to leave it. The bridge's own sentence still reaches
                // the owner through the state below - this changes what we ASK for next, not what
                // they are told happened. REQ-0032.
                if (e.detail.contains(NO_SPLIT_PANE)) {
                    _pane.value = Pane.Left
                    // **The memory agrees with this path rather than fighting it** — REQ-0042. Without
                    // this, coming back to the session would ask for the destroyed pane again and land
                    // here again, every time.
                    //
                    // The session comes from the state rather than from a parameter: this is the
                    // generic connection helper and has no session of its own, and the refusal is
                    // always about the one being watched — which is the same reason the line above can
                    // set a single `_pane` without naming one.
                    (_state.value as? AgtermUiState.Sessions)?.watching?.let { forgetPane(it.id) }
                }
                // **The reason IS carried into the state, and the line below has always carried it.**
                //
                // This comment used to say the opposite — "deliberately not carried" — three lines
                // above the code that carries it. Whoever wrote it described an earlier design and
                // the code moved; a reader trusting it would have gone looking for where the string
                // was dropped, which is nowhere.
                //
                // What is true, and what the old comment was reaching for: this app does not
                // DIAGNOSE. It quotes. `reason` is a sentence the BRIDGE wrote for the owner, and
                // `detail` is the far end's own words kept beside it — see PLAN-0023 and
                // [AgtermUiState.Refused]. Nothing here invents a cause, which is the rule the
                // comment meant to defend.
                dropConnection()
                _state.value = AgtermUiState.Refused(e.reason, e.detail)
                false
            } catch (e: Exception) {
                dropConnection()
                if (attempt < Reconnect.QUIET_RETRY_DELAYS_MS.size) {
                    delay(Reconnect.QUIET_RETRY_DELAYS_MS[attempt])
                    return@withContext attemptWithConnection(block, onRefusedContent, onRefused, attempt + 1)
                }
                _state.value = AgtermUiState.Failed(WireFailure.CannotReach)
                startHealing(WireFailure.CannotReach)
                false
            }
        }

    /**
     * Keeps trying while the failure screen is showing, so a laptop that comes back is picked up
     * without the owner tapping anything.
     *
     * **Only for failures that can heal**, and only one healer at a time. It stops the moment the
     * state is no longer a failure - a success replaces the state, and this loop notices on its next
     * turn - and it is cancelled with everything else by [release].
     *
     * It resumes what the owner was doing rather than dumping them back to the list: an outage in the
     * middle of reading a session should end with that session on screen, not with the list and a
     * second tap to get back.
     */
    private fun startHealing(failure: WireFailure) {
        if (!Reconnect.isTransient(failure)) return
        if (healer?.isActive == true) return
        healer = scope.launch {
            while (isActive && _state.value is AgtermUiState.Failed) {
                delay(Reconnect.HEAL_INTERVAL_MS)
                if (_state.value !is AgtermUiState.Failed) return@launch

                // The list first, always. `watch` needs a Sessions state to attach to and returns
                // without doing anything from a failure state - so healing straight into a session
                // would be a loop that silently accomplishes nothing.
                val resumed = withConnection { bridge ->
                    val listing = bridge.sessions()
                    _fit.value = FitState(enabled = listing.fitEnabled, columns = listing.fitColumns)
                    _state.value = AgtermUiState.Sessions(listing.sessions)
                }
                if (!resumed) continue

                // Back to where they were. An outage in the middle of reading a session should end
                // with that session on screen, not with the list and a second tap to get back.
                lastWatched?.let { watch(it) }
                return@launch
            }
        }
    }

    private fun dropConnection() {
        runCatching { connection?.close() }
        connection = null
    }

    private fun update(transform: (AgtermUiState.Sessions) -> AgtermUiState.Sessions) {
        val current = _state.value
        if (current is AgtermUiState.Sessions) _state.value = transform(current)
    }

    companion object {
        /**
         * How often an open session is re-read.
         *
         * REQ-0008 measured the digest against 2 Hz. This is slower on purpose: 2 Hz is the rate that
         * proves the digest works, not the rate a phone on mobile data should hold. Every unchanged
         * poll is still a round trip even when it carries no text.
         */
        const val POLL_INTERVAL_MS = 2_000L
    }
}


/**
 * agterm's own words for a pane that is gone, matched on so the phone can stop asking for it.
 *
 * **Matched on the DETAIL, never on the owner-facing sentence.** The bridge rewrites the message for a
 * person and keeps the original beside it; that rewrite is allowed to change wording, and a match
 * against it would break silently the day somebody improved the English. This string is agterm's, it is
 * what `detail` carries, and if IT changes the fallback stops working while everything else still
 * behaves - which is the safe direction.
 */
private const val NO_SPLIT_PANE = "no split pane"
