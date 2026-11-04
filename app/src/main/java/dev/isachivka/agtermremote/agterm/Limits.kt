package dev.isachivka.agtermremote.agterm

/**
 * The Claude Code and Codex subscription limits of the laptop, as its bridge reported them.
 *
 * The bridge reads them with the logins the two CLIs keep on the Mac and answers from a cache - the
 * endpoints are rate limited per account - so asking on every arrival at the list is cheap. The
 * refresh button is what reaches past that cache. See `bridge/internal/limits`.
 */
data class LimitWindow(
    /** "5h", "Week", "Fable week" - the bridge's words. */
    val label: String,
    /** 0..100. */
    val usedPercent: Double,
    /** Unix seconds; zero when the provider did not say. */
    val resetsAt: Long,
)

data class LimitsProvider(
    /** "claude" or "codex". */
    val name: String,
    val plan: String,
    val windows: List<LimitWindow>,
    /** When [windows] were read on the Mac, unix seconds; zero when they never were. */
    val fetchedAt: Long,
    /** Why the last attempt did not bring fresh numbers. [windows], when present, are older ones. */
    val error: String,
)

data class LimitsState(
    val providers: List<LimitsProvider> = emptyList(),
    /** A request is out. The last answer stays on screen meanwhile. */
    val loading: Boolean = false,
    /** At least one answer has arrived. Before that there is nothing to draw. */
    val known: Boolean = false,
    /** The bridge does not know the verb - a Mac app older than this phone. The card stays away. */
    val unsupported: Boolean = false,
)

/** How full a window is, for its colour. The thresholds the eye needs, not a gradient. */
enum class LimitLevel {
    Calm, Warm, Hot;

    companion object {
        fun of(percent: Double): LimitLevel = when {
            percent < 60 -> Calm
            percent < 85 -> Warm
            else -> Hot
        }
    }
}

object LimitsFormat {
    /** "2d 4h", "3h 12m", "12m", "<1m", or null once it has passed or when unknown. */
    fun until(resetsAt: Long, nowSeconds: Long): String? {
        if (resetsAt <= 0) return null
        val total = resetsAt - nowSeconds
        if (total <= 0) return null
        if (total < 60) return "<1m"
        val days = total / 86_400
        val hours = (total % 86_400) / 3_600
        val minutes = (total % 3_600) / 60
        return when {
            days > 0 -> if (hours > 0) "${days}d ${hours}h" else "${days}d"
            hours > 0 -> if (minutes > 0) "${hours}h ${minutes}m" else "${hours}h"
            else -> "${minutes}m"
        }
    }

    /** "<1m", "12m", "3h", "2d" - how old the numbers are. */
    fun age(fetchedAt: Long, nowSeconds: Long): String {
        val seconds = (nowSeconds - fetchedAt).coerceAtLeast(0)
        return when {
            seconds < 60 -> "<1m"
            seconds < 3_600 -> "${seconds / 60}m"
            seconds < 86_400 -> "${seconds / 3_600}h"
            else -> "${seconds / 86_400}d"
        }
    }

    /** Whole percent, rounded the way the eye expects a bar to read. */
    fun percent(used: Double): String = "${used.coerceIn(0.0, 100.0).let { Math.round(it) }}%"
}
