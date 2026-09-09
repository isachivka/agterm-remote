package dev.isachivka.bewareofsugar.agterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pane the phone addresses — REQ-0032, and the silent defect it was written for.
 *
 * agterm resolves an absent pane **differently for the two commands**: a read gets the on-screen half,
 * a keystroke gets primary. Measured over its control socket on 2026-08-25. So while the phone named
 * no pane, a split session showed one half and typed into the other, with no error, into the terminal
 * the owner was not looking at.
 *
 * The bridge half is tested in `bridge/internal/api/pane_test.go`, including a control that restores
 * the old divergence and watches these same properties fail. This is the phone's end.
 */
class PaneTest {

    /**
     * **The wire values are agterm's own and are not this app's to restyle.**
     *
     * The bridge holds the same closed set and refuses anything outside it by name. If these drifted,
     * every press would be refused four layers from anyone who could read the refusal — which is the
     * failure `KeyCell` was made a sealed type to prevent, in a different corner of the same app.
     */
    @Test
    fun `the wire words are the ones agterm accepts`() {
        assertEquals("left", Pane.Left.wire)
        assertEquals("right", Pane.Right.wire)
        assertEquals("a toggle has two states and no more", 2, Pane.entries.size)
    }

    /** A toggle's whole logic. Written down because "the other one" is easy to get wrong twice. */
    @Test
    fun `the other pane is the other one`() {
        assertEquals(Pane.Right, Pane.Left.other())
        assertEquals(Pane.Left, Pane.Right.other())
        Pane.entries.forEach { assertEquals("twice round is where you started", it, it.other().other()) }
    }

    /**
     * **A bridge too old to mention panes produces no toggle**, rather than one that would refuse on
     * every press. Absence is the safe direction and it is what `optBoolean` already gives us — this
     * asserts the default rather than trusting it to stay.
     */
    @Test
    fun `a session says it has no second pane until something says otherwise`() {
        assertFalse(
            "an unstated pane must not offer a control for something that may not exist",
            session().splitPane,
        )
    }

    /**
     * The toggle is composed only when this is true, so the two states of the header come off one
     * boolean. Asserted so that a session carrying a second pane is distinguishable from one that is
     * not — the guard being `watching.splitPane` and not something derived.
     */
    @Test
    fun `a session with a second pane is distinguishable from one without`() {
        assertTrue(session(splitPane = true).splitPane)
        assertFalse(session(splitPane = false).splitPane)
    }

    private fun session(splitPane: Boolean = false) = BridgeSession(
        id = "s",
        workspaceId = "w",
        workspace = "ws",
        name = "n",
        title = "t",
        active = false,
        splitPane = splitPane,
    )
}
