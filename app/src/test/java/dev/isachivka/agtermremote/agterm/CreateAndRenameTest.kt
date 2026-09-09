package dev.isachivka.agtermremote.agterm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Creating and renaming, REQ-0011, held where it can actually be run.
 *
 * The emulator is dead and `androidTest` only compiles, so every rule here that could live on the JVM
 * does. What is left for a device is whether a tap lands on a pixel; what is here is whether the rules
 * behind those taps are right.
 */
class CreateAndRenameTest {

    private fun session(id: String, workspace: String, name: String = "sh") =
        BridgeSession(id = id, workspaceId = workspace, workspace = "main", name = name, title = "", active = false)

    /**
     * Control characters built from their code points rather than typed.
     *
     * A raw control byte in source is invisible in every diff and every review, and one editor that
     * normalises it deletes the only thing the case tests while the test stays green. This file
     * already proved the point the hard way: the first draft embedded a literal NUL, which made `grep`
     * treat the whole file as binary.
     */
    private fun withCode(code: Int) = "before" + String(Character.toChars(code)) + "after"

    // --- the empty workspace, which is the reason the wire changed at all ------------------------

    /**
     * **A workspace with no sessions gets a heading.**
     *
     * This is the whole reason the bridge publishes workspaces. `workspace.new` makes an empty one —
     * measured against the live socket — and a grouping derived from sessions alone cannot describe
     * it, so the owner would press + and be shown nothing.
     */
    @Test
    fun `an empty workspace still gets a heading`() {
        val groups = groupByWorkspace(
            sessions = listOf(session("s1", "W1")),
            workspaces = listOf(BridgeWorkspace("W1", "main"), BridgeWorkspace("W2", "brand new")),
        )

        assertEquals(listOf("W1", "W2"), groups.map { it.id })
        assertEquals("the empty workspace lost its rows", 0, groups[1].sessions.size)
        assertEquals("brand new", groups[1].name)
    }

    /**
     * **Order is agterm's, not the order a first session happened to appear in.**
     *
     * The derivation can only put workspaces in the order their sessions arrive, which is right
     * exactly while that order and agterm's agree — a property nobody was holding.
     */
    @Test
    fun `the published order wins over the order sessions arrive in`() {
        val groups = groupByWorkspace(
            sessions = listOf(session("s1", "W2"), session("s2", "W1")),
            workspaces = listOf(BridgeWorkspace("W1", "first"), BridgeWorkspace("W2", "second")),
        )

        assertEquals(listOf("W1", "W2"), groups.map { it.id })
    }

    /**
     * **An older bridge sends nothing, and the screen still works.**
     *
     * Null is a bridge that predates the field, not a laptop with no workspaces — agterm keeps at
     * least one, so an empty list cannot occur. Collapsing the two would make an old bridge look like
     * an empty laptop and wipe the list.
     */
    @Test
    fun `without a published list the grouping still derives one`() {
        val groups = groupByWorkspace(
            sessions = listOf(session("s1", "W1"), session("s2", "W1")),
            workspaces = null,
        )

        assertEquals(listOf("W1"), groups.map { it.id })
        assertEquals(2, groups[0].sessions.size)
    }

    /** Nothing is ever dropped — a session whose workspace is missing from the list still appears. */
    @Test
    fun `a session whose workspace is not in the published list is still shown`() {
        val groups = groupByWorkspace(
            sessions = listOf(session("s1", "W9")),
            workspaces = listOf(BridgeWorkspace("W1", "main")),
        )

        assertEquals(listOf("W1", "W9"), groups.map { it.id })
        assertEquals(1, groups.first { it.id == "W9" }.sessions.size)
    }

    // --- the name check, which must agree with the bridge's -------------------------------------

    /**
     * **The phone's check and the bridge's must agree at the boundary.**
     *
     * They are in different languages and no type can join them, so the honest version of "keep them
     * in sync" is a test that pins the same numbers. If the phone were the looser of the two, the
     * owner would type a name, watch the dialog close, and see nothing change.
     */
    @Test
    fun `the length cap is exactly where the bridge puts it`() {
        assertNull(labelProblem("a".repeat(MaxLabelRunes)))
        assertEquals(LabelProblem.TooLong, labelProblem("a".repeat(MaxLabelRunes + 1)))
        assertEquals("the bridge's cap is 64 runes", 64, MaxLabelRunes)
    }

    /** Counted in code points, so a Cyrillic name gets the same allowance as an English one. */
    @Test
    fun `the cap counts characters and not bytes`() {
        assertNull(labelProblem("я".repeat(MaxLabelRunes)))
        assertEquals(LabelProblem.TooLong, labelProblem("я".repeat(MaxLabelRunes + 1)))
    }

    @Test
    fun `a name that is only whitespace is refused, and trimming happens first`() {
        assertEquals(LabelProblem.Empty, labelProblem("   "))
        assertEquals(LabelProblem.Empty, labelProblem(""))
        assertNull(labelProblem("  release notes  "))
        assertEquals("release notes", cleanLabel("  release notes  "))
    }

    /** C0, DEL and C1 — the same three ranges the bridge refuses, and the edges of each. */
    @Test
    fun `control characters are refused, including the C1 range`() {
        assertEquals(LabelProblem.ControlCharacter, labelProblem("two\nlines"))
        assertEquals(LabelProblem.ControlCharacter, labelProblem("tab\there"))
        for (code in listOf(0x00, 0x1b, 0x1f, 0x7f, 0x80, 0x85, 0x9f)) {
            assertEquals(
                "a control character at code point $code was accepted in a name",
                LabelProblem.ControlCharacter,
                labelProblem(withCode(code)),
            )
        }
        // And the characters just past each end are ordinary text, or the ranges are too wide - which
        // would refuse names the owner can legitimately type.
        for (code in listOf(0x20, 0x7e, 0xa0, 0x41f)) {
            assertNull(
                "code point $code is ordinary text and must be accepted",
                labelProblem(withCode(code)),
            )
        }
    }

    // --- a name the PHONE rejects is a local refusal ---------------------------------------------

    /**
     * **A name this phone will not send opens no socket and keeps the dialog open.**
     *
     * It is a local refusal, not a `Refused` screen: nothing was asked of the laptop, so nothing about
     * the laptop should be reported, and the owner's text has to survive or they retype it to find out
     * what was wrong with it.
     *
     * The dialog's confirm button is disabled for these already. That is a property of one composable
     * and can only be checked on a device, and there is no working emulator — so the same rule is held
     * here, where it runs.
     */
    @Test
    fun `a name the phone rejects never opens a socket`() = runBlocking {
        val sessions = AgtermSessions(
            connect = { throw AssertionError("a name the phone refuses must not open a socket") },
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
        )

        for (bad in listOf("", "   ", "a".repeat(MaxLabelRunes + 1), "two\nlines")) {
            sessions.renameSession("s1", bad)
            sessions.renameWorkspace("W1", bad)
        }

        // The connect lambda is the assertion: reaching it fails this test. And no note was raised,
        // because nothing was attempted — a local refusal is not a failure to report.
        assertEquals(MutationNote.None, sessions.mutation.value)
    }

    /**
     * And the other half: their text survives, because the dialog is still up.
     *
     * Closing it used to be the host's job, unconditionally, straight after asking for the rename — so
     * a name the phone refused would have closed the dialog and discarded what they typed regardless.
     */
    @Test
    fun `a rejected name leaves the dialog open`() = runBlocking {
        val listing = """{"ok":true,"sessions":[{"id":"A","workspace":"main","workspace_id":"W1","name":"agterm"}]}"""
        val sessions = AgtermSessions(
            connect = {
                BridgeConnection.ofStreams(
                    ByteArrayInputStream(List(50) { listing }.joinToString("\n").toByteArray()),
                    ByteArrayOutputStream(),
                )
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
        )
        sessions.refresh()
        withTimeout(5_000) { sessions.state.first { it is AgtermUiState.Sessions } }

        sessions.beginRename(RenameTarget.Session("A"))
        val opened = sessions.renaming.value
        assertEquals("the dialog opens on the name from the listing", "agterm", opened?.currentName)

        sessions.renameSession("A", "   ")

        assertEquals("a refused name closed the dialog and lost their text", opened, sessions.renaming.value)
    }

    // --- Delete lives in the modal, and only when a long press opened it -------------------------

    /**
     * **The owner's guard, as a rule the JVM can check.**
     *
     * Long-press-then-Delete is two deliberate acts, which is why this feature has no confirmation
     * dialog at all. The modal has four entry points and only two are long presses — creating a
     * workspace or a session also opens it — so on the create path the deliberate gesture has not
     * happened, and a tap of `+` followed by a mis-tap would destroy something with two ordinary
     * presses.
     */
    @Test
    fun `delete is offered for a long press and withheld after a create`() {
        val byPress = RenameRequest(RenameTarget.Session("s1"), "sh", RenameOrigin.LongPress)
        val byCreate = RenameRequest(RenameTarget.Session("s1"), "sh", RenameOrigin.Created)

        assertTrue("a long press is the gesture that earns Delete", byPress.mayDelete)
        assertEquals("a create has not had the deliberate gesture", false, byCreate.mayDelete)
    }

    /**
     * **The count agterm will never tell them.**
     *
     * Measured 2026-07-31: deleting a workspace takes every session inside it, silently, answering ok.
     * The list is behind the modal, so this number is the whole of what the owner is told about the
     * cost — and it is counted from the same listing that drew the rows, so it cannot disagree with
     * what they were just looking at.
     */
    @Test
    fun `the modal counts the sessions a workspace delete would take`() = runBlocking {
        val listing = """{"ok":true,"sessions":[
            {"id":"A","workspace":"main","workspace_id":"W1","name":"one"},
            {"id":"B","workspace":"main","workspace_id":"W1","name":"two"},
            {"id":"C","workspace":"other","workspace_id":"W2","name":"three"}
        ],"workspaces":[{"id":"W1","name":"main"},{"id":"W2","name":"other"}]}""".replace("\n", "")
        val sessions = AgtermSessions(
            connect = {
                BridgeConnection.ofStreams(
                    ByteArrayInputStream(List(50) { listing }.joinToString("\n").toByteArray()),
                    ByteArrayOutputStream(),
                )
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
        )
        sessions.refresh()
        withTimeout(5_000) { sessions.state.first { it is AgtermUiState.Sessions } }

        sessions.beginRename(RenameTarget.Workspace("W1"))
        assertEquals("W1 holds two sessions", 2, sessions.renaming.value?.sessionCount)

        sessions.beginRename(RenameTarget.Workspace("W2"))
        assertEquals(1, sessions.renaming.value?.sessionCount)

        // A session takes nothing with it, so the copy must not offer a number at all.
        sessions.beginRename(RenameTarget.Session("A"))
        assertEquals(0, sessions.renaming.value?.sessionCount)
    }

    /**
     * **The zero case, pinned — because a plural cannot express it in English and nothing else checks.**
     *
     * This was an `<item quantity="zero">` in the plural, and English never selects one: CLDR gives
     * English only `one` and `other`, so a count of nothing fell through to `other` and the button
     * read *"Delete workspace and its 0 sessions"* — the exact string the resource's own comment
     * called a bug.
     *
     * It is reachable in one gesture: close the last session in a workspace, then long-press its
     * header. It is also where a half-created workspace lands, since a workspace whose first session
     * failed is deliberately kept.
     *
     * Lint raised neither `UnusedQuantity` nor `ImpliedQuantity`, so the resource was silently never
     * selected and the whole gate was green over it. That is why the decision lives in Kotlin.
     */
    @Test
    fun `an empty workspace gets its own sentence rather than a plural of zero`() {
        assertEquals(
            DeleteCopy.EmptyWorkspace,
            deleteCopyFor(RenameTarget.Workspace("W1"), sessionCount = 0),
        )
        // Defensive on the same branch: a negative count is nonsense, and it must not reach a plural
        // either.
        assertEquals(
            DeleteCopy.EmptyWorkspace,
            deleteCopyFor(RenameTarget.Workspace("W1"), sessionCount = -1),
        )
    }

    /** And the counted cases still go to the plural, which is the only thing a plural is good for. */
    @Test
    fun `a workspace with sessions carries the count`() {
        assertEquals(
            DeleteCopy.WorkspaceWithSessions(1),
            deleteCopyFor(RenameTarget.Workspace("W1"), sessionCount = 1),
        )
        assertEquals(
            DeleteCopy.WorkspaceWithSessions(7),
            deleteCopyFor(RenameTarget.Workspace("W1"), sessionCount = 7),
        )
        // A session takes nothing with it, so its copy never mentions a number at all.
        assertEquals(DeleteCopy.Session, deleteCopyFor(RenameTarget.Session("s1"), sessionCount = 0))
        assertEquals(DeleteCopy.Session, deleteCopyFor(RenameTarget.Session("s1"), sessionCount = 3))
    }

    // --- the anchor, because a deleted row is a new way for one to vanish ------------------------

    /**
     * **A row the owner deleted themselves is not the same as one that vanished while they were away.**
     *
     * `restore` sends an unknown key to the top, which is right for a session closed on the laptop
     * behind their back. Here they are looking at the row, the group around it survives, and a jump to
     * the top is a lurch on the one gesture where they are surest of what they meant.
     */
    @Test
    fun `deleting the anchored session folds the anchor to its heading`() {
        val groups = groupByWorkspace(
            sessions = listOf(session("s1", "W1"), session("s2", "W1")),
            workspaces = listOf(BridgeWorkspace("W1", "main")),
        )
        val anchor = ListPosition.Anchor("s2", offset = 40)

        val moved = ListPosition.afterDelete(anchor, RenameTarget.Session("s2"), groups)

        assertEquals(ListPosition.headingKey("W1"), moved?.key)
        // Dropped for the same reason fold drops it: measured against a session row, wrong against a
        // heading of a different height.
        assertEquals(0, moved?.offset)
    }

    /** A delete somewhere else leaves the anchor exactly where it was. */
    @Test
    fun `deleting a different row leaves the anchor alone`() {
        val groups = groupByWorkspace(
            sessions = listOf(session("s1", "W1"), session("s2", "W1")),
            workspaces = listOf(BridgeWorkspace("W1", "main")),
        )
        val anchor = ListPosition.Anchor("s1", offset = 40)

        assertEquals(anchor, ListPosition.afterDelete(anchor, RenameTarget.Session("s2"), groups))
    }

    /**
     * **A deleted workspace leaves nothing to fold to**, so the top is the only honest answer — its
     * heading went with it and so did every row under it. The nearest surviving neighbour would be the
     * guess dressed as a restore that ListPosition refuses everywhere else.
     */
    @Test
    fun `deleting a workspace sends the anchor to the top, from inside or from its heading`() {
        val groups = groupByWorkspace(
            sessions = listOf(session("s1", "W1"), session("s2", "W2")),
            workspaces = listOf(BridgeWorkspace("W1", "main"), BridgeWorkspace("W2", "other")),
        )

        assertNull(ListPosition.afterDelete(
            ListPosition.Anchor("s1", 40), RenameTarget.Workspace("W1"), groups,
        ))
        assertNull(ListPosition.afterDelete(
            ListPosition.Anchor(ListPosition.headingKey("W1"), 0), RenameTarget.Workspace("W1"), groups,
        ))
        // A row in a workspace that was NOT deleted is untouched.
        assertEquals(
            ListPosition.Anchor("s2", 40),
            ListPosition.afterDelete(ListPosition.Anchor("s2", 40), RenameTarget.Workspace("W1"), groups),
        )
    }

    /**
     * And the whole point, end to end: after the delete lands and the listing no longer holds the row,
     * the re-pointed anchor still restores to a real position instead of dumping them at the top.
     */
    @Test
    fun `the re-pointed anchor survives into the listing that no longer holds the row`() {
        val before = groupByWorkspace(
            sessions = listOf(session("s1", "W1"), session("s2", "W1")),
            workspaces = listOf(BridgeWorkspace("W1", "main")),
        )
        val moved = ListPosition.afterDelete(ListPosition.Anchor("s2", 40), RenameTarget.Session("s2"), before)

        val after = groupByWorkspace(
            sessions = listOf(session("s1", "W1")),
            workspaces = listOf(BridgeWorkspace("W1", "main")),
        )
        val target = ListPosition.restore(moved, after, emptySet())

        assertEquals("the heading is row 0 and that is where they stay", 0, target?.index)

        // The control: without the re-pointing, the old anchor is simply not in the list any more.
        assertNull(ListPosition.restore(ListPosition.Anchor("s2", 40), after, emptySet()))
    }

    // --- a workspace is born with one session ----------------------------------------------------

    /**
     * **A half-created workspace is kept, not rolled back.**
     *
     * REQ-0011 refused this shape because a failure between the two calls left an empty workspace the
     * bridge could not describe and was not allowed to delete. Both halves are now false — the wire
     * publishes workspaces and the owner can delete one — so the honest outcome is to keep it, say the
     * create did not finish, and leave it on screen where a long press removes it.
     *
     * Rolling back would be the bridge destroying something nobody pressed a button for, which is a
     * different and worse thing than the owner deciding to.
     */
    @Test
    fun `a workspace whose first session fails is kept and shown, not rolled back`() = runBlocking {
        // **A refusal drops the connection**, so the refresh afterwards opens a NEW one — which is why
        // the replies are per-connection rather than one stream. The first connection sees the
        // workspace created and the session refused; the second serves the listing, which now holds
        // the empty workspace that really does exist on the laptop.
        val listing = """{"ok":true,"sessions":[],"workspaces":[{"id":"W9","name":"Workspace 9"}]}"""
        var opened = 0
        val sessions = AgtermSessions(
            connect = {
                val replies = if (opened++ == 0) {
                    listOf(
                        """{"ok":true,"created":{"id":"W9"}}""",
                        """{"ok":false,"error":"agterm said no"}""",
                    )
                } else {
                    List(50) { listing }
                }
                BridgeConnection.ofStreams(
                    ByteArrayInputStream(replies.joinToString("\n").toByteArray()),
                    ByteArrayOutputStream(),
                )
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
        )

        sessions.createWorkspace()
        withTimeout(5_000) { sessions.state.first { it is AgtermUiState.Sessions } }

        val listed = sessions.state.value as AgtermUiState.Sessions
        assertEquals(
            "the workspace that really exists must be on screen, or they press + again",
            listOf("W9"),
            listed.workspaces?.map { it.id },
        )
        assertEquals("the failure is reported", MutationNote.CreateFailed, sessions.mutation.value)
        // And the rename modal does NOT open on top of a failure note.
        assertNull(sessions.renaming.value)
    }

    // --- the Claude button's macro, REQ-0013 ------------------------------------------------------

    /**
     * A connection that records what was asked of it and can be told to refuse the first call.
     *
     * Written here rather than reused from the listing fixtures because what matters is the ORDER and
     * the COUNT of requests, which those do not observe.
     */
    private class Macro(val sessions: AgtermSessions, private val out: ByteArrayOutputStream) {
        /** What was written to the socket, one request per line. */
        fun requests(): List<String> = out.toString().trim().lines().filter { it.isNotBlank() }
    }

    private fun macroSessions(failFirstType: Boolean = false): Macro {
        val listing = """{"ok":true,"sessions":[{"id":"A","workspace":"main","workspace_id":"W1","name":"sh"}]}"""
        val firstType = if (failFirstType) """{"ok":false,"error":"no"}""" else """{"ok":true}"""
        // In order: the listing for refresh, one screen poll for watch, then a reply per type call.
        val replies = listOf(listing, """{"ok":true,"unchanged":true,"digest":"d"}""", firstType) +
            List(10) { """{"ok":true}""" }
        val out = ByteArrayOutputStream()
        val sessions = AgtermSessions(
            connect = {
                BridgeConnection.ofStreams(
                    ByteArrayInputStream(replies.joinToString("\n").toByteArray()),
                    out,
                )
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
            // Long enough that the poller fires once and never races the macro's replies - the
            // intermittent failure this test file's neighbours were bitten by.
            pollIntervalMs = 60_000,
            enterAfterTextMs = 0,
        )
        return Macro(sessions, out)
    }

    /** Drives the fixture to a watched session, and returns how many requests that took. */
    private suspend fun Macro.watching(): Int {
        sessions.refresh()
        withTimeout(5_000) { sessions.state.first { it is AgtermUiState.Sessions } }
        sessions.watch(BridgeSession("A", "W1", "main", "sh", "", false))
        withTimeout(5_000) { sessions.state.first { (it as? AgtermUiState.Sessions)?.watching != null } }
        return requests().size
    }

    /**
     * **The happy path: the command, then Return, in that order.**
     *
     * Two calls rather than one string with a newline in it, because `keys.Text` refuses a newline on
     * purpose — "type a line" and "press Return" are different acts, and this performs both rather
     * than smuggling one inside the other.
     */
    @Test
    fun `the macro types the command and then presses Return`() = runBlocking {
        val macro = macroSessions()
        val before = macro.watching()

        macro.sessions.runMacro(CLAUDE_COMMAND)

        val types = macro.requests().drop(before)
        assertEquals("exactly two calls: the text, then the key", 2, types.size)
        assertTrue("the command goes first", types[0].contains(CLAUDE_COMMAND))
        assertTrue("and it is TEXT, not a key name", types[0].contains("\"text\""))
        assertTrue("Return goes second", types[1].contains(ENTER_KEY))
        assertTrue("Return is sent as a KEY, from the allowlist", types[1].contains("\"key\""))
        // **No newline is ever smuggled into the text.** keys.Text refuses one on purpose, and this
        // feature performs the two acts rather than becoming the exception to that rule.
        assertEquals(false, types[0].contains("\\n"))
        assertEquals(false, types[0].contains("\"key\""))

        macro.sessions.stopWatching()
    }

    /**
     * **The one that matters: a failed text sends no Return.**
     *
     * Sending Return after the text failed presses it on whatever was already on the prompt — a
     * half-typed command, a confirmation waiting for an answer. The failure leaves the command unsent
     * and visible instead, which the owner can finish or clear themselves.
     */
    @Test
    fun `a failed command does not press Return on whatever was already there`() = runBlocking {
        val macro = macroSessions(failFirstType = true)
        val before = macro.watching()

        macro.sessions.runMacro(CLAUDE_COMMAND)

        val types = macro.requests().drop(before)
        assertEquals("the Return must not follow a text that did not land", 1, types.size)
        assertEquals("and what was sent was the text, not a key", false, types[0].contains("\"key\""))

        macro.sessions.stopWatching()
    }

    // --- the notes surface ----------------------------------------------------------------------

    /**
     * **The connection still wins**, and the mutation sits above typing.
     *
     * Typing and mutation belong to different screens and cannot realistically collide, which is
     * exactly why the answer is written down: "cannot happen" stops being true when a later screen
     * does both, and an emergent answer is not one anybody decided.
     */
    @Test
    fun `a connection note beats a failed create, which beats a typing report`() {
        assertEquals(
            Notice.Reconnecting,
            noticeFor(LinkNote.Reconnecting, TypingState.Sent, MutationNote.CreateFailed),
        )
        assertEquals(
            Notice.CreateFailed,
            noticeFor(LinkNote.None, TypingState.Sent, MutationNote.CreateFailed),
        )
        assertEquals(
            Notice.RenameFailed,
            noticeFor(LinkNote.None, TypingState.Closed, MutationNote.RenameFailed),
        )
        // And with nothing to report, the typing path is untouched by the new argument.
        assertEquals(Notice.Sent, noticeFor(LinkNote.None, TypingState.Sent, MutationNote.None))
        assertNull(noticeFor(LinkNote.None, TypingState.Closed, MutationNote.None))
    }

    /**
     * **A create or rename that WORKED produces no note.**
     *
     * The list refreshes and the dialog opens on the new thing; that is the result. A card announcing
     * a success the owner can already see is the chrome they objected to in the first place.
     */
    @Test
    fun `success is not a note`() {
        assertNull(noticeFor(LinkNote.None, TypingState.Closed, MutationNote.None))
        // **MutationNote has no Succeeded case at all**, which is the mechanism rather than the habit:
        // there is nowhere to put a success, so nobody can add one by reflex.
        //
        // The count is pinned so that growing this enum is a deliberate act. It caught REQ-0012 adding
        // DeleteFailed, REQ-0035 adding PaneFailed, REQ-0037 adding FitRefused and REQ-0041 adding
        // NeedsFit - and the names are asserted rather than the number alone, since a count says
        // nothing about what was added.
        assertEquals(7, MutationNote.entries.size)
        assertEquals(
            listOf("None", "CreateFailed", "RenameFailed", "DeleteFailed", "PaneFailed", "FitRefused", "NeedsFit"),
            MutationNote.entries.map { it.name },
        )
        // **The stated invariant was "every case here is a FAILURE", and REQ-0041 broke it.** Corrected
        // rather than quietly stepped past, because a guard whose prose no longer describes what it
        // guards is worse than no guard.
        //
        // NeedsFit is not a failure. Nothing went wrong: a session was opened whose shape the laptop
        // has never measured, and measuring it was DECLINED on purpose because a calibration moves his
        // window about for several seconds and he only tapped a row in a list.
        //
        // **The invariant that actually matters is untouched, and it is the narrower one**: there is no
        // Succeeded case, so nobody can add one by reflex. The test's own name says that and its first
        // assertion checks it. "Every case is a failure" was a description of the members that
        // happened to be here, and it had been mistaken for the rule.
        //
        // The real rule: a member of this enum is a reason the surface has something to SAY. A success
        // is not, because the list refreshing and the dialog opening on the new thing IS the result.
    }

    /** Both new notes take themselves away; only Reconnecting persists, and that has not changed. */
    @Test
    fun `the create and rename notes expire`() {
        assertTrue(expires(Notice.CreateFailed))
        assertTrue(expires(Notice.RenameFailed))
        assertEquals(false, expires(Notice.Reconnecting))
    }
}
