package dev.isachivka.bewareofsugar.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.security.KeyStoreException
import java.security.ProviderException
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The storage half, on the JVM, against a fake cipher. The real cipher needs `AndroidKeyStore` and is
 * covered by `KeystoreTokenCipherTest` on a device — there is no honest way to prove Keystore
 * behaviour here, and a Robolectric shadow of it would be a green test that proves nothing.
 */
class DataStoreTokenStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val token = GitHubToken("not-a-real-token")

    /**
     * Reversible on purpose: this test is about the store's logic, not about cryptography. Base64
     * rather than a wrapper string, so "what reached the file is not the raw token" stays a real
     * assertion instead of one the fake would satisfy by accident.
     */
    private class FakeCipher : TokenCipher {
        var failure: TokenDecryptionFailed? = null
        /** An untranslated throw, i.e. one the real cipher is supposed to have converted. */
        var rawDecryptFailure: Throwable? = null
        var encryptFailure: Throwable? = null
        var corruptOnEncrypt = false
        var keyDestroyed = false

        override fun encrypt(plaintext: String): String {
            encryptFailure?.let { throw it }
            encrypted++
            val payload = if (corruptOnEncrypt) "$plaintext-mangled" else plaintext
            return Base64.getEncoder().encodeToString(payload.toByteArray())
        }

        override fun decrypt(encoded: String): String {
            failure?.let { throw it }
            rawDecryptFailure?.let { throw it }
            return String(Base64.getDecoder().decode(encoded))
        }

        override fun destroyKey() {
            keyDestroyed = true
        }

        /** True once, to stand in for a blob under the legacy key - REQ-0047. */
        var rewrapOnce = false
        var encrypted = 0

        override fun needsRewrap(encoded: String): Boolean {
            val answer = rewrapOnce
            rewrapOnce = false
            return answer
        }
    }

    private fun store(cipher: TokenCipher, now: () -> Long = { 1_700_000_000L }): Pair<DataStoreTokenStore, () -> String?> {
        val file = folder.newFile("token.preferences_pb").also { it.delete() }
        val dataStore = PreferenceDataStoreFactory.create { file }
        val rawBlob = { runBlocking { dataStore.data.first()[stringPreferencesKey("token_blob")] } }
        return DataStoreTokenStore(dataStore, cipher, now) to rawBlob
    }

    @Test
    fun `nothing stored reads as none`() = runBlocking {
        val (store, _) = store(FakeCipher())
        assertEquals(StoredToken.None, store.read())
    }

    @Test
    fun `a saved token reads back with its timestamp`() = runBlocking {
        val (store, _) = store(FakeCipher())
        store.save(token, validatedAtEpochSeconds = 1_700_000_000L)

        val read = store.read()
        assertTrue(read is StoredToken.Present)
        assertEquals(token, (read as StoredToken.Present).token)
        assertEquals(1_700_000_000L, read.validatedAtEpochSeconds)
    }

    @Test
    fun `the store writes the cipher output, never the raw token`() = runBlocking {
        val (store, rawBlob) = store(FakeCipher())
        store.save(token, validatedAtEpochSeconds = 0L)

        val onDisk = rawBlob()
        assertNotNull(onDisk)
        assertFalse(onDisk!!.contains(token.value))
        // That the real ciphertext contains nothing of the token is the cipher's claim, and it is
        // asserted on a device in KeystoreTokenCipherTest.
    }

    /**
     * **The token stays - REQ-0047.** This test used to assert the opposite: a permanent failure
     * wiped the blob and the key. That destroyed the owner's token three times on the strength of a
     * StrongBox key that had gone missing, and left the screen saying "no token" as if he had never
     * pasted one.
     */
    @Test
    fun `a permanently undecryptable blob is reported, recorded, and kept`() = runBlocking {
        val cipher = FakeCipher()
        var now = 1_700_000_000L
        val (store, rawBlob) = store(cipher, now = { now })
        store.save(token, validatedAtEpochSeconds = 0L)

        cipher.failure = TokenDecryptionFailed("authentication tag did not verify", permanent = true)
        val first = store.read()
        now += 3600
        val second = store.read()

        assertEquals(
            StoredToken.Unreadable(permanent = true, reason = "authentication tag did not verify", sinceEpochSeconds = 1_700_000_000L),
            first,
        )
        assertEquals("the time it FIRST failed is what is remembered", first, second)
        assertNotNull("the blob must still be there", rawBlob())
        assertFalse("nothing destroys the key on a read", cipher.keyDestroyed)
    }

    /** And if the key comes back, so does the token, and the record of the failure goes. */
    @Test
    fun `a key that returns brings the token back and forgets the failure`() = runBlocking {
        val cipher = FakeCipher()
        var now = 1_700_000_000L
        val (store, _) = store(cipher, now = { now })
        store.save(token, validatedAtEpochSeconds = 5L)
        cipher.failure = TokenDecryptionFailed("the key was invalidated", permanent = true)
        store.read()

        cipher.failure = null
        assertEquals(StoredToken.Present(token, 5L), store.read())

        // A later failure starts a new clock rather than reporting the old one.
        now += 7200
        cipher.failure = TokenDecryptionFailed("the keystore could not be used", permanent = false)
        assertEquals(
            StoredToken.Unreadable(permanent = false, reason = "the keystore could not be used", sinceEpochSeconds = 1_700_007_200L),
            store.read(),
        )
    }

    /** A blob under the legacy key is written again under the current one, once - REQ-0047. */
    @Test
    fun `a blob that needs rewrapping is saved again under the current key`() = runBlocking {
        val cipher = FakeCipher()
        val (store, rawBlob) = store(cipher)
        store.save(token, validatedAtEpochSeconds = 7L)
        val before = rawBlob()
        val encryptedBefore = cipher.encrypted

        cipher.rewrapOnce = true
        assertEquals(StoredToken.Present(token, 7L), store.read())

        assertEquals("one more encryption, for the rewrap", encryptedBefore + 1, cipher.encrypted)
        assertNotNull(rawBlob())
        assertEquals("the same token, still readable, timestamp kept", StoredToken.Present(token, 7L), store.read())
        assertEquals("and no further rewrap", encryptedBefore + 1, cipher.encrypted)
        // The fake's ciphertext is deterministic, so the bytes match; the real cipher's would not.
        assertEquals(before, rawBlob())
    }

    /** A new save wipes the failure history along with the old blob. */
    @Test
    fun `saving a new token clears the failure record`() = runBlocking {
        val cipher = FakeCipher()
        val (store, _) = store(cipher, now = { 1_700_000_000L })
        store.save(token, validatedAtEpochSeconds = 0L)
        cipher.failure = TokenDecryptionFailed("the key was invalidated", permanent = true)
        store.read()
        cipher.failure = null

        store.save(GitHubToken("another-not-real-token"), validatedAtEpochSeconds = 9L)

        assertEquals(StoredToken.Present(GitHubToken("another-not-real-token"), 9L), store.read())
    }

    @Test
    fun `a transient failure leaves the stored token alone`() = runBlocking {
        val cipher = FakeCipher()
        val (store, rawBlob) = store(cipher)
        store.save(token, validatedAtEpochSeconds = 0L)

        cipher.failure = TokenDecryptionFailed("the key could not be used right now", permanent = false)
        assertEquals(
            StoredToken.Unreadable(permanent = false, reason = "the key could not be used right now", sinceEpochSeconds = 1_700_000_000L),
            store.read(),
        )
        assertNotNull("a locked device must not cost the owner their token", rawBlob())

        // And once the condition clears, the same blob decrypts as before.
        cipher.failure = null
        assertEquals(StoredToken.Present(token, 0L), store.read())
    }

    @Test
    fun `a saved token reports that it was saved`() = runBlocking {
        val (store, _) = store(FakeCipher())
        assertTrue(store.save(token, validatedAtEpochSeconds = 0L))
    }

    /**
     * The path this guards is the worst-timed one in the feature: the owner has pasted a good token,
     * GitHub has just said yes, and the phone will not encrypt it. That has to be a reported state,
     * not an exception out of a coroutine.
     */
    @Test
    fun `a phone that will not encrypt reports it and writes nothing`() = runBlocking {
        val cipher = FakeCipher()
        val (store, rawBlob) = store(cipher)
        cipher.encryptFailure = TokenEncryptionFailed("the phone would not encrypt it")

        assertFalse(store.save(token, validatedAtEpochSeconds = 0L))

        assertNull("nothing may be written when encryption failed", rawBlob())
        assertEquals(StoredToken.None, store.read())
    }

    @Test
    fun `an untranslated crypto failure on save is reported, not thrown`() = runBlocking {
        val cipher = FakeCipher()
        val (store, rawBlob) = store(cipher)
        // The cipher is meant to translate everything; this is the backstop for when one escapes.
        cipher.encryptFailure = KeyStoreException("not initialised")

        assertFalse(store.save(token, validatedAtEpochSeconds = 0L))
        assertNull(rawBlob())
    }

    @Test
    fun `a failed save leaves an already-stored token untouched`() = runBlocking {
        val cipher = FakeCipher()
        val (store, rawBlob) = store(cipher)
        store.save(token, validatedAtEpochSeconds = 1_700_000_000L)
        val before = rawBlob()

        cipher.encryptFailure = TokenEncryptionFailed("the phone would not encrypt it")
        assertFalse(store.save(GitHubToken("a-different-token"), validatedAtEpochSeconds = 0L))

        assertEquals("the working token must survive a failed replacement", before, rawBlob())
        assertEquals(StoredToken.Present(token, 1_700_000_000L), store.read())
    }

    /**
     * Encrypting without error is not the same as having written something readable. If what came
     * back cannot be decrypted to what went in, committing it would produce "your saved token is
     * corrupt" later, for a token that was never at fault.
     */
    @Test
    fun `a blob that does not round-trip is never committed`() = runBlocking {
        val cipher = FakeCipher()
        val (store, rawBlob) = store(cipher)
        cipher.corruptOnEncrypt = true

        assertFalse(store.save(token, validatedAtEpochSeconds = 0L))

        assertNull(rawBlob())
        assertEquals(StoredToken.None, store.read())
    }

    /**
     * ProviderException is a RuntimeException, so it is caught by nothing that catches
     * GeneralSecurityException. The cipher translates it now; this pins the store's backstop for the
     * day something else does not.
     */
    @Test
    fun `a failing provider on read is reported, not thrown`() = runBlocking {
        val cipher = FakeCipher()
        val (store, rawBlob) = store(cipher)
        store.save(token, validatedAtEpochSeconds = 0L)

        cipher.rawDecryptFailure = ProviderException("hardware failure")
        assertTrue(store.read() is StoredToken.Unreadable)
        assertNotNull("a failing provider must not cost the owner their token", rawBlob())
    }

    @Test
    fun `a failing provider on save is reported, not thrown`() = runBlocking {
        val cipher = FakeCipher()
        val (store, rawBlob) = store(cipher)
        cipher.encryptFailure = ProviderException("hardware failure")

        assertFalse(store.save(token, validatedAtEpochSeconds = 0L))
        assertNull(rawBlob())
    }

    @Test
    fun `clearing removes both the blob and the key`() = runBlocking {
        val cipher = FakeCipher()
        val (store, rawBlob) = store(cipher)
        store.save(token, validatedAtEpochSeconds = 0L)

        store.clear()

        assertNull(rawBlob())
        assertTrue(cipher.keyDestroyed)
        assertEquals(StoredToken.None, store.read())
    }
}
