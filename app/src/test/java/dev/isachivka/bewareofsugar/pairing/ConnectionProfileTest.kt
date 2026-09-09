package dev.isachivka.bewareofsugar.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing payload is the only place the laptop's address exists, so the codec is what makes
 * REQ-0009 §0 true: switching transport is re-minting this blob, not rebuilding the app.
 *
 * Everything it carries is public — a certificate and an address. The tests below are about a decoder
 * pointed at a camera, which will be shown arbitrary rubbish as a matter of course and must never
 * turn half of it into a profile.
 */
class ConnectionProfileTest {

    private val certificate = ByteArray(385) { ((it * 13 + 7) % 256).toByte() }

    private fun profile(
        kind: StreamKind = StreamKind.DirectTcp,
        host: String = "example.invalid",
        port: Int = 9999,
        cert: ByteArray = certificate,
    ) = ConnectionProfile(kind, host, port, cert)

    @Test
    fun `a profile survives a round trip unchanged`() {
        val original = profile()

        val decoded = ProfileCodec.decode(ProfileCodec.encode(original))

        assertEquals(original, decoded)
        assertTrue("the certificate must survive byte for byte", certificate.contentEquals(decoded!!.bridgeCertificate))
    }

    @Test
    fun `a hostname with non-ascii characters survives`() {
        // Punycode is the usual case, but nothing stops the owner from a UTF-8 host, and a codec that
        // mangled it would fail at connect time with no clue why.
        val original = profile(host = "лаптоп.local")

        assertEquals(original, ProfileCodec.decode(ProfileCodec.encode(original)))
    }

    // --- What a decoder pointed at a camera will actually be shown ---------------------------------

    @Test
    fun `rubbish decodes to nothing rather than to half a profile`() {
        val inputs = listOf(
            ByteArray(0),
            byteArrayOf(1),
            "not a pairing code".toByteArray(),
            ByteArray(200) { 0 },
        )

        inputs.forEach { assertNull("must not decode: ${it.size} bytes", ProfileCodec.decode(it)) }
    }

    @Test
    fun `a truncated payload decodes to nothing`() {
        val full = ProfileCodec.encode(profile())

        // Every prefix of a valid payload. A bounds bug would surface as one of these decoding, or as
        // an exception escaping into a camera preview.
        for (length in 0 until full.size) {
            assertNull("a $length-byte prefix must not decode", ProfileCodec.decode(full.copyOf(length)))
        }
    }

    @Test
    fun `trailing bytes are refused`() {
        val padded = ProfileCodec.encode(profile()) + byteArrayOf(0, 0, 0)

        assertNull("a longer blob must not decode as a profile plus rubbish", ProfileCodec.decode(padded))
    }

    /**
     * An older app meeting a newer payload must say so, not misread it. The failure this prevents is a
     * phone that pairs successfully against a profile it decoded wrongly — which looks like a working
     * pairing right up until the first connection, on a network where the owner has no way to tell a
     * bad profile from a filtered one.
     */
    @Test
    fun `an unknown version is refused`() {
        val bumped = ProfileCodec.encode(profile()).also { it[0] = (ProfileCodec.VERSION + 1).toByte() }

        assertNull(ProfileCodec.decode(bumped))
    }

    @Test
    fun `an unknown stream kind is refused`() {
        val unknown = ProfileCodec.encode(profile()).also { it[1] = 0x7F }

        assertNull("a kind this build has no provider for must not decode", ProfileCodec.decode(unknown))
    }

    @Test
    fun `an oversized length field cannot size an allocation`() {
        val hostile = ProfileCodec.encode(profile()).also {
            it[2] = 0xFF.toByte() // host length -> 65535, far past the payload
            it[3] = 0xFF.toByte()
        }

        assertNull(ProfileCodec.decode(hostile))
    }

    // --- Bounds -----------------------------------------------------------------------------------

    @Test
    fun `port bounds are enforced on the way in and on the way out`() {
        listOf(0, -1, 65536, 100000).forEach { port ->
            runCatching { ProfileCodec.encode(profile(port = port)) }
                .onSuccess { org.junit.Assert.fail("port $port must not encode") }
        }
        assertNotNull(ProfileCodec.decode(ProfileCodec.encode(profile(port = 1))))
        assertNotNull(ProfileCodec.decode(ProfileCodec.encode(profile(port = 65535))))
    }

    @Test
    fun `an empty host or certificate is refused`() {
        runCatching { ProfileCodec.encode(profile(host = "")) }
            .onSuccess { org.junit.Assert.fail("an empty host must not encode") }
        runCatching { ProfileCodec.encode(profile(cert = ByteArray(0))) }
            .onSuccess { org.junit.Assert.fail("an empty certificate must not encode") }
    }

    // --- Size, against the constraint that actually binds -------------------------------------------

    /**
     * A QR code holds 2,953 bytes in its densest mode. REQ-0008 measured a P-256 certificate at 385
     * bytes DER, so the framing is what has to stay small — and it is the reason this is not JSON.
     */
    @Test
    fun `the payload fits a QR code with room to spare`() {
        val encoded = ProfileCodec.encode(profile(host = "a-fairly-long-hostname.bewareofsugar.example"))

        val overhead = encoded.size - certificate.size
        assertTrue("framing should cost under 64 bytes, was $overhead", overhead < 64)
        assertTrue("payload must fit a QR code, was ${encoded.size}", encoded.size < 2953)
    }

    // --- Equality and disclosure --------------------------------------------------------------------

    @Test
    fun `profiles carrying the same certificate are equal`() {
        // Data-class equality compares arrays by identity, which would make a rescan of the same code
        // look like a change. That decides whether the app re-pins, so it has to compare contents.
        assertEquals(profile(cert = certificate.copyOf()), profile(cert = certificate.copyOf()))
    }

    @Test
    fun `toString does not disclose the address`() {
        val text = profile(host = "laptop.example", port = 41234).toString()

        assertFalse("the host must not reach a log or an exception message", text.contains("laptop.example"))
        assertFalse("nor the port", text.contains("41234"))
    }
}
