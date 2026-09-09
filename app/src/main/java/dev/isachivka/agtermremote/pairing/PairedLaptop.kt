package dev.isachivka.agtermremote.pairing

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * The laptop this phone is paired with, on disk.
 *
 * ### Why this is stored when REQ-0006 says nothing is
 *
 * REQ-0006 forbids persisting a reachability verdict, in any form, and this writes a file. The two
 * are not in tension and the distinction is worth stating because a later reader will otherwise see a
 * rule being bent.
 *
 * **A verdict is a statement about the network the phone was on when it was made**, so it expires the
 * moment that stops being true — yesterday's verdict on today's network is precisely the lie that
 * requirement exists to prevent. **An identity is not a statement about anything.** It is what the
 * phone is and which laptop it belongs to, and it has to survive a restart or the owner re-pairs
 * daily.
 *
 * ### What is here, and what is deliberately not
 *
 * The bridge's certificate, the host, the port and the stream kind. All public: none of it is a
 * credential, and there is nothing here an attacker with the file could authenticate with.
 *
 * The phone's private key is not among them, and could not be — it is in the hardware keystore and
 * has no extractable form. That is the property that makes storing this uninteresting.
 *
 * **Excluded from backup**, and not for tidiness. Keystore keys do not restore to another device, so
 * a restored profile would produce an app that shows a fingerprint, looks paired, and cannot complete
 * a handshake. A broken state wearing a working one's clothes is worse than an unpaired one, which at
 * least tells the owner what to do.
 */
class PairedLaptop(private val directory: File) {

    constructor(context: Context) : this(File(context.filesDir, DIRECTORY))

    private val file: File get() = File(directory, FILE)

    /** The paired laptop, or null when this phone has never been paired. */
    fun read(): ConnectionProfile? {
        val bytes = try {
            if (!file.isFile) return null
            file.readBytes()
        } catch (e: IOException) {
            return null
        }
        // A file that will not decode is treated as no pairing at all rather than as an error. The
        // remedy is the same - pair again - and a half-read profile must never reach a connection.
        return ProfileCodec.decode(bytes)
    }

    /**
     * Replaces the pairing.
     *
     * Written through a temporary file and renamed, so a process death mid-write leaves either the
     * old pairing or none — never a truncated one that decodes to something plausible. The same
     * reasoning as the datastore exclusion in the backup rules: it is the partial state that is
     * dangerous, not the absent one.
     */
    fun write(profile: ConnectionProfile) {
        directory.mkdirs()
        val temporary = File(directory, "$FILE.tmp")
        temporary.writeBytes(ProfileCodec.encode(profile))
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("could not store the pairing")
        }
    }

    /**
     * Forgets the laptop.
     *
     * Does **not** touch [PhoneIdentity]. Re-pairing with the same laptop should not need a new
     * identity re-pinned on the far side; unpairing is about which laptop, not about who this phone
     * is. Discarding the identity is a separate, louder act because it invalidates a certificate the
     * owner pinned by hand.
     */
    fun clear() {
        file.delete()
    }

    val isPaired: Boolean get() = read() != null

    companion object {
        /**
         * Matches the `pairing` path excluded in `data_extraction_rules.xml` and `backup_rules.xml`.
         * `BackupRulesTest` fails if that exclusion is removed; this constant is what it protects.
         */
        const val DIRECTORY = "pairing"
        private const val FILE = "laptop.bin"
    }
}
