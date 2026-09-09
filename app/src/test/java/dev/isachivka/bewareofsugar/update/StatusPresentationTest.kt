package dev.isachivka.bewareofsugar.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every state the check can be in, and how loudly the screen says it.
 *
 * The exhaustive `when` in `presentationOf` is what guarantees a state cannot be *missed*. This is
 * what guarantees the choice was the intended one — the two are different claims, and only the
 * compiler makes the first.
 *
 * All thirteen are listed by hand rather than reflected over. `sealedSubclasses` needs
 * kotlin-reflect, and a list that discovers itself would silently start passing for a state nobody
 * had thought about.
 */
class StatusPresentationTest {

    private val everyStatus = listOf(
        UpdateStatus.Idle,
        UpdateStatus.Checking,
        UpdateStatus.UpToDate(1_700_000_000L),
        UpdateStatus.Available(
            Release("v0.6.0", AppVersion.parse("v0.6.0"), "v0.6.0", "", false, false, emptyList()),
            1_700_000_000L,
        ),
        UpdateStatus.NoToken,
        UpdateStatus.TokenUnreadable,
        UpdateStatus.TokenExpired,
        UpdateStatus.TokenNotAccepted,
        UpdateStatus.NoRepoAccess,
        UpdateStatus.InsufficientPermission,
        UpdateStatus.RateLimited(null),
        UpdateStatus.Offline,
        UpdateStatus.GitHubUnavailable(503),
        UpdateStatus.UnreadableRelease,
    )

    @Test
    fun `all fourteen states are covered here`() {
        // Guards the list above, not the mapping. If a fifteenth state is added, presentationOf
        // fails to compile and this number is the reminder to come and think about it.
        assertEquals(14, everyStatus.size)
        assertEquals("no duplicates in the list", 14, everyStatus.toSet().size)
    }

    @Test
    fun `every state has an icon`() {
        everyStatus.forEach { status ->
            assertTrue("$status has no icon", presentationOf(status).icon != 0)
        }
    }

    // -- The tones, one claim each ----------------------------------------------------------------

    @Test
    fun `being up to date is the only success`() {
        val success = everyStatus.filter { presentationOf(it).tone == StatusTone.Success }

        assertEquals(listOf(UpdateStatus.UpToDate(1_700_000_000L)), success)
    }

    @Test
    fun `an available update is its own tone, and only it`() {
        val announced = everyStatus.filter { presentationOf(it).tone == StatusTone.UpdateAvailable }

        assertEquals(1, announced.size)
        assertTrue(announced.single() is UpdateStatus.Available)
    }

    @Test
    fun `every state the owner fixes with a token says so the same way`() {
        val needsToken = everyStatus.filter { presentationOf(it).tone == StatusTone.NeedsToken }

        assertEquals(
            listOf(
                UpdateStatus.NoToken,
                UpdateStatus.TokenUnreadable,
                UpdateStatus.TokenExpired,
                UpdateStatus.TokenNotAccepted,
                UpdateStatus.NoRepoAccess,
                UpdateStatus.InsufficientPermission,
            ),
            needsToken,
        )
    }

    /**
     * The claim that matters most, because getting it wrong is how an updater starts shouting.
     * REQ-0003 is explicit that a failed check is quiet: none of these is the owner's doing, and a
     * red card for a tunnel is not quiet.
     */
    @Test
    fun `a check that failed for reasons outside this phone stays quiet`() {
        val transient = listOf(
            UpdateStatus.Offline,
            UpdateStatus.GitHubUnavailable(503),
            UpdateStatus.RateLimited(null),
            UpdateStatus.UnreadableRelease,
        )

        transient.forEach { status ->
            assertEquals("$status must not raise its voice", StatusTone.Neutral, presentationOf(status).tone)
        }
    }

    @Test
    fun `nothing yet checked is quiet too`() {
        assertEquals(StatusTone.Neutral, presentationOf(UpdateStatus.Idle).tone)
        assertEquals(StatusTone.Neutral, presentationOf(UpdateStatus.Checking).tone)
    }

    // -- The spinner ------------------------------------------------------------------------------

    @Test
    fun `only the state that is actually working shows something moving`() {
        val moving = everyStatus.filter { presentationOf(it).spinner }

        assertEquals(listOf(UpdateStatus.Checking), moving)
    }

    @Test
    fun `a finished state never shows a spinner`() {
        // A spinner that does not spin is worse than no spinner; one that spins forever is worse
        // still, because it says work is happening when none is.
        assertFalse(presentationOf(UpdateStatus.Offline).spinner)
        assertFalse(presentationOf(UpdateStatus.Idle).spinner)
    }
}
