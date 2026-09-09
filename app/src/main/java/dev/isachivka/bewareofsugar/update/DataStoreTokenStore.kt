package dev.isachivka.bewareofsugar.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException

/**
 * The token's ciphertext, in one DataStore preference.
 *
 * What is on disk is `Base64(IV ‖ ciphertext)` and nothing else — no plaintext ever reaches this
 * layer's storage, and the key that would decrypt it never leaves the Keystore. A debug build allows
 * `run-as` to read this file; what it yields is the blob, which is useless off the device.
 *
 * On the in-memory side the token is a plain `String`, which cannot be zeroed and therefore survives
 * in the heap until it is collected — it would appear in a heap dump. Considered and rejected:
 * switching to `CharArray` is mostly theatre on Android, since the value crosses `String` boundaries
 * at the Compose text field, at the OkHttp header and at Base64 anyway. The surface that actually
 * writes the token to disk is `savedInstanceState`, and that one is closed by keeping the in-progress
 * value in a ViewModel rather than in `rememberSaveable`.
 *
 * ### Nothing here wipes a token — REQ-0047
 *
 * Until 2026-09-06 a decryption failure the cipher called *permanent* deleted the blob and the key,
 * so the next read said "no token" as if the owner had never pasted one. The classification was
 * being made from one `getEntry` returning null — and the key that went missing was a StrongBox key
 * on a phone that had just started losing it. A token destroyed on the strength of a keystore's bad
 * afternoon is exactly the loss the owner reported three times.
 *
 * Now a blob that will not read STAYS, with the reason and the time it first failed recorded beside
 * it, so the token screen can say what happened. If the key comes back, so does the token; if it
 * does not, the owner reads why and pastes a new one, which overwrites both. Remove is still his.
 */
class DataStoreTokenStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : TokenStore {

    override suspend fun read(): StoredToken {
        // A read that cannot even open the file is the same story as one that cannot decrypt: the
        // owner is told, and nothing is destroyed on the strength of it.
        val preferences = try {
            dataStore.data.first()
        } catch (e: IOException) {
            return StoredToken.Unreadable(permanent = false, reason = "the token file could not be read", sinceEpochSeconds = 0L)
        }
        val blob = preferences[KEY_BLOB] ?: return StoredToken.None
        val plaintext = try {
            cipher.decrypt(blob)
        } catch (e: TokenDecryptionFailed) {
            return unreadable(preferences, e.permanent, e.message ?: "the token could not be decrypted")
        } catch (e: GeneralSecurityException) {
            // A backstop, not the design. The cipher is supposed to translate every failure into a
            // classified TokenDecryptionFailed; if one ever escapes untranslated, reading a token
            // still must not crash the screen. Untranslated means unclassified, so it is not called
            // permanent.
            return unreadable(preferences, permanent = false, reason = e.javaClass.simpleName)
        } catch (e: ProviderException) {
            // A RuntimeException, so no catch of GeneralSecurityException covers it.
            return unreadable(preferences, permanent = false, reason = e.javaClass.simpleName)
        } catch (e: IOException) {
            return unreadable(preferences, permanent = false, reason = "the keystore could not be opened")
        }

        val present = StoredToken.Present(
            token = GitHubToken(plaintext),
            validatedAtEpochSeconds = preferences[KEY_VALIDATED_AT] ?: 0L,
        )
        // It read. A record of an earlier failure is history now, and a blob under the legacy key is
        // written again under the current one - see KeystoreTokenCipher.needsRewrap.
        if (preferences[KEY_UNREADABLE_SINCE] != null) forgetFailure()
        if (runCatching { cipher.needsRewrap(blob) }.getOrDefault(false)) {
            save(present.token, present.validatedAtEpochSeconds)
        }
        return present
    }

    /**
     * Records the failure and reports it. The blob is untouched.
     *
     * The reason written is the cipher's own fixed sentence or an exception class name — never the
     * provider's message, which could carry anything, and never a byte of the token.
     */
    private suspend fun unreadable(preferences: Preferences, permanent: Boolean, reason: String): StoredToken {
        val since = preferences[KEY_UNREADABLE_SINCE] ?: nowEpochSeconds()
        try {
            dataStore.edit {
                it[KEY_UNREADABLE_SINCE] = since
                it[KEY_UNREADABLE_REASON] = reason
            }
        } catch (e: IOException) {
            // The report still goes to the screen; only the memory of it is lost.
        }
        return StoredToken.Unreadable(permanent = permanent, reason = reason, sinceEpochSeconds = since)
    }

    private suspend fun forgetFailure() {
        try {
            dataStore.edit {
                it.remove(KEY_UNREADABLE_SINCE)
                it.remove(KEY_UNREADABLE_REASON)
            }
        } catch (e: IOException) {
            // Harmless: the next successful read tries again.
        }
    }

    override suspend fun save(token: GitHubToken, validatedAtEpochSeconds: Long): Boolean {
        // Everything that can fail happens before anything is written, so a failure here cannot
        // leave a half-written blob that a later read would report as a corrupt token.
        val blob = try {
            cipher.encrypt(token.value)
        } catch (e: TokenEncryptionFailed) {
            return false
        } catch (e: GeneralSecurityException) {
            // Backstop, as in read(): an untranslated failure still must not crash the save path.
            return false
        } catch (e: ProviderException) {
            return false
        } catch (e: IOException) {
            return false
        }

        // What goes to disk has to come back. Encrypting successfully is not the same as having
        // written something readable - a key that changed underneath us would pass the step above
        // and fail every read afterwards, which surfaces as "your saved token is corrupt" for a
        // token that was never at fault.
        val readable = try {
            cipher.decrypt(blob) == token.value
        } catch (e: TokenDecryptionFailed) {
            false
        } catch (e: GeneralSecurityException) {
            false
        } catch (e: ProviderException) {
            false
        }
        if (!readable) return false

        return try {
            dataStore.edit { preferences ->
                preferences[KEY_BLOB] = blob
                preferences[KEY_VALIDATED_AT] = validatedAtEpochSeconds
                // A new blob has no failure history.
                preferences.remove(KEY_UNREADABLE_SINCE)
                preferences.remove(KEY_UNREADABLE_REASON)
            }
            true
        } catch (e: IOException) {
            // DataStore writes through a temporary file and renames, so a failed write leaves what
            // was there before rather than a partial file.
            false
        }
    }

    override suspend fun lastCheckedAt(): Long = try {
        dataStore.data.first()[KEY_LAST_CHECKED] ?: 0L
    } catch (e: IOException) {
        // Unreadable means "no record of a check", which makes the next check due. Erring towards
        // one extra API call rather than towards never checking again.
        0L
    }

    override suspend fun recordChecked(atEpochSeconds: Long) {
        try {
            dataStore.edit { it[KEY_LAST_CHECKED] = atEpochSeconds }
        } catch (e: IOException) {
            // The check already happened; failing to write when it happened is not worth a state.
            // The cost is one extra check on the next launch.
        }
    }

    override suspend fun lastFoundTag(): String? = try {
        dataStore.data.first()[KEY_LAST_FOUND_TAG]
    } catch (e: IOException) {
        // Unreadable means "nothing remembered", which shows no notice. Erring towards saying
        // nothing rather than towards announcing an update on the strength of a file we could not
        // read.
        null
    }

    override suspend fun recordFound(tag: String?) {
        try {
            dataStore.edit { preferences ->
                if (tag == null) preferences.remove(KEY_LAST_FOUND_TAG) else preferences[KEY_LAST_FOUND_TAG] = tag
            }
        } catch (e: IOException) {
            // Same reasoning as recordChecked: the check happened, and failing to remember it costs
            // a banner that reappears on the next successful check.
        }
    }

    /** The owner's Remove, and nothing else calls this — REQ-0047. */
    override suspend fun clear() {
        // The key goes first, deliberately. Destroying it is what actually makes the stored bytes
        // unusable, so if the file write below fails, what is left behind is undecryptable rather
        // than a token that is still perfectly good after the owner asked for it to be removed.
        cipher.destroyKey()
        try {
            dataStore.edit { it.clear() }
        } catch (e: IOException) {
            // Removal already happened in the only sense that matters.
        }
    }

    companion object {
        /**
         * Produces `<filesDir>/datastore/github_token.preferences_pb`. That path is excluded from
         * cloud backup and device transfer in `res/xml/data_extraction_rules.xml`, and
         * `BackupRulesTest` fails if that exclusion is ever removed.
         */
        const val DATASTORE_NAME = "github_token"

        private val KEY_BLOB = stringPreferencesKey("token_blob")
        private val KEY_VALIDATED_AT = longPreferencesKey("validated_at_epoch_seconds")
        private val KEY_LAST_CHECKED = longPreferencesKey("last_checked_at_epoch_seconds")
        private val KEY_LAST_FOUND_TAG = stringPreferencesKey("last_found_tag")
        /** When the blob first failed to read, and the cipher's sentence for the latest failure. REQ-0047. */
        private val KEY_UNREADABLE_SINCE = longPreferencesKey("unreadable_since_epoch_seconds")
        private val KEY_UNREADABLE_REASON = stringPreferencesKey("unreadable_reason")
    }
}

private val Context.tokenDataStore: DataStore<Preferences> by preferencesDataStore(
    name = DataStoreTokenStore.DATASTORE_NAME,
)

/** The one the app uses. Tests build their own with a fake cipher. */
fun tokenStore(context: Context): TokenStore =
    DataStoreTokenStore(context.applicationContext.tokenDataStore, KeystoreTokenCipher())
