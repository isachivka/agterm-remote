package dev.isachivka.bewareofsugar.agterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four states, and everything that is not one of them.
 *
 * The strings asserted here are the same four the bridge's own test asserts. Two decoders is two
 * chances to drift, and this file is half of what stops that being discovered on the owner's screen.
 *
 * No session or workspace name appears — ids only. A status is a shape and may be written down; what
 * their sessions are called may not.
 */
class SessionStatusTest {

    private fun session(id: String, status: SessionStatus) =
        BridgeSession(id, workspaceId = "W1", workspace = "", title = "", active = false, name = id, status = status)

    @Test
    fun `the three agterm sends decode to the three it means`() {
        assertEquals(SessionStatus.Running, statusFromWire("active"))
        assertEquals(SessionStatus.NeedsYou, statusFromWire("blocked"))
        assertEquals(SessionStatus.Done, statusFromWire("completed"))
    }

    /**
     * **Absence is a state, not a gap.** agterm's encoder omits `idle`, and `org.json` hands an absent
     * field over as an empty string — so both arrive here, and both mean the same thing.
     */
    @Test
    fun `nothing said means idle`() {
        assertEquals(SessionStatus.Idle, statusFromWire(null))
        assertEquals(SessionStatus.Idle, statusFromWire(""))
    }

    /**
     * **An unrecognised value is idle, and that is a decision.** A state we cannot interpret is not
     * one of the other three, and rendering it as one would put a confident wrong glyph on a row. Idle
     * draws a plain dot, which is the honest picture of having nothing to say.
     *
     * `idle` itself is in this list because agterm never sends it — it omits instead — so meeting it
     * would already mean something changed at the far end.
     */
    @Test
    fun `anything else is idle rather than a guess`() {
        for (value in listOf("idle", "Active", "ACTIVE", "waiting", "running", "blocked ", "active blocked")) {
            assertEquals("$value must not be read as a state", SessionStatus.Idle, statusFromWire(value))
        }
    }

    /** A session that says nothing is idle, without any caller having to say so. */
    @Test
    fun `a session built without a status is idle`() {
        assertEquals(
            SessionStatus.Idle,
            BridgeSession("s1", workspaceId = "W1", workspace = "", name = "s1", title = "", active = false).status,
        )
    }

    @Test
    fun `a group wants attention when something inside it is waiting on the owner`() {
        val group = WorkspaceGroup(
            id = "W1",
            name = "",
            sessions = listOf(session("s1", SessionStatus.Running), session("s2", SessionStatus.NeedsYou)),
        )
        assertTrue(wantsAttention(group))
    }

    /**
     * **And it does not for the other three.** A dot that lit up for a running session would light up
     * on nearly every group, and a dot that is always on is one the owner learns to ignore — which
     * costs them the one case it exists for.
     */
    @Test
    fun `a group of running finished and idle sessions is not asking for anything`() {
        val group = WorkspaceGroup(
            id = "W1",
            name = "",
            sessions = listOf(
                session("s1", SessionStatus.Running),
                session("s2", SessionStatus.Done),
                session("s3", SessionStatus.Idle),
            ),
        )
        assertFalse(wantsAttention(group))
    }

    @Test
    fun `an empty group is not asking for anything`() {
        assertFalse(wantsAttention(WorkspaceGroup(id = "W1", name = "", sessions = emptyList())))
    }

    /**
     * **Drawn only while the group is folded**, which is a narrower question than whether it wants
     * anything. With the group open its rows say so themselves, and a dot repeating what is already on
     * screen is one the owner learns to ignore.
     *
     * This rule used to live inside the header composable, where the only way to check it was to run a
     * UI test on a device. It is a pure function so that it is checked here instead.
     */
    @Test
    fun `the attention dot is drawn only when the group is folded`() {
        val waiting = WorkspaceGroup("W1", "", listOf(session("s1", SessionStatus.NeedsYou)))
        val busy = WorkspaceGroup("W2", "", listOf(session("s2", SessionStatus.Running)))

        assertTrue(showsAttention(waiting, closed = setOf("W1")))
        assertFalse("an open group's rows already say so", showsAttention(waiting, closed = emptySet()))
        assertFalse("nothing in here is waiting", showsAttention(busy, closed = setOf("W2")))
        assertFalse(showsAttention(busy, closed = emptySet()))
    }

    /**
     * Folding one group takes away its rows and nobody else's.
     *
     * The screen draws one row per key, so this is the row count the owner sees, asserted without a
     * device — the composable adds no rows of its own and the keys are what the LazyColumn holds.
     */
    @Test
    fun `folding a group removes exactly its own rows`() {
        val groups = groupByWorkspace(
            listOf(
                BridgeSession("a1", "W1", "", "a1", "", false),
                BridgeSession("a2", "W1", "", "a2", "", false),
                BridgeSession("b1", "W2", "", "b1", "", false),
            ),
        )
        val open = ListPosition.listKeys(groups, emptySet())
        val folded = ListPosition.listKeys(groups, setOf("W1"))

        assertEquals(open.size - 2, folded.size)
        assertTrue("the folded group keeps its heading", "workspace-W1" in folded)
        assertTrue("the other group is untouched", listOf("workspace-W2", "b1").all { it in folded })
    }
}
