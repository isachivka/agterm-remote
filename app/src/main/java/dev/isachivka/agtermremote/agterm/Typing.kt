package dev.isachivka.agtermremote.agterm

/**
 * What the input bar is showing, as a value.
 *
 * ### The draft is not here
 *
 * Until 2026-09-06 [Composing] carried the draft, and every transition out of it — sending a key,
 * reporting a result, dismissing the report — came back with an empty one. So pressing Esc, an arrow,
 * or overpulling the terminal into PgUp wiped whatever the owner had typed - text in the field, an
 * overpull to scroll the terminal, and the field came back empty. The draft now lives
 * in `AgtermSessions`, per session and on disk, and this type says only what MODE the bar is in.
 *
 * ### The draft is not terminal content and must never be drawn as if it were
 *
 * What the owner sees in [TerminalBox] is only ever what the laptop sent back. Their own typing lives
 * in the bar, in its own place, until it has been sent AND come back on a poll. Echoing a draft into
 * the terminal would be inventing screen content. It also could not be made correct: a shell echoes
 * what you type, `vim` does not, a password prompt echoes nothing on purpose.
 *
 * ### Sent and acknowledged are different, and the gap is where failures hide
 *
 * **An unacknowledged keystroke and a keystroke that produced no output look identical on screen.**
 * So the states below distinguish what the owner can be in, and the copy for each says only what is
 * known.
 */
sealed interface TypingState {

    /**
     * The input bar is not open.
     *
     * **The default, and typing has to be entered deliberately.** The owner is sending real commands
     * to a real machine; an input that is always focused is one a pocket can type into.
     */
    data object Closed : TypingState

    /**
     * Open, and nothing is outstanding. The draft is beside this state, not in it — see the class
     * comment.
     *
     * [notice] is a sentence the input bar shows above the field, and it is **null almost always**.
     * It exists for one case: the laptop refused what was sent because of what the text
     * IS, so the owner is back here with their words intact and needs to know why.
     */
    data class Composing(val notice: Notice? = null) : TypingState

    /**
     * Why the last send did not happen, as a case rather than a string.
     *
     * **A closed set, so the copy lives in the resource file and not in the state machine** - and so
     * the bridge's own words, which name byte offsets and our internal key names, can never reach a
     * screen. The phone decides which of these applies from what it can see locally.
     */
    enum class Notice {
        /** The text has line breaks in it, and a terminal would read each one as Return. */
        LineBreaks,

        /** Something else in the text the laptop would not accept as typing. */
        NotTypable,

        /**
         * The laptop refused the FILE, on the picker path.
         *
         * Its own case rather than reusing [NotTypable], because that sentence says *"will not type
         * that"* and nothing was being typed.
         */
        FileRefused,
    }

    /**
     * Sent to the bridge; the laptop has not shown anything back yet.
     *
     * Its own state because it is genuinely unknown, not merely slow. It carries no payload: the
     * status line does not name the keystroke, because on a password prompt that would print the
     * password the terminal had just hidden. The draft, if there is one, is untouched by a key being
     * outstanding — it is beside this state, not inside it.
     */
    data object Sent : TypingState

    /**
     * Sent, acknowledged by the bridge, and the screen did not change.
     *
     * **The honest end of the ambiguity.** It does not say the keystroke failed, because it did not:
     * the bridge accepted it. It says the laptop showed nothing, which is a perfectly ordinary
     * outcome — a password prompt, a command still running, a key `vim` swallowed.
     */
    data object NoChange : TypingState

    /**
     * The keystroke did not reach the laptop.
     *
     * Distinct from [NoChange] because the remedies differ and so does the truth: this one did not
     * arrive, that one arrived and had no visible effect.
     */
    data object Failed : TypingState
}

/**
 * The input bar's state machine, as pure functions.
 *
 * No Compose, no coroutines, no Android. Every transition is a function of what it was handed, which
 * is what lets the part that decides whether the owner is told the truth be tested on the JVM.
 *
 * The transition that carries the weight is [onScreenSettled]: it is the only thing that can resolve
 * [TypingState.Sent], and it resolves it differently depending on whether the screen actually moved.
 */
object Typing {

    /** Opening the bar. Deliberate, from a control the owner pressed. */
    fun open(): TypingState = TypingState.Composing()

    /** Closing it. Any outstanding report is discarded with it; it was about a keystroke, not a session. */
    fun close(): TypingState = TypingState.Closed

    /**
     * The owner edited the draft. Only meaningful while composing, and its one effect on this state is
     * to put a notice away: it described a send, and this is a different one.
     */
    fun edited(state: TypingState): TypingState =
        if (state is TypingState.Composing) TypingState.Composing() else state

    /** The moment of sending: something is outstanding. */
    fun sending(): TypingState = TypingState.Sent

    /** The bridge refused, or could not be reached. */
    fun failed(): TypingState = TypingState.Failed

    /**
     * The laptop refused what was sent, because of what the text is.
     *
     * Back to composing with a notice; the draft itself is restored by the caller, who still has the
     * text. The notice is chosen from what the phone can see in the text, never by reading the
     * bridge's message. Line breaks are the case the owner actually hit — pasting a message from a
     * chat app — and everything else is honestly vaguer, because the phone genuinely does not know.
     */
    fun refused(text: String?): TypingState = TypingState.Composing(
        notice = if (text?.contains('\n') == true) TypingState.Notice.LineBreaks
        else TypingState.Notice.NotTypable,
    )

    /** The same, for the file picker: the laptop refused the FILE and the draft was never sent. */
    fun refusedFile(): TypingState = TypingState.Composing(notice = TypingState.Notice.FileRefused)

    /**
     * A poll landed. [changed] is whether the screen text actually moved.
     *
     * This is the only transition out of [TypingState.Sent], and the distinction it makes is the whole
     * point of the state existing: a screen that moved is an acknowledgement the owner can see, and a
     * screen that did not is an honest *nothing came back* rather than silence.
     *
     * A poll landing while the owner is composing or closed changes nothing.
     */
    fun onScreenSettled(state: TypingState, changed: Boolean): TypingState = when (state) {
        TypingState.Sent -> if (changed) TypingState.Composing() else TypingState.NoChange
        else -> state
    }

    /**
     * The owner put a typing report away. Back to composing; the bar stays open and usable. **It does
     * not close the bar**: dismissing a note is a statement about the note.
     */
    fun dismissReport(state: TypingState): TypingState = when (state) {
        TypingState.Sent, TypingState.NoChange, TypingState.Failed -> TypingState.Composing()
        is TypingState.Composing, TypingState.Closed -> state
    }

    /** Whether the bar should be accepting input right now. */
    fun isOpen(state: TypingState): Boolean = state !is TypingState.Closed
}

/**
 * Puts a path at the end of a draft, with one space between and never two.
 *
 * Separate and pure because it is the kind of one-liner that is wrong by a space for a month: `cat ` +
 * a pick should read `cat /tmp/...`, and `cat` + a pick should too. Trailing whitespace the owner typed
 * is theirs and is not trimmed — only the join is decided here.
 */
fun appendPath(draft: String, path: String): String =
    if (draft.isEmpty() || draft.endsWith(" ")) draft + path else "$draft $path"
