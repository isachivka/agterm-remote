package dev.isachivka.agtermremote.agterm

/**
 * The one thing the notes surface is showing, whatever produced it.
 *
 * ### Why the typing report moved here
 *
 * The owner, on the first build of design/v1: *"notifications still in the keyboard block, but on
 * design it is in special place over terminal with x button to close"*. They are right, and the rule
 * that admits it is the rule that already governs this surface: **it carries what the app OBSERVED.**
 *
 * A typing outcome is observed. The bridge accepted the keystroke, or the laptop's screen did not
 * change, or it did not arrive at all — each of those is something this app watched happen, exactly as
 * a connection being waited out is. So it belongs on the same surface as [LinkNote].
 *
 * What is still forbidden has not moved an inch: **nothing about what any agent is doing or waiting
 * for.** No note here may be produced by anything except a fact this app witnessed.
 *
 * ### One surface, one occupant, and the precedence is stated rather than emergent
 *
 * Two sources now feed one place, so which wins is a decision and not an accident of ordering.
 *
 * **A connection note beats a typing note.** If the link is being waited out, a report about a
 * keystroke is stale at best — and at worst it says *sent* about a socket that is gone. The
 * connection is the precondition for the keystroke meaning anything, so it is the thing to say.
 *
 * ### The keystroke itself never appears here
 *
 * None of these carries a payload, for the reason [TypingState.Sent] gives: an earlier version named
 * the outstanding keystroke, which printed the owner's typing back at them — including, in the
 * password-prompt case its own copy describes, a password the terminal had deliberately hidden.
 * `TypingCopyTest` holds that, and it holds through this move.
 */
sealed interface Notice {

    /** The connection dropped and is being waited out. */
    data object Reconnecting : Notice

    /** It came back. */
    data object Reconnected : Notice

    /** A keystroke is with the bridge and the laptop has not shown anything yet. */
    data object Sent : Notice

    /**
     * The bridge took it and the laptop's screen did not change.
     *
     * **Ordinary, and not a failure.** A password prompt, a command still running, a key `vim`
     * swallowed. It is drawn in the same neutral colours as the other reports and never in the error
     * ones — moving this report to a card must not quietly promote it to an alarm.
     */
    data object NoChange : Notice

    /** It did not reach the laptop. The only one of these that is a failure. */
    data object Failed : Notice

    /**
     * A workspace or session was not created.
     *
     * **Said here because there is nowhere else the owner would see it.** A create that fails leaves
     * the list exactly as it was, so silence is indistinguishable from a phone that ignored the press
     * — and they would press again.
     */
    data object CreateFailed : Notice

    /**
     * A rename did not reach the laptop.
     *
     * Distinct from [CreateFailed] because the owner pressed a specific button, and a note that names
     * neither leaves them to work out which of the two things they did went wrong.
     *
     * **A name the bridge would REFUSE never arrives here.** That case keeps the dialog open with
     * their text still in it and says what shape of thing is wrong; this is only for a rename that was
     * well formed and did not cross the wire.
     */
    data object RenameFailed : Notice

    /**
     * A close or a delete did not go through.
     *
     * **The most important of the three failures to report**, because the owner has just pressed
     * Delete and the row is still there. Silence would read as the app ignoring them, and pressing
     * again is exactly the wrong instinct to encourage when the thing being pressed destroys work.
     *
     * It covers a refusal as well as a dead link — agterm keeps at least one workspace, so a delete
     * can legitimately come back refused, and the reason is shown by the screen's own refusal state.
     */
    data object DeleteFailed : Notice

    /**
     * The second pane could not be opened — REQ-0035.
     *
     * **Said because the alternative is a button that appears not to work.** The owner taps once for
     * the right pane. If the laptop refuses, or the link is gone, or a listing taken afterwards still
     * shows no pane, the icon stays exactly where it was — which is correct, and indistinguishable
     * from a tap the phone ignored. He would tap again, and the tap he is repeating is the one that
     * starts a shell.
     *
     * Distinct from [CreateFailed] because that one names a session or a workspace, and a note that
     * named neither would leave him working out which of the things he pressed went wrong.
     */
    data object PaneFailed : Notice

    /**
     * The laptop could not work out the width — REQ-0037.
     *
     * **This replaced a full-screen error, and that is the whole of it.** On 2026-08-26 a calibration
     * declined and the owner's terminal, session list and input bar were replaced by a page-filling
     * English sentence about *probe widths* — addressed to us, lifted out of a log, with nothing in it
     * he could act on. REQ-0017 abolished exactly that shape for pasted text; the fit path had grown
     * it back.
     *
     * **A fit that will not measure is not the link breaking.** The bridge answered, his session is
     * fine, and his window has been put back. That is one line on this surface, in words about his
     * terminal rather than about our probe — the arithmetic goes to the bridge's log, which is who it
     * was always addressed to.
     */
    data object FitRefused : Notice

    /**
     * This session's shape has never been fitted, and nothing was measured for it — REQ-0041.
     *
     * **The only thing the automatic re-apply ever puts on screen.** Switching to a session whose
     * geometry the laptop has not measured could have started a calibration; a calibration is a dozen
     * resizes over several seconds across a window he may not be looking at, and one beginning because
     * he tapped a row in a list is a surprise from a machine he is not watching.
     *
     * So he is told, and he presses when he wants it. **One press is a smaller cost than a window that
     * moves on its own.**
     */
    data object NeedsFit : Notice

    /**
     * A recalibration finished, and this is what the laptop measured — REQ-0033.
     *
     * ### Why it exists at all
     *
     * The owner could not tell the long press had done anything: *"анимацию надо сделать чтоб я понял
     * когда сработало, ну или там нотификацию, хоть что-то."*
     *
     * **And the terminal reflowing is not feedback.** A recalibration that lands on the same column
     * count changes nothing on screen, so anything keyed on the picture changing reports success only
     * in the cases he could already see. This fires on *we asked and the laptop answered*, which is
     * the event, and it fires identically whether the number moved or not.
     *
     * ### The only Notice carrying a payload, and the rule it is checked against
     *
     * The others deliberately carry none, because an earlier version named the outstanding keystroke
     * and printed the owner's own typing back at them — a password among it. **That rule is about
     * owner CONTENT.** [columns] is a number the laptop measured about its own window, already shown
     * on the fit note a few dp above this surface. It is not something they typed and it cannot become
     * so: it is an `Int`.
     */
    data class Measured(val columns: Int) : Notice
}

/**
 * What the mutation path last observed, for the surface above.
 *
 * Its own type rather than a nullable [Notice] so that "nothing has been attempted" and "the last
 * attempt succeeded" are the same state — because they are: a successful create or rename produces no
 * note at all. The list refreshes and the dialog opens on the new thing, which IS the result.
 */
enum class MutationNote {
    None,
    CreateFailed,
    RenameFailed,
    DeleteFailed,

    /**
     * The pane toggle asked for the right pane and did not get it — REQ-0035.
     *
     * **The first member of this enum produced by the SESSION screen rather than the list**, which is
     * why the precedence note in [noticeFor] stopped being hypothetical the day this was added. See
     * the paragraph there that predicted it.
     */
    PaneFailed,

    /**
     * The laptop declined to measure the width — REQ-0037. See [Notice.FitRefused].
     *
     * **The second member produced by the session screen**, after [PaneFailed]. Both sit in the header
     * a few dp apart, which is why the precedence in [noticeFor] stopped being hypothetical.
     */
    FitRefused,

    /**
     * A session was opened whose geometry has never been fitted — REQ-0041. See [Notice.NeedsFit].
     *
     * **Not a failure**, unlike every other member here. It is the one case where the automatic path
     * has something worth saying, and it says it where the other fit notes live.
     */
    NeedsFit,
}

/**
 * Which note the surface shows, or none.
 *
 * A pure function of the two sources so the precedence is a thing that can be read and tested, rather
 * than whichever composable happens to be written first.
 */
fun noticeFor(
    link: LinkNote,
    typing: TypingState,
    /**
     * The list screen's source. Defaulted to [MutationNote.None] so the session screen, which has no
     * mutations, is not made to pass an argument about a thing it cannot do.
     */
    mutation: MutationNote = MutationNote.None,
    /**
     * The column count a just-finished recalibration reported, or null — REQ-0033.
     *
     * Above typing and below the connection. It is the newer event and the one the owner deliberately
     * asked for, so a keystroke report from before he reached for the escape hatch is the stale one;
     * but a link in doubt still wins, because a measurement reported over a dead socket is a number
     * about a window nobody can currently see.
     */
    recalibrated: Int? = null,
): Notice? = when (link) {
    // The connection wins. Everything below it is a report about something that crossed, or failed to
    // cross, a link that is currently in doubt.
    LinkNote.Reconnecting -> Notice.Reconnecting
    LinkNote.Reconnected -> Notice.Reconnected
    // **Mutation beats typing, and the precedence is written down rather than argued impossible.**
    //
    // **They collide now, and the paragraph that said they could not is why nothing had to be
    // decided when it happened.** It read: "in practice they cannot collide: typing belongs to the
    // open session and creating and renaming belong to the list, and the owner is on one screen or
    // the other" - and then, in the same breath, that "cannot happen" is exactly the kind of claim
    // that stops being true when a later screen does both.
    //
    // REQ-0035 is that later screen. The pane toggle sits in the header above the typing bar, so a
    // failed pane and an outstanding keystroke are one tap apart on the same screen. The rule was
    // already here and already right, which is the whole return on having written it down instead of
    // letting the answer be whichever branch got typed first.
    //
    // Mutation is above typing because it is the newer event: a typing report describes a keystroke
    // from before they reached for the control.
    // **Mutation now sits ABOVE the measurement, and that order changed with REQ-0035.**
    //
    // It used to be below. A recalibration is a report that something the owner asked for HAPPENED; a
    // mutation note is a report that something he asked for did NOT. When both are outstanding the
    // failure is the one he needs, and the pane toggle put the two within one tap of each other for
    // the first time — the fit control and the pane control are neighbours in the same header.
    LinkNote.None -> when (mutation) {
        MutationNote.CreateFailed -> Notice.CreateFailed
        MutationNote.RenameFailed -> Notice.RenameFailed
        MutationNote.DeleteFailed -> Notice.DeleteFailed
        MutationNote.PaneFailed -> Notice.PaneFailed
        MutationNote.FitRefused -> Notice.FitRefused
        MutationNote.NeedsFit -> Notice.NeedsFit
        MutationNote.None -> if (recalibrated != null) {
            Notice.Measured(recalibrated)
        } else {
            when (typing) {
                TypingState.Sent -> Notice.Sent
                TypingState.NoChange -> Notice.NoChange
                TypingState.Failed -> Notice.Failed
                // Composing and Closed are not reports about anything.
                is TypingState.Composing, TypingState.Closed -> null
            }
        }
    }
}

/**
 * Whether a note takes itself away after a while.
 *
 * **[Notice.Reconnecting] is the only one that persists**, because it is the only one describing
 * something that is still happening: the phone is still trying, and a card that vanished mid-retry
 * would leave the owner watching a frozen screen with no account of it again.
 *
 * Everything else describes a moment that has passed. A card about a moment that has passed becomes
 * chrome if it sits there, which is what the owner was objecting to in the first place.
 */
fun expires(notice: Notice): Boolean = notice != Notice.Reconnecting

/**
 * How long a note that describes a finished moment stays before it takes itself away.
 *
 * **Which notes expire is [expires]' decision, not this constant's.** Only [Notice.Reconnecting]
 * persists, because it is the only one describing something still happening.
 *
 * Long enough to be read by someone who was looking at a frozen screen, short enough not to cover the
 * output it is announcing the return of.
 */
const val NoticeVisibleMs = 6_000L
