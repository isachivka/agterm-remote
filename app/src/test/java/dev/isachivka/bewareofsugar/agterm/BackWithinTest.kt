package dev.isachivka.bewareofsugar.agterm

import dev.isachivka.bewareofsugar.agterm.BackWithin.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What back means inside the agterm screen.
 *
 * The owner's report: the system gesture inside a session left the app instead of returning to the
 * list, while the header's arrow did the right thing. Two definitions of back, one of which Android
 * could not see.
 */
class BackWithinTest {

    @Test
    fun `inside a session, back returns to the list`() {
        assertEquals(Action.CloseSession, BackWithin.action(typingOpen = false, inSession = true))
    }

    /**
     * The typing bar is INSIDE the session, so it goes first. Closing the session while the bar is
     * open would take away both in one press and leave the owner guessing which one the gesture meant.
     */
    @Test
    fun `an open typing bar is closed before the session`() {
        assertEquals(Action.CloseTyping, BackWithin.action(typingOpen = true, inSession = true))
    }

    /**
     * **The half that keeps the gesture usable.** On the list this screen owns nothing, so back must
     * fall through to the app's own stack and behave exactly as it does today.
     */
    @Test
    fun `on the list, back is not this screen's business`() {
        assertEquals(Action.LeaveScreen, BackWithin.action(typingOpen = false, inSession = false))
        assertFalse(
            "the list would swallow the gesture and strand the owner inside the app",
            BackWithin.handles(typingOpen = false, inSession = false),
        )
    }

    /** And it intercepts exactly when it has something to do - never more, never less. */
    @Test
    fun `it intercepts only when there is somewhere to go`() {
        assertTrue(BackWithin.handles(typingOpen = false, inSession = true))
        assertTrue(BackWithin.handles(typingOpen = true, inSession = true))
        // Defensive: a bar open with no session should still be closable rather than ignored.
        assertTrue(BackWithin.handles(typingOpen = true, inSession = false))
    }
}
