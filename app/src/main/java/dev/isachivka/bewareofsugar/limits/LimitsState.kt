package dev.isachivka.bewareofsugar.limits

/**
 * What the tile is in — REQ-0045's table of states.
 *
 * A snapshot travels with the states that can follow one, so a laptop that stopped answering does not
 * blank the numbers it answered with a minute ago: they stay, with their age, under a footer that says
 * the laptop is unreachable. [shown] is the one place that decision is made, so the tile cannot draw
 * numbers from one field in one state and another field in the next.
 */
sealed interface LimitsState {
    /** Nothing asked yet. The launcher asks on its first appearance, so this is brief. */
    data object Idle : LimitsState

    data object NotPaired : LimitsState

    /** The bridge answered `unknown verb` or `malformed request`: it predates this tile. */
    data object BridgeTooOld : LimitsState

    /** The bridge refused for a reason of its own, in its own words. */
    data class Refused(val reason: String, val last: LimitsSnapshot?) : LimitsState

    data class Unreachable(val last: LimitsSnapshot?) : LimitsState

    data class Fetching(val last: LimitsSnapshot?) : LimitsState

    data class Held(val snapshot: LimitsSnapshot) : LimitsState

    val shown: LimitsSnapshot?
        get() = when (this) {
            is Held -> snapshot
            is Fetching -> last
            is Unreachable -> last
            is Refused -> last
            Idle, NotPaired, BridgeTooOld -> null
        }

    val busy: Boolean get() = this is Fetching
}
