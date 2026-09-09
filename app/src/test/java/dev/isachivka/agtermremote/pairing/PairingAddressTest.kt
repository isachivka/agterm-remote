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
     * This is the failure that actually happened, on 2026-08-09: the bare registered name is the
     * suffix, shared with every other service behind it, and the bridge lives on its own
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

    // ---------------------------------------------------------------------------------------------
    // Reading one back, which is the same contract in the other direction.
    // ---------------------------------------------------------------------------------------------

    /**
     * **The case table, once, so the formatter and the parser cannot each have their own.**
     *
     * Mirrors `DialAddressTests.swift` row for row. Neither side can call the other, so this table is
     * the only thing holding them together, and the settings screen is where it now matters at
     * runtime: an owner whose address changed types a string on the phone that the Mac would have
     * rendered.
     */
    private data class Row(val host: String, val port: Int, val rendered: String)

    private val table = listOf(
        Row("agterm.example-homelab.invalid", 8443, "agterm.example-homelab.invalid:8443"),
        Row("agterm.example-homelab.invalid", 443, "agterm.example-homelab.invalid:443"),
        Row("agterm.example-homelab.invalid.", 8443, "agterm.example-homelab.invalid.:8443"),
        Row("example-homelab.invalid", 8443, "example-homelab.invalid:8443"),
        Row("AGTERM.Example-Homelab.invalid", 8443, "AGTERM.Example-Homelab.invalid:8443"),
        Row("192.0.2.10", 8444, "192.0.2.10:8444"),
        Row("127.0.0.1", 1, "127.0.0.1:1"),
        Row("2001:db8::1", 8443, "[2001:db8::1]:8443"),
        Row("host-with-the-highest-port.invalid", 65535, "host-with-the-highest-port.invalid:65535"),
    )

    @Test
    fun `every row survives rendering and being read back`() {
        table.forEach { row ->
            assertEquals(row.rendered, PairingAddress.of(row.host, row.port))
            assertEquals(
                "${row.rendered} did not survive",
                TypedAddress.Read(row.host, row.port),
                PairingAddress.parse(row.rendered),
            )
        }
    }

    @Test
    fun `every row survives being read and rendered again`() {
        table.forEach { row ->
            val read = PairingAddress.parse(row.rendered) as TypedAddress.Read
            assertEquals(row.rendered, PairingAddress.of(read.host, read.port))
        }
    }

    /**
     * The brackets are syntax and not part of the name, so they come off — and go back on. A host
     * stored with them and one stored without are the same machine, and must render identically.
     */
    @Test
    fun `brackets are syntax rather than part of the name`() {
        val read = PairingAddress.parse("[2001:db8::1]:8443") as TypedAddress.Read

        assertEquals("2001:db8::1", read.host)
        assertEquals("[2001:db8::1]:8443", PairingAddress.of(read.host, read.port))
        assertEquals(PairingAddress.of("[2001:db8::1]", 8443), PairingAddress.of(read.host, read.port))
    }

    /** Whitespace cannot be part of an address, and a phone keyboard adds one after a paste. */
    @Test
    fun `surrounding whitespace is removed rather than refused`() {
        assertEquals(
            TypedAddress.Read("agterm.example-homelab.invalid", 8443),
            PairingAddress.parse("  agterm.example-homelab.invalid:8443\n"),
        )
    }

    /**
     * **Each refusal is its own, and none of them is a guess.**
     *
     * The row that matters most is the unbracketed IPv6: splitting on one of nine colons would store
     * an address that connects somewhere else entirely, which is worse than any refusal.
     */
    @Test
    fun `what is not an address is refused for the reason it is not one`() {
        val refusals = mapOf(
            "" to AddressRefusal.Empty,
            "   " to AddressRefusal.Empty,
            ":8443" to AddressRefusal.Empty,
            "agterm.example-homelab.invalid" to AddressRefusal.NoPort,
            "agterm.example-homelab.invalid:" to AddressRefusal.NoPort,
            "[2001:db8::1]" to AddressRefusal.NoPort,
            "[2001:db8::1]8443" to AddressRefusal.NoPort,
            "agterm.example-homelab.invalid:https" to AddressRefusal.PortNotANumber,
            "agterm.example-homelab.invalid:84 43" to AddressRefusal.PortNotANumber,
            "agterm.example-homelab.invalid:0" to AddressRefusal.PortOutOfRange,
            "agterm.example-homelab.invalid:65536" to AddressRefusal.PortOutOfRange,
            "agterm.example-homelab.invalid:99999999999999" to AddressRefusal.PortNotANumber,
            "2001:db8::1:8443" to AddressRefusal.AmbiguousWithoutBrackets,
            "[2001:db8::1:8443" to AddressRefusal.UnclosedBracket,
        )

        refusals.forEach { (typed, why) ->
            assertEquals(
                "'$typed' was not refused as $why",
                TypedAddress.Refused(why),
                PairingAddress.parse(typed),
            )
        }
    }

    /**
     * The house rule, in its narrowest form: **a failure that only reports is a defect.** Every
     * sentence here has to leave the owner with something to do, and the property is asserted rather
     * than the wording, so re-writing the copy does not need this test changed.
     */
    @Test
    fun `every refusal names something the owner can do`() {
        val verbs = listOf("Type ", "Add ", "Write ", "Check ")

        AddressRefusal.entries.forEach { refusal ->
            assertTrue(
                "$refusal does not tell the owner what to do: ${refusal.sentence}",
                verbs.any { refusal.sentence.contains(it) },
            )
            assertTrue("$refusal is not a sentence", refusal.sentence.endsWith("."))
        }
    }
}
