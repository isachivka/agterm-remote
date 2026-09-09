package dev.isachivka.agtermremote.agterm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * One button, two states — REQ-0035.
 *
 * The owner: *"если сессия есть мы её показываем, если её нет мы её создаём и потом показываем"*.
 *
 * ### The property every test here is about
 *
 * **The icon must never be lit for a pane the phone is not reading.** Going right can start a shell on
 * the owner's Mac and can fail three different ways — the laptop refuses, the link drops, or the pane
 * is gone again by the time anyone looks. In every one of them the control stays where it was and says
 * so, rather than lighting the right half over a screen it cannot read.
 *
 * So nothing in `togglePane` sets [Pane.Right] because a call returned ok. It is set only after a
 * listing taken AFTERWARDS says the pane is there.
 */
class TogglePaneTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    /**
     * A holder that will not poll during the test.
     *
     * The poll and the toggle read the same canned stream, so a poller running every millisecond
     * would eat the replies these tests are aiming at `sessions()` — the flake `unpolledHolder`
     * exists for in `AgtermSessionsTest`, met again here.
     */
    /** What the phone actually put on the wire, so a call can be asserted instead of assumed. */
    private val sent = ByteArrayOutputStream()

    private fun wire() = sent.toString()

    private fun holder(vararg replies: String): AgtermSessions {
        return AgtermSessions(
            connect = {
                BridgeConnection.ofStreams(
                    ByteArrayInputStream(replies.joinToString("\n").toByteArray()),
                    sent,
                )
            },
            scope = scope,
            io = Dispatchers.Unconfined,
            pollIntervalMs = 600_000,
        )
    }

    private fun listing(splitPane: Boolean) =
        """{"ok":true,"sessions":[{"id":"A","workspace":"main","name":"agterm","active":true,""" +
            """"split":$splitPane}]}"""

    private val screen = """{"ok":true,"text":"output","digest":"d1"}"""
    private val ok = """{"ok":true}"""

    private suspend fun AgtermSessions.settled() =
        withTimeout(5_000) { state.first { it !is AgtermUiState.Loading } } as AgtermUiState.Sessions

    /**
     * Opens the session, leaving the canned stream positioned after the listing and the first poll.
     *
     * `connection` is cached by `withConnection`, so every call in a test reads from ONE stream in
     * order: the listing `refresh` asks for, the single screen read `watch` does on entry, and then
     * whatever the toggle asks for.
     */
    private suspend fun AgtermSessions.watching(): AgtermSessions {
        refresh()
        watch(settled().sessions.first())
        return this
    }

    /**
     * **The pane already exists: the phone switches and creates nothing.**
     *
     * One listing to ask, and no `pane.open`. The reason has changed and the assertion has not: it used
     * to be that revealing a collapsed pane would rearrange his window, which he had not asked for.
     * REQ-0042 is him asking for exactly that — *"при переключении мы держим их фуллскрин"* — so the
     * phone now DOES rearrange it, with `pane.show`.
     *
     * What must still never happen here is a `pane.open`, because that one can start a shell.
     */
    @Test
    fun `an existing pane is shown without asking the laptop to open anything`() = runBlocking {
        val sessions = holder(listing(true), ok, screen, listing(true), ok).watching()

        sessions.togglePane()

        assertTrue("a pane that already exists was created again", "pane.open" !in wire())
        assertTrue("the pane was not maximized on his Mac", "pane.show" in wire())
        assertEquals(Pane.Right, sessions.paneShown.value)
        assertEquals("nothing failed, so nothing is reported", MutationNote.None, sessions.mutation.value)
    }

    /**
     * **The pane does not exist: it is created, confirmed, and only then shown.**
     *
     * Four replies in order — the listing that says there is none, the `pane.open`, the listing that
     * says there is one now, and the `pane.show` that maximizes it.
     */
    @Test
    fun `a missing pane is created and then shown`() = runBlocking {
        val sessions =
            holder(listing(false), screen, listing(false), ok, listing(true), ok).watching()

        sessions.togglePane()

        assertEquals(Pane.Right, sessions.paneShown.value)
        assertEquals(MutationNote.None, sessions.mutation.value)
    }

    /**
     * **THE FAILURE THE WHOLE DESIGN IS FOR.** The create is accepted and the pane still is not there.
     *
     * agterm answers `ok` with the session id whether it created, revealed or did nothing — so `ok` is
     * not evidence a pane exists. Something closing it in the moment between is far-fetched; the point
     * is that the code does not rely on it being far-fetched. The icon stays left and says why.
     */
    @Test
    fun `a create the laptop confirms but the listing does not leaves the icon alone`() = runBlocking {
        val sessions = holder(listing(false), screen, listing(false), ok, listing(false)).watching()

        sessions.togglePane()

        assertEquals("lit for a pane that is not there", Pane.Left, sessions.paneShown.value)
        assertEquals(MutationNote.PaneFailed, sessions.mutation.value)
    }

    /** A refused `pane.open` leaves the icon where it was and names what the owner pressed. */
    @Test
    fun `a refused open leaves the icon alone and says so`() = runBlocking {
        val sessions = holder(
            listing(false), screen, listing(false),
            """{"ok":false,"error":"no such session: A"}""",
        ).watching()

        sessions.togglePane()

        assertEquals(Pane.Left, sessions.paneShown.value)
        assertEquals(MutationNote.PaneFailed, sessions.mutation.value)
    }

    /**
     * **The way back moves the icon at once, and maximizes afterwards** — REQ-0042.
     *
     * This test used to be called `going back to the left pane crosses no wire`, and that sentence
     * stopped being true: showing a pane now maximizes it, in BOTH directions. What survives is the
     * asymmetry the old name was really about — every session has a left pane, so what the phone READS
     * cannot fail, and the icon does not wait on the laptop to say so.
     */
    @Test
    fun `going back to the left pane maximizes it too`() = runBlocking {
        val sessions = holder(listing(true), ok, screen, listing(true), ok, ok).watching()
        sessions.togglePane()
        assertEquals(Pane.Right, sessions.paneShown.value)

        sessions.togglePane()

        assertEquals(Pane.Left, sessions.paneShown.value)
        assertEquals(MutationNote.None, sessions.mutation.value)
        assertTrue("the left pane was not maximized on his Mac", wire().contains("\"pane\":\"left\""))
    }

    /**
     * **The icon does not depend on the laptop in the left direction, and this is where that is
     * proved.**
     *
     * The maximize is refused. What the phone SHOWS is still Left, because every session has that pane
     * and reading it never needed the Mac's agreement — only his window layout did. He gets a note
     * about the part that failed and keeps the terminal, which is the REQ-0037 rule.
     *
     * Written as a failure rather than a success because a passing maximize cannot tell "the icon moved
     * on its own" from "the icon moved because the call worked".
     */
    @Test
    fun `a refused maximize on the way left still shows him the left pane`() = runBlocking {
        val sessions = holder(
            listing(true), ok, screen, listing(true), ok,
            """{"ok":false,"error":"no such session: A"}""",
        ).watching()
        sessions.togglePane()
        assertEquals(Pane.Right, sessions.paneShown.value)

        sessions.togglePane()

        assertEquals("the icon waited on the laptop for a pane that cannot fail", Pane.Left, sessions.paneShown.value)
        assertEquals(MutationNote.PaneFailed, sessions.mutation.value)
    }

    /**
     * **It asks the laptop rather than reading the session it is holding.**
     *
     * `watching` is a SNAPSHOT: it is written when the session is opened and the poll only ever copies
     * the screen text, so a split created on the Mac since then is invisible in it. Here the snapshot
     * says there is no pane and the listing taken at tap time says there is — and the phone switches
     * without calling `pane.open`, which it could only do by having asked.
     *
     * Deciding on the snapshot instead would send a create for a session that already has a pane.
     */
    @Test
    fun `it trusts a listing taken now over the session it opened with`() = runBlocking {
        val sessions = holder(listing(false), screen, listing(true), ok).watching()

        sessions.togglePane()

        assertEquals(Pane.Right, sessions.paneShown.value)
        assertTrue(
            "it asked the laptop and believed the answer, rather than the stale row it was holding",
            sessions.mutation.value == MutationNote.None,
        )
    }
}
