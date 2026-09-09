package dev.isachivka.bewareofsugar.update.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Play said, turned into what the owner is told — REQ-0050.
 *
 * This is the whole of the Play path's decision-making, and it is here rather than against Play's
 * own `AppUpdateInfo` for a reason worth keeping: that class is final, has no public constructor,
 * and answers about a real track. A mapping written against it could only ever be exercised by
 * shipping two releases and waiting.
 */
class PlayUpdateCheckTest {

    private val now = 1_700_000_000L

    private fun info(
        availability: Int,
        versionCode: Int = 3400,
        immediateAllowed: Boolean = true,
    ) = PlayUpdateInfo(availability, versionCode, immediateAllowed)

    @Test
    fun `an available update carries the version code play offered`() {
        val status = PlayUpdateCheck.evaluate(
            info(PlayUpdateCheck.UPDATE_AVAILABLE, versionCode = 3401),
            now,
        )
        assertEquals(PlayUpdateStatus.Available(3401, now, immediateAllowed = true), status)
    }

    @Test
    fun `an available update play will not force is still an available update`() {
        // The distinction the screen needs: there IS a newer version, and this app cannot be the one
        // to install it. Reporting up-to-date here would be a lie, and reporting a forceable update
        // would give the owner a button that does nothing.
        val status = PlayUpdateCheck.evaluate(
            info(PlayUpdateCheck.UPDATE_AVAILABLE, immediateAllowed = false),
            now,
        )
        assertEquals(PlayUpdateStatus.Available(3400, now, immediateAllowed = false), status)
    }

    @Test
    fun `no update available is up to date`() {
        assertEquals(
            PlayUpdateStatus.UpToDate(now),
            PlayUpdateCheck.evaluate(info(PlayUpdateCheck.UPDATE_NOT_AVAILABLE), now),
        )
    }

    @Test
    fun `an update already running is its own state`() {
        // Not Available: there is nothing to start, only something Play is already doing. Offering
        // "Update" here starts a second flow over the first.
        assertEquals(
            PlayUpdateStatus.InProgress(now),
            PlayUpdateCheck.evaluate(info(PlayUpdateCheck.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS), now),
        )
    }

    @Test
    fun `unknown is not up to date`() {
        // The one wrong answer available here. "Up to date" is the sentence that makes the owner
        // stop looking, and Play saying it does not know is not Play saying there is nothing.
        val status = PlayUpdateCheck.evaluate(info(PlayUpdateCheck.UNKNOWN), now)
        assertEquals(PlayUpdateStatus.Unknown(now), status)
        assertTrue(status !is PlayUpdateStatus.UpToDate)
    }

    @Test
    fun `a value this code has never seen is unknown rather than up to date`() {
        // Play may add constants. The failure mode of guessing must be "I cannot tell you", never
        // "there is nothing new".
        val status = PlayUpdateCheck.evaluate(info(availability = 99), now)
        assertEquals(PlayUpdateStatus.Unknown(now), status)
    }

    @Test
    fun `the restated constants match the values play publishes`() {
        // These four are copied out of Play's UpdateAvailability rather than imported, because
        // importing it would put a throwing stub on the unit-test classpath. This test is the pin:
        // it fails if someone edits the numbers, and PlayUpdates stops compiling if Play moves them.
        assertEquals(0, PlayUpdateCheck.UNKNOWN)
        assertEquals(1, PlayUpdateCheck.UPDATE_NOT_AVAILABLE)
        assertEquals(2, PlayUpdateCheck.UPDATE_AVAILABLE)
        assertEquals(3, PlayUpdateCheck.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS)
    }
}
