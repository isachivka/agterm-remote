package dev.isachivka.agtermremote.pairing

import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * The SHA-256 of a certificate's DER, formatted for a person to compare against another screen.
 *
 * This is the authenticity mechanism for pairing, and the only step in the whole design that
 * depends on a human. Both certificates are public, so neither leg of the exchange needs to be
 * private — it needs the owner to be sure they pinned the certificate they meant to. They read this
 * off the laptop and off the phone and check that it is the same string.
 *
 * **The format must match `bridgecert` exactly.** Two devices showing the same bytes in two different
 * groupings is a comparison the owner will get wrong, or — worse — will decide is a mismatch and stop
 * a pairing that was fine. The Go side builds it as sixteen groups of four uppercase hex separated by
 * single spaces (`pinning.Fingerprint`), and prints it as two rows of eight
 * (`bridgecert.printFingerprint`). Both are reproduced here rather than approximated, and a test pins
 * the exact string against a known certificate so the two implementations cannot drift apart
 * silently.
 */
object Fingerprint {

    /** Groups per row when shown to a person. Two rows of eight is what fits a phone without wrapping. */
    private const val GROUPS_PER_ROW = 8

    /**
     * The single-line form: sixteen groups of four uppercase hex, space separated.
     *
     * Byte-pair grouping rather than the more common colon-separated single bytes. That is not a
     * preference — it is what the Go side emits, and matching it is the whole requirement.
     */
    fun of(certificate: X509Certificate): String = of(certificate.encoded)

    fun of(der: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(der)
        return buildString {
            for (i in digest.indices step 2) {
                if (i > 0) append(' ')
                append(HEX[(digest[i].toInt() shr 4) and 0xF])
                append(HEX[digest[i].toInt() and 0xF])
                append(HEX[(digest[i + 1].toInt() shr 4) and 0xF])
                append(HEX[digest[i + 1].toInt() and 0xF])
            }
        }
    }

    /**
     * The two-row form the owner actually reads, matching `bridgecert`'s output.
     *
     * Returned as a list rather than a joined string so the caller decides how to lay it out — the
     * screen puts one row above the other in a monospace style, and a joined string with a newline in
     * it would render differently depending on the composable that received it.
     */
    fun rows(certificate: X509Certificate): List<String> = rows(of(certificate))

    fun rows(fingerprint: String): List<String> =
        fingerprint.split(' ').chunked(GROUPS_PER_ROW).map { it.joinToString(" ") }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
