package dev.isachivka.agtermremote.pairing

import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * **The acceptance test for the phone provisioning itself**, which was agreed as:
 * `PhoneProvisioningScaffold` can be deleted without anything breaking.
 *
 * That harness was the only way a phone ever got an identity, in this project's entire history. It
 * needed a cable, `adb` and an instrumentation runner, which meant the owner could not pair a phone
 * at all — including re-pairing one that had been paired before.
 *
 * The scaffold is deleted. This asserts the app does the same job by itself: **generates its own key,
 * writes its own certificate, and produces something the owner can actually send to the laptop.**
 *
 * Everything here runs against the real keystore and the real FileProvider. There is no seam and that
 * is deliberate — a stubbed keystore would prove the code compiles, and the thing that was broken for
 * a milestone was whether the platform would do it.
 */
class PhoneProvisionsItselfTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun start_from_nothing() {
        PhoneIdentity.clear()
        File(context.filesDir, "pairing/outgoing").deleteRecursively()
    }

    /**
     * The app mints its own identity, with no harness involved.
     *
     * `PhoneIdentity.certificate()` had exactly one production caller before this — `keyManager()` —
     * and nothing in the shipping app ever called the half that generates. This is that gap closed.
     */
    @Test
    fun theAppGeneratesItsOwnIdentity() {
        assertEquals("precondition: no identity", null, PhoneIdentity.existing())

        val state = KeystorePhoneHalf().provision()

        assertTrue("provisioning returned $state on a device with a screen lock",
            state is PhoneHalf.Provisioning.Ready)
        assertNotNull("no certificate in the keystore after provisioning", PhoneIdentity.existing())
    }

    /**
     * Provisioning is idempotent: a phone that already has a usable identity keeps it.
     *
     * Opening the pairing screen and backing out must not invalidate a pairing that works — the
     * laptop pinned a specific certificate, and replacing it here would silently break the thing the
     * owner came to check on.
     */
    @Test
    fun provisioningTwiceKeepsTheSameIdentity() {
        val first = (KeystorePhoneHalf().provision() as PhoneHalf.Provisioning.Ready).fingerprint
        val second = (KeystorePhoneHalf().provision() as PhoneHalf.Provisioning.Ready).fingerprint

        assertEquals("provisioning replaced a usable identity", first, second)
    }

    /**
     * The certificate becomes a file the owner can send, and the FileProvider will hand it over.
     *
     * **This is the assertion that the `file_paths.xml` entry is right.** `getUriForFile` throws
     * `IllegalArgumentException` for a path the provider does not cover, so a mismatch between
     * `CertificateHandover`'s directory and the XML fails here rather than under the owner's thumb on
     * a button that does nothing.
     */
    @Test
    fun theCertificateBecomesSomethingTheOwnerCanSend() {
        val intent = CertificateHandover.shareIntent(context)

        assertEquals(Intent.ACTION_SEND, intent.action)
        val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        assertNotNull("no file URI in the share intent", uri)
        assertTrue(
            "the receiving app was not granted read access, so the file would arrive empty",
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )

        val pem = sharedFile().readText()
        assertTrue("not a PEM certificate:\n$pem", pem.startsWith("-----BEGIN CERTIFICATE-----"))
        assertTrue("not a PEM certificate:\n$pem", pem.trimEnd().endsWith("-----END CERTIFICATE-----"))
    }

    /**
     * **A share after a re-mint must yield the NEW certificate, never the one before it.**
     *
     * The write path rebuilds the file from the live keystore on every share, which is correct — and it
     * is correct by one line that a reasonable-looking edit could turn into a skip-if-exists. Nothing
     * checked it, and on 2026-07-29 an hour went into asking whether a stale file was why the owner's
     * laptop had a certificate the phone was not presenting. It was not, but the question could only be
     * answered by reading the source, which is the definition of a property with no test.
     *
     * The fingerprint is also in the FILENAME now, so two of these in a Downloads folder disagree
     * visibly rather than becoming `phone-cert.pem` and `phone-cert-1.pem`.
     */
    @Test
    fun aShareAfterANewKeyCarriesTheNewCertificate() {
        CertificateHandover.shareIntent(context)
        val first = sharedFile()
        val firstBytes = first.readBytes()

        PhoneIdentity.clear()
        CertificateHandover.shareIntent(context)
        val second = sharedFile()

        assertFalse(
            "the share handed out the certificate from before the re-mint, so the laptop would pin " +
                "a key this phone no longer has",
            second.readBytes().contentEquals(firstBytes),
        )
        assertArrayEquals(
            "the shared bytes are not the keystore's current certificate",
            PhoneIdentity.certificate().encoded,
            derOf(second.readText()),
        )
        assertNotEquals(
            "both shares used one filename, so they are indistinguishable once downloaded",
            first.name,
            second.name,
        )
        assertEquals(
            "the old file is still there; the app can offer a certificate it does not use",
            1,
            File(context.filesDir, "pairing/outgoing").listFiles()!!.size,
        )
    }

    /** The one file the outgoing directory is allowed to contain. */
    private fun sharedFile(): File =
        File(context.filesDir, "pairing/outgoing").listFiles()!!.single()

    private fun derOf(pem: String): ByteArray = java.util.Base64.getMimeDecoder().decode(
        pem.substringAfter("-----BEGIN CERTIFICATE-----")
            .substringBefore("-----END CERTIFICATE-----"),
    )

    /**
     * And the part that matters to the laptop: **the PEM is the key the phone will actually present.**
     *
     * A file that is a valid certificate but not *this phone's* would pin something that can never
     * connect — and it would look exactly like success until the first handshake. So the bytes are
     * compared against the keystore's own, not merely parsed.
     */
    @Test
    fun theSharedCertificateIsThisPhonesOwn() {
        CertificateHandover.shareIntent(context)

        val pem = File(File(context.filesDir, "pairing/outgoing"), "phone-cert.pem").readText()
        val der = java.util.Base64.getMimeDecoder().decode(
            pem.replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .trim(),
        )

        assertTrue(
            "the shared certificate is not the one in the keystore, so pinning it would pin a " +
                "phone that does not exist",
            der.contentEquals(PhoneIdentity.existing()!!.encoded),
        )
    }

    /**
     * The fingerprint the screen shows is the fingerprint of the file being sent.
     *
     * The owner compares this against what `bridgecert pin` prints. If the two were computed from
     * different things, the comparison would be theatre — and it is the only defence against pinning
     * the wrong file out of a Downloads folder.
     */
    @Test
    fun theFingerprintOnScreenDescribesTheFileBeingSent() {
        val shown = (KeystorePhoneHalf().provision() as PhoneHalf.Provisioning.Ready).fingerprint
        CertificateHandover.shareIntent(context)

        val ofFile = Fingerprint.rows(Fingerprint.of(PhoneIdentity.existing()!!))

        assertEquals("the screen and the file disagree about which phone this is", ofFile, shown)
    }
}
