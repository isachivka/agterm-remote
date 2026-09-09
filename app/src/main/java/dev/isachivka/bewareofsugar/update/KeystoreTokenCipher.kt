package dev.isachivka.bewareofsugar.update

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableEntryException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a key that is generated inside `AndroidKeyStore` and cannot be exported.
 *
 * Deliberately not `androidx.security:security-crypto` / `EncryptedSharedPreferences`. That library's
 * last release (1.1.0, July 2025) deprecated all of its APIs "in favour of existing platform APIs and
 * direct use of Android Keystore" — Google's own migration advice is this class. Taking it would mean
 * a frozen dependency, plus Tink transitively, to avoid code whose correctness still has to be proven
 * by an on-device test either way, because Keystore does not exist on the JVM.
 *
 * Boring on purpose: one standard construction, a fresh random IV per write, no custom KDF and no key
 * wrapping. The only local invention is the container format, which is a single length byte.
 *
 * ### The key is an ordinary TEE key since REQ-0047, and `github_token_v1` is the one it was not
 *
 * The first key was asked for with `setIsStrongBoxBacked(true)` and `setUnlockedDeviceRequired(true)`.
 * It served for six weeks and then, three times in a few days in September 2026, the owner opened the
 * app to *"No token yet"* — the key had gone, the app had wiped the blob it could no longer read, and
 * he minted a new token each time. The phone's OTHER Keystore key, the pairing identity in
 * `PhoneIdentity`, is a plain TEE key with neither flag and has never been lost on the same phone.
 * That is the whole of the evidence, and it is enough to stop asking for the two properties the
 * failing key had and the surviving key lacks: StrongBox keys are reported lost sporadically on Pixels,
 * and unlocked-device-required keys are bound to the lock screen's own key material.
 *
 * So a new alias, [DEFAULT_ALIAS], gets the plain spec. The old alias is still READ: a blob written
 * under it decrypts if its key is still there, and [needsRewrap] then tells the store to write it
 * again under the new key. [destroyKey] deletes both.
 */
class KeystoreTokenCipher(
    private val alias: String = DEFAULT_ALIAS,
    /** The alias the first version wrote under. Null for a cipher with no past, which is what a test wants. */
    private val legacyAlias: String? = LEGACY_ALIAS,
) : TokenCipher {

    override fun encrypt(plaintext: String): String = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyForEncryption())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv

        // [1 byte IV length][IV][ciphertext+tag]. Self-describing, so a future change to the IV
        // length cannot silently misread blobs written by this version.
        val packed = ByteArray(1 + iv.size + ciphertext.size)
        packed[0] = iv.size.toByte()
        iv.copyInto(packed, destinationOffset = 1)
        ciphertext.copyInto(packed, destinationOffset = 1 + iv.size)
        Base64.getEncoder().encodeToString(packed)
    } catch (e: GeneralSecurityException) {
        // KeyStore.getInstance and getEntry declare KeyStoreException; the cipher declares the rest.
        throw TokenEncryptionFailed("the phone would not encrypt it", e)
    } catch (e: ProviderException) {
        // Key generation failing outright.
        throw TokenEncryptionFailed("the phone would not make a key for it", e)
    } catch (e: IOException) {
        // KeyStore.load.
        throw TokenEncryptionFailed("the keystore could not be opened", e)
    }

    override fun decrypt(encoded: String): String {
        val (iv, ciphertext) = unpack(encoded)
        // The current key first, then the one the first version wrote under. A blob under the old
        // key fails the current key's tag check and is then tried against its own; a blob under the
        // current key never reaches the old one.
        val keys = keysForDecryption()
        if (keys.isEmpty()) throw TokenDecryptionFailed("no key under the alias", permanent = true)
        var lastBadTag: TokenDecryptionFailed? = null
        for (key in keys) {
            try {
                return open(key, iv, ciphertext)
            } catch (e: TokenDecryptionFailed) {
                if (e.cause is AEADBadTagException && keys.size > 1) {
                    lastBadTag = e
                    continue
                }
                throw e
            }
        }
        throw lastBadTag ?: TokenDecryptionFailed("no key under the alias", permanent = true)
    }

    /**
     * True when [encoded] does not open under the CURRENT key — it was written under the legacy one,
     * or under a key that is gone. A store that gets true after a successful [decrypt] writes the
     * plaintext again, which puts it under the current key.
     */
    override fun needsRewrap(encoded: String): Boolean {
        val current = keyUnder(alias) ?: return true
        val (iv, ciphertext) = unpack(encoded)
        return try {
            open(current, iv, ciphertext)
            false
        } catch (e: TokenDecryptionFailed) {
            true
        }
    }

    /**
     * Best effort, and deliberately silent on failure.
     *
     * `KeyStore.deleteEntry` declares `KeyStoreException`, and this runs on the Remove path — which
     * exists to end calmly. If a key cannot be deleted, what is left behind is a key with no
     * ciphertext to match it: unusable to anyone, overwritten by the next save, and not worth
     * crashing the screen whose whole job is to degrade politely.
     */
    override fun destroyKey() {
        for (name in listOfNotNull(alias, legacyAlias)) {
            try {
                keyStore().deleteEntry(name)
            } catch (e: GeneralSecurityException) {
                // Nothing to do and nothing to say: the ciphertext is what mattered.
            } catch (e: ProviderException) {
                // A failing provider, same reasoning.
            } catch (e: IOException) {
                // KeyStore.load, same reasoning.
            }
        }
    }

    private fun unpack(encoded: String): Pair<ByteArray, ByteArray> {
        val packed = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw TokenDecryptionFailed("stored blob is not valid Base64", permanent = true, cause = e)
        }
        val ivLength = packed.firstOrNull()?.toInt() ?: -1
        if (ivLength <= 0 || packed.size <= 1 + ivLength) {
            throw TokenDecryptionFailed("stored blob is truncated", permanent = true)
        }
        return packed.copyOfRange(1, 1 + ivLength) to packed.copyOfRange(1 + ivLength, packed.size)
    }

    private fun open(key: SecretKey, iv: ByteArray, ciphertext: ByteArray): String = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    } catch (e: GeneralSecurityException) {
        throw keystoreFailure(e)
    } catch (e: ProviderException) {
        // Not a GeneralSecurityException - it is a RuntimeException, so it needs saying twice.
        throw keystoreFailure(e)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /**
     * The keys for reading, current first, legacy second, each present only if there is one under its
     * alias.
     *
     * `getEntry` declares `UnrecoverableEntryException`, `KeyStoreException` and
     * `NoSuchAlgorithmException`, and `load` declares `IOException` — none of which Kotlin will
     * warn about. Every one of them is translated here, because the caller above this is a stored
     * token that will not decrypt: a line of copy, never a crash.
     */
    private fun keysForDecryption(): List<SecretKey> =
        listOfNotNull(keyUnder(alias), legacyAlias?.let { keyUnder(it) })

    private fun keyUnder(name: String): SecretKey? = try {
        (keyStore().getEntry(name, null) as? KeyStore.SecretKeyEntry)?.secretKey
    } catch (e: GeneralSecurityException) {
        throw keystoreFailure(e)
    } catch (e: ProviderException) {
        throw keystoreFailure(e)
    } catch (e: IOException) {
        throw TokenDecryptionFailed("the keystore could not be opened", permanent = false, cause = e)
    }

    /**
     * The key for writing, under the current alias, creating one only when there is genuinely none.
     *
     * Note what it deliberately does not do: if `getEntry` *throws*, that propagates rather than
     * falling through to [generateKey]. Generating a fresh key over one that exists but could not be
     * read would leave any blob already on disk encrypted under a key that is now gone.
     */
    private fun keyForEncryption(): SecretKey =
        (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generateKey()

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(spec())
        return generator.generateKey()
    }

    /**
     * The spec the pairing identity has lived with for two months on the owner's phone, and nothing
     * the lost key had that it does not: no StrongBox, no unlocked-device requirement, no user
     * authentication. Hardware-backed in the TEE on every device this app targets.
     */
    private fun spec(): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            // A fresh IV per encryption, enforced by the platform rather than by our own discipline.
            .setRandomizedEncryptionRequired(true)
            .build()

    internal companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        /** The plain TEE key — REQ-0047. */
        const val DEFAULT_ALIAS = "github_token_v2"
        /** The StrongBox, unlocked-device-required key of 2026-07-27 to 2026-09-06. Read, never written. */
        const val LEGACY_ALIAS = "github_token_v1"
        const val KEY_SIZE_BITS = 256
        const val TAG_LENGTH_BITS = 128
    }
}

/**
 * Which Keystore failures mean the stored blob is dead, and which mean today is a bad day.
 *
 * The flag is COPY since REQ-0047, not a trigger: nothing is wiped on the strength of it any more.
 * *Permanent* tells the owner to paste a new token rather than wait; *transient* tells him the one he
 * has is still there and may read fine after an unlock or a reboot. Being wrong in the permanent
 * direction costs him a paste; being wrong the other way costs him nothing but a wait, because the
 * blob stays either way.
 *
 * Separated from the class so the decision can be unit tested on the JVM, where a real Keystore does
 * not exist.
 */
internal fun keystoreFailure(cause: Exception): TokenDecryptionFailed = when (cause) {
    // Wrong key, or the bytes were altered. No key will ever verify this tag.
    is AEADBadTagException ->
        TokenDecryptionFailed("authentication tag did not verify", permanent = true, cause = cause)

    // The key is gone for good: new biometric enrolment, screen lock removed.
    is KeyPermanentlyInvalidatedException ->
        TokenDecryptionFailed("the key was invalidated", permanent = true, cause = cause)

    // The entry exists but its material cannot be recovered. UnrecoverableKeyException is a subclass
    // of this - catching only the subclass, as this did before review, let the parent through and
    // crashed the one path that must not crash.
    is UnrecoverableEntryException ->
        TokenDecryptionFailed("the key entry is unrecoverable", permanent = true, cause = cause)

    // The keystore itself could not be used - not initialised, or the provider refused. That says
    // nothing about the blob.
    is KeyStoreException ->
        TokenDecryptionFailed("the keystore could not be used", permanent = false, cause = cause)

    // How AndroidKeyStore surfaces a provider that is failing or misbehaving. A RuntimeException, so
    // it slips past any catch of GeneralSecurityException. Transient: hardware failing right now is
    // not evidence that the ciphertext is dead.
    is ProviderException ->
        TokenDecryptionFailed("the keystore provider is failing", permanent = false, cause = cause)

    // Everything else: an InvalidKeyException or an IllegalBlockSizeException wrapping a
    // KeyStoreException, which is how a key that is temporarily unusable surfaces. Transient by
    // construction.
    else ->
        TokenDecryptionFailed("the key could not be used right now", permanent = false, cause = cause)
}
