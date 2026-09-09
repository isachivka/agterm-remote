package dev.isachivka.bewareofsugar.update

/**
 * Encrypts and decrypts the token. One implementation on the device ([KeystoreTokenCipher]), one
 * fake in the unit tests.
 */
interface TokenCipher {

    /**
     * @return an opaque, storable string.
     * @throws TokenEncryptionFailed on any failure — callers must not see raw crypto types, and a
     * phone that will not encrypt is a state the owner is told about, not a crash.
     */
    fun encrypt(plaintext: String): String

    /** @throws TokenDecryptionFailed always, on any failure — callers must not see raw crypto types. */
    fun decrypt(encoded: String): String

    /** Deletes the key, so anything still encrypted with it is permanently unreadable. */
    fun destroyKey()

    /**
     * True when [encoded] was not written under the current key and should be written again — the
     * migration off the legacy key, REQ-0047. Asked only after a successful [decrypt]. False for a
     * cipher that has no notion of an older key.
     */
    fun needsRewrap(encoded: String): Boolean = false
}

/**
 * @param permanent true when this blob can never be decrypted again and should be wiped. False when
 * the failure may be transient and the stored blob must be left alone — see [StoredToken.Unreadable].
 *
 * The message never contains any part of the plaintext; there is a test for that.
 */
class TokenDecryptionFailed(
    message: String,
    val permanent: Boolean,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The phone would not encrypt the token, so it was not stored.
 *
 * There is no "permanent" flag here on purpose: nothing was written, so there is nothing to wipe and
 * nothing to classify. The owner is told the token was good and the phone would not keep it, which
 * is the one thing that stops them going off to mint a replacement for a problem that was never the
 * token's.
 *
 * The message never contains any part of the plaintext; there is a test for that.
 */
class TokenEncryptionFailed(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
