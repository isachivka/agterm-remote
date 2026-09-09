package dev.isachivka.bewareofsugar.limits

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Green from 50 left, amber from 20, red below — the thresholds of the owner's own Claude Code status
 * line, so the phone and the terminal never disagree about whether a number is worrying.
 */
enum class LimitTone { Good, Low, Critical }

fun toneFor(remainingPct: Int): LimitTone = when {
    remainingPct >= 50 -> LimitTone.Good
    remainingPct >= 20 -> LimitTone.Low
    else -> LimitTone.Critical
}

/** A countdown under a day; a weekday and a local time from a day on. */
sealed interface ResetText {
    data class In(val hours: Int, val minutes: Int) : ResetText
    data class At(val dayOfWeek: DayOfWeek, val time: LocalTime) : ResetText
}

fun resetTextFor(resetsAt: Instant, now: Instant, zone: ZoneId): ResetText {
    val left = Duration.between(now, resetsAt)
    // A reset already behind us is "now" rather than a negative countdown: the provider will report
    // the new window on the next read, and until then the honest thing to say is that it is due.
    if (left.isNegative) return ResetText.In(0, 0)
    if (left < Duration.ofDays(1)) return ResetText.In(left.toHours().toInt(), (left.toMinutes() % 60).toInt())
    val local = resetsAt.atZone(zone)
    return ResetText.At(local.dayOfWeek, local.toLocalTime().withSecond(0).withNano(0))
}

/** The line under both halves. One per state; the sentence for each lives in strings.xml. */
sealed interface Footer {
    data object Idle : Footer
    data object NotPaired : Footer
    data object TooOld : Footer
    data object Unreachable : Footer
    data object Updating : Footer
    data class Refused(val reason: String) : Footer
    data class UpdatedAgo(val minutes: Long) : Footer
}

fun footerFor(state: LimitsState, now: Instant): Footer = when (state) {
    LimitsState.Idle -> Footer.Idle
    LimitsState.NotPaired -> Footer.NotPaired
    LimitsState.BridgeTooOld -> Footer.TooOld
    is LimitsState.Refused -> Footer.Refused(state.reason)
    is LimitsState.Unreachable -> Footer.Unreachable
    is LimitsState.Fetching -> Footer.Updating
    is LimitsState.Held ->
        Footer.UpdatedAgo(Duration.between(state.snapshot.fetchedAt, now).toMinutes().coerceAtLeast(0))
}
