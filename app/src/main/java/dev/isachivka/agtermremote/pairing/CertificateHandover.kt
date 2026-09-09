package dev.isachivka.agtermremote.pairing

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.util.Base64

/**
 * Getting this phone's certificate onto the laptop, and remembering that the owner did it.
 *
 * ### Why this exists
 *
 * REQ-0009 finding 1: the phone's certificate reached the laptop only through
 * `PhoneProvisioningScaffold` and `adb`, which meant **no phone could be paired without a cable and
 * an instrumentation runner**. That harness is the thing this file replaces.
 *
 * ### Why the share sheet rather than a QR
 *
 * The laptop has no camera pointed at the phone, so the direction that worked for laptop-to-phone
 * does not reverse. The alternatives were a certificate typed by hand — about 500 characters of
 * base64, which nobody will do — or a change to how the bridge decides what to trust.
 *
 * **The share sheet needs neither.** The owner is sitting at the laptop with the phone in their hand;
 * AirDrop puts a file in `~/Downloads` in seconds, and `bridgecert pin` already takes a PEM path. No
 * new trust model, no new bridge code, no cable.
 */
object CertificateHandover {

    /**
     * A directory of its OWN, not the one holding the paired profile.
     *
     * `PairedLaptop` stores the laptop's address and certificate in `files/pairing/`. Exposing that
     * directory to the FileProvider would put the address inside the shareable tree — and while a
     * grant is per-URI and per-intent, the narrower path costs nothing and removes the question.
     * Only this phone's own certificate is ever shared.
     *
     * **Must stay in step with the `pairing` entry in `res/xml/file_paths.xml`.** That coupling is
     * held by `PhoneProvisionsItselfTest.theCertificateBecomesSomethingTheOwnerCanSend`, which calls
     * [shareIntent] for real: `FileProvider.getUriForFile` throws for a path the provider does not
     * cover, so a mismatch fails there rather than under the owner's thumb on a button that does
     * nothing.
     */
    private const val DIRECTORY = "pairing/outgoing"

    /**
     * Named for what it is on the receiving end, **and for WHICH one it is**.
     *
     * It lands in the owner's Downloads among other people's files, so `phone.pem` a week later is a
     * mystery. Worse, under a fixed name a second share lands beside the first as `phone-cert-1.pem`
     * and the two are indistinguishable without opening them — and `bridgecert pin` will pin a
     * perfectly valid OLD certificate without complaint, so the mistake surfaces an hour later as a
     * refused handshake with nothing pointing at its cause.
     *
     * Measured, 2026-07-29: exactly that happened. A file holding the previous identity was handed to
     * the laptop while the phone had already minted a new one, and the only thing that could tell them
     * apart was a fingerprint nobody had in front of them.
     *
     * So the fingerprint is IN the name. Two files in Downloads now disagree visibly.
     */
    private fun fileName(fingerprint: List<String>): String =
        "phone-cert-${fingerprint.first().replace(" ", "")}.pem"

    /**
     * Writes this phone's certificate as PEM and returns an intent that offers to send it.
     *
     * The certificate is public — it is pinned, not secret — so sharing it through the system sheet
     * exposes nothing. What matters is that the *right* one arrives, which is why the screen shows
     * the fingerprint to check against what `bridgecert pin` prints.
     */
    fun shareIntent(context: Context): Intent {
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }

        // Read the certificate ONCE, here, and derive both the name and the bytes from that same
        // read. Fingerprinting a second call to certificate() would be a second read of the keystore,
        // and a name that could disagree with its own contents is the whole failure being fixed.
        val certificate = PhoneIdentity.certificate()
        val fingerprint = Fingerprint.rows(Fingerprint.of(certificate))

        // Exactly one certificate lives here at a time. Our own directory, so this deletes nothing of
        // the owner's - and it means the app can never offer a file that is not its current identity,
        // which is the same guarantee as rewriting but survives the name changing.
        directory.listFiles()?.forEach { it.delete() }

        val file = File(directory, fileName(fingerprint))
        file.writeText(pem(certificate.encoded))

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.pairing", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/x-pem-file"
            putExtra(Intent.EXTRA_STREAM, uri)
            // The receiving app gets read access to this one file for this one intent, and nothing
            // else in the directory.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * PEM, 64 characters per line.
     *
     * The line length is not decoration: OpenSSL and Go's `pem` decoder both accept long lines, but a
     * file the owner may open in a text editor should look like every other certificate they have
     * seen, and `bridgecert` prints one in exactly this shape.
     */
    private fun pem(der: ByteArray): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n"
    }
}

/**
 * Remembers that the owner said they pinned the certificate on the laptop.
 *
 * **A marker file rather than a preference**, so it sits beside the stored profile and is removed by
 * the same `clear()` that forgets the laptop. A handover that outlived the pairing it belonged to
 * would put the screen back into `Paired` for a laptop that has never heard of this phone.
 *
 * What is stored is the owner's assertion, not an observation: whether `bridgecert pin` ran is a fact
 * about the laptop and cannot be checked from here. It is remembered only so the screen does not ask
 * again every time it opens.
 */
class FileHandover(private val directory: File) : PairingSequence.Handover {

    private val marker: File get() = File(directory, "handover-done")

    override fun done(): Boolean = marker.exists()

    override fun markDone() {
        directory.mkdirs()
        marker.writeText("")
    }

    override fun clear() {
        marker.delete()
    }
}
