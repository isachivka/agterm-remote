package dev.isachivka.bewareofsugar.update

/**
 * Where the owner's token lives between app launches.
 *
 * Split from its implementation so the storage logic can be unit tested on the JVM against a fake
 * cipher: the real one needs `AndroidKeyStore`, which does not exist off-device.
 */
interface TokenStore {

    suspend fun read(): StoredToken

    /**
     * @param validatedAtEpochSeconds when the API last accepted this token. Passed in rather than
     * read from a clock so the caller is testable, and kept because iteration 2 needs to tell
     * "this token has been working and just stopped" from "this token was never any good".
     *
     * @return false when the phone would not store it. It returns rather than throws because this is
     * reached at the worst possible moment - the owner has pasted a good token and GitHub has just
     * said yes - and "the app died while saving it" is precisely the updater-that-only-works-when-
     * everything-is-fine failure REQ-0003 is written against. A false here is a state with copy.
     *
     * On false, nothing has been written: an already-stored token is left exactly as it was.
     */
    suspend fun save(token: GitHubToken, validatedAtEpochSeconds: Long): Boolean

    suspend fun clear()

    /** 0 when no check has ever run. Persisted, because "once a day" has to survive a restart. */
    suspend fun lastCheckedAt(): Long

    suspend fun recordChecked(atEpochSeconds: Long)

    /**
     * The tag the last **successful** check found newer than what is installed, or null if it found
     * nothing newer.
     *
     * This exists because the app remembered *when* it last checked and not *what it found*, so an
     * update announced before a restart went unannounced after one — and within the same day the
     * once-a-day rule stopped it rediscovering the answer it had already had. REQ-0004's launcher
     * puts a banner on the home screen, which turned that from a subtlety into the first thing the
     * owner sees.
     *
     * **It is a cache and never a claim.** Nothing here is trusted on its own: it is revalidated
     * against the installed version every time it is read, it goes when the token goes, a failed
     * check never writes it, and it never answers the question of whether a check is due.
     * `UpdateNotice` is where those rules live.
     */
    suspend fun lastFoundTag(): String?

    /** @param tag null when a successful check found nothing newer, which forgets the previous one. */
    suspend fun recordFound(tag: String?)
}

sealed interface StoredToken {

    /** Nothing stored: first run, or the owner removed it. */
    data object None : StoredToken

    data class Present(val token: GitHubToken, val validatedAtEpochSeconds: Long) : StoredToken

    /**
     * Something is stored and could not be decrypted. **It is still there** — REQ-0047 ended the
     * version of this that wiped it. The owner is told what happened and since when, and decides.
     *
     * @param permanent the cipher's reading of the failure: true when no key will ever open this blob
     * (the key is gone or the bytes were altered), false when the keystore merely would not answer
     * right now. Copy, not a trigger — nothing is deleted on the strength of it.
     * @param reason the cipher's own fixed sentence, or an exception class name. Never the provider's
     * words and never a byte of the token; shown to the owner so the next report names the cause.
     * @param sinceEpochSeconds when this blob first failed to read, as recorded beside it. 0 when the
     * record itself could not be read.
     */
    data class Unreadable(
        val permanent: Boolean,
        val reason: String,
        val sinceEpochSeconds: Long,
    ) : StoredToken
}
