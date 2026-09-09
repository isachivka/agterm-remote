package dev.isachivka.bewareofsugar.agterm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Re-applying the fit when the geometry changes — REQ-0041.
 *
 * His case: he moves from a session with a split to one without. The window was sized for a pane and
 * the pane is now the whole terminal, so the fit no longer describes what he is looking at.
 *
 * **The intent is "keep this terminal readable on this screen"**, not a one-shot action attached to a
 * press — so a session switch and a pane switch both count.
 *
 * ### The rule this file exists to hold
 *
 * **ONLY "needs fit" PUTS ANYTHING ON SCREEN.** Every other outcome on this path is silent: he did not
 * press anything, so there is nothing to report to him. The tempting bug is the sympathetic one — a
 * link dropping mid-switch feels worth mentioning, and mentioning it puts a note in front of him for an
 * action he never took, which is the fit refusal taking his screen one layer up.
 */
class RefitTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val sent = ByteArrayOutputStream()

    private fun holder(vararg replies: String) = AgtermSessions(
        connect = {
            BridgeConnection.ofStreams(
                ByteArrayInputStream(replies.joinToString("\n").toByteArray()), sent,
            )
        },
        scope = scope,
        io = Dispatchers.Unconfined,
        pollIntervalMs = 600_000,
    )

    private val listing =
        """{"ok":true,"sessions":[{"id":"A","workspace":"main","name":"agterm","active":true}],""" +
            """"fit_enabled":true,"columns":45}"""
    private val screen = """{"ok":true,"text":"output","digest":"d1"}"""

    private suspend fun AgtermSessions.opened(): AgtermSessions {
        refresh()
        watch((withTimeout(5_000) { state.first { it !is AgtermUiState.Loading } }
            as AgtermUiState.Sessions).sessions.first())
        return this
    }

    private val requests: List<String> get() = sent.toString(Charsets.UTF_8.name()).trim().lines()

    /** A cached entry applies silently: he answered that question already and asking twice is asking twice. */
    @Test
    fun `a cached fit applies with nothing said`() = runBlocking {
        val sessions = holder(listing, screen, """{"ok":true,"fit_enabled":true,"columns":39}""").opened()

        sessions.refit(boxWidthDp = 440, characterWidthMilliDp = 9800)

        assertEquals(39, sessions.fit.value.columns)
        assertEquals("a silent apply must say nothing", MutationNote.None, sessions.mutation.value)
    }

    /** **The one thing that speaks.** */
    @Test
    fun `a geometry the laptop has never measured asks him to press`() = runBlocking {
        val sessions = holder(listing, screen, """{"ok":true,"needs_fit":true,"fit_enabled":true}""").opened()

        sessions.refit(boxWidthDp = 440, characterWidthMilliDp = 9800)

        assertEquals(MutationNote.NeedsFit, sessions.mutation.value)
    }

    /**
     * **THE RULE, over every way this can go wrong.** A refusal, a laptop that will not answer, a
     * bridge too old to understand `cached_only` and rejecting the request outright: all silent.
     */
    @Test
    fun `nothing else on this path ever reaches the screen`() = runBlocking {
        for (reply in listOf(
            """{"ok":false,"error":"agterm is not answering"}""",
            """{"ok":false,"error":"json: unknown field \"cached_only\"","refusal":"content"}""",
            """{"ok":false,"error":"no such session"}""",
        )) {
            val sessions = holder(listing, screen, reply).opened()

            sessions.refit(boxWidthDp = 440, characterWidthMilliDp = 9800)

            assertEquals(
                "a failure he did not cause put a note on his screen: $reply",
                MutationNote.None,
                sessions.mutation.value,
            )
            // **And the screen itself is untouched, which is the half that nearly shipped broken.**
            //
            // The first version of this test asserted only on the note. A plain refusal drops the
            // connection and replaces the terminal with an error page - so an older bridge rejecting
            // `cached_only` as an unknown field would have taken his session away because he tapped a
            // row in a list, and this test would have passed.
            val state = sessions.state.value
            assertEquals(
                "his terminal was replaced by a failure he did not cause: $reply",
                true,
                state is AgtermUiState.Sessions && state.watching != null,
            )
        }
    }

    /**
     * **Off means nothing happens, and off has three states.**
     *
     * `enabled` is true, false, or null for NOT ANSWERED. Only true acts — treating null as on would
     * resize his window off the back of a reply that never came, which is [adoptFit]'s lesson inverted.
     */
    @Test
    fun `a fit that is off or unanswered crosses no wire at all`() = runBlocking {
        for (flag in listOf(""""fit_enabled":false""", """"fit_enabled":null""")) {
            sent.reset()
            val sessions = holder(
                """{"ok":true,"sessions":[{"id":"A","name":"a","active":true}],$flag}""", screen,
            ).opened()
            val before = requests.size

            sessions.refit(boxWidthDp = 440, characterWidthMilliDp = 9800)

            assertEquals("a fit that is not ON sent a request: $flag", before, requests.size)
        }
    }

    /** The request says which pane it is for, because the pane is half of what the geometry IS. */
    @Test
    fun `the re-apply names the pane and asks the laptop not to measure`() = runBlocking {
        val sessions = holder(listing, screen, """{"ok":true,"fit_enabled":true,"columns":39}""").opened()

        sessions.refit(boxWidthDp = 440, characterWidthMilliDp = 9800)

        val last = requests.last()
        assertEquals("the re-apply did not ask for a cached fit: $last", true, last.contains(""""cached_only":true"""))
        assertEquals("the re-apply named no pane: $last", true, last.contains(""""pane""""))
        assertEquals("an automatic call must never force a measurement: $last", false, last.contains("recalibrate"))
    }
}
