package dev.isachivka.bewareofsugar.limits

import java.time.Instant

/**
 * How much of each subscription is left, as the bridge published it — REQ-0045.
 *
 * Mirrors the wire and adds nothing: two providers, each with its windows or the reason it could not
 * be asked, and the moment the bridge's cache was filled. Percent is what is LEFT, the way the owner's
 * status line already says it, never what is used — the conversion happened once, on the Mac, and no
 * screen here has to remember which way round a number is.
 */
enum class WindowKind(val wire: String, val label: String) {
    FiveHour("5h", "5h"),
    SevenDay("7d", "7d");

    companion object {
        /** Null for a kind this phone does not know, which the parser drops rather than guesses at. */
        fun fromWire(s: String): WindowKind? = entries.firstOrNull { it.wire == s }
    }
}

data class LimitWindow(
    val kind: WindowKind,
    val remainingPct: Int,
    /** Null when the provider gave none, or gave one this phone could not read. */
    val resetsAt: Instant?,
)

/**
 * Why a provider could not be asked. The bridge's closed vocabulary; a word outside it is
 * [Malformed], because a word this phone does not know is not a word it can put a sentence to.
 */
enum class ProviderError(val wire: String) {
    NoCredential("no_credential"),
    Expired("expired"),
    Unreachable("unreachable"),
    Malformed("malformed");

    companion object {
        fun fromWire(s: String): ProviderError = entries.firstOrNull { it.wire == s } ?: Malformed
    }
}

sealed interface ProviderLimits {
    data class Windows(val windows: List<LimitWindow>) : ProviderLimits
    data class Failed(val error: ProviderError) : ProviderLimits
}

data class LimitsSnapshot(
    /** The bridge's cache time, not the reply time — so "updated N min ago" is about the number. */
    val fetchedAt: Instant,
    val claude: ProviderLimits,
    val codex: ProviderLimits,
)
