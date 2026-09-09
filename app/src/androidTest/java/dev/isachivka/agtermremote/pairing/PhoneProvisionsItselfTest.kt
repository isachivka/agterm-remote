package dev.isachivka.agtermremote.pairing

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * **The acceptance test for the phone provisioning itself.**
 *
 * The harness this replaced needed a cable, `adb` and an instrumentation runner, which meant the
 * owner could not pair a phone at all — including re-pairing one that had been paired before.
 *
 * ### What went with the certificate handover
 *
 * Half of this file used to assert that the phone's certificate became a `.pem` the owner could
 * AirDrop to the Mac, and that the FileProvider would hand it over: the file name, the share intent,
 * the URI grant, and that a share after a re-mint carried the new certificate rather than the old.
 * **Enrolment deleted the errand, so it deleted those assertions.** The certificate now travels over
 * the connection the pairing code authenticates, which `EnrollmentTest` proves against a real bridge,
 * and this application shares no file with anything.
 *
 * What is left is the half that is still true and still needs hardware: the app mints its own
 * identity, on the real keystore, and does not replace one that works.
 */
class PhoneProvisionsItselfTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun start_from_nothing() {
        PhoneIdentity.clear()
        // The directory the retired share wrote into, cleared so this test starts from nothing.
        //
        // **Nothing in the application removes it any more**, and that is deliberate rather than an
        // oversight: the code that swept up after retired routes went with the routes, and this
        // application has never shipped a release, so no phone outside this repository has ever
        // written either directory. If one is ever released with the old share in it, the sweep comes
        // back - and it belongs in the app, not here.
        File(context.filesDir, "pairing/outgoing").deleteRecursively()
    }

    /**
     * The app mints its own identity, with no harness involved.
     *
     * `PhoneIdentity.certificate()` had exactly one production caller before the app grew one —
     * `keyManager()` — and nothing in the shipping app ever called the half that generates.
     */
    @Test
    fun theAppGeneratesItsOwnIdentity() {
        assertNull("precondition: no identity", PhoneIdentity.existing())

        val certificate = PhoneIdentity.certificate()

        assertNotNull("no certificate in the keystore after provisioning", PhoneIdentity.existing())
        assertEquals(
            "the certificate handed back is not the one the keystore holds",
            Fingerprint.of(certificate),
            Fingerprint.of(PhoneIdentity.existing()!!),
        )
    }

    /**
     * Provisioning is idempotent: a phone that already has a usable identity keeps it.
     *
     * **This is the one that matters, and it is the lesson of 2026-07-29.** Opening the pairing screen
     * must not invalidate a pairing that works — the Mac pinned a specific certificate, and replacing
     * it costs the pairing with nothing on the phone able to say so. The enrolment path calls exactly
     * this method, and it calls nothing that deletes.
     */
    @Test
    fun provisioningTwiceKeepsTheSameIdentity() {
        val first = Fingerprint.of(PhoneIdentity.certificate())
        val second = Fingerprint.of(PhoneIdentity.certificate())

        assertEquals("provisioning replaced a usable identity", first, second)
    }

    /** And the key behind it is one this device can actually sign with. */
    @Test
    fun theIdentityCanSign() {
        PhoneIdentity.certificate()

        assertTrue(
            "a key that cannot sign pairs and then cannot reach the API",
            PhoneIdentity.signingState() == SigningState.Ready,
        )
    }
}
