package dev.isachivka.bewareofsugar.limits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * The tile's words and colours, pinned here rather than left to a screenshot: the thresholds are the
 * status line's, the reset text changes shape at a day, and every state has exactly one footer.
 */
class LimitsPresentationTest {

    private val now = Instant.parse("2026-09-06T10:00:00Z")
    private val utc = ZoneId.of("UTC")
    private val snapshot = LimitsSnapshot(
        fetchedAt = now.minusSeconds(12 * 60),
        claude = ProviderLimits.Windows(listOf(LimitWindow(WindowKind.FiveHour, 89, null))),
        codex = ProviderLimits.Failed(ProviderError.Expired),
    )

    @Test
    fun `the tone thresholds are the status line's`() {
        assertEquals(LimitTone.Good, toneFor(100))
        assertEquals(LimitTone.Good, toneFor(50))
        assertEquals(LimitTone.Low, toneFor(49))
        assertEquals(LimitTone.Low, toneFor(20))
        assertEquals(LimitTone.Critical, toneFor(19))
        assertEquals(LimitTone.Critical, toneFor(0))
    }

    @Test
    fun `under a day the reset is a countdown, from a day on it is a weekday and time`() {
        assertEquals(ResetText.In(0, 59), resetTextFor(now.plusSeconds(59 * 60), now, utc))
        assertEquals(ResetText.In(23, 0), resetTextFor(now.plusSeconds(23 * 3600), now, utc))
        assertEquals(ResetText.At(DayOfWeek.MONDAY, LocalTime.of(11, 0)), resetTextFor(now.plusSeconds(25 * 3600), now, utc))
        assertEquals(ResetText.At(DayOfWeek.MONDAY, LocalTime.of(10, 0)), resetTextFor(now.plusSeconds(8 * 24 * 3600), now, utc))
    }

    @Test
    fun `the weekday and time are in the zone asked for`() {
        val moscow = ZoneId.of("Europe/Moscow")
        assertEquals(ResetText.At(DayOfWeek.MONDAY, LocalTime.of(14, 0)), resetTextFor(now.plusSeconds(25 * 3600), now, moscow))
    }

    @Test
    fun `a reset in the past reads as now`() {
        assertEquals(ResetText.In(0, 0), resetTextFor(now.minusSeconds(60), now, utc))
    }

    @Test
    fun `each state has one footer`() {
        assertEquals(Footer.Idle, footerFor(LimitsState.Idle, now))
        assertEquals(Footer.NotPaired, footerFor(LimitsState.NotPaired, now))
        assertEquals(Footer.TooOld, footerFor(LimitsState.BridgeTooOld, now))
        assertEquals(Footer.Unreachable, footerFor(LimitsState.Unreachable(null), now))
        assertEquals(Footer.Unreachable, footerFor(LimitsState.Unreachable(snapshot), now))
        assertEquals(Footer.Updating, footerFor(LimitsState.Fetching(null), now))
        assertEquals(Footer.Updating, footerFor(LimitsState.Fetching(snapshot), now))
        assertEquals(Footer.Refused("no reader"), footerFor(LimitsState.Refused("no reader", null), now))
        assertEquals(Footer.UpdatedAgo(12), footerFor(LimitsState.Held(snapshot), now))
    }

    @Test
    fun `a snapshot from the future is zero minutes old, not negative`() {
        val ahead = snapshot.copy(fetchedAt = now.plusSeconds(120))
        assertEquals(Footer.UpdatedAgo(0), footerFor(LimitsState.Held(ahead), now))
    }

    @Test
    fun `the numbers shown are the last snapshot, whatever the state around it`() {
        assertEquals(snapshot, LimitsState.Unreachable(snapshot).shown)
        assertEquals(snapshot, LimitsState.Fetching(snapshot).shown)
        assertEquals(snapshot, LimitsState.Refused("x", snapshot).shown)
        assertEquals(snapshot, LimitsState.Held(snapshot).shown)
        assertNull(LimitsState.NotPaired.shown)
        assertNull(LimitsState.BridgeTooOld.shown)
        assertNull(LimitsState.Idle.shown)
    }

    @Test
    fun `only fetching is busy`() {
        assertEquals(true, LimitsState.Fetching(null).busy)
        assertEquals(true, LimitsState.Fetching(snapshot).busy)
        assertEquals(false, LimitsState.Held(snapshot).busy)
        assertEquals(false, LimitsState.Unreachable(snapshot).busy)
        assertEquals(false, LimitsState.Idle.busy)
    }
}
