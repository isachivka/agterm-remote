package dev.isachivka.bewareofsugar.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The four rules that keep a remembered tag a cache rather than a claim.
 *
 * One test per rule, plus the case the whole thing exists for: the app used to remember *when* it
 * checked and not *what it found*, so an update announced before a restart vanished after one, and
 * the once-a-day rule then stopped it rediscovering the answer it already had.
 */
class UpdateNoticeTest {

    private val installed = AppVersion(0, 5, 0)

    private fun release(tag: String) = Release(
        tag = tag,
        version = AppVersion.parse(tag),
        title = tag,
        notes = "notes",
        draft = false,
        prerelease = false,
        assets = emptyList(),
    )

    private fun announce(status: UpdateStatus, remembered: String?) =
        UpdateNotice.tagToAnnounce(status, remembered, installed)

    /** The bug. Idle is what the app is after a restart when no check is due. */
    @Test
    fun `an update found before a restart is still announced after one`() {
        assertEquals("v0.6.0", announce(UpdateStatus.Idle, "v0.6.0"))
    }

    @Test
    fun `with nothing remembered and nothing checked, nothing is said`() {
        assertNull(announce(UpdateStatus.Idle, null))
    }

    // -- Guard 1: revalidated on read ------------------------------------------------------------

    @Test
    fun `a remembered tag the owner has already installed is not announced`() {
        // Nothing tells this app that an install happened, so the tag is not cleared on install -
        // it simply stops being newer, every time it is read.
        assertNull(announce(UpdateStatus.Idle, "v0.5.0"))
        assertNull(announce(UpdateStatus.Idle, "v0.4.0"))
    }

    @Test
    fun `a remembered tag that is not a version this app can read is not announced`() {
        // Not compared optimistically: an unreadable version is not a newer one.
        assertNull(announce(UpdateStatus.Idle, "nightly"))
        assertNull(announce(UpdateStatus.Idle, "v0.6.0-rc1"))
    }

    // -- Guard 2: cleared when the token is removed ----------------------------------------------

    @Test
    fun `with no token there is nothing to announce`() {
        // Belt and braces. Removing the token clears the whole preferences file, so nothing is
        // remembered - and even if something were, announcing an update the owner cannot download
        // until they deal with a token is a notice that only nags.
        assertNull(announce(UpdateStatus.NoToken, "v0.6.0"))
    }

    // -- Guard 3: a failed check never creates a notice -------------------------------------------

    @Test
    fun `a failed check invents nothing`() {
        val failures = listOf(
            UpdateStatus.Offline,
            UpdateStatus.GitHubUnavailable(503),
            UpdateStatus.RateLimited(null),
            UpdateStatus.TokenExpired,
            UpdateStatus.TokenNotAccepted,
            UpdateStatus.NoRepoAccess,
            UpdateStatus.InsufficientPermission,
            UpdateStatus.UnreadableRelease,
        )

        failures.forEach { status ->
            assertNull("$status must not conjure a notice", announce(status, null))
        }
    }

    /**
     * The other half of guard 3, and the one worth being explicit about: a failure does not *erase*
     * what the last successful check found either. Being offline says nothing about whether a
     * release exists — the same reasoning REQ-0003 uses for not dropping a stored token when the
     * network is down.
     */
    @Test
    fun `a failed check leaves the last successful answer standing`() {
        assertEquals("v0.6.0", announce(UpdateStatus.Offline, "v0.6.0"))
        assertEquals("v0.6.0", announce(UpdateStatus.GitHubUnavailable(500), "v0.6.0"))
    }

    // -- A fresh answer always wins ---------------------------------------------------------------

    @Test
    fun `a check that just found nothing newer silences a stale memory`() {
        assertNull(announce(UpdateStatus.UpToDate(1_700_000_000L), "v0.6.0"))
    }

    @Test
    fun `a live answer is announced from the live release, not from the memory`() {
        val status = UpdateStatus.Available(release("v0.7.0"), 1_700_000_000L)

        assertEquals("v0.7.0", announce(status, "v0.6.0"))
    }

    @Test
    fun `mid-check, whatever was known before still stands`() {
        // Otherwise the banner blinks out for the length of every check on every launch.
        assertEquals("v0.6.0", announce(UpdateStatus.Checking, "v0.6.0"))
    }

    @Test
    fun `an unknown installed version announces nothing`() {
        // Nothing to compare against, so nothing can be shown to be newer.
        assertNull(UpdateNotice.tagToAnnounce(UpdateStatus.Idle, "v9.9.9", installed = null))
    }
}
