package dev.isachivka.agtermremote.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exact string is pinned, because a second program will render the same value.
 *
 * The bridge face will show the address beside the QR; this screen shows it after the decode; the
 * owner compares the two by eye. Nothing at runtime checks that the two agree, so nothing at runtime
 * would notice them diverging — exactly the argument [FingerprintTest] makes, and the same remedy.
 *
 * Hosts here are `.invalid` and `.example` by construction. The shapes are real; the addresses are
 * not, and the laptop's actual address does not belong in this repository.
 */
class PairingAddressTest {

    private fun profile(host: String, port: Int) =
        ConnectionProfile(StreamKind.DirectTcp, host, port, ByteArray(4))

    @Test
    fun `a plain host renders as host and port`() {
        assertEquals("agterm.example-homelab.invalid:8443", PairingAddress.of("agterm.example-homelab.invalid", 8443))
    }

    @Test
    fun `it reads the same address out of a decoded profile`() {
        assertEquals(
            "agterm.example-homelab.invalid:8443",
            PairingAddress.of(profile("agterm.example-homelab.invalid", 8443)),
        )
    }

    /**
     * **The port is never elided, not even when it looks like a default.**
     *
     * The bridge listens on 8444 and the router publishes 8443, and a QR carrying the listen address
     * pairs perfectly and then never connects. A renderer that hid "the obvious port" would hide the
     * field most likely to be wrong.
     */
    @Test
    fun `port 443 is shown rather than treated as a default worth hiding`() {
        assertEquals("agterm.example-homelab.invalid:443", PairingAddress.of("agterm.example-homelab.invalid", 443))
        assertNotEquals(
            PairingAddress.of("agterm.example-homelab.invalid", 443),
            PairingAddress.of("agterm.example-homelab.invalid", 8443),
        )
    }

    /**
     * **The suffix and the subdomain must not elide into each other.**
     *
     * This is the failure that actually happened, on 2026-08-09: the bare KeenDNS name is the
     * homelab's suffix, shared with every other service on it, and the bridge lives on its own
     * subdomain. Pairing to the bare name reaches the router and not us, and every screen afterwards
     * says the laptop is not answering. The two strings are one label apart, one is a suffix of the
     * other, and a renderer that truncated, ellipsised or right-aligned could show them identically.
     */
    @Test
    fun `a bare suffix and a host under it render distinctly and in full`() {
        val suffix = PairingAddress.of("example-homelab.invalid", 8443)
        val host = PairingAddress.of("agterm.example-homelab.invalid", 8443)

        assertNotEquals(suffix, host)
        assertEquals("example-homelab.invalid:8443", suffix)
        assertEquals("agterm.example-homelab.invalid:8443", host)
        assertTrue("the distinguishing label was dropped", host.startsWith("agterm."))
    }

    /**
     * A trailing dot is a different name, so it must look like one. Stripping it would render two
     * distinct hosts as one string, which is the one thing this function may not do.
     */
    @Test
    fun `a trailing dot survives and stays distinguishable`() {
        assertEquals("agterm.example-homelab.invalid.:8443", PairingAddress.of("agterm.example-homelab.invalid.", 8443))
        assertNotEquals(
            PairingAddress.of("agterm.example-homelab.invalid.", 8443),
            PairingAddress.of("agterm.example-homelab.invalid", 8443),
        )
    }

    /** Without brackets the port's colon is one of nine and the reader cannot tell where the address ends. */
    @Test
    fun `an IPv6 literal is bracketed`() {
        assertEquals("[2001:db8::1]:8443", PairingAddress.of("2001:db8::1", 8443))
    }

    @Test
    fun `a host that already carries brackets is not bracketed twice`() {
        assertEquals("[2001:db8::1]:8443", PairingAddress.of("[2001:db8::1]", 8443))
    }

    @Test
    fun `an IPv4 literal is left alone`() {
        assertEquals("192.0.2.10:8444", PairingAddress.of("192.0.2.10", 8444))
    }

    /**
     * Long hosts are rendered whole. Shortening belongs to the screen, which may wrap — it may never
     * abbreviate, because the abbreviated part is where a wrong address hides.
     */
    @Test
    fun `a long host is not truncated or ellipsised`() {
        val long = "a-really-quite-long-subdomain.beneath-another-long-one.example-homelab.invalid"

        val rendered = PairingAddress.of(long, 65535)

        assertEquals("$long:65535", rendered)
        assertTrue("the host was abbreviated", !rendered.contains("…") && !rendered.contains("..."))
    }

    /**
     * Case is carried through rather than folded. DNS does not care, but two profiles that differ by
     * a byte must not become one string on the screen that exists to tell them apart.
     */
    @Test
    fun `case is preserved rather than normalised away`() {
        assertEquals("AGTERM.Example-Homelab.invalid:8443", PairingAddress.of("AGTERM.Example-Homelab.invalid", 8443))
    }
}
