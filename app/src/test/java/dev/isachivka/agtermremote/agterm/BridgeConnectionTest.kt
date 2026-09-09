package dev.isachivka.agtermremote.agterm

import dev.isachivka.agtermremote.wire.WireException
import dev.isachivka.agtermremote.wire.WireFailure
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The two verbs, over a pair of streams rather than over TLS.
 *
 * The stack below this — WebSocket, pinned mTLS — has its own tests and its own review. What these
 * cover is the protocol: that a reply is read as one line of JSON, that `unchanged` is not an empty
 * screen, and that a laptop which answers and refuses is never described as a laptop that did not
 * answer.
 */
class BridgeConnectionTest {

    private val sent = ByteArrayOutputStream()

    private fun request(): JSONObject = JSONObject(sent.toString(Charsets.UTF_8).trim().lines().first())

    @Test
    fun `the session list is parsed into rows`() {
        val bridge = open(
            """{"ok":true,"sessions":[
               {"id":"A","workspace_id":"W-main","workspace":"main","name":"agterm","title":"Create backup script","active":true},
               {"id":"B","workspace_id":"W-side","workspace":"side","name":"build","active":false}]}""".trimIndent()
                .replace("\n", ""),
        )

        val listing = bridge.sessions()

        assertEquals(2, listing.sessions.size)
        assertEquals(BridgeSession("A", "W-main", "main", "agterm", "Create backup script", true), listing.sessions[0])
        assertEquals(BridgeSession("B", "W-side", "side", "build", "", false), listing.sessions[1])
        // A reply that says nothing about the setting is NOT-ANSWERED, never off.
        assertEquals(null, listing.fitEnabled)
    }

    @Test
    fun `a screen request carries the session, the line bound and the digest`() {
        val bridge = open("""{"ok":true,"text":"hello","digest":"d1"}""")

        bridge.screen("SESSION-1", Pane.Left, lines = 40, digest = "previous")

        val sent = request()
        assertEquals("screen", sent.getString("verb"))
        assertEquals("SESSION-1", sent.getString("session"))
        assertEquals(40, sent.getInt("lines"))
        assertEquals("previous", sent.getString("digest"))
    }

    /**
     * **THE APP-SIDE VERSION OF THE DEFECT'S TEST.** One pane goes out on the screen request and the
     * same one on the keystroke.
     *
     * agterm resolves an absent pane differently per command: a read gets the on-screen half, a
     * keystroke gets primary. So while the phone sent none, a split session showed one and typed into
     * the other, silently, into the terminal the owner was not looking at. Both halves of this are
     * asserted here because a request that carries the pane on only one of the two is the whole bug.
     */
    @Test
    fun `the screen and the keystroke both carry the same pane`() {
        for (pane in Pane.entries) {
            // **`sent` is one buffer for the whole test and `request()` reads its FIRST line**, so
            // without these resets the second assertion would re-read the first request and pass
            // while proving nothing. Found by reading the harness rather than by watching it go green.
            sent.reset()
            open("""{"ok":true,"text":"hello","digest":"d1"}""").screen("SESSION-1", pane)
            assertEquals(
                "the screen request named the wrong pane",
                pane.wire,
                request().getString("pane"),
            )

            sent.reset()
            open("""{"ok":true}""").type("SESSION-1", pane, text = "ls")
            assertEquals(
                "the keystroke went to a different pane than the screen was read from",
                pane.wire,
                request().getString("pane"),
            )
        }
    }

    /**
     * **A SECOND PANE EXISTS is not the same question as A SPLIT IS ON SCREEN.**
     *
     * ### What shipped in v0.18.0
     *
     * The bridge forwarded agterm's own `split` field, which means *both panes are visible*. Measured
     * over agterm's control socket on 2026-08-25, on throwaway sessions created and closed for the
     * purpose:
     *
     * ```
     * state                     agterm's split   surfaces        right pane reads?
     * no second pane ever       false            [left]          no - "session has no split pane"
     * second pane, collapsed    false            [left right]    YES, perfectly
     * second pane, on screen    true             [left right]    yes
     * ```
     *
     * The bridge now derives the field from the surfaces and publishes it under the SAME wire key.
     * That is the whole reason this test reads JSON rather than checking a Kotlin field: renaming the
     * property would compile and pass everywhere, and the only thing that can catch the key drifting
     * is a test that names it.
     *
     * ### It is not the addressed-pane defect again
     *
     * Nothing disagreed with itself. The read and the keystroke went to the same pane, the one the
     * owner chose. What a collapsed split removed was REACH: the toggle is composed only when this is
     * true, so a session opened while the split was collapsed offered no way to the second pane at
     * all, though it existed and read perfectly.
     *
     * **An earlier version of this comment said the toggle vanished underneath him mid-session,
     * leaving him on the right pane with no way back. It cannot** — the session row is a snapshot
     * taken at open and does not change while the screen is up. The mistake was reading two gates without asking when the
     * value they read can change.
     */
    @Test
    fun `a session with a second pane decodes from the split key whatever agterm calls it`() {
        val bridge = open(
            """{"ok":true,"sessions":[""" +
                """{"id":"A","name":"one","active":true,"split":true},""" +
                """{"id":"B","name":"two","active":false,"split":false}]}""",
        )

        val rows = bridge.sessions().sessions

        assertTrue("a session the bridge says has a second pane must offer the toggle", rows[0].splitPane)
        assertTrue("a session with one pane must not", !rows[1].splitPane)
    }

    /**
     * And a bridge too old to say anything produces no toggle rather than one that refuses on every
     * press. **This is the mixture the wire key was kept for**: the owner installs the phone and the
     * Mac app separately and nothing negotiates a version between them.
     */
    @Test
    fun `a session list from a bridge that mentions no panes offers no second pane`() {
        val bridge = open("""{"ok":true,"sessions":[{"id":"A","name":"one","active":true}]}""")

        assertTrue(!bridge.sessions().sessions[0].splitPane)
    }

    /** The first request has nothing to compare against, and must not invent a digest. */
    @Test
    fun `the first screen request sends no digest at all`() {
        val bridge = open("""{"ok":true,"text":"hello","digest":"d1"}""")

        bridge.screen("SESSION-1", Pane.Left)

        assertTrue("an absent digest must be absent, not empty", !request().has("digest"))
    }

    /**
     * **`unchanged` is not a blank screen, and the difference is the whole point of the digest.**
     *
     * 95% of idle polls were measured to convey nothing. If this returned empty text, the box would
     * blank every two seconds on a session that is simply quiet — the digest would have turned a
     * bandwidth saving into a visible defect.
     */
    @Test
    fun `an unchanged reply is distinguishable from an empty screen`() {
        val unchanged = open("""{"ok":true,"unchanged":true}""").screen("S", Pane.Left)
        val blank = open("""{"ok":true,"text":"","digest":"d"}""").screen("S", Pane.Left)

        assertEquals(ScreenText.Unchanged, unchanged.text)
        assertEquals(ScreenText.Text("", "d"), blank.text)
    }

    /**
     * **A laptop that answered is never reported as one that did not.**
     *
     * The bridge replies `ok:false` when it cannot talk to agterm. That is not a transport failure and
     * must not become one: `CannotReach` copy would send the owner to look at their network, having
     * already had a reply from the machine on the other end of it.
     */
    @Test
    fun `a refusal from the bridge is not a transport failure`() {
        val bridge = open("""{"ok":false,"error":"agterm is not running"}""")

        val thrown = runCatching { bridge.sessions() }.exceptionOrNull()

        assertTrue("must be a refusal, was $thrown", thrown is BridgeRefused)
        assertTrue("must not be a WireFailure", thrown !is WireException)
    }

    /**
     * **The bridge's sentence is the message; the far end's is evidence, and they arrive separately.**
     *
     * On 2026-08-12 the owner's Terminal screen read `failed to read surface buffer` and nothing else
     * — agterm describing its own internals, relayed verbatim to a person. The bridge now writes the
     * message and puts agterm's words in `detail`, so both have to survive the parse or the screen
     * has only half of what it needs.
     */
    @Test
    fun `a refusal carries the bridge's words and the far end's separately`() {
        val bridge = open(
            """{"ok":false,"error":"Your laptop has not opened this session yet.",""" +
                """"detail":"failed to read surface buffer"}""",
        )

        val thrown = runCatching { bridge.screen("A", Pane.Left) }.exceptionOrNull()

        assertTrue("must be a refusal, was $thrown", thrown is BridgeRefused)
        assertEquals("Your laptop has not opened this session yet.", (thrown as BridgeRefused).reason)
        assertEquals("failed to read surface buffer", thrown.detail)
    }

    /**
     * A refusal whose message is already the far end's own words carries no detail, and the app must
     * not invent one — an empty details line under the button would be a control with nothing in it.
     */
    @Test
    fun `a refusal with no detail yields an empty one rather than a null`() {
        val bridge = open("""{"ok":false,"error":"agterm is not answering"}""")

        val thrown = runCatching { bridge.screen("A", Pane.Left) }.exceptionOrNull()

        assertEquals("", (thrown as BridgeRefused).detail)
    }

    @Test
    fun `a reply that is not JSON is Malformed, which silence is not`() {
        val bridge = open("this is not json")

        val thrown = runCatching { bridge.sessions() }.exceptionOrNull()

        assertEquals(WireFailure.Malformed, (thrown as? WireException)?.failure)
    }

    /** End of stream mid-exchange is the far end going away, which is a different fact from bad JSON. */
    @Test
    fun `no reply at all is CannotReach rather than Malformed`() {
        val bridge = open()

        val thrown = runCatching { bridge.sessions() }.exceptionOrNull()

        assertEquals(WireFailure.CannotReach, (thrown as? WireException)?.failure)
    }

    /**
     * A reply that is neither `unchanged` nor carrying text is refused rather than defaulted.
     *
     * Defaulting to "" would render as a session that has gone blank — and a terminal genuinely can be
     * blank, so the two must not be made to look alike.
     */
    @Test
    fun `a screen reply with neither text nor unchanged is Malformed`() {
        val bridge = open("""{"ok":true,"digest":"d"}""")

        val thrown = runCatching { bridge.screen("S", Pane.Left) }.exceptionOrNull()

        assertEquals(WireFailure.Malformed, (thrown as? WireException)?.failure)
    }

    /**
     * **A draft with a line break goes as a PASTE; one without goes as typing.**
     *
     * Asserted on the bytes because this is the whole difference between the owner's chat message
     * landing in their editor and its lines running as commands — and because the phone chooses
     * between the two from the text itself, which is a decision worth pinning where it is made.
     */
    @Test
    fun `a draft with line breaks is sent as a paste, not as typing`() {
        val bridge = open("""{"ok":true}""")

        bridge.type("S", Pane.Left, paste = "alpha\nbeta")

        assertTrue("""the request carries no paste field: $sentText""", sentText.contains("\"paste\""))
        assertTrue("a paste must not also be sent as text: $sentText", !sentText.contains("\"text\""))
    }

    @Test
    fun `an ordinary draft is still sent as text`() {
        val bridge = open("""{"ok":true}""")

        bridge.type("S", Pane.Left, text = "ls -la")

        assertTrue("an ordinary line grew a paste field: $sentText", !sentText.contains("\"paste\""))
        assertTrue(sentText.contains("\"text\""))
    }

    /**
     * **The fit request carries the pane, because the fit is FOR the pane**.
     *
     * The fit's target is the half of the terminal he is looking at; the window is only the lever that
     * reaches it. Measured on his own machine: a session whose divider sits at 0.286 renders 47 columns
     * on the left and 121 on the right, so a fit that did not know the side would be right for one and
     * 2.5x wrong for the other.
     *
     * Asserted on the bytes rather than on a lambda, for the reason the recalibrate flag is: a renamed
     * argument can make a UI test pass while the same request as before goes out.
     */
    @Test
    fun `the fit request says which pane it is fitting`() {
        for (pane in Pane.entries) {
            sent.reset()
            open("""{"ok":true,"fit_enabled":true,"columns":45}""")
                .resize("S", pane, boxWidthDp = 440, characterWidthMilliDp = 9777, marginDp = 4)

            assertEquals(
                "the fit went out without saying which pane it was for",
                pane.wire,
                request().getString("pane"),
            )
        }
    }

    /**
     * **The long press is the only thing that asks for a recalibration, so this asserts the WIRE.**
     *
     * The bridge has had a `recalibrate` flag since the fit was built, commented as the
     * only thing that forces a fresh measurement, and the phone could never send it - which is why a
     * cached fit that had gone wrong could not be corrected from the owner's hand. Asserted on the
     * bytes rather than on a lambda, because a renamed callback can make a UI test pass while sending
     * the same request as before.
     */
    @Test
    fun `a long press asks the laptop to measure again`() {
        val bridge = open("""{"ok":true,"fit_enabled":true,"columns":45}""")

        bridge.resize("S", Pane.Left, boxWidthDp = 440, characterWidthMilliDp = 9777, marginDp = 4, recalibrate = true)

        assertTrue(
            "the request carries no recalibrate flag, so a held press asks for nothing new: $sentText",
            sentText.contains(""""recalibrate":true"""),
        )
    }

    /**
     * And an ordinary press puts NOTHING new on the wire.
     *
     * Not `recalibrate: false` - absent. A bridge that has never heard of the flag must see exactly
     * the request it has always seen, and a false that is present is a false somebody can read as an
     * instruction.
     */
    @Test
    fun `an ordinary press sends no recalibrate flag at all`() {
        val bridge = open("""{"ok":true,"fit_enabled":true,"columns":45}""")

        bridge.resize("S", Pane.Left, boxWidthDp = 440, characterWidthMilliDp = 9777, marginDp = 4)

        assertTrue("an ordinary press mentioned recalibrate: $sentText", !sentText.contains("recalibrate"))
    }

    private val sentText: String get() = sent.toString(Charsets.UTF_8.name())

    private fun open(vararg replies: String): BridgeConnection =
        BridgeConnection.ofStreams(
            input = ByteArrayInputStream(replies.joinToString("\n").toByteArray(Charsets.UTF_8)),
            output = sent,
        )

    /**
     * **The width setting must arrive on the SESSION LIST**, not only on a response to resize.
     *
     * It arrived only on resize, and the toggle is gated on having seen it - so learning the state
     * required pressing the button and pressing required knowing the state. The owner pressed a
     * control that was doing exactly what it was told across three builds.
     */
    @Test
    fun `an off setting on the list reply is read as off, not as unknown`() {
        val bridge = open("""{"ok":true,"sessions":[],"fit_enabled":false,"columns":0}""")

        assertEquals(false, bridge.sessions().fitEnabled)
    }

    @Test
    fun `an on setting arrives from the list with its column count`() {
        val bridge = open("""{"ok":true,"sessions":[],"fit_enabled":true,"columns":39}""")

        val listing = bridge.sessions()
        assertEquals(true, listing.fitEnabled)
        assertEquals(39, listing.fitColumns)
    }

    /**
     * An older bridge sends no workspace identity. Those sessions decode with an empty one and are
     * grouped together rather than dropped - a hierarchy that swallows a session is worse than a flat
     * list, and the phone must not require a bridge it cannot upgrade.
     */
    @Test
    fun `a listing without workspace identities still yields every session`() {
        val bridge = open("""{"ok":true,"sessions":[{"id":"A","workspace":"main","name":"agterm","active":true}]}""")

        val listing = bridge.sessions()

        assertEquals(1, listing.sessions.size)
        assertEquals("", listing.sessions[0].workspaceId)
    }

    // --- styled screens ------------------------------------------------------------------------------

    @Test
    fun `a plain screen request says nothing about style`() {
        open("""{"ok":true,"text":"hello","digest":"d1"}""").screen("S", Pane.Left)

        assertTrue("styled must be absent, not false", !request().has("styled"))
    }

    @Test
    fun `a styled screen request asks for one`() {
        open("""{"ok":true,"text":"hello","digest":"d1"}""").screen("S", Pane.Left, styled = true)

        assertTrue(request().getBoolean("styled"))
    }

    @Test
    fun `a reply that came through zmx is marked styled and one that fell back is not`() {
        val styled = open("""{"ok":true,"text":"[1mx[0m","digest":"d1","styled":true}""")
            .screen("S", Pane.Left, styled = true)
        val plain = open("""{"ok":true,"text":"x","digest":"d2"}""").screen("S", Pane.Left, styled = true)

        assertEquals(ScreenText.Text("[1mx[0m", "d1", styled = true), styled.text)
        assertEquals(ScreenText.Text("x", "d2", styled = false), plain.text)
    }
}
