package dev.isachivka.agtermremote.pairing

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * What one QR code says.
 *
 * The Mac draws a code; this phone reads it and, from these bytes alone, knows where to dial, which
 * certificate to accept there, and what one-time secret proves it was in the room. Everything after
 * this - the handshake, the token, the phone sending its own certificate back - is bootstrapped from
 * here and from nothing else. There is no directory, no account and no server in the middle to ask.
 *
 * The wire layout and the reasoning behind every field are documented once, in Go, at the top of
 * `bridge/internal/enroll/format.go`, and the bytes are pinned in `wire/`. This file is the second
 * implementation of that one format, in a language that cannot be compiled against the first.
 */
data class EnrollPayload(
    /**
     * Where the phone dials: a DNS name or a literal IP address, as text, exactly as it arrived.
     *
     * **Bare**, with no brackets even for an IPv6 literal - see [dialAddress]. It is the owner's own
     * address and belongs in no log and no repository.
     */
    val host: String,
    /** The bridge's TLS port. */
    val port: Int,
    /** SHA-256 of the bridge certificate's DER. 32 bytes, and not the certificate itself. */
    val fingerprint: ByteArray,
    /**
     * The one-time enrolment secret. 32 bytes.
     *
     * It authorises exactly one pairing and is worthless afterwards, which is the property that lets
     * it be shown on a screen at all. It is still a secret while the window is open, so it is not in
     * [toString] and must not reach a log.
     */
    val token: ByteArray,
    /**
     * When this offer stops being accepted, in Unix SECONDS.
     *
     * A `Long` holding what the wire carries as a `uint32`, so the range ends on 2106-02-07 and
     * begins at the epoch. Not an `Int`: the last representable second is 4294967295, which does not
     * fit one, and reading it into a signed 32-bit value would turn the far end of the range into a
     * date in 1969.
     */
    val expiryUnix: Long,
) {

    /**
     * Host and port as a string something can connect to.
     *
     * ### Why this is here and not two lines at the call site
     *
     * **An IPv6 literal has to be bracketed before a port can be appended, and the payload does not
     * carry the brackets.** [host] is bare - `2001:db8::1`, not `[2001:db8::1]` - because the field
     * is one host, and putting brackets in the BYTES would put a display-versus-dial ambiguity into
     * the wire format: two spellings of one host, and every implementation guessing which it holds.
     *
     * The consequence is that `host + ":" + port` is right for a DNS name, right for IPv4, and
     * silently wrong for every IPv6 address - `2001:db8::1:8443` is not an address, and what happens
     * next is a phone that will not pair for a reason nobody can see by looking at the QR code. It is
     * the obvious two lines and it is what a dialler written from the field list does.
     *
     * So the rule lives once, here: bracket the host if and only if it contains a colon, then append
     * `:` and the port. That is `net.JoinHostPort` on the Go side, and **it is pinned across the two
     * languages by the `dial_address` field on every accept vector** - a Go helper this app cannot
     * import proves nothing about this app. See `wire/README.md`.
     *
     * Not [PairingAddress.of], which renders an address for a person to compare against the laptop.
     * The two rules agree on everything the wire can carry and differ on a host that arrives already
     * bracketed, which this format forbids and that one tolerates; and that function belongs to the
     * codec being replaced.
     */
    val dialAddress: String get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"

    // Data classes compare arrays by identity, which would make two payloads decoded from the same
    // code unequal. Equality decides whether a rescan changed anything, so it compares contents.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EnrollPayload) return false
        return host == other.host &&
            port == other.port &&
            fingerprint.contentEquals(other.fingerprint) &&
            token.contentEquals(other.token) &&
            expiryUnix == other.expiryUnix
    }

    override fun hashCode(): Int {
        var result = host.hashCode()
        result = 31 * result + port
        result = 31 * result + fingerprint.contentHashCode()
        result = 31 * result + token.contentHashCode()
        result = 31 * result + expiryUnix.hashCode()
        return result
    }

    /**
     * Deliberately carries none of it.
     *
     * This type reaches exception messages and log lines by accident more often than by design, and
     * a stack trace in a bug report must not carry somebody's address - nor the enrolment token,
     * which is live for as long as the window is open. The rendering is a constant, so it is the same
     * for every payload; that is what `EnrollPayloadTest` asserts, and it is a stronger statement
     * than a list of things checked absent.
     */
    override fun toString(): String =
        "EnrollPayload(host=<redacted>, port=<redacted>, fingerprint=<redacted>, " +
            "token=<redacted>, expiry=<redacted>)"
}

/**
 * What reading a pairing code produced.
 *
 * Three outcomes and not two, because **"this Mac is newer than this app" and "that is not a pairing
 * code" are different things to tell an owner**, with different remedies: update the phone, or scan
 * again. Collapsing them costs the owner of a newer Mac an afternoon looking for a broken camera.
 *
 * A decoder facing a lens sees rubbish constantly, so the ordinary refusal is not an error condition
 * and is not thrown. It is a value, and the screen renders it.
 */
sealed interface EnrollDecode {

    /** The bytes were a pairing code this build understands. */
    data class Read(val payload: EnrollPayload) : EnrollDecode

    /**
     * The first byte named a version this build does not speak.
     *
     * [version] is what was actually seen, so the screen can say which - a message about something
     * "newer" is false for the version-zero payload a truncated-then-padded buffer produces.
     *
     * The version is the FIRST field of the format for exactly this reason: it is readable from one
     * byte, before any length field has been trusted, so a payload from a future version is reported
     * as one rather than parsed hopefully into whatever the old layout says.
     */
    data class UnsupportedVersion(val version: Int) : EnrollDecode

    /**
     * Not a pairing code, or a damaged one.
     *
     * One outcome for every other refusal on purpose. `wire/README.md` names eight refusal kinds and
     * requires that a decoder distinguish only the version; the rest - not base64, empty, too short,
     * a length over the ceiling, an empty host, a buffer that is not the length its own header
     * describes, a host that is not UTF-8 - all mean the same thing to the person holding the phone,
     * and all have the same remedy.
     */
    data object NotAPairingCode : EnrollDecode
}

/**
 * The pairing payload codec, decode half.
 *
 * ### This runs before any authentication whatsoever
 *
 * Its input is whatever was in front of the camera, put there by anyone. So it refuses rather than
 * interprets: a version it does not know, a buffer that ends early, a length field that overruns what
 * it was handed, and any trailing byte at all. No length field sizes an allocation without [MAX_FIELD]
 * over it, and nothing is returned half-populated. A decoder that guesses is a decoder somebody can
 * steer.
 *
 * ### There is no encoder here, and that is not an omission
 *
 * Only the Mac mints these. An encoder on this side would be a second implementation of the writing
 * half with no caller, and the first thing anyone would do with it is write a round-trip test - which
 * passes just as happily when both halves are wrong together. The vectors in `wire/` are what this is
 * checked against instead.
 */
object EnrollCodec {

    /**
     * The first byte of every payload.
     *
     * A phone reading a version it does not know must say so to its owner rather than parse the
     * remainder hopefully. Bumping this is a breaking wire change and lands on both sides at once -
     * see `wire/README.md`, which describes what has to happen to the vectors when it does.
     */
    const val VERSION = 1

    /**
     * The ceiling on any length-prefixed field, in bytes. Matches `enroll.MaxField` on the Go side.
     *
     * It bounds an allocation made from a number somebody else chose, and it is far above any
     * legitimate host: a DNS name cannot exceed 253 bytes.
     */
    const val MAX_FIELD = 4096

    private const val FINGERPRINT_LENGTH = 32
    private const val TOKEN_LENGTH = 32
    private const val EXPIRY_LENGTH = 4

    /** version + host length + port, plus the three fixed fields: the smallest a payload can be. */
    private const val FIXED_LENGTH = 1 + 2 + 2 + FINGERPRINT_LENGTH + TOKEN_LENGTH + EXPIRY_LENGTH

    /**
     * Reads the text a QR code carried, and says why if it will not.
     *
     * **Standard, padded base64** - `java.util.Base64.getDecoder()`, matching Go's
     * `base64.StdEncoding`. It refuses the URL-safe alphabet, which is pinned by a reject vector
     * because that refusal is invisible from inside Go, where a URL-safe encoder and a standard one
     * round-trip equally well.
     *
     * ### The padding is checked here, because the decoder does not do it
     *
     * `wire/README.md` and `bridge/internal/enroll/format.go` both said the standard decoder refuses
     * missing padding. **It does not**, measured on JDK 21: `getDecoder().decode("AQAMZXhhbXBsZQ")`
     * returns ten bytes as happily as the padded form, because the padding character is "accepted and
     * interpreted as the end of the encoded byte data, but is not required". What it does refuse is a
     * final unit of the wrong length, which is why the near miss `"AQAMZXhhbXBsZQ="` throws and the
     * unpadded form does not.
     *
     * Left alone, that is precisely the cross-language drift `wire/` exists to catch: Go's
     * `StdEncoding` refuses an unpadded code and this side would have accepted it, so the same string
     * would be a pairing code on one machine and not on the other. The `unpadded` reject vector caught
     * it, and this is the line that satisfies it - a length that is not a multiple of four is not
     * standard padded base64, whatever the decoder is willing to make of it.
     *
     * The one asymmetry that remains, and which the vectors cannot pin, is recorded in
     * `wire/README.md`: Go's decoder skips carriage returns and newlines where this one refuses them,
     * so Go is still the more permissive side. Nothing in this project ever emits either.
     *
     * Nothing is trimmed on the way in. Whitespace around a pasted code is not tolerated here on
     * purpose - the two sides have to agree on what a valid code is, and a decoder that quietly
     * accepts more than the other does is how they stop agreeing.
     */
    fun readText(text: String): EnrollDecode {
        if (text.length % 4 != 0) return EnrollDecode.NotAPairingCode
        val bytes = try {
            Base64.getDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            return EnrollDecode.NotAPairingCode
        }
        return read(bytes)
    }

    /**
     * Reads a decoded payload, and says why if it will not.
     *
     * The order of the checks is the Go decoder's order and is part of the contract: empty, then the
     * version, then the minimum length, then the host length against its ceiling and against zero,
     * then the buffer against the length its own header describes, then the host against UTF-8. The
     * version comes before every length so that a payload from a future version is reported as one.
     */
    fun read(bytes: ByteArray): EnrollDecode {
        if (bytes.isEmpty()) return EnrollDecode.NotAPairingCode

        val version = bytes[0].toInt() and 0xFF
        if (version != VERSION) return EnrollDecode.UnsupportedVersion(version)

        if (bytes.size < FIXED_LENGTH) return EnrollDecode.NotAPairingCode

        val hostLength = u16(bytes, 1)
        // Both halves matter and neither implies the other. The ceiling stops a hostile length
        // becoming an allocation; the length check below stops it becoming a read past the end -
        // 4096 is a comfortable allocation and still far past the end of an 85-byte buffer.
        if (hostLength > MAX_FIELD) return EnrollDecode.NotAPairingCode
        // A payload naming no host is nothing the phone can act on.
        if (hostLength == 0) return EnrollDecode.NotAPairingCode

        // One check for short and for long: the length the header describes is the only length this
        // payload may have. A trailing byte is the tail of a second message, or somebody probing for
        // a parser that ignores what it does not understand.
        if (bytes.size != FIXED_LENGTH + hostLength) return EnrollDecode.NotAPairingCode

        // Refused rather than repaired. `String(bytes, UTF_8)` substitutes a replacement character
        // for every bad byte and returns happily, which would arrive at a DIFFERENT host from the one
        // the owner is looking at - so the decoder reports rather than substitutes.
        val host = utf8OrNull(bytes.copyOfRange(3, 3 + hostLength)) ?: return EnrollDecode.NotAPairingCode

        var at = 3 + hostLength
        val port = u16(bytes, at)
        at += 2
        // copyOfRange, so the payload owns its bytes and the buffer it came from can be reused.
        val fingerprint = bytes.copyOfRange(at, at + FINGERPRINT_LENGTH)
        at += FINGERPRINT_LENGTH
        val token = bytes.copyOfRange(at, at + TOKEN_LENGTH)
        at += TOKEN_LENGTH
        val expiry = u32(bytes, at)

        return EnrollDecode.Read(
            EnrollPayload(
                host = host,
                port = port,
                fingerprint = fingerprint,
                token = token,
                expiryUnix = expiry,
            ),
        )
    }

    /**
     * The text a QR code carried, or null if it was not a pairing code this build understands.
     *
     * For callers that have nothing different to say about the two refusals. Anything pointed at a
     * camera or at a pasted string should use [readText] instead and tell the owner which it was.
     */
    fun decodeText(text: String): EnrollPayload? = (readText(text) as? EnrollDecode.Read)?.payload

    /** As [decodeText], for bytes that have already been base64-decoded. */
    fun decode(bytes: ByteArray): EnrollPayload? = (read(bytes) as? EnrollDecode.Read)?.payload

    /** Big-endian, matching the format. Every integer on this wire is. */
    private fun u16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    /** Into a `Long`, because a `uint32` does not fit a signed `Int` - see [EnrollPayload.expiryUnix]. */
    private fun u32(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 0xFF) shl 24) or
            ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or
            (bytes[at + 3].toLong() and 0xFF)

    private fun utf8OrNull(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }
}
