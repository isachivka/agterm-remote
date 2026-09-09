package dev.isachivka.bewareofsugar.reachability

/** One row, ready to draw. */
data class ServiceRowState(
    val service: HomeService,
    val reachability: Reachability,
    /** Null where no connection was attempted. Absence must render as absence. */
    val family: AddressFamily? = null,
) {
    val presentation: ReachabilityRow get() = rowFor(reachability)

    /**
     * The status the service answered with, where one exists.
     *
     * Already in the state and previously discarded before it reached the screen, which is why
     * "Reachable, oddly" covered 404 and 418 indistinguishably.
     */
    val statusCode: Int? get() = (reachability as? Reachability.Answered)?.code

    /** Null when there is no meaningful elapsed time to show. */
    val latencyMillis: Long? get() = when (reachability) {
        is Reachability.Answered -> reachability.latencyMillis
        is Reachability.RouterAnswered -> reachability.latencyMillis
        else -> null
    }
}

/**
 * Everything the screen draws, assembled from pure functions.
 *
 * Built here rather than in the composable so the whole screen can be rendered in any state without a
 * socket — which is what makes nineteen outcomes a set of previews and tests instead of nineteen trips
 * to a filtered network.
 */
data class ReachabilityUiState(
    val rows: List<ServiceRowState>,
    val notCheckable: List<String>,
) {
    val summary: CheckSummary get() = summaryOf(rows.map { it.reachability }, rows.size)

    companion object {
        fun initial(): ReachabilityUiState = of(
            ServiceRegistry.remote.associate { it.id to Probe(Reachability.NotChecked) },
        )

        fun of(results: Map<String, Probe>): ReachabilityUiState = ReachabilityUiState(
            rows = ServiceRegistry.remote.map { service ->
                val probe = results[service.id] ?: Probe(Reachability.NotChecked)
                ServiceRowState(service, probe.reachability, probe.family)
            },
            notCheckable = ServiceRegistry.withoutRemoteAddress,
        )

        /**
         * A spread for the preview: reachable, an auth challenge, the router answering, a certificate
         * that could not be verified, and a blocked handshake. Chosen to put the states that are hard
         * to read next to each other, which is what a preview is for.
         */
        fun preview(): ReachabilityUiState = of(
            mapOf(
                "torrent" to Probe(Reachability.Answered(401, 128, AnswerMeaning.NeedsAuth), AddressFamily.IPv4),
                "files" to Probe(Reachability.Answered(200, 94, AnswerMeaning.Reachable), AddressFamily.IPv4),
                "immich" to Probe(Reachability.RouterAnswered(112), AddressFamily.IPv6),
                "n8n" to Probe(Reachability.TlsRejected(TlsFailure.Untrusted, 402), AddressFamily.IPv4),
                "inventory" to Probe(Reachability.TlsRejected(TlsFailure.HandshakeFailed, 4_010), AddressFamily.IPv6),
                "ha" to Probe(Reachability.Answered(502, 220, AnswerMeaning.GatewayDown), AddressFamily.IPv4),
                "docs" to Probe(Reachability.Answered(302, 4_120, AnswerMeaning.Redirecting), AddressFamily.IPv4),
                // No connection was attempted, so no family. The row must show that as nothing.
                "frigate" to Probe(Reachability.NameNotResolved),
                "navidrome" to Probe(Reachability.ConnectionTimedOut, AddressFamily.IPv6),
                "jellyfin" to Probe(Reachability.Checking),
            ),
        )
    }
}
