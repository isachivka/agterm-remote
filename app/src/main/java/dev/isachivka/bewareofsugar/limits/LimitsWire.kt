package dev.isachivka.bewareofsugar.limits

import dev.isachivka.bewareofsugar.wire.WireException
import dev.isachivka.bewareofsugar.wire.WireFailure
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The `limits` reply, read into [LimitsSnapshot].
 *
 * A reply with no `limits` object is [WireFailure.Malformed]: the bridge said ok and did not answer the
 * question. A provider object that is missing, or whose window list is empty, is that provider failing
 * with [ProviderError.Malformed] — the other provider is still read. A window kind this phone does not
 * know is dropped, so a bridge that one day publishes a per-model window does not break the tile that
 * chose not to show one.
 */
fun parseLimits(reply: JSONObject): LimitsSnapshot {
    val limits = reply.optJSONObject("limits") ?: throw WireException(WireFailure.Malformed)
    val fetchedAt = instantOrNull(limits.optString("fetched_at")) ?: throw WireException(WireFailure.Malformed)
    return LimitsSnapshot(
        fetchedAt = fetchedAt,
        claude = provider(limits.optJSONObject("claude")),
        codex = provider(limits.optJSONObject("codex")),
    )
}

private fun provider(obj: JSONObject?): ProviderLimits {
    if (obj == null) return ProviderLimits.Failed(ProviderError.Malformed)
    val error = obj.optString("error")
    if (error.isNotEmpty()) return ProviderLimits.Failed(ProviderError.fromWire(error))
    val array = obj.optJSONArray("windows") ?: return ProviderLimits.Failed(ProviderError.Malformed)
    val windows = (0 until array.length()).mapNotNull { i ->
        val w = array.optJSONObject(i) ?: return@mapNotNull null
        val kind = WindowKind.fromWire(w.optString("kind")) ?: return@mapNotNull null
        // `remaining_pct` is never omitted by the bridge - zero left is the most important value it
        // carries - so its absence is a window this phone cannot read rather than a zero.
        if (!w.has("remaining_pct")) return@mapNotNull null
        LimitWindow(kind, w.optInt("remaining_pct").coerceIn(0, 100), instantOrNull(w.optString("resets_at")))
    }
    return if (windows.isEmpty()) ProviderLimits.Failed(ProviderError.Malformed) else ProviderLimits.Windows(windows)
}

private fun instantOrNull(s: String): Instant? =
    if (s.isEmpty()) null else try { Instant.parse(s) } catch (e: DateTimeParseException) { null }
