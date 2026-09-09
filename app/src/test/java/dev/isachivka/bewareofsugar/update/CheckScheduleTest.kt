package dev.isachivka.bewareofsugar.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "At most once a day, plus a manual check", at the boundary rather than by waiting a day. */
class CheckScheduleTest {

    private val now = 1_700_000_000L
    private val day = CheckSchedule.ONE_DAY_SECONDS

    @Test
    fun `a first launch checks`() {
        assertTrue(CheckSchedule.isDue(lastCheckedAtEpochSeconds = 0L, nowEpochSeconds = now, manual = false))
    }

    @Test
    fun `just checked does not check again`() {
        assertFalse(CheckSchedule.isDue(now - 1, now, manual = false))
    }

    @Test
    fun `one second short of a day does not check`() {
        assertFalse(CheckSchedule.isDue(now - (day - 1), now, manual = false))
    }

    @Test
    fun `exactly a day checks`() {
        assertTrue(CheckSchedule.isDue(now - day, now, manual = false))
    }

    @Test
    fun `a week later checks`() {
        assertTrue(CheckSchedule.isDue(now - 7 * day, now, manual = false))
    }

    @Test
    fun `a manual check always runs`() {
        // A manual check that answers "not yet, come back tomorrow" is not a manual check.
        assertTrue(CheckSchedule.isDue(now, now, manual = true))
        assertTrue(CheckSchedule.isDue(now - 1, now, manual = true))
    }

    @Test
    fun `a clock that moved backwards does not lock out checking`() {
        // A timezone change, a hand-set clock, or a reboot before NTP caught up. Trusting a stored
        // time in the future would suppress every check until real time caught up with it.
        assertTrue(CheckSchedule.isDue(lastCheckedAtEpochSeconds = now + 5 * day, nowEpochSeconds = now, manual = false))
    }
}
