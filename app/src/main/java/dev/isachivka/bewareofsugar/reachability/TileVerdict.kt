package dev.isachivka.bewareofsugar.reachability

/**
 * Whether the owner can get to a service.
 *
 * **One predicate, one place, shared by the tile and the screen.** The tile is a summary of the rows
 * directly below it, so if the two can ever disagree the tile is a second opinion rather than a
 * summary — and a second opinion on the launcher, where there is nothing to compare it against, is
 * just a confident number.
 *
 * It answers the owner's question — *can I get to this service* — and not *did a packet come back*.
 * Exhaustive, so a new state has to decide rather than defaulting to false and quietly counting as
 * unavailable.
 */
fun isAvailable(reachability: Reachability): Boolean = when (reachability) {
    // Not answers. Counted by neither side - see availableOf.
    Reachability.NotChecked, Reachability.Checking -> false

    is Reachability.Answered -> when (reachability.meaning) {
        // The service itself answered. A 401 is up: that rule does not change here.
        AnswerMeaning.Reachable,
        AnswerMeaning.NeedsAuth,
        AnswerMeaning.Redirecting,
        AnswerMeaning.AnsweredOddly,
        -> true

        // The ingress answered and the thing behind it is not running; or it answered and is broken.
        // Reached, in both cases, and not usable - which is what the owner is asking about.
        AnswerMeaning.GatewayDown, AnswerMeaning.ServiceError -> false
    }

    // The case most likely to be got wrong. You reached the owner's router, not their service:
    // the network is fine and the thing they wanted is not there.
    is Reachability.RouterAnswered -> false

    Reachability.NameNotResolved,
    Reachability.ConnectionRefused,
    Reachability.ConnectionTimedOut,
    Reachability.NoAnswerInTime,
    is Reachability.TlsRejected,
    is Reachability.CheckFailed,
    -> false
}

/** How loudly the launcher tile speaks. */
enum class TileLevel {
    /** Nothing has been asked yet. **Must not look like an answer.** */
    NotChecked,

    /** Nothing is reachable. The owner's own word for this was grey. */
    None,

    /** Some, but not more than half. The state they did not name and which 1–5 of 10 falls into. */
    Some,

    /** More than half. The owner's word was green. */
    Most,
}

/**
 * What the tile says, decided once and rendered twice — as a colour and as a sentence.
 *
 * @param busy true while a check is running, so the tile can say *Checking…* rather than showing a
 * tally that is still moving.
 */
data class TileVerdict(
    val level: TileLevel,
    val available: Int,
    val total: Int,
    val busy: Boolean,
)

/**
 * The tile's verdict over a set of results.
 *
 * **A partial round is not a verdict.** Until every service has settled the tile stays
 * [TileLevel.NotChecked] and busy, because a tally that is still arriving would flash green as the
 * fast services land and then fall to amber as the slow ones fail — a number that changes under the
 * owner's eye while claiming to be an answer.
 *
 * @param total always the number of services with a remote address. Hiding modules changes what is on
 * the launcher; it does not change how many services the owner has.
 */
fun tileVerdictOf(
    results: Collection<Reachability>,
    total: Int = ServiceRegistry.remote.size,
): TileVerdict {
    val settled = results.filterNot { it == Reachability.NotChecked || it == Reachability.Checking }
    val busy = results.any { it == Reachability.Checking }

    if (settled.size < total) {
        return TileVerdict(TileLevel.NotChecked, available = 0, total = total, busy = busy)
    }

    val available = settled.count(::isAvailable)
    val level = when {
        available == 0 -> TileLevel.None
        // Strictly more than half: 6 of 10, not 5. The owner said "more than half", and half is not
        // more than half.
        available * 2 > total -> TileLevel.Most
        else -> TileLevel.Some
    }
    return TileVerdict(level, available, total, busy = false)
}
