package dev.isachivka.agtermremote.pairing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * **Where Try again goes**, which is the whole of what these two camera methods differ in.
 *
 * Both show the paste field, because in both cases the owner needs something they can do right now.
 * They differ in what happens next, and getting that wrong in either direction is a real failure:
 *
 *  - latching on a camera that merely faltered takes a working scanner away from somebody whose scan
 *    was a moment from completing, and leaves Try again offering the screen they are already on;
 *  - not latching on a camera the owner refused sends them to a viewfinder that will never open.
 */
class PairingFlowTest {

    private fun flow() = PairingFlow { EnrollResult.Refused("nothing here enrols") }

    /**
     * **The one this test file exists for.** A camera that opened and then reported an error is
     * usually a camera that is about to work — the library publishes those while it is retrying, after
     * another application lets go, or when this app comes back to the foreground and wins the camera
     * back. Try again has to return to the scanner.
     */
    @Test
    fun `try again after a camera error returns to the scanner`() {
        val flow = flow()

        flow.cameraStalled()
        assertEquals("the owner needs something to do meanwhile", PairingUi.NeedsCamera, flow.state)

        flow.retry()

        assertEquals("the scanner was given up over an error that was about to clear", PairingUi.Scanning, flow.state)
    }

    /**
     * And the other direction: a camera the phone does not have, or the owner refused, must not be
     * offered again. This one is a fact about the device rather than about the moment.
     */
    @Test
    fun `try again after a camera that cannot open stays on the paste field`() {
        val flow = flow()

        flow.cameraUnavailable()
        flow.retry()

        assertEquals(PairingUi.NeedsCamera, flow.state)
    }

    /**
     * **A stall must not undo a refusal.** The two arrive from the same screen and can arrive in
     * either order: a phone whose owner said no can still raise a camera error from a viewfinder that
     * was open before they were asked, and the refusal is the fact that survives.
     */
    @Test
    fun `a camera error does not hand the scanner back to a phone that refused the camera`() {
        val flow = flow()

        flow.cameraUnavailable()
        flow.cameraStalled()
        flow.retry()

        assertEquals("a refused camera was offered again", PairingUi.NeedsCamera, flow.state)
    }

    /**
     * **A refusal the owner has since undone.**
     *
     * The settings screen sends them to the system toggle and brings them back, so the refusal that
     * latched has to be capable of being un-latched — otherwise granting the permission changes
     * nothing they can see, and Try again still returns to the paste field.
     */
    @Test
    fun `granting the camera in the system settings gives the scanner back`() {
        val flow = flow()

        flow.cameraUnavailable()
        flow.cameraRestored()

        assertEquals("the viewfinder did not come back", PairingUi.Scanning, flow.state)

        flow.retry()

        assertEquals("Try again still leads to the paste field", PairingUi.Scanning, flow.state)
    }

    /**
     * And it does not drag somebody out of what they were doing. The permission can change under a
     * screen that is part-way through an enrolment or showing its result, and only the screen that is
     * *asking for a camera* is the one a granted camera belongs on.
     */
    @Test
    fun `a camera granted underneath a result does not replace the result`() = runBlocking {
        val flow = flow()

        flow.onCode("this is not a pairing code")
        val shown = flow.state
        assertNotEquals("the fixture never reached a result", PairingUi.Scanning, shown)

        flow.cameraRestored()

        assertEquals("a permission change threw away what the owner was reading", shown, flow.state)
    }

    /** Nothing has gone wrong: the screen opens on the scanner and Try again returns to it. */
    @Test
    fun `a screen with a working camera returns to the scanner`() {
        val flow = flow()

        assertEquals(PairingUi.Scanning, flow.state)

        flow.retry()

        assertEquals(PairingUi.Scanning, flow.state)
    }
}
