package dev.isachivka.agtermremote.agterm

import dev.isachivka.agtermremote.pairing.SigningState
import dev.isachivka.agtermremote.wire.WireException
import dev.isachivka.agtermremote.wire.WireFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The holder: what the owner ends up looking at when each thing goes wrong.
 *
 * Deterministic by construction rather than by waiting — the dispatcher and the poll interval are
 * both injected, because a test that races a thread hop or sleeps two seconds a poll is one that
 * passes on a fast machine and gets re-run on a slow one. That is how a real failure becomes a flake.
 */
class AgtermSessionsTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    /**
     * A holder whose canned stream cannot run dry.
     *
     * **The trailing `unchanged` replies are what make this deterministic**, and they were added after
     * a real intermittent failure: `watch` polls every millisecond here, so a test that supplied
     * exactly one screen reply raced its own `stopWatching`. Lose the race and the second poll read
     * end-of-stream, which is a genuine `CannotReach`, and the assertion fell over with a
     * `ClassCastException` on a `Failed` state it never expected. About one run in six.
     *
     * The product was right both times: an exhausted socket IS unreachable. The fixture was modelling
     * a laptop that answers once and then vanishes, which is not the thing under test - so it now
     * answers "nothing changed" for as long as it is asked, which is what an idle terminal does.
     */
    private fun holder(vararg replies: String, connect: (() -> BridgeConnection?)? = null) =
        AgtermSessions(
            connect = connect ?: {
                val idle = List(500) { """{"ok":true,"unchanged":true,"digest":"d1"}""" }
                BridgeConnection.ofStreams(
                    ByteArrayInputStream((replies.toList() + idle).joinToString("\n").toByteArray()),
                    ByteArrayOutputStream(),
                )
            },
            scope = scope,
            io = Dispatchers.Unconfined,
            pollIntervalMs = 1,
        )

    /**
     * A holder whose POLLER will not eat the replies a test is aiming at another call.
     *
     * **CI caught this and a local run did not, which is the whole reason it exists.** [holder] polls
     * every millisecond, and a watched session's poll takes the next canned reply off the same
     * stream — so a test that watches, then types, is racing its own fixture for which call gets
     * which line. It passed here and failed twice on a slower machine, which is the shape of a flake
     * we would have spent an afternoon on later.
     *
     * The poll still runs once when the session is watched, and then not again for the length of the
     * test. Nothing about the code under test changes; the fixture stops competing with it.
     */
    private fun unpolledHolder(vararg replies: String) =
        AgtermSessions(
            connect = {
                BridgeConnection.ofStreams(
                    ByteArrayInputStream(replies.joinToString("\n").toByteArray()),
                    ByteArrayOutputStream(),
                )
            },
            scope = scope,
            io = Dispatchers.Unconfined,
            pollIntervalMs = 600_000,
        )

    private suspend fun AgtermSessions.settled(): AgtermUiState =
        withTimeout(5_000) { state.first { it !is AgtermUiState.Loading } }

    /**
     * **The barrier for anything written AFTER the screen changes — the recovery note, above all.**
     *
     * [settled] waits for the state to leave `Loading`, and that happens *inside* the fetch: the
     * listing replaces the screen, and only then does the successful attempt write
     * `LinkNote.Reconnected`. A test that asserted the note after `settled()` was reading a value
     * the fetch had not reached yet, and it failed roughly one run in three, over three sightings.
     *
     * The fix is the barrier, not the assertion. `refresh()` hands back its `Job`; joining it means
     * the round is over and every write it makes has happened. The poller and the healer are
     * launched into the scope rather than into this job, so this cannot wait on a loop that never
     * ends.
     */
    private suspend fun AgtermSessions.refreshed(): AgtermUiState {
        withTimeout(5_000) { refresh().join() }
        return state.value
    }

    /**
     * A key that can NEVER sign must not raise a prompt, because no prompt can rescue it.
     *
     * The state exists because of a real crash path: every key minted before `DIGEST_NONE` was added
     * to the spec throws on `initSign` for an incompatible digest. It used to escape a pre-flight that
     * runs before any socket opens, so the first launch after that fix crashed on opening Terminal for
     * precisely the owners who already had a key.
     *
     * **And it must not reach the network**, which is the assertion that carries the owner's complaint
     * 5: a phone that cannot use its own key and opens a socket anyway gets a closed stream back and
     * reports that the laptop is not answering.
     */
    @Test
    fun `an unusable key is reported without opening a socket to blame`() = runBlocking {
        val sessions = AgtermSessions(
            connect = { throw AssertionError("must not reach the network with a key that cannot sign") },
            keyState = { SigningState.Unusable },
            scope = scope,
            io = Dispatchers.Unconfined,
            pollIntervalMs = 1,
        )

        sessions.refresh()

        assertEquals(AgtermUiState.Failed(WireFailure.IdentityUnusable), sessions.settled())
    }

    /**
     * A phone with NO key and no laptop is told there is nothing paired - it is not asked to
     * authenticate first.
     *
     * Asking someone to prove who they are before telling them there is nothing to connect to reads
     * as correct in a bug tracker, because the final screen is right, and is absurd in the hand.
     */
    @Test
    fun `a brand-new phone is told nothing is paired`() = runBlocking {
        val sessions = AgtermSessions(
            connect = { null },
            keyState = { SigningState.Absent },
            scope = scope,
            io = Dispatchers.Unconfined,
            pollIntervalMs = 1,
        )

        sessions.refresh()

        assertEquals(AgtermUiState.NotPaired, sessions.settled())
    }

    @Test
    fun `no paired laptop is its own state, not a failure`() = runBlocking {
        val sessions = holder(connect = { null })

        sessions.refresh()

        assertEquals(AgtermUiState.NotPaired, sessions.settled())
    }

    @Test
    fun `a transport failure arrives as the failure the copy branches on`() = runBlocking {
        val sessions = holder(connect = { throw WireException(WireFailure.NotPinned) })

        sessions.refresh()

        assertEquals(AgtermUiState.Failed(WireFailure.NotPinned), sessions.settled())
    }

    /** A laptop that answered and refused is never described as one that did not answer. */
    @Test
    fun `a refusal from the bridge is its own state`() = runBlocking {
        val sessions = holder("""{"ok":false,"error":"agterm is not running"}""")

        sessions.refresh()

        // **The bridge's own words reach the state**, so the screen can show them instead of a
        // sentence about listing sessions that describes nothing that happened.
        assertEquals(AgtermUiState.Refused("agterm is not running"), sessions.settled())
    }

    /**
     * **Both halves of a refusal reach the state, and they are different halves.**
     *
     * `reason` is what the owner reads; `detail` is what agterm said, kept for the person debugging
     * and shown small, under the button. A state that dropped `detail` would leave the screen unable
     * to offer the evidence at all, and a state that put it in `reason` would put it back on the
     * headline — which is the bug of 2026-08-12.
     */
    @Test
    fun `a refusal keeps our words and the far end's apart`() = runBlocking {
        val sessions = holder(
            """{"ok":false,"error":"Your laptop has not opened this session yet.",""" +
                """"detail":"failed to read surface buffer"}""",
        )

        sessions.refresh()

        assertEquals(
            AgtermUiState.Refused(
                reason = "Your laptop has not opened this session yet.",
                detail = "failed to read surface buffer",
            ),
            sessions.settled(),
        )
    }

    @Test
    fun `the session list reaches the screen`() = runBlocking {
        val sessions = holder(
            """{"ok":true,"sessions":[{"id":"A","workspace":"main","name":"agterm","active":true}]}""",
        )

        sessions.refresh()

        val state = sessions.settled() as AgtermUiState.Sessions
        assertEquals(listOf("agterm"), state.sessions.map { it.name })
        assertEquals(null, state.watching)
    }

    /**
     * **An unchanged screen must not blank the box.**
     *
     * 95% of idle polls carry no text, measured. If `Unchanged` were treated as empty output, a quiet
     * session would flash blank every poll — the digest would have turned a bandwidth saving into the
     * most visible defect in the feature. The second reply here is `unchanged`, and the text from the
     * first must survive it.
     */
    @Test
    fun `an unchanged poll leaves the text on screen`() = runBlocking {
        val sessions = holder(
            """{"ok":true,"sessions":[{"id":"A","workspace":"main","name":"agterm","active":true}]}""",
            """{"ok":true,"text":"first output","digest":"d1"}""",
            """{"ok":true,"unchanged":true}""",
            """{"ok":true,"unchanged":true}""",
        )
        sessions.refresh()
        val listed = sessions.settled() as AgtermUiState.Sessions

        sessions.watch(listed.sessions.first())
        val shown = withTimeout(5_000) {
            sessions.state.first { (it as? AgtermUiState.Sessions)?.screen?.isNotEmpty() == true }
        }

        assertEquals("first output", (shown as AgtermUiState.Sessions).screen)
        // And it is still there after the unchanged replies have been consumed.
        assertEquals("first output", (sessions.state.value as AgtermUiState.Sessions).screen)
        sessions.release()
    }

    /** Leaving a session stops the polling rather than letting it run against a screen nobody sees. */
    @Test
    fun `closing a session clears what was on screen`() = runBlocking {
        val sessions = holder(
            """{"ok":true,"sessions":[{"id":"A","workspace":"main","name":"agterm","active":true}]}""",
            """{"ok":true,"text":"output","digest":"d1"}""",
        )
        sessions.refresh()
        sessions.watch((sessions.settled() as AgtermUiState.Sessions).sessions.first())

        sessions.stopWatching()

        val state = sessions.state.value as AgtermUiState.Sessions
        assertEquals(null, state.watching)
        assertTrue("nothing of the session's content is left behind", state.screen.isEmpty())
    }

    /**
     * **The defect this diff exists for.** The setting used to be written only by the action that
     * changed it, so a `resize` reply that never arrived left the toggle unpressed for as long as the
     * owner stayed on the screen - looking at a window that had already been resized, holding a button
     * that said it had not been. Their words: it changes size but the button is not pressed and I
     * cannot unpress it.
     *
     * The poll already crosses the wire every two seconds and the bridge already puts the flag on every
     * reply. Here the laptop reports the fit as ON while the phone still believes it is off, and the
     * next poll must correct it without anyone pressing anything.
     */
    @Test
    fun `a poll corrects a toggle the resize reply never reached`() = runBlocking {
        val sessions = holder(
            """{"ok":true,"sessions":[{"id":"A","workspace":"main","name":"agterm","active":true}],"fit_enabled":false,"columns":0}""",
            """{"ok":true,"text":"output","digest":"d1","fit_enabled":true,"columns":41}""",
        )
        sessions.refresh()
        assertEquals(false, sessions.fit.value.enabled)

        sessions.watch((sessions.settled() as AgtermUiState.Sessions).sessions.first())

        val corrected = withTimeout(5_000) { sessions.fit.first { it.enabled == true } }
        assertEquals(41, corrected.columns)
        sessions.release()
    }

    /**
     * **Null is NOT ANSWERED and must never be rendered as off.**
     *
     * A reply that does not carry the flag - an older bridge, a shape we have not met - would, if
     * written through as false, unpress a button whose fit is still applied and invite the owner to
     * press it again, re-applying something already in force. That is the inverse of the bug above and
     * worse than it: same shape as the `omitempty` bool that could not say "off" and left the control
     * dead all morning.
     *
     * **What this fixture models, said plainly.** Today's bridge puts `fit_enabled` on every reply,
     * including the unchanged ones - `every_reply_test.go` fails if it does not - so the flagless reply
     * below is NOT a message our bridge sends. It stands for a bridge that omits it: an older build, a
     * partial reply, a shape we have not met. That is the case the rule exists for, and saying so keeps
     * this from being read as a description of the current wire.
     *
     * The sibling test above uses `columns` alongside `fit_enabled`, which our bridge does send on
     * every reply - held on the Go side by `TestAPollReplyCarriesTheCountAsWellAsTheFlag`, added after
     * the bridge briefly published the flag without the count.
     */
    @Test
    fun `the poll never unpresses a button it was not told about`() = runBlocking {
        val sessions = holder(
            """{"ok":true,"sessions":[{"id":"A","workspace":"main","name":"agterm","active":true}],"fit_enabled":true,"columns":41}""",
            """{"ok":true,"text":"output","digest":"d1"}""",
        )
        sessions.refresh()
        sessions.watch((sessions.settled() as AgtermUiState.Sessions).sessions.first())

        // Let the flagless replies run: the text arrives, so the poll demonstrably ran.
        withTimeout(5_000) {
            sessions.state.first { (it as? AgtermUiState.Sessions)?.screen?.isNotEmpty() == true }
        }

        assertEquals(true, sessions.fit.value.enabled)
        assertEquals(41, sessions.fit.value.columns)
        sessions.release()
    }

    /** A poll that FAILS leaves the pressed state exactly as it was - it is not evidence about the fit. */
    @Test
    fun `a failed poll leaves the pressed state exactly as it was`() = runBlocking {
        val sessions = holder(
            connect = {
                // One reply, then end of stream: the poll after it is a genuine CannotReach.
                BridgeConnection.ofStreams(
                    ByteArrayInputStream(
                        """{"ok":true,"sessions":[{"id":"A","workspace":"main","name":"agterm","active":true}],"fit_enabled":true,"columns":41}""".toByteArray(),
                    ),
                    ByteArrayOutputStream(),
                )
            },
        )
        sessions.refresh()
        sessions.watch((sessions.settled() as AgtermUiState.Sessions).sessions.first())

        withTimeout(5_000) { sessions.state.first { it is AgtermUiState.Failed } }

        assertEquals(true, sessions.fit.value.enabled)
        assertEquals(41, sessions.fit.value.columns)
        sessions.release()
    }

    /**
     * **A bridge that restarts must not become an error screen.**
     *
     * The owner was shown "your router answered, but nothing was listening behind it" at 19:05:44,
     * two seconds before the second start of one of our own deploys. The message was right; the dead
     * end was not. The first attempt here fails the way a restarting bridge fails - the stream is
     * closed, which is a genuine CannotReach - and the second succeeds. Nobody taps anything.
     */
    @Test
    fun `a bridge that is restarting is waited out rather than shown as a failure`() = runBlocking {
        var attempts = 0
        val sessions = holder(
            connect = {
                attempts++
                val body = if (attempts == 1) {
                    // A closed stream: refused, reset, restarting. Indistinguishable from here.
                    ""
                } else {
                    """{"ok":true,"sessions":[{"id":"A","workspace":"main","name":"agterm","active":true}],"fit_enabled":false}"""
                }
                BridgeConnection.ofStreams(
                    ByteArrayInputStream(body.toByteArray()), ByteArrayOutputStream(),
                )
            },
        )

        sessions.refresh()

        val settled = withTimeout(10_000) { sessions.state.first { it is AgtermUiState.Sessions } }
        assertEquals(1, (settled as AgtermUiState.Sessions).sessions.size)
        assertTrue("the retry never happened", attempts >= 2)
        sessions.release()
    }

    /**
     * **And an identity refusal is NOT waited out.** Retrying the pinned door would turn a security
     * boundary into a spinner: the owner would see a delay where they should see a refusal, and the
     * one screen that tells them to act would arrive late.
     *
     * A phone whose key can never sign is the identity failure reachable without a socket, so it is
     * the one this can assert on: it must reach the screen without spending the quiet phase at all.
     */
    @Test
    fun `an identity failure goes straight to the screen`() = runBlocking {
        val sessions = AgtermSessions(
            connect = { throw AssertionError("a socket was opened for a key that cannot sign") },
            keyState = { SigningState.Unusable },
            scope = scope,
            io = Dispatchers.Unconfined,
            pollIntervalMs = 1,
        )

        sessions.refresh()

        // Immediately: no retry budget spent. The window is far shorter than the quiet phase.
        val state = withTimeout(300) { sessions.state.first { it is AgtermUiState.Failed } }
        assertEquals(WireFailure.IdentityUnusable, (state as AgtermUiState.Failed).failure)
        sessions.release()
    }

    /**
     * A refusal with no text still has somewhere to land, and the screen keeps its generic line for
     * exactly that case - the app must not render an empty failure.
     */
    @Test
    fun `a refusal with no reason is still a refusal`() = runBlocking {
        val sessions = holder("""{"ok":false}""")

        sessions.refresh()

        assertEquals(AgtermUiState.Refused(""), sessions.settled())
    }

    // --- the connection note -----------------------------------------------------------------------

    /** A holder whose first N connections fail transiently and whose next one works. */
    private fun flaky(failures: Int): AgtermSessions {
        var attempts = 0
        return AgtermSessions(
            connect = {
                if (attempts++ < failures) throw WireException(WireFailure.BridgeNotListening)
                val idle = List(500) { """{"ok":true,"unchanged":true,"digest":"d1"}""" }
                BridgeConnection.ofStreams(
                    ByteArrayInputStream(
                        (listOf("""{"ok":true,"sessions":[{"id":"A","workspace":"w","name":"n","active":true}]}""") + idle)
                            .joinToString("\n").toByteArray(),
                    ),
                    ByteArrayOutputStream(),
                )
            },
            scope = scope,
            io = Dispatchers.Unconfined,
            pollIntervalMs = 1,
        )
    }

    /**
     * **Nothing is said when nothing happened.**
     *
     * A first connection that simply works is not a recovery, and greeting the owner with one would be
     * the app announcing an event it did not observe — the failure mode the whole notes surface was
     * nearly built around.
     */
    @Test
    fun `a clean connection says nothing`() = runBlocking {
        val sessions = holder("""{"ok":true,"sessions":[]}""")

        // refreshed(), not settled(): asserting a note is ABSENT needs the round to be over, or the
        // assertion passes by reading before the write it exists to forbid.
        sessions.refreshed()

        assertEquals(LinkNote.None, sessions.link.value)
    }

    /**
     * A transient failure is waited out, and the owner is told that it is being waited out — which is
     * the thing they could not see. It says nothing about WHY: from here a restarting bridge, a
     * sleeping laptop and a lost network are the same silence.
     */
    @Test
    fun `waiting out a transient failure says so and then says it came back`() = runBlocking {
        val sessions = flaky(failures = 1)

        // The quiet retry runs inside refresh, so by the time the round is OVER both transitions have
        // happened. Recovery is the one that survives, and it is only reachable through the first.
        //
        // `refreshed()`, not `settled()`: the note is written after the listing replaces the screen,
        // so waiting for the screen to change is waiting for the wrong thing. This test and
        // `dismissing a note clears it` failed the same way for the same reason.
        sessions.refreshed()

        assertEquals(LinkNote.Reconnected, sessions.link.value)
    }

    /**
     * **An identity failure is not a connection to wait out**, so it produces no note at all. Retrying
     * a pinned door would turn a refusal into a spinner, and a card saying "reconnecting" over a
     * failure that will never heal is the same lie in a smaller box.
     */
    @Test
    fun `an identity failure produces no note`() = runBlocking {
        val sessions = AgtermSessions(
            connect = { throw WireException(WireFailure.NotPinned) },
            scope = scope,
            io = Dispatchers.Unconfined,
            pollIntervalMs = 1,
        )

        sessions.refreshed()

        assertEquals(LinkNote.None, sessions.link.value)
    }

    /** Dismissing puts the card away. The connection is not consulted and nothing about it changes. */
    @Test
    fun `dismissing a note clears it`() = runBlocking {
        val sessions = flaky(failures = 1)
        sessions.refreshed()
        assertEquals(LinkNote.Reconnected, sessions.link.value)

        sessions.dismissNotice()

        assertEquals(LinkNote.None, sessions.link.value)
    }

    /**
     * **A refusal about the TEXT does not take the screen away.**
     *
     * The owner pasted a message from a chat app, pressed send, and the terminal, the session list
     * and the input bar were replaced by a full-page error listing our internal key names. Nothing
     * about the laptop was wrong: one request was, and it would have been wrong against a perfectly
     * healthy one.
     *
     * Three things are asserted together because the bug was all three at once: the screen stays, the
     * connection is not dropped, and their text comes back to the box.
     */
    @Test
    fun `a refusal about the text keeps the screen, the connection and the draft`() = runBlocking {
        val sessions = unpolledHolder(
            """{"ok":true,"sessions":[{"id":"A","workspace":"main","name":"agterm","active":true}]}""",
            """{"ok":true,"text":"output","digest":"d1"}""",
            """{"ok":false,"error":"text contains a control character (U+000A at byte 5)","refusal":"content"}""",
        )
        sessions.refresh()
        sessions.watch((sessions.settled() as AgtermUiState.Sessions).sessions.first())
        sessions.settled()

        sessions.type(text = "one\ntwo")
        withTimeout(5_000) { sessions.typing.first { it is TypingState.Composing } }

        assertTrue(
            "the screen was replaced; a no about the text is not the connection breaking",
            sessions.state.value is AgtermUiState.Sessions,
        )
        assertEquals(TypingState.Composing(TypingState.Notice.LineBreaks), sessions.typing.value)
        assertEquals(
            "their text did not come back to the box, so it is gone and they must copy it again",
            "one\ntwo",
            sessions.draft.value,
        )
    }

    // --- The draft has a life of its own ------------------------------------------------------------

    private val sessionA = "11111111-1111-4111-8111-111111111111"
    private val sessionB = "22222222-2222-4222-8222-222222222222"
    private val twoSessions =
        """{"ok":true,"sessions":[{"id":"$sessionA","workspace":"main","name":"a","active":true},""" +
            """{"id":"$sessionB","workspace":"main","name":"b","active":false}]}"""

    /** Like [unpolledHolder], and it hands back what the phone WROTE, so the order of sends can be read. */
    private fun recordingHolder(vararg replies: String, drafts: DraftStore? = null): Pair<AgtermSessions, ByteArrayOutputStream> {
        val written = ByteArrayOutputStream()
        val sessions = AgtermSessions(
            connect = {
                BridgeConnection.ofStreams(ByteArrayInputStream(replies.joinToString("\n").toByteArray()), written)
            },
            scope = scope,
            io = Dispatchers.Unconfined,
            pollIntervalMs = 600_000,
            drafts = drafts,
            draftWriteDelayMs = 0,
        )
        return sessions to written
    }

    private fun ByteArrayOutputStream.requests(): List<String> =
        toString(Charsets.UTF_8.name()).lines().filter { it.isNotBlank() }

    private suspend fun AgtermSessions.openFirst() {
        refresh()
        watch((settled() as AgtermUiState.Sessions).sessions.first())
        settled()
    }

    /**
     * **The bug the owner reported:** text in the input field, an overpull to scroll the terminal,
     * and the field came back empty. The overpull sends PgUp as a key, and a key used to go through
     * the state that carried no draft.
     */
    @Test
    fun `a key press leaves the draft where it was`() = runBlocking {
        val (sessions, written) = recordingHolder(
            twoSessions,
            """{"ok":true,"text":"output","digest":"d1"}""",
            """{"ok":true}""",
        )
        sessions.openFirst()
        sessions.openTyping()
        sessions.editDraft("git commit -m 'half")

        sessions.type(key = TerminalPull.PAGE_UP)
        withTimeout(5_000) { sessions.typing.first { it is TypingState.Sent } }

        assertEquals("git commit -m 'half", sessions.draft.value)
        assertTrue(written.requests().last().contains(""""key":"pageup""""))
        assertFalse("a key press must not send the draft", written.requests().last().contains("half"))
    }

    /** Each session has its own draft, and switching brings the other session's back. */
    @Test
    fun `the draft belongs to the session`() = runBlocking {
        val (sessions, _) = recordingHolder(
            twoSessions,
            """{"ok":true,"text":"a's screen","digest":"d1"}""",
            """{"ok":true,"text":"b's screen","digest":"d2"}""",
            """{"ok":true,"text":"a's screen","digest":"d1"}""",
        )
        sessions.openFirst()
        sessions.openTyping()
        sessions.editDraft("for a")
        val listed = (sessions.state.value as AgtermUiState.Sessions).sessions

        sessions.watch(listed[1])
        assertEquals("", sessions.draft.value)
        sessions.editDraft("for b")
        sessions.watch(listed[0])

        assertEquals("for a", sessions.draft.value)
    }

    /** And it comes back from disk when the process did not survive. */
    @Test
    fun `a draft survives a new holder through the store`() = runBlocking {
        val store = DraftStore(java.nio.file.Files.createTempDirectory("drafts").toFile())
        val (first, _) = recordingHolder(twoSessions, """{"ok":true,"text":"s","digest":"d1"}""", drafts = store)
        first.openFirst()
        first.openTyping()
        first.editDraft("do not lose me")
        withTimeout(5_000) { while (store.read(sessionA) != "do not lose me") delay(5) }

        val (second, _) = recordingHolder(twoSessions, """{"ok":true,"text":"s","digest":"d1"}""", drafts = store)
        second.openFirst()

        assertEquals("do not lose me", second.draft.value)
    }

    /**
     * **Return with something in the field types it and then presses Return**, with a pause between,
     * and the draft is gone from the field the moment it is sent.
     */
    @Test
    fun `return with a draft sends the text and then the key`() = runBlocking {
        val (sessions, written) = recordingHolder(
            twoSessions,
            """{"ok":true,"text":"output","digest":"d1"}""",
            """{"ok":true}""",
            """{"ok":true}""",
        )
        sessions.openFirst()
        sessions.openTyping()
        sessions.editDraft("ls -la")

        sessions.type(key = ENTER_KEY)
        assertEquals("cleared at the moment of sending", "", sessions.draft.value)
        withTimeout(5_000) { while (written.requests().size < 4) delay(5) }

        val sends = written.requests().takeLast(2)
        assertTrue(sends[0], sends[0].contains(""""text":"ls -la"""") && !sends[0].contains(""""key""""))
        assertTrue(sends[1], sends[1].contains(""""key":"enter"""") && !sends[1].contains(""""text""""))
    }

    /** Return with nothing in the field is the bare key it always was. */
    @Test
    fun `return with an empty field is just return`() = runBlocking {
        val (sessions, written) = recordingHolder(
            twoSessions,
            """{"ok":true,"text":"output","digest":"d1"}""",
            """{"ok":true}""",
        )
        sessions.openFirst()
        sessions.openTyping()

        sessions.type(key = ENTER_KEY)
        withTimeout(5_000) { while (written.requests().size < 3) delay(5) }

        assertEquals(3, written.requests().size)
        assertTrue(written.requests().last().contains(""""key":"enter""""))
    }

    /** A send the connection dropped puts the words back, not only a refusal. */
    @Test
    fun `a send that did not land returns the draft`() = runBlocking {
        val (sessions, _) = recordingHolder(
            twoSessions,
            """{"ok":true,"text":"output","digest":"d1"}""",
            // A refusal that is not about the content: the laptop answered, and the text went nowhere.
            """{"ok":false,"error":"agterm is not answering"}""",
        )
        sessions.openFirst()
        sessions.openTyping()
        sessions.editDraft("precious")

        sessions.type(text = "precious")
        withTimeout(5_000) { sessions.typing.first { it is TypingState.Failed } }

        assertEquals("precious", sessions.draft.value)
    }

    /** A successful send clears the draft everywhere, including the store. */
    @Test
    fun `a landed send clears the draft on disk too`() = runBlocking {
        val store = DraftStore(java.nio.file.Files.createTempDirectory("drafts").toFile())
        val (sessions, written) = recordingHolder(
            twoSessions,
            """{"ok":true,"text":"output","digest":"d1"}""",
            """{"ok":true}""",
            drafts = store,
        )
        sessions.openFirst()
        sessions.openTyping()
        sessions.editDraft("sent soon")
        withTimeout(5_000) { while (store.read(sessionA) != "sent soon") delay(5) }

        sessions.type(text = "sent soon")
        withTimeout(5_000) { while (written.requests().size < 3) delay(5) }
        withTimeout(5_000) { while (store.read(sessionA).isNotEmpty()) delay(5) }

        assertEquals("", sessions.draft.value)
    }

    /**
     * **The pasted message now goes through, and it goes through as a paste.**
     *
     * The bug the owner reported, as the test that would have caught it: a multi-line draft used to
     * be refused at the boundary and cost them their text. It is now sent in the field whose meaning
     * is "this was pasted", which is what stops the line breaks becoming Returns at the far end.
     */
    @Test
    fun `a multi-line draft is sent as a paste and lands`() = runBlocking {
        val sessions = unpolledHolder(
            """{"ok":true,"sessions":[{"id":"A","workspace":"main","name":"agterm","active":true}]}""",
            """{"ok":true,"text":"output","digest":"d1"}""",
            """{"ok":true}""",
        )
        sessions.refresh()
        sessions.watch((sessions.settled() as AgtermUiState.Sessions).sessions.first())
        sessions.settled()

        sessions.type(text = "alpha\nbeta")
        withTimeout(5_000) { sessions.typing.first { it is TypingState.Sent } }

        assertTrue(
            "the screen was taken away by a send that succeeded",
            sessions.state.value is AgtermUiState.Sessions,
        )
    }

    /**
     * And the OTHER direction, because the dangerous mistake is the generous one: a refusal with no
     * marker is a bridge saying the laptop could not serve this, and that still takes the screen.
     */
    @Test
    fun `a refusal without the marker still reports the connection`() = runBlocking {
        val sessions = unpolledHolder(
            """{"ok":true,"sessions":[{"id":"A","workspace":"main","name":"agterm","active":true}]}""",
            """{"ok":true,"text":"output","digest":"d1"}""",
            """{"ok":false,"error":"agterm is not running"}""",
        )
        sessions.refresh()
        sessions.watch((sessions.settled() as AgtermUiState.Sessions).sessions.first())
        sessions.settled()

        sessions.type(text = "ls")
        withTimeout(5_000) { sessions.state.first { it is AgtermUiState.Refused } }

        assertEquals(
            AgtermUiState.Refused("agterm is not running"),
            sessions.state.value,
        )
    }

    /**
     * A note is about the connection this screen was holding. Keeping it across a release would greet
     * the owner on their next visit with news about a socket that no longer exists.
     */
    @Test
    fun `releasing the screen clears the note`() = runBlocking {
        val sessions = flaky(failures = 1)
        sessions.refreshed()

        sessions.release()

        assertEquals(LinkNote.None, sessions.link.value)
    }

    /**
     * The toggle is read on EVERY poll rather than at watch time, so flipping it in Settings takes
     * effect on the next reply without leaving and re-entering the session.
     */
    @Test
    fun `the poll asks for a styled screen when the setting says so and the reply's flag reaches the state`() = runBlocking {
        val sent = ByteArrayOutputStream()
        var wantStyled = false
        val sessions = AgtermSessions(
            connect = {
                val replies = listOf(
                    """{"ok":true,"sessions":[{"id":"A","workspace":"main","name":"agterm","active":true}]}""",
                    """{"ok":true,"text":"[1mfirst[0m","digest":"d1","styled":true}""",
                ) + List(500) { """{"ok":true,"unchanged":true,"digest":"d1","styled":true}""" }
                BridgeConnection.ofStreams(
                    ByteArrayInputStream(replies.joinToString("\n").toByteArray()),
                    sent,
                )
            },
            scope = scope,
            io = Dispatchers.Unconfined,
            pollIntervalMs = 1,
            styled = { wantStyled },
        )
        sessions.refresh()
        val listed = sessions.settled() as AgtermUiState.Sessions
        wantStyled = true

        sessions.watch(listed.sessions.first())
        val shown = withTimeout(5_000) {
            sessions.state.first { (it as? AgtermUiState.Sessions)?.screen?.isNotEmpty() == true }
        } as AgtermUiState.Sessions

        val screenRequests = sent.toString(Charsets.UTF_8).trim().lines()
            .map { org.json.JSONObject(it) }.filter { it.getString("verb") == "screen" }
        assertTrue("the poll must ask for a styled screen", screenRequests.first().getBoolean("styled"))
        assertEquals("[1mfirst[0m", shown.screen)
        assertTrue("the state must know the text carries SGR", shown.screenStyled)
        sessions.release()
    }
}
