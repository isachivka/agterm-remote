package dev.isachivka.agtermremote.agterm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class LimitsTest {

    private val listing = """{"ok":true,"sessions":[{"id":"A","workspace_id":"W","workspace":"main","name":"agterm","active":true}]}"""
    private val answer = """{"ok":true,"limits":[
        {"name":"claude","plan":"Max 20x","fetched_at":1000,"windows":[{"label":"5h","used_percent":41.5,"resets_at":4600},{"label":"Fable week","used_percent":0}]},
        {"name":"codex","plan":"Pro Lite","error":"rate limited","windows":[{"label":"Week","used_percent":3,"resets_at":9000}]}]}"""
        .replace("\n", "")

    @Test
    fun `the reply is read into providers and the request carries force only when asked`() {
        val sent = ByteArrayOutputStream()
        val bridge = BridgeConnection.ofStreams(ByteArrayInputStream("$answer\n$answer".toByteArray()), sent)

        val providers = bridge.limits(force = false)
        bridge.limits(force = true)

        assertEquals(
            listOf(
                LimitsProvider("claude", "Max 20x", listOf(LimitWindow("5h", 41.5, 4600), LimitWindow("Fable week", 0.0, 0)), 1000, ""),
                LimitsProvider("codex", "Pro Lite", listOf(LimitWindow("Week", 3.0, 9000)), 0, "rate limited"),
            ),
            providers,
        )
        val requests = sent.toString(Charsets.UTF_8).trim().lines().map(::JSONObject)
        assertEquals("limits", requests[0].getString("verb"))
        assertFalse(requests[0].has("force"))
        assertTrue(requests[1].getBoolean("force"))
    }

    private fun sessions(vararg replies: String) = AgtermSessions(
        connect = {
            BridgeConnection.ofStreams(ByteArrayInputStream(replies.joinToString("\n").toByteArray()), ByteArrayOutputStream())
        },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        io = Dispatchers.Unconfined,
        pollIntervalMs = 600_000,
    )

    @Test
    fun `limits arrive over the list`() = runBlocking {
        val s = sessions(listing, answer)
        withTimeout(5_000) { s.refresh().join(); s.loadLimits().join() }

        assertTrue(s.limits.value.known)
        assertEquals(2, s.limits.value.providers.size)
        assertTrue(limitsCardShown(s.limits.value))
    }

    @Test
    fun `an older bridge refuses the verb and the list stays`() = runBlocking {
        val s = sessions(listing, """{"ok":false,"error":"unknown verb"}""")
        withTimeout(5_000) { s.refresh().join(); s.loadLimits().join() }

        assertTrue(s.state.value is AgtermUiState.Sessions)
        assertTrue(s.limits.value.unsupported)
        assertFalse(limitsCardShown(s.limits.value))
    }

    @Test
    fun `the format reads the way the card needs`() {
        assertEquals("2d 4h", LimitsFormat.until(1000 + 2 * 86_400 + 4 * 3_600, 1000))
        assertEquals("3h 12m", LimitsFormat.until(1000 + 3 * 3_600 + 12 * 60, 1000))
        assertEquals("<1m", LimitsFormat.until(1030, 1000))
        assertNull(LimitsFormat.until(900, 1000))
        assertNull(LimitsFormat.until(0, 1000))
        assertEquals("12m", LimitsFormat.age(1000, 1000 + 12 * 60))
        assertEquals("42%", LimitsFormat.percent(41.5))
        assertEquals(LimitLevel.Calm, LimitLevel.of(59.9))
        assertEquals(LimitLevel.Warm, LimitLevel.of(60.0))
        assertEquals(LimitLevel.Hot, LimitLevel.of(85.0))
    }
}
