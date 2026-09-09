package dev.isachivka.bewareofsugar.update.play

import dev.isachivka.bewareofsugar.update.UpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the launcher's Updates tile says, driven by Play — REQ-0050.
 *
 * The tile is the one place an update can reach the owner without them asking, so the rule it has to
 * keep is REQ-0003's: quiet unless there is something to do.
 */
class PlayHomeStatusTest {

    private val now = 1_700_000_000L

    @Test
    fun `up to date reaches the tile`() {
        assertEquals(
            UpdateStatus.UpToDate(now),
            homeUpdateStatusOf(PlayUpdateStatus.UpToDate(now)),
        )
    }

    @Test
    fun `an available update announces a version name`() {
        assertEquals("v0.34.0", homeNoticeTagOf(PlayUpdateStatus.Available(3400, now, true)))
    }

    @Test
    fun `a small code is still a version name`() {
        // 199 is 0.1.99, not something unnameable - see VersionCodeNameTest. The raw-number fallback
        // in homeNoticeTagOf survives for negative codes alone, which Play does not send.
        assertEquals("v0.1.99", homeNoticeTagOf(PlayUpdateStatus.Available(199, now, true)))
    }

    @Test
    fun `an update play will not let us install is still announced`() {
        // The tile's job is to say something exists. Whether this app or the Store performs it is
        // the update screen's problem, and silence here would hide a real update.
        assertEquals("v0.34.0", homeNoticeTagOf(PlayUpdateStatus.Available(3400, now, immediateAllowed = false)))
    }

    @Test
    fun `an available update does not become an Available tile state`() {
        // UpdateStatus.Available carries a GitHub Release, which Play never gives us. Mapping onto
        // it would mean constructing a fake one, and a fake release on the launcher is a fake
        // version number in front of the owner. Availability travels as the notice tag instead.
        assertEquals(UpdateStatus.Idle, homeUpdateStatusOf(PlayUpdateStatus.Available(3400, now, true)))
    }

    @Test
    fun `every quiet state is quiet`() {
        val quiet = listOf(
            PlayUpdateStatus.Idle,
            PlayUpdateStatus.Checking,
            PlayUpdateStatus.UpToDate(now),
            PlayUpdateStatus.InProgress(now),
            PlayUpdateStatus.NotFromPlay,
            PlayUpdateStatus.Unknown(now),
            PlayUpdateStatus.Failed(-6),
            PlayUpdateStatus.Failed(null),
        )
        quiet.forEach { assertNull("$it should not announce anything", homeNoticeTagOf(it)) }
    }

    @Test
    fun `an update already installing is not announced as one to start`() {
        // Called out separately from the list above because it is the one that looks like it should
        // announce: there IS a newer version. Play is already applying it, and inviting the owner to
        // start another is the failure.
        assertNull(homeNoticeTagOf(PlayUpdateStatus.InProgress(now)))
    }

    @Test
    fun `a build that did not come from play leaves the tile silent`() {
        // It has something to say, but the tile is not where it says it. A launcher badge the owner
        // cannot act on from the launcher is a nag.
        assertNull(homeNoticeTagOf(PlayUpdateStatus.NotFromPlay))
        assertEquals(UpdateStatus.Idle, homeUpdateStatusOf(PlayUpdateStatus.NotFromPlay))
    }
}
