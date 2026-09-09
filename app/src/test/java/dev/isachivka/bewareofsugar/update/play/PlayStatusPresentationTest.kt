package dev.isachivka.bewareofsugar.update.play

import dev.isachivka.bewareofsugar.update.StatusTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How loudly the Play status card speaks — REQ-0050.
 *
 * Every branch, because the `when` is exhaustive and a ninth state would otherwise render whatever
 * the last branch happened to be. The rule under test is the one the GitHub path already states:
 * loud styling is for something the owner just did that did not work.
 */
class PlayStatusPresentationTest {

    private val now = 1_700_000_000L

    @Test
    fun `up to date is the only success`() {
        assertEquals(StatusTone.Success, presentationOf(PlayUpdateStatus.UpToDate(now)).tone)
    }

    @Test
    fun `an available update is the update tone`() {
        assertEquals(
            StatusTone.UpdateAvailable,
            presentationOf(PlayUpdateStatus.Available(3400, now, true)).tone,
        )
    }

    @Test
    fun `an update in progress reads as something happening, not as nothing to do`() {
        val p = presentationOf(PlayUpdateStatus.InProgress(now))
        assertEquals(StatusTone.UpdateAvailable, p.tone)
        assertTrue("an update being applied should spin", p.spinner)
    }

    @Test
    fun `checking spins`() {
        assertTrue(presentationOf(PlayUpdateStatus.Checking).spinner)
    }

    @Test
    fun `nothing else spins`() {
        // A spinner that does not spin is worse than no spinner: it says "wait" forever.
        listOf(
            PlayUpdateStatus.Idle,
            PlayUpdateStatus.UpToDate(now),
            PlayUpdateStatus.Available(3400, now, true),
            PlayUpdateStatus.NotFromPlay,
            PlayUpdateStatus.Unknown(now),
            PlayUpdateStatus.Failed(-6),
        ).forEach { assertFalse("$it must not spin", presentationOf(it).spinner) }
    }

    @Test
    fun `a build that did not come from play has something for the owner to do`() {
        assertEquals(StatusTone.NeedsToken, presentationOf(PlayUpdateStatus.NotFromPlay).tone)
    }

    @Test
    fun `failures are quiet`() {
        // REQ-0003's rule, inherited: a check the owner did not start, that failed for reasons that
        // are not theirs, gets a sentence and no alarm.
        assertEquals(StatusTone.Neutral, presentationOf(PlayUpdateStatus.Failed(-6)).tone)
        assertEquals(StatusTone.Neutral, presentationOf(PlayUpdateStatus.Failed(null)).tone)
        assertEquals(StatusTone.Neutral, presentationOf(PlayUpdateStatus.Unknown(now)).tone)
    }

    @Test
    fun `unknown is never dressed as success`() {
        // The one wrong answer available here: green means the owner stops looking.
        assertFalse(presentationOf(PlayUpdateStatus.Unknown(now)).tone == StatusTone.Success)
    }

    @Test
    fun `idle claims nothing`() {
        assertEquals(StatusTone.Neutral, presentationOf(PlayUpdateStatus.Idle).tone)
    }
}
