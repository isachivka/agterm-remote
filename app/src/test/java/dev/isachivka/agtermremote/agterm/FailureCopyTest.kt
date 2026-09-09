package dev.isachivka.agtermremote.agterm

import dev.isachivka.agtermremote.wire.WireFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The house rule, as a check rather than as a sentence.
 *
 * **Never assert a cause that cannot be observed.** It has held since REQ-0005 and was held to over
 * the Immich report, and this feature is where it is hardest: a sleeping laptop, a filtered network
 * and a VPN on the laptop all arrive at the phone as the same silence. Copy that picks one of them is
 * wrong five times out of six and sounds authoritative every time.
 *
 * A rule in a document is checked by whoever remembers it. This reads the actual shipped strings.
 */
class FailureCopyTest {

    private val strings: Map<String, String> by lazy {
        val file = listOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml"),
        ).firstOrNull { it.isFile } ?: error("strings.xml not found from ${File(".").absolutePath}")
        Regex("""<string name="(agterm_[a-z_]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /**
     * The words that name a cause this app cannot see.
     *
     * REQ-0009 §6 names the first three explicitly: the copy "does not say the network is filtered, it
     * does not say the laptop is asleep, and it does not say the VPN is up". The rest are the same
     * mistake in other clothes.
     */
    private val unobservable = listOf(
        "asleep", "sleeping", "vpn", "filtered", "firewall", "blocked",
        "offline", "wi-fi", "wifi", "carrier", "mobile data", "signal",
    )

    /** The proof that the check can see something: a sentence that breaks the rule is caught. */
    @Test
    fun `the check itself detects a forbidden cause`() {
        val bad = "Your laptop is not answering. It is probably asleep, or your network is filtered."

        val hits = unobservable.filter { bad.contains(it, ignoreCase = true) }

        assertEquals(listOf("asleep", "filtered"), hits)
    }

    @Test
    fun `no failure string names a cause the app cannot observe`() {
        val offenders = strings
            .filterKeys { it.startsWith("agterm_failure_") }
            .mapNotNull { (key, text) ->
                val hits = unobservable.filter { text.contains(it, ignoreCase = true) }
                if (hits.isEmpty()) null else "$key names ${hits.joinToString()}"
            }

        assertEquals("copy must say what was observed and stop there", emptyList<String>(), offenders)
    }

    /**
     * **The distinction iteration 2b was rebuilt to protect.**
     *
     * These two are the only failures the owner can act on, and the actions are on *different
     * machines*: re-pair the phone, or mint a new certificate on the laptop. One string for both would
     * undo the reason `PinnedTrust` records its verdict where no provider can lose it.
     */
    @Test
    fun `a wrong certificate and an expired one do not share copy`() {
        assertTrue(
            "NotPinned and CertificateExpired must not resolve to the same string",
            copyFor(WireFailure.NotPinned) != copyFor(WireFailure.CertificateExpired),
        )
        assertTrue(
            "the remedy for a wrong certificate is to pair again",
            strings.getValue("agterm_failure_not_pinned").contains("Pair again", ignoreCase = true),
        )
        assertTrue(
            "the remedy for an expiry is a new certificate on the LAPTOP",
            strings.getValue("agterm_failure_certificate_expired").contains("laptop", ignoreCase = true),
        )
    }

    /**
     * A backlog is ours and the copy says so.
     *
     * The value was renamed in 2b precisely so this sentence could exist: the bridge behaved
     * correctly and this app stopped draining the stream, so copy that blamed the laptop would send
     * the owner to a machine that was fine.
     */
    @Test
    fun `the backlog failure does not blame the laptop`() {
        val text = strings.getValue("agterm_failure_reader_fell_behind")

        assertTrue("must own the failure, was: $text", text.contains("This app", ignoreCase = true))
    }

    /**
     * Every failure resolves to copy, and the ones with different remedies say different things.
     *
     * **The existence half is already guaranteed by the compiler** — `copyFor` is an exhaustive `when`,
     * so a new [WireFailure] fails to build until it has a string. What this list is really for is the
     * DISTINCTNESS assertion below, and that is why it is maintained by hand.
     *
     * It was maintained badly: `AuthenticationDeclined` was missing from it for its whole existence,
     * so nothing checked that the copy for "you declined" differed from the copy for anything else.
     * That failure is gone with the unlock prompt itself (2026-07-29). What replaced it as the
     * assertion that matters is below: [WireFailure.IdentityUnusable] must not read like a laptop
     * problem, because a phone that cannot use its own key blaming the laptop is complaint 5.
     */
    @Test
    fun `every failure has copy behind it`() {
        val failures = listOf(
            WireFailure.NotPaired,
            WireFailure.IdentityUnusable,
            WireFailure.NotPinned,
            WireFailure.CertificateExpired,
            WireFailure.CannotReach,
            WireFailure.BridgeNotListening,
            WireFailure.ReaderFellBehind,
            WireFailure.Malformed,
            WireFailure.TimedOut(WireFailure.TimedOut.Stage.Connecting),
            WireFailure.TimedOut(WireFailure.TimedOut.Stage.Handshaking),
            WireFailure.TimedOut(WireFailure.TimedOut.Stage.AwaitingResponse),
        )

        // Nine distinct strings for eleven failures: the three TimedOut stages deliberately share
        // one, because a stage is a fact about this app's internals and not about the owner's laptop.
        assertEquals(9, failures.map { copyFor(it) }.distinct().size)
        assertEquals(
            "the three stages must not become three different sentences",
            1,
            failures.filterIsInstance<WireFailure.TimedOut>().map { copyFor(it) }.distinct().size,
        )
    }

    /**
     * **A failure that is this phone's fault must not describe the laptop.**
     *
     * The owner's complaint 5, in their words: the app "answered with something this app did not
     * understand" about a machine that answered perfectly. On 2026-07-29 the bridge logged a
     * successful verb at 18:27:32 and refused every handshake afterwards because the phone had
     * replaced its own key — and what the owner was shown was that their laptop was not answering.
     *
     * The instrument is the control: `CannotReach` — a failure that genuinely IS about the far end —
     * must contain the forbidden words, or this test is asserting against a vocabulary that has moved
     * on and would pass for the wrong reason.
     */
    @Test
    fun `a failure of this phone's own key never blames the laptop`() {
        val ours = strings.getValue("agterm_failure_identity_unusable")

        listOf("not answering", "did not understand", "cannot reach").forEach {
            assertTrue(
                "copy for a key this phone cannot use says \"$it\", about a laptop that answered: $ours",
                !ours.contains(it, ignoreCase = true),
            )
        }
        assertTrue("it must name the phone as the thing at fault, was: $ours",
            ours.contains("this phone", ignoreCase = true))
        assertTrue(
            "the control failed: no string in this app says \"not answering\" any more, so the " +
                "assertion above would pass against a vocabulary that has simply moved on",
            strings.getValue("agterm_failure_cannot_reach").contains("not answering", ignoreCase = true),
        )
    }

    /** Identity failures offer pairing; the rest offer a retry that can actually succeed. */
    @Test
    fun `only identity failures send the owner to pairing`() {
        assertTrue(isIdentityFailure(WireFailure.NotPinned))
        assertTrue(isIdentityFailure(WireFailure.CertificateExpired))
        assertTrue(isIdentityFailure(WireFailure.NotPaired))
        // Ours rather than theirs, but the remedy is still a new identity, so it offers pairing.
        assertTrue(isIdentityFailure(WireFailure.IdentityUnusable))
        assertTrue(!isIdentityFailure(WireFailure.CannotReach))
        assertTrue(!isIdentityFailure(WireFailure.BridgeNotListening))
        assertTrue(!isIdentityFailure(WireFailure.ReaderFellBehind))
        assertTrue(!isIdentityFailure(WireFailure.Malformed))
        assertTrue(!isIdentityFailure(WireFailure.TimedOut(WireFailure.TimedOut.Stage.Connecting)))
    }
}
