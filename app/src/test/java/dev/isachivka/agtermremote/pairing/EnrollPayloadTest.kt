package dev.isachivka.agtermremote.pairing

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * The decoder, held to the same bytes the Go encoder is held to.
 *
 * ### Both files, from the repository root, and never a copy
 *
 * `wire/enroll-payload-vectors.json` and `wire/enroll-payload-reject-vectors.json` are the contract
 * between two hand-written implementations of one format - the Go encoder in
 * `bridge/internal/enroll` and this decoder. They cannot be compiled against each other and nothing
 * else in either build connects them, so the bytes in those two files are the connection. A copy
 * inside this module is how they drift: the copy is what these tests pin, the original is what the Go
 * tests pin, both suites stay green, and nobody notices until somebody is holding a phone that will
 * not pair. `wire/README.md` states the rule and `scripts/check-wire-vectors-consumed.sh` enforces
 * both halves of it.
 *
 * ### The reject half is the half that matters here
 *
 * The accept vectors cannot tell a decoder that shrugs at a version it does not know from one that
 * refuses it - measured on the Go side, where mutating `Decode` to accept any version under 11 left
 * every accept test green. This decoder is the half facing a camera, running on bytes a stranger can
 * hold in front of a lens before any authentication exists at all, so what it refuses is as much of
 * the contract as what it accepts.
 */
class EnrollPayloadTest {

    /**
     * Set by `app/build.gradle.kts` from `rootProject`, which is the repository root locally and in
     * CI alike. `error(...)` rather than a default or a skip: a test that quietly does nothing when
     * its fixture is missing reports success by not running, which is the failure this repository's
     * guards exist to prevent.
     */
    private val wireDir = File(System.getProperty("wire.dir") ?: error("wire.dir is not set"))

    private fun accepted(): List<JSONObject> = load("enroll-payload-vectors.json")

    private fun rejected(): List<JSONObject> = load("enroll-payload-reject-vectors.json")

    private fun load(name: String): List<JSONObject> {
        val file = wireDir.resolve(name)
        assertTrue("no vectors at ${file.absolutePath}", file.isFile)
        val array = JSONArray(file.readText())
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    /** The ordinary vector - a short host, the usual port - used where one payload is enough. */
    private fun firstVectorText(): String = accepted().first().getString("text")

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // --- What a correct encoder produces --------------------------------------------------------

    @Test
    fun `every accept vector decodes to the payload it names`() {
        val vectors = accepted()
        assertTrue("want at least three vectors, found ${vectors.size}", vectors.size >= 3)

        vectors.forEach { v ->
            val name = v.getString("name")
            val got = EnrollCodec.decodeText(v.getString("text")) ?: fail("vector $name did not decode") as Nothing

            assertEquals("$name: host", v.getString("host"), got.host)
            assertEquals("$name: port", v.getInt("port"), got.port)
            assertEquals("$name: fingerprint", v.getString("fingerprint_hex"), got.fingerprint.toHex())
            assertEquals("$name: token", v.getString("token_hex"), got.token.toHex())
            assertEquals("$name: expiry", v.getLong("expiry_unix"), got.expiryUnix)
            assertEquals("$name: scheme", v.getInt("scheme"), got.scheme.wire)
        }
    }

    /**
     * **Both layouts decode, and the set carries both on purpose.**
     *
     * Version 2 added the scheme byte. Version 1 is read and never written, because refusing it would
     * report "this Mac is newer than this app" about a code this build reads perfectly - the wrong
     * sentence and one nobody can act on. What version 1 MEANT is a plain connection, since that is
     * the only thing any implementation of it ever did.
     */
    @Test
    fun `both payload versions decode, and version one means a plain connection`() {
        val byVersion = accepted().groupBy { it.getInt("version") }

        assertTrue("the vector set must carry version 1", byVersion.containsKey(1))
        assertTrue("the vector set must carry version 2", byVersion.containsKey(2))

        byVersion.getValue(1).forEach { v ->
            val name = v.getString("name")
            val got = EnrollCodec.decodeText(v.getString("text")) ?: fail("vector $name did not decode") as Nothing
            assertEquals("$name: a version 1 code means a plain connection", StreamKind.DirectTcp, got.scheme)
        }
        assertTrue(
            "the version 2 vectors must exercise both schemes, or the field is untested",
            byVersion.getValue(2).map { it.getInt("scheme") }.toSet() ==
                setOf(StreamKind.DirectTcp.wire, StreamKind.DirectTls.wire),
        )
    }

    /**
     * **One layer past `dial_address`, and the reason the scheme is in the payload at all.**
     *
     * `dial_address` pins the bracketing. This pins the bracketing AND the scheme mapping together,
     * because a dialler builds one string and gets both right or neither - and being wrong produces a
     * phone that cannot reach a front door with the address correct and nothing on the wire to say
     * why. That failure has now happened twice, once for each constant the scheme replaced.
     */
    @Test
    fun `every accept vector produces the dial URL the vectors derive`() {
        accepted().forEach { v ->
            val name = v.getString("name")
            val got = EnrollCodec.decodeText(v.getString("text")) ?: fail("vector $name did not decode") as Nothing

            assertEquals("$name: dial URL", v.getString("dial_url"), got.dialUrl)
        }
    }

    /**
     * The stored profile has to dial the same way the enrolment did, and the two derive it
     * separately. A pairing that completes and then never connects is what disagreement looks like.
     */
    @Test
    fun `the stored profile dials the same URL the payload did`() {
        accepted().forEach { v ->
            val name = v.getString("name")
            val payload = EnrollCodec.decodeText(v.getString("text")) ?: fail("vector $name") as Nothing
            val profile = ConnectionProfile(payload.scheme, payload.host, payload.port, ByteArray(1))

            assertEquals("$name: the terminal must dial what enrolment dialled", payload.dialUrl, profile.dialUrl)
        }
    }

    /**
     * **The field that is not in the bytes.**
     *
     * `dial_address` is derived from `host` and `port`, and it is here because the payload's host is
     * bare: `2001:db8::1`, never `[2001:db8::1]`. `host + ":" + port` is right for a name, right for
     * IPv4 and silently wrong for every IPv6 literal, which is the one bug a decoder test on its own
     * cannot see - both sides read the host correctly and the next line ruins it.
     */
    @Test
    fun `every accept vector produces the dial address the vectors derive`() {
        accepted().forEach { v ->
            val name = v.getString("name")
            val got = EnrollCodec.decodeText(v.getString("text")) ?: fail("vector $name did not decode") as Nothing

            assertEquals("$name: dial address", v.getString("dial_address"), got.dialAddress)
        }
    }

    /**
     * The rule on its own, stated as `wire/README.md` states it: bracket the host if and only if it
     * contains a colon, then append `:` and the port. Written out beside the vectors rather than
     * instead of them, because the vectors happen to carry one IPv6 host and this says which
     * property that one host is standing for.
     */
    @Test
    fun `the dial address brackets an IPv6 literal and leaves everything else alone`() {
        assertEquals("192.0.2.7:8443", payload(host = "192.0.2.7", port = 8443).dialAddress)
        assertEquals("laptop.example:8443", payload(host = "laptop.example", port = 8443).dialAddress)
        assertEquals("[2001:db8::1]:8443", payload(host = "2001:db8::1", port = 8443).dialAddress)
    }

    // --- What must be refused -------------------------------------------------------------------

    /**
     * Every entry in the reject file, fed to the STRING entry point.
     *
     * Not to `decode(ByteArray)`: two of these are deliberately not base64 at all and one is the
     * empty string, so the alphabet and the padding are part of what is being pinned.
     */
    @Test
    fun `every reject vector is refused`() {
        val vectors = rejected()
        assertTrue("want the full reject set, found ${vectors.size}", vectors.size >= 12)

        vectors.forEach { v ->
            val name = v.getString("name")
            assertNull(
                "$name must not decode - ${v.getString("note")}",
                EnrollCodec.decodeText(v.getString("text")),
            )
        }
    }

    /**
     * **The one the reject vectors actually caught, and the premise behind it, asserted.**
     *
     * `wire/README.md` and `bridge/internal/enroll/format.go` both said the standard decoder refuses
     * missing padding, and the `unpadded` vector was written on that belief. It is not true:
     * `java.util.Base64.getDecoder()` treats the padding character as "accepted... but not required",
     * so it hands back the full payload. Go's `StdEncoding` does refuse it, so the two sides would
     * have disagreed about whether an unpadded string is a pairing code - which is the exact drift
     * `wire/` exists to catch, caught by the half of it that faces the camera.
     *
     * The first assertion is the premise, not decoration. Without it the padding check in the codec
     * looks redundant, and the first person to tidy it away would reintroduce the divergence with
     * every test still green.
     */
    @Test
    fun `padding is enforced here, because the standard decoder does not enforce it`() {
        val unpadded = rejected().single { it.getString("name") == "unpadded" }.getString("text")

        // 86 rather than 85 since the payload grew a scheme byte at version 2. The NUMBER is not the
        // point and the premise is: the platform decoder returns a whole payload for text Go refuses,
        // so this codec has to enforce the padding itself.
        assertEquals("the premise: the decoder itself lets this through", 86, Base64.getDecoder().decode(unpadded).size)
        assertNull("and the codec must not", EnrollCodec.decodeText(unpadded))
    }

    /**
     * The refusal kinds in `wire/README.md`'s table are all still exercised.
     *
     * Guards the file rather than the decoder: a reject vector deleted, or a whole kind quietly
     * dropped, would leave the test above green on a smaller contract.
     */
    @Test
    fun `the reject vectors still cover every refusal kind the format names`() {
        val kinds = rejected().map { it.getString("refusal") }.toSet()

        assertEquals(
            setOf(
                "not-standard-base64",
                "empty-payload",
                "unsupported-version",
                "too-short",
                "host-length-over-ceiling",
                "empty-host",
                "length-mismatch",
                "host-not-utf8",
                "unsupported-scheme",
            ),
            kinds,
        )
    }

    /**
     * **The distinction the owner is told apart by.**
     *
     * "This Mac is running something newer than this app" and "that is not a pairing code" are
     * different sentences and different remedies - update the phone, versus scan again - so they
     * cannot be the same refusal. The version is the first byte of the payload for exactly this
     * reason: it is readable before any length field has been trusted.
     *
     * Driven from the file's own `refusal` field rather than from a hand-written list, so a vector
     * added to either side arrives already asserted.
     */
    @Test
    fun `a version this build does not speak is refused as a version, and nothing else is`() {
        val byKind = rejected().groupBy { it.getString("refusal") }

        val versions = byKind.getValue("unsupported-version")
        assertEquals("the format names two version vectors", 2, versions.size)
        versions.forEach { v ->
            val name = v.getString("name")
            val refusal = EnrollCodec.readText(v.getString("text"))
            assertTrue(
                "$name must be refused as a version, was $refusal",
                refusal is EnrollDecode.UnsupportedVersion,
            )
            // The version it saw, not merely that it saw one - a screen that says "newer" about a
            // version 0 payload is saying something false.
            val seen = Base64.getDecoder().decode(v.getString("text"))[0].toInt() and 0xFF
            assertEquals(name, seen, (refusal as EnrollDecode.UnsupportedVersion).version)
        }

        byKind.filterKeys { it != "unsupported-version" }.values.flatten().forEach { v ->
            val name = v.getString("name")
            assertTrue(
                "$name is damage, not a version - it must not tell the owner to update",
                EnrollCodec.readText(v.getString("text")) is EnrollDecode.NotAPairingCode,
            )
        }
    }

    /**
     * **Every reject vector is refused FOR THE REASON THE FILE NAMES, not merely refused.**
     *
     * Go's reject test has always pinned the kind as well as the name, so a change that turns a
     * reject case valid — or refused by accident, for a different reason — goes red there. This side
     * asserted only that the decoder said no, and **this side is the one facing the camera**: it
     * parses bytes a stranger holds in front of a lens, before any authentication exists.
     *
     * The asymmetry is not hypothetical. The padding premise was wrong in three places for months
     * and it was a reject vector that caught it; a vector that starts being refused for the wrong
     * reason is the same class of drift with nothing left to catch it.
     *
     * The refusal kind is for this test and for the wire contract. **It is not for the screen** —
     * see [EnrollDecode.NotAPairingCode], which is deliberately one outcome to a person.
     */
    @Test
    fun `every reject vector is refused for the reason the vectors name`() {
        val kinds = mapOf(
            "not-standard-base64" to EnrollRefusal.NotStandardBase64,
            "empty-payload" to EnrollRefusal.EmptyPayload,
            "too-short" to EnrollRefusal.TooShort,
            "host-length-over-ceiling" to EnrollRefusal.HostLengthOverCeiling,
            "empty-host" to EnrollRefusal.EmptyHost,
            "length-mismatch" to EnrollRefusal.LengthMismatch,
            "host-not-utf8" to EnrollRefusal.HostNotUtf8,
            "unsupported-scheme" to EnrollRefusal.UnsupportedScheme,
        )

        rejected().forEach { v ->
            val name = v.getString("name")
            val refusal = v.getString("refusal")
            if (refusal == "unsupported-version") return@forEach
            val want = kinds[refusal]
                ?: fail("$name names a refusal kind this test does not know: $refusal") as Nothing

            val got = EnrollCodec.readText(v.getString("text"))
            assertTrue("$name was not refused at all: $got", got is EnrollDecode.NotAPairingCode)
            assertEquals(
                "$name must be refused as $refusal",
                want,
                (got as EnrollDecode.NotAPairingCode).refusal,
            )
        }
    }

    /**
     * The file's kinds and this build's are the same set. A kind added on the Go side with nothing
     * reading it here would otherwise sit unasserted, which is how the two decoders start disagreeing
     * about which codes exist.
     */
    @Test
    fun `the vectors name no refusal kind this decoder cannot produce`() {
        val named = rejected().map { it.getString("refusal") }.toSet()
        val known = EnrollRefusal.entries.map { it.wire }.toSet() + "unsupported-version"

        assertEquals(known, named)
    }

    // --- The same refusals through the byte entry point -----------------------------------------

    @Test
    fun `a newer version is refused`() {
        val bytes = Base64.getDecoder().decode(firstVectorText()).also { it[0] = 99 }

        assertNull(EnrollCodec.decode(bytes))
        assertEquals(EnrollDecode.UnsupportedVersion(99), EnrollCodec.read(bytes))
    }

    @Test
    fun `a trailing byte is refused`() {
        val bytes = Base64.getDecoder().decode(firstVectorText()) + 0

        assertNull("a trailing byte is the tail of a second message, not slack", EnrollCodec.decode(bytes))
    }

    /**
     * Every prefix of a valid payload. A bounds bug would surface here as one of them decoding, or
     * as an exception escaping into a camera preview - which is the caller.
     */
    @Test
    fun `no prefix of a valid payload decodes`() {
        val full = Base64.getDecoder().decode(firstVectorText())

        for (length in 0 until full.size) {
            assertNull("a $length-byte prefix must not decode", EnrollCodec.decode(full.copyOf(length)))
        }
        assertNotNull("the whole thing still must", EnrollCodec.decode(full))
    }

    /** The decoded payload owns its bytes; the buffer it came from can be reused or wiped. */
    @Test
    fun `the fingerprint and the token are copies, not views of the input`() {
        val bytes = Base64.getDecoder().decode(firstVectorText())
        val payload = EnrollCodec.decode(bytes)!!
        val fingerprint = payload.fingerprint.toHex()
        val token = payload.token.toHex()

        bytes.fill(0)

        assertEquals(fingerprint, payload.fingerprint.toHex())
        assertEquals(token, payload.token.toHex())
    }

    // --- Equality and disclosure ----------------------------------------------------------------

    @Test
    fun `two payloads decoded from the same text are equal`() {
        // Data-class equality compares arrays by identity, which would make a rescan of the same
        // code look like a change. That decides whether the app re-pins, so it compares contents.
        assertEquals(EnrollCodec.decodeText(firstVectorText()), EnrollCodec.decodeText(firstVectorText()))
        assertEquals(
            EnrollCodec.decodeText(firstVectorText()).hashCode(),
            EnrollCodec.decodeText(firstVectorText()).hashCode(),
        )
    }

    /**
     * **A stack trace in a bug report must not carry somebody's address.**
     *
     * Pinned as an exact string rather than as a list of things absent, and that is the stronger
     * statement: a `contains` check cannot be written for the boundary vector, whose host is the
     * single letter `a` and appears inside the word `Payload`. What is asserted instead is that the
     * rendering is the same for every payload, which is only true if it carries none of them.
     */
    @Test
    fun `toString carries no part of the payload`() {
        val renderings = accepted().map { EnrollCodec.decodeText(it.getString("text"))!!.toString() }.toSet()

        assertEquals("every payload must render identically", 1, renderings.size)
        assertEquals(
            "EnrollPayload(host=<redacted>, port=<redacted>, fingerprint=<redacted>, " +
                "token=<redacted>, expiry=<redacted>)",
            renderings.single(),
        )

        val ordinary = EnrollCodec.decodeText(firstVectorText())!!
        assertFalse("the host must not reach a log or an exception message", ordinary.toString().contains(ordinary.host))
        assertFalse("nor the port", ordinary.toString().contains(ordinary.port.toString()))
    }

    private fun payload(host: String, port: Int) = EnrollPayload(
        host = host,
        port = port,
        fingerprint = ByteArray(32),
        token = ByteArray(32),
        expiryUnix = 0,
    )
}
