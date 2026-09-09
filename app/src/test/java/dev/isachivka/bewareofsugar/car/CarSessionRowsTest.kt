package dev.isachivka.bewareofsugar.car

import dev.isachivka.bewareofsugar.agterm.AgtermUiState
import dev.isachivka.bewareofsugar.agterm.BridgeSession
import dev.isachivka.bewareofsugar.agterm.BridgeWorkspace
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The session list as the car shows it: the phone's grouping, cut to the host's row limit.
 *
 * The host says how many rows a list may carry, and it says so at runtime. A list over the limit is
 * not truncated by the host; it is refused, and the app is closed. So the cap is applied here, where a
 * test can watch it, and the order it cuts from is the listing order the owner already knows.
 */
class CarSessionRowsTest {

    private fun session(id: String, ws: String) =
        BridgeSession(id = id, workspaceId = ws, workspace = "", name = id, title = "", active = false)

    private val workspaces = listOf(BridgeWorkspace("w1", "pets"), BridgeWorkspace("w2", ""))
    private val sessions = listOf(session("a", "w1"), session("b", "w2"), session("c", "w1"), session("d", "w2"))

    @Test
    fun `sessions are grouped by workspace, in listing order, with the unnamed label`() {
        val sections = carSections(AgtermUiState.Sessions(sessions, workspaces), limit = 10, unnamed = "no name")
        assertEquals(listOf("pets", "no name"), sections.map { it.title })
        assertEquals(listOf("a", "c"), sections[0].sessions.map { it.id })
        assertEquals(listOf("b", "d"), sections[1].sessions.map { it.id })
    }

    @Test
    fun `the cap cuts from the tail and drops a section it emptied`() {
        val sections = carSections(AgtermUiState.Sessions(sessions, workspaces), limit = 2, unnamed = "no name")
        assertEquals(listOf("pets"), sections.map { it.title })
        assertEquals(listOf("a", "c"), sections[0].sessions.map { it.id })
    }

    @Test
    fun `a cap that splits a section keeps its head`() {
        val sections = carSections(AgtermUiState.Sessions(sessions, workspaces), limit = 3, unnamed = "no name")
        assertEquals(listOf(2, 1), sections.map { it.sessions.size })
        assertEquals("b", sections[1].sessions.single().id)
    }
}
