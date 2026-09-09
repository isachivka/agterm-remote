package dev.isachivka.agtermremote.pairing

import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

/**
 * The one trust decision the enrolment handshake can make, and the four ways it can be wrong.
 *
 * The QR code carries 32 bytes and no certificate, so this is the whole of what stands between the
 * phone and a stranger on the same address. Everything the phone sends afterwards — the one-time
 * token above all — is sent only because this said yes.
 */
class FingerprintTrustTest {

    private fun selfSigned(): X509Certificate =
        HeldCertificate.Builder().commonName("a bridge").build().certificate

    private fun sha256(der: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(der)

    @Test
    fun acceptsExactlyTheCertificateWhoseFingerprintMatches() {
        val cert = selfSigned()
        val trust = FingerprintTrust(sha256(cert.encoded))
        trust.checkServerTrusted(arrayOf(cert), "EC")
    }

    @Test
    fun refusesAnyOtherCertificate() {
        val trust = FingerprintTrust(sha256(selfSigned().encoded))
        assertThrows(CertificateException::class.java) {
            trust.checkServerTrusted(arrayOf(selfSigned()), "EC")
        }
    }

    @Test
    fun refusesAnEmptyChain() {
        val trust = FingerprintTrust(sha256(selfSigned().encoded))
        assertThrows(CertificateException::class.java) {
            trust.checkServerTrusted(arrayOf(), "EC")
        }
    }

    @Test
    fun refusesANullChain() {
        val trust = FingerprintTrust(sha256(selfSigned().encoded))
        assertThrows(CertificateException::class.java) {
            trust.checkServerTrusted(null, "EC")
        }
    }

    /**
     * **The leaf is `chain[0]`, and nothing else in the chain is looked at.**
     *
     * A chain that CONTAINS the pinned certificate somewhere is not a chain that presented it. The
     * peer is `chain[0]`; anything after it is an issuer the peer nominated, and in a design with no
     * certificate authority there is no such thing. Searching the chain would let anybody who can
     * fetch the bridge's public certificate — it is public — present their own key and staple the
     * real one behind it.
     */
    @Test
    fun comparesOnlyTheLeaf() {
        val leaf = selfSigned()
        val other = selfSigned()
        val trust = FingerprintTrust(sha256(leaf.encoded))
        assertThrows(CertificateException::class.java) {
            trust.checkServerTrusted(arrayOf(other, leaf), "EC")
        }
    }

    /**
     * A chain whose leaf matches is accepted whatever is stapled behind it — the mirror of
     * [comparesOnlyTheLeaf], and the half that proves "only the leaf" rather than "the chain must be
     * one long".
     */
    @Test
    fun ignoresWhateverIsStapledBehindAMatchingLeaf() {
        val leaf = selfSigned()
        val trust = FingerprintTrust(sha256(leaf.encoded))
        trust.checkServerTrusted(arrayOf(leaf, selfSigned()), "EC")
    }

    /**
     * This manager is installed on a client. Being asked to judge a client means it has been
     * installed on a server, which is a wiring mistake and must be loud rather than permissive.
     */
    @Test
    fun neverJudgesAClient() {
        val trust = FingerprintTrust(sha256(selfSigned().encoded))
        assertThrows(IllegalStateException::class.java) {
            trust.checkClientTrusted(arrayOf(selfSigned()), "EC")
        }
    }

    /**
     * There is no issuer in this design at all, so there are no accepted issuers. Returning the
     * pinned certificate here would describe it as a certificate authority.
     */
    @Test
    fun acceptsNoIssuers() {
        assertEquals(0, FingerprintTrust(sha256(selfSigned().encoded)).acceptedIssuers.size)
    }

    /**
     * A fingerprint that is not 32 bytes cannot be a SHA-256 digest, so it can never match anything.
     * Refused where it is handed over rather than on a handshake, because the remedy is a different
     * pairing code and not a retry.
     */
    @Test
    fun refusesAFingerprintThatIsNotADigest() {
        assertThrows(IllegalArgumentException::class.java) {
            FingerprintTrust(ByteArray(31))
        }
    }
}
