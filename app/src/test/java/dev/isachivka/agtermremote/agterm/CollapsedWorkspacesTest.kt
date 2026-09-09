package dev.isachivka.agtermremote.agterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ids only, as everywhere in this feature. A workspace's name is never written down. */
class CollapsedWorkspacesTest {

    private val listed = listOf("W1", "W2", "W3")

    @Test
    fun `toggling shuts a group and opens it again`() {
        val once = CollapsedWorkspaces.toggle(emptySet(), "W1")
        assertEquals(setOf("W1"), once)
        assertEquals(emptySet<String>(), CollapsedWorkspaces.toggle(once, "W1"))
    }

    @Test
    fun `toggling one group leaves the others where they were`() {
        assertEquals(setOf("W1", "W2"), CollapsedWorkspaces.toggle(setOf("W1"), "W2"))
    }

    /**
     * **The case the whole design turns on.** A workspace that appears on the laptop while everything
     * was collapsed is OPEN, because the set names what is shut and nobody has shut this one.
     *
     * Holding the open ones instead would make every new workspace arrive invisible and stay that way
     * until somebody noticed it was missing — the failure `groupByWorkspace` refuses in its own form.
     */
    @Test
    fun `a workspace nobody has closed is open`() {
        val closed = CollapsedWorkspaces.press(emptySet(), listOf("W1", "W2"))
        assertFalse("W3" in closed)
    }

    @Test
    fun `all closed is asked of what is on screen`() {
        assertTrue(CollapsedWorkspaces.allClosed(setOf("W1", "W2", "W3"), listed))
        assertFalse(CollapsedWorkspaces.allClosed(setOf("W1", "W2"), listed))
    }

    /**
     * A set holding a workspace that has since closed on the laptop does not make the visible ones
     * collapsed. The question is about the rows in front of the owner, not about what the set
     * remembers.
     */
    @Test
    fun `a stale id does not make the listed groups look closed`() {
        assertFalse(CollapsedWorkspaces.allClosed(setOf("W1", "W2", "gone"), listed))
    }

    /** Nothing on screen is not "everything is closed": there would be nothing for the control to open. */
    @Test
    fun `an empty list is not all closed`() {
        assertFalse(CollapsedWorkspaces.allClosed(setOf("W1"), emptyList()))
    }

    @Test
    fun `the header control closes everything and then opens everything`() {
        val closed = CollapsedWorkspaces.press(emptySet(), listed)
        assertEquals(listed.toSet(), closed)
        assertEquals(emptySet<String>(), CollapsedWorkspaces.press(closed, listed))
    }

    /** Half-closed reads as not-all-closed, so the control finishes the job rather than undoing it. */
    @Test
    fun `pressing with some groups open closes the rest`() {
        assertEquals(listed.toSet(), CollapsedWorkspaces.press(setOf("W2"), listed))
    }

    /**
     * Collapsing names the listed ids, so an id for a workspace that no longer exists is dropped
     * rather than accumulating for the life of the process.
     */
    @Test
    fun `collapsing forgets workspaces that are no longer there`() {
        assertEquals(listed.toSet(), CollapsedWorkspaces.press(setOf("gone"), listed))
    }
}
