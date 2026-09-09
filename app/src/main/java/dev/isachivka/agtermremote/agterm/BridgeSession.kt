package dev.isachivka.agtermremote.agterm

/**
 * One session on the laptop, as the bridge publishes it.
 *
 * The bridge drops nearly everything agterm returns — overlays, watermarks, restore commands,
 * geometry — so this mirrors what it publishes and adds nothing. A field that exists here but not
 * there would be a field this app invented about the owner's machine.
 *
 * **This paragraph used to list `splits` among the things dropped, and that is no longer true.**
 * REQ-0032 widened the narrowing by exactly one field, because a feature finally needed it. Corrected
 * rather than deleted, because the sentence was the reason nobody looked for a split here.
 *
 * REQ-0034 then changed what that one field REPORTS without adding a second: the bridge reads agterm's
 * surfaces and publishes whether a second pane exists, rather than passing on agterm's `split`, which
 * answers the narrower question of whether both panes are on screen. The narrowing is still one field
 * wide.
 */
data class BridgeSession(
    val id: String,
    /**
     * Which workspace this session is IN, by identity.
     *
     * **Grouping is done on this and never on [workspace].** Two workspaces can share a name, and
     * grouping by name would merge them into one heading - the owner would see a session under a
     * workspace it is not in. Empty when an older bridge did not send it, which groups those sessions
     * together under one heading rather than dropping any of them.
     */
    val workspaceId: String,
    /** The workspace's name, for reading. May be empty; the screen supplies its own label then. */
    val workspace: String,
    val name: String,
    val title: String,
    val active: Boolean,
    /**
     * What the session is doing, as agterm's agent hooks report it.
     *
     * Defaulted to [SessionStatus.Idle] rather than left required, because that is what an older
     * bridge saying nothing MEANS — and it keeps every existing construction of this type honest
     * instead of forcing a value to be invented at each one.
     */
    val status: SessionStatus = SessionStatus.Idle,
    /**
     * Whether this session has a second pane the phone can address — **on screen or collapsed on the
     * Mac, either way.** REQ-0034.
     *
     * **What makes the pane toggle exist at all.** False means the control is ABSENT, not disabled —
     * this project ruled on that when the key bar's spare cell was left empty rather than made a dead
     * button, because a control that looks like a control and does nothing teaches the owner that this
     * app's buttons are decorative.
     *
     * ### It used to be called `split`, and the rename is the fix
     *
     * The old name was agterm's word, carrying agterm's meaning: **both panes visible**. Collapsing a
     * split on the Mac made it false while the pane went on existing and went on reading perfectly
     * from the phone — so a session opened in that state offered no toggle at all, and its second
     * pane was unreachable from the phone though nothing was wrong with it.
     *
     * **The symptom first written here was wrong and is corrected rather than deleted.** It said the
     * toggle vanished *underneath* him, leaving him on the right pane with no way back. That cannot
     * happen: the row this field lives on is a snapshot taken when the session is opened — see
     * [AgtermSessions.togglePane] — so it does not change while he is looking at the screen. The
     * defect was real and narrower, and the mistake is logged as entry 15 in
     * `docs/qa/instruments-that-lied.md`: a failure mode derived from reading two gates, without
     * asking when the value they read can change.
     *
     * The wire key is still `split` — see the bridge's `api.Session.SplitPane` for why the spelling
     * outlived the meaning.
     *
     * Defaulted to false so every existing construction of this type stays honest, and so a bridge too
     * old to say anything produces no toggle rather than one that would refuse on every press.
     */
    val splitPane: Boolean = false,
)

/**
 * A session's screen, or the news that it has not changed.
 *
 * `Unchanged` is not an empty screen and the difference is load-bearing: REQ-0008 measured that 95%
 * of idle poll traffic conveys nothing, so the digest exists to avoid moving those bytes. Rendering
 * `Unchanged` as "no output" would put a blank terminal in front of the owner every time their
 * session was quiet — the opposite of what the digest is for.
 */
sealed interface ScreenText {

    /** Fresh text, with the digest to send back next time. */
    /**
     * [styled] says [body] carries SGR sequences, because the bridge read it through zmx. False on a
     * plain read AND on the silent fallback from a styled request, so the box parses escapes only
     * when there are some to parse.
     */
    data class Text(val body: String, val digest: String, val styled: Boolean = false) : ScreenText

    /** The far end has the same bytes we already hold. Keep showing what is on screen. */
    data object Unchanged : ScreenText
}

/**
 * One poll's whole answer: the text, and the width setting as the laptop holds it.
 *
 * **The setting rides on the poll because the poll is already crossing the wire.** The bridge puts
 * `fit_enabled` on every reply — `every_reply_test.go` fails if any reply omits it — and this app used
 * to parse the text out and drop the flag on the floor, so the toggle was only ever corrected by
 * `refresh()`, which runs on entering the screen and never again.
 *
 * That made a lost `resize` reply permanent for as long as the owner stayed on the screen: the window
 * resized, the button read unpressed, and the only way back was to leave the session and come back.
 * A value that can only be set by the action that changed it is wrong for exactly as long as any one
 * call is lost, and "until they navigate away" is not a bounded time.
 */
data class ScreenUpdate(val text: ScreenText, val fit: FitState)


/**
 * The width setting AS THE BRIDGE HOLDS IT. The phone displays this and keeps no copy of its own.
 *
 * ### Why there is no local flag any more
 *
 * There was one, and it caused the bug that blocked the owner on 2026-07-30: their cache read
 * `enabled: false` while they were pressing a button they expected to turn it on, so the press sent
 * the opposite of what they intended and the feature looked dead.
 *
 * **A local copy of remote state is a second source of truth**, and the moment the two disagree the
 * control does the opposite of what the owner means. The bridge owns the setting — it is what
 * persists it across reconnects and restarts — so the bridge's answer is the only version.
 *
 * [enabled] is null before the laptop has answered. **That is rendered as "not known yet", never as
 * off.** A moment of blankness costs nothing; a confident wrong toggle costs them a feature that
 * appears dead, which is exactly what happened.
 */
data class FitState(val enabled: Boolean?, val columns: Int)

/**
 * What the laptop did with an automatic re-apply — REQ-0041.
 *
 * ### Two outcomes, and neither of them is a failure
 *
 * The phone asks *"apply the fit you already have for this geometry, and measure nothing."* The laptop
 * either has one or it does not, and **not having one is an answer to that question** rather than an
 * error — which is why it is a case of this type and not an exception. Dressing a legitimate answer as
 * a failure is how the fit refusal came to replace the owner's terminal with a full-page error.
 *
 * A third outcome — the link did not answer — is deliberately **absent**. Everything that goes wrong on
 * this path is silent: he did not press anything, so there is nothing to report to him. See
 * [AgtermSessions.refit].
 */
sealed interface Refit {

    /** The laptop had a fit for this geometry and applied it. Silent: he already answered this once. */
    data class Applied(val fit: FitState) : Refit

    /**
     * The laptop has never measured this geometry, and was asked not to measure it now.
     *
     * **The only outcome on this path that puts anything on screen.** A calibration is a visible hunt
     * across a window he may not be looking at, so he is told and left to press.
     */
    data object NeedsFit : Refit
}

/**
 * The session list plus the width setting, as one reply.
 *
 * ### Why the setting travels with the list
 *
 * **So the phone knows the state before the owner touches anything.** It used to arrive only on a
 * response to `resize`, and the toggle is gated on having seen it — so learning the state required
 * pressing the button, and pressing required knowing the state. A deadlock by construction, and the
 * owner pressed a control that was doing exactly what it was told across three builds.
 *
 * The bridge sets it on every reply now, centrally, so no verb can forget. [fitEnabled] is null only
 * when the laptop genuinely has not said — an older bridge — and that is rendered as "not known yet",
 * never as off.
 */
data class SessionListing(
    val sessions: List<BridgeSession>,
    /**
     * Every workspace the laptop holds, in agterm's own order — or null when the bridge did not say.
     *
     * **Null and empty are not the same thing here, which is why this is nullable.** Null means an
     * older bridge that predates the field, and the screen falls back to deriving the grouping from
     * the sessions. An empty list would mean a laptop with no workspaces at all, which agterm does not
     * permit — it keeps at least one. Collapsing the two would make an old bridge look like an empty
     * laptop and wipe the list.
     */
    val workspaces: List<BridgeWorkspace>? = null,
    val fitEnabled: Boolean? = null,
    val fitColumns: Int = 0,
)

/**
 * One workspace on the laptop, as the bridge publishes it.
 *
 * ### Why this exists when [BridgeSession] already carries a workspace id and name
 *
 * **A workspace holding no sessions cannot be described by the session list.** The grouping is
 * rebuilt from the sessions, so a workspace with none produces no heading and simply is not there.
 *
 * That was harmless until the owner could create one. `workspace.new` makes exactly that — an empty
 * workspace, measured against the live socket on 2026-07-31 — so without this the owner would press +
 * and be shown nothing at all.
 *
 * It also makes the ORDER right rather than approximately right. Before this, workspaces appeared in
 * the order their first session happened to appear in, which is correct only while agterm's order and
 * that order agree.
 */
data class BridgeWorkspace(
    val id: String,
    /** May be empty. The screen supplies its own label then, rather than the bridge inventing one. */
    val name: String,
)
