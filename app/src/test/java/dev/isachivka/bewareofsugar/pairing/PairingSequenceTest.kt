package dev.isachivka.bewareofsugar.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The property this sequence exists to hold: **a decoded code is not a pairing.**
 *
 * Between decoding and storing sits the owner comparing two fingerprints, and that comparison is the
 * entire authenticity argument for a channel that is deliberately not confidential. If anything were
 * written before they answered, the comparison would be decorative and the *stop* they can give would
 * have nothing left to prevent.
 */
class PairingSequenceTest {

    @get:Rule val folder = TemporaryFolder()

    private val certificate = ByteArray(385) { ((it * 13 + 7) % 256).toByte() }
    private fun profile() = ConnectionProfile(StreamKind.DirectTcp, "laptop.example", 51820, certificate)
    private fun store() = PairedLaptop(File(folder.root, PairedLaptop.DIRECTORY))

    /**
     * A phone half that always provisions, so tests about the LAPTOP half are not about this one.
     *
     * [minted] counts identities this phone has produced. It is the instrument for the finding of
     * 2026-07-29 — the certificate the laptop pins is only pinned while it exists, so a test that
     * checks the returned state without checking whether a NEW key was cut would pass on the code that
     * cost the owner their pairing.
     */
    private class FakePhone(
        private var provisioning: PhoneHalf.Provisioning =
            PhoneHalf.Provisioning.Ready(listOf("AAAA BBBB", "CCCC DDDD")),
    ) : PhoneHalf {
        var provisionCalls = 0
        var minted = 0
        var identity = false
        override fun exists(): Boolean = identity
        override fun provision(): PhoneHalf.Provisioning {
            provisionCalls++
            if (provisioning is PhoneHalf.Provisioning.Ready && !identity) {
                identity = true
                minted++
            }
            return provisioning
        }

        override fun replace(): PhoneHalf.Provisioning {
            identity = true
            minted++
            provisioning = PhoneHalf.Provisioning.Ready(listOf("EEEE FFFF", "0000 1111"))
            return provisioning
        }

        fun refuse() { provisioning = PhoneHalf.Provisioning.NoScreenLock }

        /** The key exists and will not sign — the condition the owner's phone was in at 18:28:50. */
        fun cannotSign() {
            identity = true
            provisioning = PhoneHalf.Provisioning.CannotSign(listOf("DEAD BEEF", "DEAD BEEF"))
        }

        /** The key signs fine and expires — every key minted before 2026-07-29. */
        fun madeTheOldWay() {
            identity = true
            provisioning = PhoneHalf.Provisioning.MadeTheOldWay(listOf("0LD0 0LD0", "0LD0 0LD0"))
        }
    }

    private class FakeHandover(private var value: Boolean = false) : PairingSequence.Handover {
        override fun done(): Boolean = value
        override fun markDone() { value = true }
        override fun clear() { value = false }
    }

    private fun sequence(
        phone: PhoneHalf = FakePhone(),
        handover: PairingSequence.Handover = FakeHandover(),
        store: PairedLaptop = store(),
    ) = PairingSequence(store, phone, handover)

    private fun codeFor(p: ConnectionProfile): Triple<IntArray, Int, Int> {
        val m = QRCodeWriter().encode(PairingCode.encodeToText(ProfileCodec.encode(p)), BarcodeFormat.QR_CODE, 600, 600)
        val pixels = IntArray(m.width * m.height)
        for (y in 0 until m.height) for (x in 0 until m.width) {
            pixels[y * m.width + x] = if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return Triple(pixels, m.width, m.height)
    }

    @Test
    fun `an unpaired phone starts unpaired`() {
        assertEquals(PairingState.NotPaired, sequence().initial())
    }

    /**
     * **The regression that cost the owner their pairing.**
     *
     * Measured on their Pixel, 2026-07-29. A key that had been signing at 18:27:32 was classified
     * unusable minutes later; the pairing screen replaced it on the spot, and from 18:28:50 onward the
     * bridge refused every handshake with `peer certificate is not the pinned certificate`. The phone
     * had thrown away the only credential the laptop would accept, and no screen said so — the owner
     * was told their laptop was not answering.
     *
     * So: **opening this screen mints nothing.** The count is the assertion, not the state, because
     * the state was plausible on the broken code too.
     */
    @Test
    fun `arriving at the pairing screen never replaces the identity`() {
        val store = store().apply { write(profile()) }
        val phone = FakePhone().apply { cannotSign() }
        val before = phone.minted

        val state = sequence(phone = phone, store = store).initial()

        assertEquals(
            "the key was replaced by looking at it; the laptop's pin is now stale and nothing on the " +
                "phone can refresh it",
            before,
            phone.minted,
        )
        assertTrue("a key that will not sign must be reported", state is PairingState.IdentityCannotSign)
    }

    /**
     * **The omission this test exists for.**
     *
     * Removing the unlock flag changes keys minted from then on. The only key that mattered was minted
     * before, and an app update does not touch the keystore — so on the owner's own phone the fix was
     * invisible and they would have paired straight back onto the armed defect.
     *
     * And it must be REPORTED, not repaired: the key still signs, so replacing it silently would be
     * the original disaster with a different trigger.
     */
    @Test
    fun `a key made the old way is reported, and the phone still has it afterwards`() {
        val store = store().apply { write(profile()) }
        val phone = FakePhone().apply { madeTheOldWay() }
        val before = phone.minted

        val state = sequence(phone = phone, store = store).initial()

        assertTrue(
            "a key bound to a recent unlock was not reported, so the owner would pair onto it again",
            state is PairingState.IdentityMadeTheOldWay,
        )
        assertEquals("reporting it replaced it", before, phone.minted)
    }

    /**
     * **The route out has to exist while the key still works**, which is the owner's exact case.
     *
     * Offering the replacement only for a key that cannot sign is why there was no way forward: a key
     * made the old way signs right up until the moment it doesn't.
     */
    @Test
    fun `a key that still signs can still be replaced when the owner asks`() {
        val store = store().apply { write(profile()) }
        val phone = FakePhone().apply { madeTheOldWay() }
        val sequence = sequence(phone = phone, store = store)
        val before = phone.minted

        val state = sequence.onReplaceIdentity()

        assertEquals("the owner asked for a new key and did not get one", before + 1, phone.minted)
        assertTrue(state is PairingState.PhoneHalfOutstanding)
    }

    /** The owner is shown the certificate the LAPTOP still pins, which is the one they can check. */
    @Test
    fun `the unusable identity reports the fingerprint the laptop pinned`() {
        val store = store().apply { write(profile()) }
        val phone = FakePhone().apply { cannotSign() }

        val state = sequence(phone = phone, store = store).initial() as PairingState.IdentityCannotSign

        assertEquals(listOf("DEAD BEEF", "DEAD BEEF"), state.phoneFingerprint)
    }

    /** Confirming a scanned code is still not a licence to destroy a key. */
    @Test
    fun `confirming a code does not replace an identity that will not sign`() {
        val phone = FakePhone().apply { cannotSign() }
        val sequence = sequence(phone = phone)
        val (pixels, w, h) = codeFor(profile())
        val comparing = sequence.onImage(pixels, w, h) as PairingState.Comparing
        val before = phone.minted

        val state = sequence.onFingerprintConfirmed(comparing)

        assertEquals("the owner pressed confirm, not replace", before, phone.minted)
        assertTrue(state is PairingState.IdentityCannotSign)
    }

    /** And when they DO ask, it happens — otherwise a permanently dead key would have no way out. */
    @Test
    fun `asking for a new identity replaces it`() {
        val store = store().apply { write(profile()) }
        val phone = FakePhone().apply { cannotSign() }
        val sequence = sequence(phone = phone, store = store)
        val before = phone.minted

        val state = sequence.onReplaceIdentity()

        assertEquals("the owner asked for a new key and did not get one", before + 1, phone.minted)
        assertTrue(state is PairingState.PhoneHalfOutstanding)
    }

    /**
     * **A new key is a new pairing job, and the phone must stop claiming otherwise.**
     *
     * This is the second half of the same defect: the owner's phone re-minted and went on presenting
     * itself as paired, so nothing on it ever suggested the laptop needed to pin anything.
     */
    @Test
    fun `replacing the identity retracts the claim that the laptop has it`() {
        val store = store().apply { write(profile()) }
        val handover = FakeHandover(value = true)
        val phone = FakePhone().apply { cannotSign() }

        val state = sequence(phone = phone, handover = handover, store = store).onReplaceIdentity()

        assertTrue(
            "the phone still says the laptop has its certificate, about a certificate that no longer " +
                "exists",
            !handover.done(),
        )
        assertTrue("a replaced identity is a pairing half-done", state is PairingState.PhoneHalfOutstanding)
    }

    @Test
    fun `a decoded code waits for the owner and stores nothing`() {
        val (pixels, w, h) = codeFor(profile())

        val state = sequence().onImage(pixels, w, h)

        assertTrue("a decoded code must wait to be confirmed", state is PairingState.Comparing)
        assertNull("nothing may be stored before the owner has compared", store().read())
    }

    @Test
    fun `confirming is the only thing that stores`() {
        val seq = sequence()
        val (pixels, w, h) = codeFor(profile())
        val comparing = seq.onImage(pixels, w, h) as PairingState.Comparing

        // Was `is Paired` until REQ-0009 finding 1. Confirming stores the laptop and nothing more:
        // the laptop still has no idea this phone exists, so the honest next state is the half-done
        // one. The assertion this test exists for - that confirming is what writes - is unchanged.
        val next = seq.onFingerprintConfirmed(comparing)

        assertTrue(next is PairingState.PhoneHalfOutstanding)
        assertEquals(profile(), store().read())
    }

    /**
     * The case the whole comparison exists for: the code decoded perfectly and came from the wrong
     * machine. There is deliberately no continue-anyway.
     */
    @Test
    fun `rejecting the fingerprint stores nothing and returns to the start`() {
        val seq = sequence()
        val (pixels, w, h) = codeFor(profile())
        seq.onImage(pixels, w, h)

        assertEquals(PairingState.NotPaired, seq.onFingerprintRejected())
        assertNull("a rejected fingerprint must leave no pairing behind", store().read())
    }

    @Test
    fun `an image that is not a pairing code stores nothing`() {
        val blank = IntArray(600 * 600) { 0xFFFFFFFF.toInt() }

        assertEquals(PairingState.NotAPairingCode, sequence().onImage(blank, 600, 600))
        assertNull(store().read())
    }

    @Test
    fun `a paired phone starts paired and shows the fingerprint it pinned`() {
        val store = store()
        val handover = FakeHandover()
        val seq = sequence(store = store, handover = handover)
        val (pixels, w, h) = codeFor(profile())
        val half = seq.onFingerprintConfirmed(
            seq.onImage(pixels, w, h) as PairingState.Comparing,
        ) as PairingState.PhoneHalfOutstanding
        // The pairing is only finished once the owner says they pinned it on the laptop. Before this
        // line, resuming lands on the half-done state - which is the point of that state.
        seq.onHandoverDone(half)

        val restarted = sequence(store = store, handover = handover).initial()

        assertTrue(restarted is PairingState.Paired)
        assertEquals(
            "the fingerprint shown must be the one that was pinned",
            Fingerprint.rows(Fingerprint.of(certificate)),
            (restarted as PairingState.Paired).laptopFingerprint,
        )
    }

    @Test
    fun `the fingerprint shown is the laptop's certificate, not the payload`() {
        val (pixels, w, h) = codeFor(profile())

        val comparing = sequence().onImage(pixels, w, h) as PairingState.Comparing

        assertEquals(Fingerprint.rows(Fingerprint.of(certificate)), comparing.laptopFingerprint)
    }

    @Test
    fun `unpairing forgets the laptop`() {
        val seq = sequence()
        val (pixels, w, h) = codeFor(profile())
        seq.onFingerprintConfirmed(seq.onImage(pixels, w, h) as PairingState.Comparing)

        assertEquals(PairingState.NotPaired, seq.onUnpair())
        assertNull(store().read())
    }

    // --- REQ-0009 finding 1: "paired" must mean three things, not one -----------------------------

    @Test
    fun `confirming the laptop does not claim the laptop knows this phone`() {
        val (pixels, w, h) = codeFor(profile())
        val seq = sequence()
        val comparing = seq.onImage(pixels, w, h) as PairingState.Comparing

        val next = seq.onFingerprintConfirmed(comparing)

        assertTrue(
            "confirming the laptop landed on $next; the laptop has not been given this phone's " +
                "certificate yet, so this is not Paired",
            next is PairingState.PhoneHalfOutstanding,
        )
    }

    @Test
    fun `the half-done state carries this phone's fingerprint, which is what the owner compares`() {
        val (pixels, w, h) = codeFor(profile())
        val seq = sequence()
        val state = seq.onFingerprintConfirmed(seq.onImage(pixels, w, h) as PairingState.Comparing)

        val half = state as PairingState.PhoneHalfOutstanding
        assertEquals(listOf("AAAA BBBB", "CCCC DDDD"), half.phoneFingerprint)
        assertTrue(
            "the two fingerprints must be different values; showing the laptop's twice would be a " +
                "comparison that always succeeds",
            half.phoneFingerprint != half.laptopFingerprint,
        )
    }

    @Test
    fun `only the owner saying so completes the pairing`() {
        val (pixels, w, h) = codeFor(profile())
        val seq = sequence()
        val half = seq.onFingerprintConfirmed(
            seq.onImage(pixels, w, h) as PairingState.Comparing,
        ) as PairingState.PhoneHalfOutstanding

        assertTrue(seq.onHandoverDone(half) is PairingState.Paired)
    }

    /**
     * The defect finding 1 actually found: a stored profile alone reading as `Paired`.
     */
    @Test
    fun `a stored laptop alone does not read as paired`() {
        val store = store()
        store.write(profile())

        val resumed = sequence(store = store).initial()

        assertTrue(
            "a stored profile with no handover read as $resumed; that is finding 1 - the screen said " +
                "Paired while the laptop had never heard of this phone",
            resumed is PairingState.PhoneHalfOutstanding,
        )
    }

    @Test
    fun `a completed pairing resumes as paired rather than asking again`() {
        val store = store()
        store.write(profile())

        val resumed = sequence(store = store, handover = FakeHandover(value = true)).initial()

        assertTrue(resumed is PairingState.Paired)
    }

    @Test
    fun `unpairing forgets the handover too`() {
        val store = store()
        store.write(profile())
        val handover = FakeHandover(value = true)
        val seq = sequence(store = store, handover = handover)

        seq.onUnpair()

        assertTrue(
            "the handover outlived the pairing it belonged to, so re-pairing a different laptop " +
                "would resume as Paired for a laptop that has never heard of this phone",
            !handover.done(),
        )
    }

    // --- The identity is generated when pairing BEGINS ---------------------------------------------

    @Test
    fun `beginning pairing generates the identity`() {
        val phone = FakePhone()
        val seq = sequence(phone = phone)

        assertTrue("no identity before pairing begins", !phone.exists())
        seq.begin()
        assertTrue(
            "pairing began without generating an identity; generating it later means producing a " +
                "certificate while the owner is away from the laptop, with nowhere to send it",
            phone.exists(),
        )
    }

    @Test
    fun `a phone with no screen lock is told so rather than crashing`() {
        val phone = FakePhone().apply { refuse() }

        assertEquals(PairingState.NoScreenLock, sequence(phone = phone).begin())
    }

    @Test
    fun `a phone with no screen lock cannot be driven past it by confirming a code`() {
        val (pixels, w, h) = codeFor(profile())
        val phone = FakePhone()
        val store = store()
        val seq = sequence(phone = phone, store = store)
        val comparing = seq.onImage(pixels, w, h) as PairingState.Comparing
        phone.refuse()

        val next = seq.onFingerprintConfirmed(comparing)

        assertEquals(PairingState.NoScreenLock, next)
        assertNull(
            "the profile was stored for a phone that cannot hold a key, which would read as a " +
                "pairing that can never work",
            store.read(),
        )
    }
}
