package dev.isachivka.agtermremote.agterm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The hierarchy, grouped by identity.
 *
 * The owner asked to SEE the structure agterm already holds. What makes that safe rather than merely
 * present is which key decides membership, and every test here is about that.
 *
 * **No real workspace or session name appears in this file.** The fixtures are ids and placeholders;
 * their names are on the owner's screen and nowhere else.
 */
class WorkspaceGroupingTest {

    private fun session(id: String, workspaceId: String, workspace: String = "w") =
        BridgeSession(id = id, workspaceId = workspaceId, workspace = workspace, title = "", active = false, name = id)

    /**
     * **Two workspaces that share a name stay two workspaces.** Grouping by name would merge them and
     * the owner would see a session under a workspace it is not in - a wrong answer presented
     * confidently, which is the failure mode this key exists to remove.
     */
    @Test
    fun `workspaces with the same name are not merged`() {
        val grouped = groupByWorkspace(
            listOf(
                session("s1", workspaceId = "W1", workspace = "same"),
                session("s2", workspaceId = "W2", workspace = "same"),
            ),
        )

        assertEquals(2, grouped.size)
        assertEquals(listOf("W1", "W2"), grouped.map { it.id })
    }

    /**
     * **And membership is by identity, not by adjacency.** Grouping by "the workspace changed between
     * neighbours" is correct exactly while the list arrives grouped - a property nobody is holding and
     * nobody would notice breaking.
     */
    @Test
    fun `a workspace whose sessions arrive apart is still one workspace`() {
        val grouped = groupByWorkspace(
            listOf(
                session("s1", workspaceId = "W1"),
                session("s2", workspaceId = "W2"),
                session("s3", workspaceId = "W1"),
            ),
        )

        assertEquals(2, grouped.size)
        assertEquals(listOf("s1", "s3"), grouped.first { it.id == "W1" }.sessions.map { it.id })
    }

    /** Order is agterm's at both levels: first appearance for workspaces, arrival order within one. */
    @Test
    fun `order comes from the listing and nothing is sorted`() {
        val grouped = groupByWorkspace(
            listOf(
                session("s1", workspaceId = "Zebra"),
                session("s2", workspaceId = "Alpha"),
                session("s3", workspaceId = "Zebra"),
            ),
        )

        // Not alphabetical - the workspace whose first session came first comes first.
        assertEquals(listOf("Zebra", "Alpha"), grouped.map { it.id })
        assertEquals(listOf("s1", "s3"), grouped[0].sessions.map { it.id })
    }

    /**
     * **A hierarchy that swallows a session is worse than a flat list.** Whatever the grouping does,
     * it holds exactly what it was given.
     */
    @Test
    fun `every session survives the grouping`() {
        val sessions = listOf(
            session("s1", workspaceId = "W1"),
            session("s2", workspaceId = ""),
            session("s3", workspaceId = "W2", workspace = ""),
            session("s4", workspaceId = "W1"),
        )

        val regrouped = groupByWorkspace(sessions).flatMap { it.sessions }

        assertEquals(sessions.size, regrouped.size)
        assertEquals(sessions.map { it.id }.toSet(), regrouped.map { it.id }.toSet())
    }

    /** A workspace with no name is still a workspace with an identity, and still gets its group. */
    @Test
    fun `a nameless workspace is grouped by its identity`() {
        val grouped = groupByWorkspace(
            listOf(
                session("s1", workspaceId = "W1", workspace = ""),
                session("s2", workspaceId = "W1", workspace = ""),
            ),
        )

        assertEquals(1, grouped.size)
        assertEquals("W1", grouped[0].id)
        // Empty, so the screen supplies its own label rather than this function inventing a name.
        assertEquals("", grouped[0].name)
        assertEquals(2, grouped[0].sessions.size)
    }

    /** An older bridge sends no identity at all; those sessions group together rather than vanish. */
    @Test
    fun `sessions with no workspace identity still appear`() {
        val grouped = groupByWorkspace(
            listOf(session("s1", workspaceId = ""), session("s2", workspaceId = "")),
        )

        assertEquals(1, grouped.size)
        assertEquals(2, grouped[0].sessions.size)
    }
}
