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
 * The phone remembers which pane he was reading, per session, ONE.
 *
 * ### Why a memory is needed at all now
 *
 * Showing a pane MAXIMIZES it, so only one is on screen at a time and re-entering a session is a
 * choice. Made arbitrarily it would put him in the terminal he was not reading, on a screen where the
 * other one is no longer visible beside it.
 *
 * ### The rule the memory must not break
 *
 * A pane still does not carry across SESSIONS. What is remembered is per session and checked against
 * the row being opened before it is believed.
 */
class PaneMemoryTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val sent = ByteArrayOutputStream()

    private fun wire() = sent.toString()

    private fun holder(vararg replies: String) = AgtermSessions(
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

    private fun row(id: String, splitPane: Boolean) =
        """{"id":"$id","workspace":"main","name":"agterm","active":true,"split":$splitPane}"""

    private fun listing(vararg rows: String) = """{"ok":true,"sessions":[${rows.joinToString(",")}]}"""

    private val screen = """{"ok":true,"text":"output","digest":"d1"}"""
    private val ok = """{"ok":true}"""

    private suspend fun AgtermSessions.settled() =
        withTimeout(5_000) { state.first { it !is AgtermUiState.Loading } } as AgtermUiState.Sessions

    private fun AgtermSessions.rowFor(id: String) =
        (state.value as AgtermUiState.Sessions).sessions.first { it.id == id }

    /**
     * **The whole feature, in one test.** He reads the right pane, leaves the session, comes back, and
     * is in the right pane.
     *
     * Without the memory he lands on the left every time — and the pane he was reading is no longer
     * beside it on screen, because the phone maximized the one it showed.
     */
    @Test
    fun `coming back to a session returns to the pane he was reading`() = runBlocking {
        val sessions = holder(
            listing(row("A", true)), ok, screen,
            listing(row("A", true)), ok,
            ok, screen,
        )
        sessions.refresh()
        sessions.settled()
        sessions.watch(sessions.rowFor("A"))
        sessions.togglePane()
        assertEquals(Pane.Right, sessions.paneShown.value)

        sessions.watch(sessions.rowFor("A"))

        assertEquals("he was put back in the left pane he had left", Pane.Right, sessions.paneShown.value)
    }

    /**
     * **A pane does not carry across sessions**, which is the rule the memory is not allowed to break.
     *
     * The reasoning `TerminalScroll.onSessionOpened` applies to a scroll offset: a value from a
     * different buffer means nothing here. It is still true of ANOTHER session's pane; the memory is
     * per session and this is the assertion that says which.
     */
    @Test
    fun `a pane does not carry from one session to another`() = runBlocking {
        val sessions = holder(
            listing(row("A", true), row("B", true)), ok, screen,
            listing(row("A", true), row("B", true)), ok,
            ok, screen,
        )
        sessions.refresh()
        sessions.settled()
        sessions.watch(sessions.rowFor("A"))
        sessions.togglePane()
        assertEquals(Pane.Right, sessions.paneShown.value)

        sessions.watch(sessions.rowFor("B"))

        assertEquals("another session's pane was carried over", Pane.Left, sessions.paneShown.value)
    }

    /**
     * **A remembered Right for a session that no longer has a right pane resolves to Left, for free.**
     *
     * `watch` is handed the row it is opening, and that row carries `split` from the listing the phone
     * just refreshed. So the commonest way a memory goes stale — he closed the split on the Mac — is
     * settled before anything reaches the wire, and there is no call to maximize a pane that is gone.
     */
    @Test
    fun `a remembered pane is not believed over the row being opened`() = runBlocking {
        val sessions = holder(
            listing(row("A", true)), ok, screen,
            listing(row("A", true)), ok,
            listing(row("A", false)), screen,
        )
        sessions.refresh()
        sessions.settled()
        sessions.watch(sessions.rowFor("A"))
        sessions.togglePane()
        assertEquals(Pane.Right, sessions.paneShown.value)

        // The listing refreshes and the split is gone from the Mac.
        sessions.refresh()
        sessions.watch(sessions.rowFor("A"))

        assertEquals("a destroyed pane was reopened from memory", Pane.Left, sessions.paneShown.value)
    }

    /**
     * **Opening a session maximizes the pane it opens on**, TWO, on the switch path.
     *
     * The same reading the re-applied fit was given: a session switch
     * is a switch.
     */
    @Test
    fun `opening a session maximizes the pane it lands on`() = runBlocking {
        val sessions = holder(listing(row("A", true)), ok, screen)
        sessions.refresh()
        sessions.settled()

        sessions.watch(sessions.rowFor("A"))

        assertTrue("the pane was not maximized on the way in", "pane.show" in wire())
    }

    /**
     * **The memory agrees with the reset-on-vanish path rather than fighting it.**
     *
     * A pane DESTROYED on the Mac makes every read that names it fail, and there has always been a
     * handler for that: drop to Left so the next poll succeeds. The memory is cleared at that same
     * point and nowhere else.
     *
     * Without it, the row would still say `split` — the destroyed pane's session can keep one for a
     * refresh or two — and every return to this session would ask for the dead pane again and land back
     * in the same handler. **A second existence check would have been the wrong fix**: the path already
     * knows, and this makes the memory listen to it.
     */
    @Test
    fun `a pane destroyed on the mac is forgotten, not asked for again`() = runBlocking {
        val sessions = holder(
            listing(row("A", true)), ok, screen,
            listing(row("A", true)), ok,
            // Back into the session: it maximizes, and the screen read then finds the pane gone.
            ok, """{"ok":false,"error":"that pane is gone","detail":"session has no split pane"}""",
        )
        sessions.refresh()
        sessions.settled()
        sessions.watch(sessions.rowFor("A"))
        sessions.togglePane()
        assertEquals(Pane.Right, sessions.paneShown.value)

        sessions.watch(sessions.rowFor("A"))
        assertEquals("the vanish handler did not drop the pane", Pane.Left, sessions.paneShown.value)

        // The refusal dropped the connection and put the bridge's words on screen, which is what a
        // refusal has always done. Reconnecting replays the fixture, so the row still says `split` —
        // exactly the stale listing this test needs, because a memory nothing cleared would believe it.
        sessions.refresh()
        sessions.watch(sessions.rowFor("A"))

        assertEquals(
            "the destroyed pane was asked for again from a memory nothing cleared",
            Pane.Left,
            sessions.paneShown.value,
        )
    }

    /**
     * **Coming back to a session never creates a pane.**
     *
     * A tap on the toggle may start a shell, and that is the owner's own gesture. Tapping a row in
     * a list is not, and a shell appearing on his laptop because of it is the surprise
     * `IntentApplyIfKnown` exists to prevent, one feature over.
     */
    @Test
    fun `opening a session with one pane asks the laptop for nothing`() = runBlocking {
        val sessions = holder(listing(row("A", false)), screen)
        sessions.refresh()
        sessions.settled()

        sessions.watch(sessions.rowFor("A"))

        assertTrue("a session with one pane was sent a maximize it refuses", "pane.show" !in wire())
        assertTrue("opening a session created a pane", "pane.open" !in wire())
        assertEquals(Pane.Left, sessions.paneShown.value)
    }
}
