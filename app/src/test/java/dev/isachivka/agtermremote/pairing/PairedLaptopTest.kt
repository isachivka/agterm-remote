package dev.isachivka.agtermremote.pairing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The pairing is the one thing this app stores, and the failure that matters is not losing it — it is
 * reading back something that is not what was written.
 */
class PairedLaptopTest {

    @get:Rule val folder = TemporaryFolder()

    private val certificate = ByteArray(385) { ((it * 13 + 7) % 256).toByte() }
    private fun profile(host: String = "laptop.example", port: Int = 51820) =
        ConnectionProfile(StreamKind.DirectTcp, host, port, certificate)

    private fun store() = PairedLaptop(File(folder.root, PairedLaptop.DIRECTORY))

    @Test
    fun `an unpaired phone reports no laptop`() {
        assertNull(store().read())
        assertFalse(store().isPaired)
    }

    @Test
    fun `a pairing survives being written and read back`() {
        val original = profile()
        store().write(original)

        assertEquals(original, store().read())
        assertTrue(store().isPaired)
    }

    @Test
    fun `pairing again replaces the laptop rather than adding one`() {
        store().write(profile(host = "first.example"))
        store().write(profile(host = "second.example"))

        assertEquals("second.example", store().read()!!.host)
    }

    /**
     * A process death mid-write must leave the old pairing or none, never a truncated one that
     * decodes to something plausible. Simulated by truncating the stored file, which is what a
     * partial write would leave behind.
     */
    @Test
    fun `a truncated file reads as no pairing rather than as a bad one`() {
        store().write(profile())
        val stored = File(folder.root, PairedLaptop.DIRECTORY).listFiles()!!.first { it.name == "laptop.bin" }
        stored.writeBytes(stored.readBytes().copyOf(20))

        assertNull("a partial profile must never reach a connection", store().read())
    }

    @Test
    fun `rubbish in the file reads as no pairing`() {
        val dir = File(folder.root, PairedLaptop.DIRECTORY).apply { mkdirs() }
        File(dir, "laptop.bin").writeBytes(ByteArray(200) { 0x5A })

        assertNull(store().read())
    }

    @Test
    fun `clearing forgets the laptop`() {
        store().write(profile())
        store().clear()

        assertNull(store().read())
    }

    /**
     * Spec §5.5. A dynamic IP that changes is an address problem, not a trust problem: the owner
     * edits the address in settings and stays paired with the same laptop. Re-pairing instead would
     * mint a new key on the phone and evict the peer their Mac has pinned.
     */
    @Test
    fun `changing the address keeps the pinned certificate`() {
        store().write(profile(host = "first.example", port = 8443))

        store().setAddress("second.example", 9443)

        val after = store().read()!!
        assertEquals("second.example", after.host)
        assertEquals(9443, after.port)
        assertArrayEquals("the pinned laptop must survive a renumbering", certificate, after.bridgeCertificate)
    }

    /**
     * There is no address to change on a laptop that is not there, and inventing a profile would
     * store a certificate nobody chose.
     */
    @Test
    fun `changing the address of nothing pairs nothing`() {
        store().setAddress("laptop.example", 8443)

        assertNull(store().read())
    }

    /**
     * The dialler's rendering of the address, which is not the screen's. An IPv6 literal is stored
     * bare, exactly as the pairing payload carried it, and has to be bracketed before a socket sees
     * it — the same rule `EnrollPayload.dialAddress` applies to the same address.
     */
    @Test
    fun `the dial address brackets an IPv6 literal and leaves a name alone`() {
        assertEquals("laptop.example:8443", profile(host = "laptop.example", port = 8443).dialAddress)
        assertEquals("[fd00::1]:8443", profile(host = "fd00::1", port = 8443).dialAddress)
    }

    @Test
    fun `the directory name is the one the backup rules exclude`() {
        // If this constant is renamed without the XML being updated, the pairing silently starts
        // going to Google's cloud backup - and the symptom is a restored phone that looks paired and
        // cannot connect, which is the state the exclusion exists to prevent.
        assertEquals("pairing", PairedLaptop.DIRECTORY)
    }
}
