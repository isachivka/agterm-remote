package dev.isachivka.agtermremote.pairing

import java.io.ByteArrayOutputStream

/**
 * Everything the phone needs to reach one laptop, and the reason the parameterisation is
 * real rather than a claim.
 *
 * **The address is not in this application.** It is not a default, not a build field, not a constant
 * with a comment saying "change me". It arrives in the pairing payload and is stored on the device,
 * so switching from a direct forwarded port to a relay through SugarDaddy is the owner re-minting one
 * payload on the laptop and re-scanning it. No rebuild, no release, and no address ever enters the
 * repository — which is a ruling holding by construction rather than by anyone remembering
 * it.
 *
 * [kind] is the one field that is not purely data: a new stream provider is code. It is carried so
 * that adding one is additive — an old app meeting a new kind says so, instead of connecting to
 * something it does not understand.
 */
data class ConnectionProfile(
    val kind: StreamKind,
    val host: String,
    val port: Int,
    /** The bridge's certificate, DER, pinned byte for byte. Public — it is not a credential. */
    val bridgeCertificate: ByteArray,
) {
    // Data classes compare arrays by identity, which would make two profiles carrying the same
    // certificate unequal. Equality is used when deciding whether a rescan actually changed anything,
    // so it has to compare contents.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConnectionProfile) return false
        return kind == other.kind && host == other.host && port == other.port &&
            bridgeCertificate.contentEquals(other.bridgeCertificate)
    }

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + host.hashCode()
        result = 31 * result + port
        result = 31 * result + bridgeCertificate.contentHashCode()
        return result
    }

    // Deliberately does not print the host, the port or the certificate. This type ends up in
    // exception messages and log lines by accident more often than by design, and the address of the
    // highest-value endpoint in this homelab should not travel that way.
    override fun toString(): String = "ConnectionProfile(kind=$kind, host=<redacted>, port=<redacted>)"
}

/**
 * How the mTLS session's bytes are carried.
 *
 * The mTLS session is identical under every candidate transport and only the byte-stream
 * provider differs. A direct forward and an outbound relay are the *same* provider with a different
 * address, which is why one entry covers both.
 */
enum class StreamKind(val wire: Int) {
    /** A plain TCP connection to [ConnectionProfile.host]. Covers both a forwarded port and a relay. */
    DirectTcp(1),
    ;

    companion object {
        fun fromWire(value: Int): StreamKind? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * The pairing payload codec.
 *
 * Deliberately boring and deliberately not JSON. A QR code's capacity is the binding constraint —
 * 2,953 bytes in the densest mode — and a ~500-byte certificate should not be sharing it with field
 * names. Length-prefixed binary costs a few bytes of framing and nothing else.
 *
 * Everything here is public data: a certificate and an address. Nothing in this file handles a secret,
 * and nothing in it should ever need to.
 */
object ProfileCodec {

    /**
     * Bumped only on an incompatible change. An older app meeting a newer payload must say so rather
     * than misread it — the failure this prevents is a phone silently pairing against a profile it
     * decoded wrongly, which would look like a working pairing until the first connection.
     */
    const val VERSION = 1

    /** Guards against a hostile or corrupt payload sizing an allocation. Far above any real value. */
    private const val MAX_FIELD = 4096

    fun encode(profile: ConnectionProfile): ByteArray {
        require(profile.port in 1..65535) { "port out of range" }
        val host = profile.host.toByteArray(Charsets.UTF_8)
        require(host.isNotEmpty() && host.size <= MAX_FIELD) { "host out of range" }
        require(profile.bridgeCertificate.isNotEmpty()) { "certificate is empty" }

        return ByteArrayOutputStream().apply {
            write(VERSION)
            write(profile.kind.wire)
            writeShort(host.size)
            write(host)
            writeShort(profile.port)
            writeShort(profile.bridgeCertificate.size)
            write(profile.bridgeCertificate)
        }.toByteArray()
    }

    /**
     * Returns null for anything that is not a payload this version understands.
     *
     * Null rather than an exception, and never a partially-populated profile: the caller is a screen
     * pointed at a camera, and *"that is not a pairing code"* is a normal thing for it to see. What it
     * must never do is accept half of one.
     */
    fun decode(bytes: ByteArray): ConnectionProfile? {
        val reader = Reader(bytes)
        val version = reader.byte() ?: return null
        if (version != VERSION) return null
        val kind = StreamKind.fromWire(reader.byte() ?: return null) ?: return null

        val hostLength = reader.short() ?: return null
        if (hostLength !in 1..MAX_FIELD) return null
        val host = reader.bytes(hostLength) ?: return null

        val port = reader.short() ?: return null
        if (port !in 1..65535) return null

        val certLength = reader.short() ?: return null
        if (certLength !in 1..MAX_FIELD) return null
        val certificate = reader.bytes(certLength) ?: return null

        // Trailing bytes mean this is not the payload it claims to be. Accepting them would let a
        // longer, differently-shaped blob decode as a valid profile plus rubbish.
        if (!reader.exhausted()) return null

        return ConnectionProfile(
            kind = kind,
            host = String(host, Charsets.UTF_8),
            port = port,
            bridgeCertificate = certificate,
        )
    }

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    /** A bounds-checked cursor. Every read returns null past the end rather than throwing. */
    private class Reader(private val source: ByteArray) {
        private var index = 0

        fun byte(): Int? = if (index < source.size) source[index++].toInt() and 0xFF else null

        fun short(): Int? {
            val high = byte() ?: return null
            val low = byte() ?: return null
            return (high shl 8) or low
        }

        fun bytes(count: Int): ByteArray? {
            if (count < 0 || index + count > source.size) return null
            return source.copyOfRange(index, index + count).also { index += count }
        }

        fun exhausted(): Boolean = index == source.size
    }
}
