package dev.isachivka.bewareofsugar.car

import dev.isachivka.bewareofsugar.agterm.AgtermSessions
import dev.isachivka.bewareofsugar.agterm.AgtermUiState
import dev.isachivka.bewareofsugar.agterm.BridgeConnection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The car's holder drives the phone's state machine through exactly four verbs.
 *
 * Canned replies over a byte stream, the fixture AgtermSessionsTest built, with the same trailing
 * `unchanged` replies so a watched session's poll can never run the stream dry and report a laptop
 * that vanished.
 */
class CarSessionsHolderTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun holder(vararg replies: String): CarSessionsHolder {
        val idle = List(500) { """{"ok":true,"unchanged":true,"digest":"d1"}""" }
        val sessions = AgtermSessions(
            connect = {
                BridgeConnection.ofStreams(
                    ByteArrayInputStream((replies.toList() + idle).joinToString("\n").toByteArray()),
                    ByteArrayOutputStream(),
                )
            },
            scope = scope,
            io = Dispatchers.Unconfined,
            pollIntervalMs = 1,
        )
        return CarSessionsHolder(sessions, scope)
    }

    /** The barrier AgtermSessionsTest uses: the fetch is over when the state has left Loading. */
    private fun CarSessionsHolder.settled(): AgtermUiState = runBlocking {
        withTimeout(5_000) { sessions.state.first { it !is AgtermUiState.Loading } }
    }

    private val listing = """{"ok":true,"sessions":[{"id":"A","workspace":"main","name":"agterm","active":true}]}"""

    @Test
    fun `start lists the sessions`() {
        val h = holder(listing)
        h.start()
        val state = h.settled() as AgtermUiState.Sessions
        assertEquals("A", state.sessions.single().id)
    }

    @Test
    fun `open watches, and leave stops watching`() {
        val h = holder(listing, """{"ok":true,"text":"hello","digest":"d1"}""")
        h.start()
        val session = (h.settled() as AgtermUiState.Sessions).sessions.single()
        h.open(session)
        assertEquals("A", (h.sessions.state.value as AgtermUiState.Sessions).watching?.id)
        h.leave()
        val after = h.sessions.state.value as AgtermUiState.Sessions
        assertNull(after.watching)
        assertEquals("", after.screen)
    }

    @Test
    fun `release ends the scope, so nothing polls after the host is gone`() {
        val h = holder(listing)
        h.start()
        h.settled()
        assertTrue(scope.isActive)
        h.release()
        assertFalse(scope.isActive)
    }
}
