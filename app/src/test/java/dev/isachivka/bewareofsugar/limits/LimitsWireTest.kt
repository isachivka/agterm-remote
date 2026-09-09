package dev.isachivka.bewareofsugar.limits

import dev.isachivka.bewareofsugar.wire.WireException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The `limits` reply as the bridge writes it — REQ-0045 section 2 — and the ways it can be less than
 * that without taking the tile down with it.
 */
class LimitsWireTest {

    private val reply = """
        {"ok":true,"fit_enabled":false,"needs_fit":false,"limits":{
          "fetched_at":"2026-09-06T06:02:11Z",
          "claude":{"windows":[
            {"kind":"5h","remaining_pct":89,"resets_at":"2026-09-06T07:29:59Z"},
            {"kind":"7d","remaining_pct":0}]},
          "codex":{"error":"expired"}}}
    """.trimIndent()

    @Test
    fun `both providers are read, one with windows and one with an error`() {
        val snapshot = parseLimits(JSONObject(reply))

        assertEquals(Instant.parse("2026-09-06T06:02:11Z"), snapshot.fetchedAt)
        val claude = snapshot.claude as ProviderLimits.Windows
        assertEquals(
            listOf(
                LimitWindow(WindowKind.FiveHour, 89, Instant.parse("2026-09-06T07:29:59Z")),
                LimitWindow(WindowKind.SevenDay, 0, null),
            ),
            claude.windows,
        )
        assertEquals(ProviderLimits.Failed(ProviderError.Expired), snapshot.codex)
    }

    @Test
    fun `an error this phone does not know is malformed, not a crash`() {
        val snapshot = parseLimits(
            JSONObject("""{"ok":true,"limits":{"fetched_at":"2026-09-06T06:02:11Z","claude":{"error":"quota_moon"},"codex":{"windows":[]}}}"""),
        )
        assertEquals(ProviderLimits.Failed(ProviderError.Malformed), snapshot.claude)
        // An empty window list is a provider that answered nothing usable.
        assertEquals(ProviderLimits.Failed(ProviderError.Malformed), snapshot.codex)
    }

    @Test
    fun `a missing provider object and an unknown window kind are dropped without a crash`() {
        val snapshot = parseLimits(
            JSONObject("""{"ok":true,"limits":{"fetched_at":"2026-09-06T06:02:11Z","claude":{"windows":[{"kind":"30d","remaining_pct":5},{"kind":"7d","remaining_pct":40}]}}}"""),
        )
        assertEquals(listOf(LimitWindow(WindowKind.SevenDay, 40, null)), (snapshot.claude as ProviderLimits.Windows).windows)
        assertEquals(ProviderLimits.Failed(ProviderError.Malformed), snapshot.codex)
    }

    @Test
    fun `no limits object at all is a malformed reply`() {
        val thrown = runCatching { parseLimits(JSONObject("""{"ok":true}""")) }.exceptionOrNull()
        assertTrue("$thrown", thrown is WireException)
    }

    @Test
    fun `an unreadable resets_at is absence rather than a crash`() {
        val snapshot = parseLimits(
            JSONObject("""{"ok":true,"limits":{"fetched_at":"2026-09-06T06:02:11Z","claude":{"windows":[{"kind":"5h","remaining_pct":1,"resets_at":"soon"}]},"codex":{"error":"unreachable"}}}"""),
        )
        assertNull((snapshot.claude as ProviderLimits.Windows).windows.single().resetsAt)
    }

    @Test
    fun `every word of the bridge's vocabulary has a value here`() {
        for (wire in listOf("no_credential", "expired", "unreachable", "malformed")) {
            assertEquals(wire, ProviderError.fromWire(wire).wire)
        }
    }
}
