package dev.isachivka.bewareofsugar.reachability

/**
 * The one line at the top that says what the ten rows add up to.
 *
 * It exists because a tally is not a diagnosis. Ten rows failing the same way is **one** thing that
 * happened, and presenting it as ten separate failures would be technically accurate and useless —
 * which is the difference between a tool and a table.
 *
 * What it may say is carefully bounded. It describes **the failures observed**, and never claims the
 * ten services share infrastructure. Over IPv4 they do — one address, one port, one ingress — but all
 * ten also publish distinct IPv6 addresses, and the app cannot know which family the phone used. So
 * "every row failed at the same layer" is something this can assert, and "your one door is shut" is
 * not.
 */
sealed interface CheckSummary {

    data object NotChecked : CheckSummary

    data class Running(val done: Int, val total: Int) : CheckSummary

    /** At least one service answered. */
    data class Reached(val reached: Int, val total: Int) : CheckSummary

    /** Nothing answered. [pattern] is the part worth reading. */
    data class NothingReached(val total: Int, val pattern: FailurePattern) : CheckSummary
}

/**
 * The shape of a total failure, which is more informative than the fact of it.
 */
enum class FailurePattern {

    /**
     * Every row failed at the same layer — all DNS, or all connection, or all TLS.
     *
     * The signature of a network doing something to the owner rather than of their services being
     * down, because ten unrelated services do not break in the same way at the same moment. Stated as
     * a fact about the failures and not about the hosts.
     */
    AllSameLayer,

    /** Different layers, so no single story fits. */
    Mixed,

    /**
     * Everything was answered by the owner's router rather than by a service.
     *
     * Nothing is filtering — the connections succeeded and the certificate verified — and nothing is
     * proxying these names. That is a homelab problem and a very specific one.
     */
    RouterOnly,
}

/** Where a check gave up, which is what "the same way" means. */
private enum class FailureLayer { Name, Connection, Tls, Answer, Unknown, NotAFailure }

private fun layerOf(reachability: Reachability): FailureLayer = when (reachability) {
    Reachability.NameNotResolved -> FailureLayer.Name
    Reachability.ConnectionRefused, Reachability.ConnectionTimedOut -> FailureLayer.Connection
    is Reachability.TlsRejected -> FailureLayer.Tls
    Reachability.NoAnswerInTime -> FailureLayer.Answer
    is Reachability.CheckFailed -> FailureLayer.Unknown
    is Reachability.Answered, is Reachability.RouterAnswered,
    Reachability.NotChecked, Reachability.Checking,
    -> FailureLayer.NotAFailure
}

/**
 * @param results one entry per service, in any order.
 * @param total how many services there are, so a partial set reads as "still running" rather than as
 * a verdict about the ones that happen to have finished.
 */
fun summaryOf(results: Collection<Reachability>, total: Int): CheckSummary {
    if (results.isEmpty() || results.all { it == Reachability.NotChecked }) return CheckSummary.NotChecked

    val settled = results.filterNot { it == Reachability.NotChecked || it == Reachability.Checking }
    if (settled.size < total) return CheckSummary.Running(settled.size, total)

    // Only a service answering counts as reached. The router answering means the network is fine and
    // the service is not there, which is a different sentence and belongs below.
    val reached = settled.count { it is Reachability.Answered }
    if (reached > 0) return CheckSummary.Reached(reached, total)

    if (settled.all { it is Reachability.RouterAnswered }) {
        return CheckSummary.NothingReached(total, FailurePattern.RouterOnly)
    }

    val layers = settled.map(::layerOf).toSet()
    val pattern = if (layers.size == 1 && layers.first() != FailureLayer.NotAFailure) {
        FailurePattern.AllSameLayer
    } else {
        FailurePattern.Mixed
    }
    return CheckSummary.NothingReached(total, pattern)
}
