package dev.isachivka.bewareofsugar.agterm

import dev.isachivka.bewareofsugar.wire.WireFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * REQ-0014: the list refreshes when it becomes visible, and on a pull.
 *
 * The trigger is an effect keyed on [showsList], and the failure mode of keying an effect on derived
 * state is an infinite loop — the fetch flips the key, which runs the effect, which fetches. That
 * cannot be read off a composable, so it is asserted here as transitions between states.
 */
class ListVisibilityTest {

    private val session = BridgeSession("A", "W1", "main", "sh", "", false)
    private val listed = AgtermUiState.Sessions(listOf(session))

    // --- the predicate ---------------------------------------------------------------------------

    @Test
    fun `the list is showing while it is arriving and while it is listed`() {
        assertTrue("Loading is the list, still arriving", showsList(AgtermUiState.Loading))
        assertTrue(showsList(listed))
    }

    /**
     * **Loading counting as the list is load-bearing, not a nicety.**
     *
     * Without it the first composition sees no list, never fetches, and nothing ever reaches Sessions
     * — a deadlock by construction. This is the assertion that says so out loud.
     */
    @Test
    fun `without Loading counting as the list, nothing would ever fetch`() {
        assertTrue(
            "if this is ever false, entry cannot fetch and the screen never leaves Loading",
            showsList(AgtermUiState.Loading),
        )
    }

    @Test
    fun `the list is not showing when the terminal is over it`() {
        assertEquals(false, showsList(listed.copy(watching = session)))
    }

    /** A failure carries its own action; refreshing underneath one fights the button they must press. */
    @Test
    fun `a failure, pairing or a disconnect is not the list`() {
        assertEquals(false, showsList(AgtermUiState.NotPaired))
        assertEquals(false, showsList(AgtermUiState.Disconnected))
        assertEquals(false, showsList(AgtermUiState.Failed(WireFailure.CannotReach)))
        assertEquals(false, showsList(AgtermUiState.Refused("no")))
    }

    // --- the transitions, which are what the effect actually keys on -------------------------------

    /**
     * **A refresh must not re-trigger itself.** The three transitions a fetch produces all keep the
     * predicate true, so the effect does not re-run and there is no loop.
     */
    @Test
    fun `nothing a fetch does flips the trigger`() {
        // Arriving: Loading -> Sessions. One fetch, not two.
        assertEquals(showsList(AgtermUiState.Loading), showsList(listed))
        // Refreshing in place: the spinner going up and coming down.
        assertEquals(showsList(listed), showsList(listed.copy(refreshing = true)))
        assertEquals(showsList(listed.copy(refreshing = true)), showsList(listed))
    }

    /**
     * And the transition that MUST flip it, or the owner's report is not fixed: coming back from a
     * session. Nothing remounts, so this is the only thing that can trigger the refresh.
     */
    @Test
    fun `returning from a session flips the trigger, which is the whole bug`() {
        val watching = listed.copy(watching = session)

        assertEquals(false, showsList(watching))
        assertTrue("returning to the list must become visible again", showsList(listed))
    }

    // --- the fetch, the spinner, and the guard ----------------------------------------------------

    private class Fixture(val sessions: AgtermSessions, private val out: ByteArrayOutputStream) {
        fun requests(): List<String> = out.toString().trim().lines().filter { it.isNotBlank() }
    }

    private fun fixture(failing: Boolean = false): Fixture {
        val listing = """{"ok":true,"sessions":[{"id":"A","workspace":"main","workspace_id":"W1","name":"sh"}]}"""
        val out = ByteArrayOutputStream()
        val sessions = AgtermSessions(
            connect = {
                val replies = if (failing) List(20) { """{"ok":false,"error":"no"}""" } else List(20) { listing }
                BridgeConnection.ofStreams(ByteArrayInputStream(replies.joinToString("\n").toByteArray()), out)
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
        )
        return Fixture(sessions, out)
    }

    @Test
    fun `a settled list is not refreshing`() = runBlocking {
        val f = fixture()
        f.sessions.refresh()
        val state = withTimeout(5_000) { f.sessions.state.first { it is AgtermUiState.Sessions } }

        assertEquals(false, (state as AgtermUiState.Sessions).refreshing)
    }

    /**
     * **The spinner stops when the fetch fails.**
     *
     * The shape this project keeps meeting: an indicator spinning over a screen that has stopped. The
     * owner must end up looking at the list they had or at the failure — never at a spinner over a
     * dead connection.
     */
    @Test
    fun `a failed fetch leaves no spinner behind`() = runBlocking {
        val f = fixture(failing = true)
        f.sessions.refresh()
        val state = withTimeout(5_000) { f.sessions.state.first { it !is AgtermUiState.Loading } }

        // Either the screen moved to the failure - in which case there is no list to spin over - or a
        // list is still showing and it is NOT refreshing. Both are acceptable; a spinner is not.
        val stillSpinning = (state as? AgtermUiState.Sessions)?.refreshing == true
        assertEquals("a spinner outlived the fetch it describes", false, stillSpinning)
    }

    /**
     * **A pull during an outstanding fetch is a no-op, not a queued round trip.**
     *
     * Asserted by counting what reached the wire, because "it did not fetch twice" is exactly the kind
     * of claim that reads true and is false.
     */
    @Test
    fun `a second refresh while one is outstanding does not ask twice`() = runBlocking {
        // **A reply that does not arrive until this test says so.** Without it the fetch completes
        // before the second call is made and the guard is never exercised - the test would pass while
        // guarding nothing, which is the failure mode it exists to avoid.
        val held = CountDownLatch(1)
        val listing = """{"ok":true,"sessions":[{"id":"A","workspace":"main","workspace_id":"W1","name":"sh"}]}"""
        val body = List(20) { listing }.joinToString("\n").toByteArray()
        val stream = object : InputStream() {
            private val inner = ByteArrayInputStream(body)
            private var waited = false
            override fun read(): Int {
                if (!waited) {
                    waited = true
                    held.await(5, TimeUnit.SECONDS)
                }
                return inner.read()
            }
        }
        val out = ByteArrayOutputStream()
        val sessions = AgtermSessions(
            connect = { BridgeConnection.ofStreams(stream, out) },
            scope = CoroutineScope(Dispatchers.Default),
            // A REAL dispatcher, so the blocked read does not block the thread making the second call.
            io = Dispatchers.IO,
        )

        sessions.refresh()
        // Wait until the first fetch has actually reached the socket, so the second is genuinely
        // concurrent rather than merely second.
        withTimeout(5_000) { while (out.toString().isEmpty()) yield() }

        sessions.refresh() // the pull, arriving while the first round is still outstanding
        yield()
        held.countDown()
        withTimeout(5_000) { sessions.state.first { it is AgtermUiState.Sessions } }

        val listings = out.toString().trim().lines().filter { it.contains("\"sessions\"") }
        assertEquals(
            "the second refresh queued a duplicate round trip instead of being absorbed",
            1,
            listings.size,
        )
    }
}
