package dev.isachivka.agtermremote.settings

import dev.isachivka.agtermremote.pairing.ConnectionProfile
import dev.isachivka.agtermremote.pairing.PairedLaptop
import dev.isachivka.agtermremote.pairing.SigningState
import dev.isachivka.agtermremote.pairing.StreamKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The settings screen's rules, without a screen.
 *
 * The store is real — `PairedLaptop` is a file, and faking it would make *the certificate survived* a
 * statement about the fake. The keystore is two lambdas, so the two destructive acts in the whole
 * product are exercised here rather than only on a device.
 */
class LaptopSettingsTest {

    @get:Rule val folder = TemporaryFolder()

    /** Not a real certificate: nothing here parses it, and what matters is that the bytes do not move. */
    private val certificate = ByteArray(385) { ((it * 31 + 11) % 256).toByte() }

    private fun store() = PairedLaptop(File(folder.root, PairedLaptop.DIRECTORY))

    private fun paired(host: String = "agterm.example-homelab.invalid", port: Int = 8443) =
        store().apply { write(ConnectionProfile(StreamKind.DirectTls, host, port, certificate)) }

    private var identityDiscarded = 0

    private fun settings(
        store: PairedLaptop = store(),
        key: SigningState = SigningState.Ready,
    ) = LaptopSettings(
        store = store,
        discardIdentity = { identityDiscarded++ },
        keyState = { key },
    )

    // --- The address, which is the whole reason this screen exists -------------------------------

    /**
     * **The central requirement, and it is a security property rather than a convenience.**
     *
     * The pinned identity is independent of where that laptop happens to answer. An owner whose
     * address changed — a new dynamic IP, a tunnel rebuilt — must not have to re-pair, because
     * re-pairing mints a new key on this phone and evicts the peer their Mac pinned by hand. A text
     * field that quietly reset trust would be the most expensive control in the application.
     */
    @Test
    fun `editing the address keeps the pinned laptop`() {
        val store = paired()
        val before = store.read()!!.bridgeCertificate
        val settings = settings(store)

        settings.edit("example.test:9443")
        assertTrue(settings.save())

        val after = store.read()!!
        assertEquals("example.test", after.host)
        assertEquals(9443, after.port)
        assertArrayEquals(before, after.bridgeCertificate)
    }

    /** And the stream kind with it: how the phone opens the address is not part of the address. */
    @Test
    fun `moving the laptop changes nothing but the address`() {
        val store = paired()
        val before = store.read()!!
        settings(store).apply { edit("moved.example:1"); save() }

        val after = store.read()!!
        assertEquals(before.kind, after.kind)
        assertArrayEquals(before.bridgeCertificate, after.bridgeCertificate)
        assertEquals("moved.example", after.host)
        assertEquals(1, after.port)
    }

    @Test
    fun `the box opens on what this phone actually dials`() {
        assertEquals("agterm.example-homelab.invalid:8443", settings(paired()).address)
    }

    /**
     * The box is re-rendered from what was stored rather than left as typed. Whitespace a paste
     * brought in is gone at that point, and a box still showing it invites somebody to "fix" an
     * address that is already right.
     */
    @Test
    fun `what is saved is shown back in the one rendering`() {
        val settings = settings(paired())

        settings.edit("  [2001:db8::1]:8443  ")
        settings.save()

        assertEquals("[2001:db8::1]:8443", settings.address)
        assertEquals("2001:db8::1", store().read()!!.host)
    }

    /**
     * **Nothing is stored when what was typed is not an address**, and the sentence says which way it
     * was wrong. The alternative — storing a guess — is a phone that pairs perfectly and then never
     * connects, which is the failure this project has paid for most often.
     */
    @Test
    fun `an address that will not parse changes nothing and says why`() {
        val store = paired()
        val settings = settings(store)

        settings.edit("agterm.example-homelab.invalid")

        assertFalse(settings.save())
        assertEquals("agterm.example-homelab.invalid", store.read()!!.host)
        assertEquals(8443, store.read()!!.port)
        assertTrue("the refusal must name the missing port: ${settings.note}", settings.note!!.contains("port"))
    }

    /** Typing again clears the last verdict: a refusal about a string nobody is looking at is noise. */
    @Test
    fun `editing clears whatever the screen last said`() {
        val settings = settings(paired())
        settings.edit("nonsense")
        settings.save()
        assertNotNull(settings.note)

        settings.edit("still-typing")

        assertNull(settings.note)
    }

    // --- Unpairing, which is total ---------------------------------------------------------------

    /**
     * **Both halves, because the bridge keeps exactly one peer in this version.**
     *
     * A phone that kept its key after unpairing would present a certificate the Mac has already been
     * told to forget. Half a pairing is not a state worth being in.
     */
    @Test
    fun `unpairing clears both the laptop and the phone key`() {
        val store = paired()
        val settings = settings(store)

        settings.ask(LaptopSettings.Destruction.Unpair)
        settings.confirm()

        assertNull(store.read())
        assertEquals(1, identityDiscarded)
        assertNull(settings.laptop)
    }

    /**
     * **Nothing is destroyed by one press.** There is no state here that moves from paired to nothing
     * without a confirmation having been asked for first — which is the shape the predecessor project
     * lacked when a broad verdict was wired straight to a `clear()`.
     */
    @Test
    fun `a confirmation nobody asked for destroys nothing`() {
        val store = paired()
        val settings = settings(store)

        settings.confirm()

        assertNotNull(store.read())
        assertEquals(0, identityDiscarded)
    }

    @Test
    fun `asking and then cancelling destroys nothing`() {
        val store = paired()
        val settings = settings(store)

        settings.ask(LaptopSettings.Destruction.Unpair)
        settings.dismiss()
        settings.confirm()

        assertNotNull(store.read())
        assertEquals(0, identityDiscarded)
    }

    /**
     * **Every warning names what will be lost.** A confirmation that only asks *are you sure*
     * transfers no information and is answered yes by reflex. The property is asserted, not the
     * wording.
     */
    @Test
    fun `every confirmation says what it costs`() {
        LaptopSettings.Destruction.entries.forEach { what ->
            assertTrue(
                "$what does not say the key goes: ${what.warning}",
                what.warning.contains("key", ignoreCase = true),
            )
            assertTrue(
                "$what does not say what happens to the Mac: ${what.warning}",
                what.warning.contains("Mac"),
            )
            assertTrue(
                "$what does not say pairing again is what comes next: ${what.warning}",
                what.warning.contains("pairing again", ignoreCase = true),
            )
        }
    }

    // --- The key that will not sign, and the one way out of it ------------------------------------

    /**
     * **The verdict decides what is OFFERED and never what is deleted.**
     *
     * `SigningState.Unusable` is reached by catching `GeneralSecurityException`, which is broad on
     * purpose. On 2026-07-29 one misclassification of a working key destroyed the owner's identity
     * because that verdict was wired to a `clear()`. Here it moves a Boolean that decides whether a
     * button is drawn, and the deletion is performed by the person.
     */
    @Test
    fun `the replacement is offered only to a phone whose key will not sign`() {
        assertFalse(settings(key = SigningState.Ready).keyIsUnusable)
        assertFalse(settings(key = SigningState.Absent).keyIsUnusable)
        assertTrue(settings(key = SigningState.Unusable).keyIsUnusable)
    }

    /** A phone with an unusable key is normally not paired at all — the gate refused it before it enrolled. */
    @Test
    fun `replacing the key discards it and stops offering to`() {
        var state: SigningState = SigningState.Unusable
        val settings = LaptopSettings(
            store = store(),
            discardIdentity = { identityDiscarded++; state = SigningState.Absent },
            keyState = { state },
        )
        assertTrue(settings.keyIsUnusable)

        settings.ask(LaptopSettings.Destruction.ReplaceKey)
        settings.confirm()

        assertEquals(1, identityDiscarded)
        assertFalse("the button that has done its work must go", settings.keyIsUnusable)
        assertTrue("it must say the key is gone: ${settings.note}", settings.note!!.contains("key"))
    }

    /** It clears the laptop too. A new key is a new phone as far as the Mac is concerned. */
    @Test
    fun `replacing the key on a paired phone forgets the laptop with it`() {
        val store = paired()
        val settings = settings(store, key = SigningState.Unusable)

        settings.ask(LaptopSettings.Destruction.ReplaceKey)
        settings.confirm()

        assertNull(store.read())
    }
}
