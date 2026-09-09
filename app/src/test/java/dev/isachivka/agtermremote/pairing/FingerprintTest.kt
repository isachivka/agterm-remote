package dev.isachivka.agtermremote.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The format is a contract with another program, in another language, that this code never talks to.
 *
 * `bridgecert` on the laptop prints a fingerprint; this app prints one; the owner compares them by
 * eye. Nothing at runtime checks that the two agree, so nothing at runtime would notice them
 * diverging — the owner would simply see two different strings and conclude the pairing was tampered
 * with. That failure is indistinguishable from the attack the comparison exists to detect, which is
 * why the exact output is pinned rather than described.
 */
class FingerprintTest {

    /**
     * A fixed, obviously synthetic input rather than a real certificate.
     *
     * `of` hashes the bytes it is given, so a certificate would exercise the same path while adding a
     * committed artefact that looks like it means something. Cross-implementation agreement on a real
     * certificate is checked against the live `bridgecert` and reported as evidence; this vector is
     * the regression guard.
     */
    private val vector = ByteArray(400) { ((it * 7 + 3) % 256).toByte() }

    @Test
    fun `the single-line form matches bridgecert byte for byte`() {
        assertEquals(
            "297D EAAF BBE0 BBB7 C80D 3996 F99E 0F6B 37A8 51CB 1F92 E842 32CB F2F6 4894 8594",
            Fingerprint.of(vector),
        )
    }

    @Test
    fun `the display form is two rows of eight, matching bridgecert's layout`() {
        assertEquals(
            listOf(
                "297D EAAF BBE0 BBB7 C80D 3996 F99E 0F6B",
                "37A8 51CB 1F92 E842 32CB F2F6 4894 8594",
            ),
            Fingerprint.rows(Fingerprint.of(vector)),
        )
    }

    @Test
    fun `it is sixteen groups of four uppercase hex`() {
        val groups = Fingerprint.of(vector).split(' ')

        assertEquals("SHA-256 is 32 bytes, paired into 16 groups", 16, groups.size)
        groups.forEach { group ->
            assertEquals("each group is two bytes", 4, group.length)
            assertEquals("uppercase, because bridgecert prints uppercase", group.uppercase(), group)
            assertEquals("hex only", "", group.filterNot { it in "0123456789ABCDEF" })
        }
    }

    /**
     * The property the owner is actually relying on: a different certificate looks different. Not a
     * cryptographic claim, a guard against the formatter collapsing inputs — a bug that truncated or
     * padded would produce identical strings for different certificates and silently defeat the whole
     * comparison.
     */
    @Test
    fun `different bytes give different fingerprints`() {
        val other = vector.copyOf().also { it[399] = (it[399] + 1).toByte() }

        assertNotEquals(Fingerprint.of(vector), Fingerprint.of(other))
    }

    @Test
    fun `an empty input still produces a well-formed fingerprint`() {
        // SHA-256 of nothing is a real value; there is no reason for this to be a special case, and a
        // formatter that threw here would fail on a screen rather than in a test.
        val groups = Fingerprint.of(ByteArray(0)).split(' ')

        assertEquals(16, groups.size)
    }
}
