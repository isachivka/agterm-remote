package dev.isachivka.bewareofsugar.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.runBlocking
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.SecretKeyFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.File

/**
 * The half that cannot be proven on the JVM: a real `AndroidKeyStore` key, real AES-GCM, and the
 * store on top of it.
 *
 * Uses its own key alias and its own DataStore file so a run never touches the token the owner may
 * have saved on the device.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreTokenCipherTest {

    private val alias = "github_token_test"
    private val secret = "not-a-real-token"

    private lateinit var cipher: KeystoreTokenCipher
    private lateinit var file: File

    /**
     * One DataStore file per test method. DataStore refuses two live instances over the same file in
     * a process, and every test in this class runs in the same one.
     */
    @get:Rule
    val testName = TestName()

    @Before
    fun setUp() {
        // No legacy alias: the migration has its own test, and the default legacy alias is the
        // owner's real key on a real device.
        cipher = KeystoreTokenCipher(alias, legacyAlias = null)
        cipher.destroyKey()
        file = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "datastore/token_cipher_test_${testName.methodName}.preferences_pb",
        )
        file.delete()
    }

    @After
    fun tearDown() {
        cipher.destroyKey()
        file.delete()
    }

    @Test
    fun roundTrips() {
        assertEquals(secret, cipher.decrypt(cipher.encrypt(secret)))
    }

    @Test
    fun theCiphertextDoesNotContainThePlaintext() {
        assertFalse(cipher.encrypt(secret).contains(secret))
    }

    @Test
    fun everyEncryptionUsesAFreshIv() {
        // setRandomizedEncryptionRequired(true) is what enforces this; if the key were ever created
        // without it, two encryptions of the same token would produce the same blob.
        assertNotEquals(cipher.encrypt(secret), cipher.encrypt(secret))
    }

    @Test
    fun aSecondInstanceReadsWhatTheFirstWrote() {
        val blob = cipher.encrypt(secret)
        // A fresh object, as after a process restart: the key is found in the Keystore, not held.
        assertEquals(secret, KeystoreTokenCipher(alias).decrypt(blob))
    }

    @Test
    fun losingTheKeyIsPermanentAndSaysSo() {
        val blob = cipher.encrypt(secret)
        cipher.destroyKey()

        val failure = runCatching { cipher.decrypt(blob) }.exceptionOrNull()
        assertTrue(failure is TokenDecryptionFailed)
        assertTrue("a missing key can never come back", (failure as TokenDecryptionFailed).permanent)
    }

    @Test
    fun aTamperedBlobIsRejectedRatherThanReturningRubbish() {
        val blob = cipher.encrypt(secret)
        val tampered = blob.dropLast(2) + if (blob.endsWith("A=")) "B=" else "A="

        val failure = runCatching { cipher.decrypt(tampered) }.exceptionOrNull()
        assertTrue(failure is TokenDecryptionFailed)
    }

    @Test
    fun theStoreRoundTripsAgainstTheRealCipher() = runBlocking {
        val store = DataStoreTokenStore(PreferenceDataStoreFactory.create { file }, cipher)
        store.save(GitHubToken(secret), validatedAtEpochSeconds = 1_700_000_000L)

        val read = store.read()
        assertEquals(StoredToken.Present(GitHubToken(secret), 1_700_000_000L), read)

        // And the file on disk holds none of it.
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(secret))
    }

    /**
     * **The blob stays - REQ-0047.** This asserted the opposite until the owner lost his token three
     * times to a StrongBox key that went missing.
     */
    @Test
    fun aTokenStoredWithAKeyThatIsGoneReadsAsUnreadableAndIsKept() = runBlocking {
        val store = DataStoreTokenStore(PreferenceDataStoreFactory.create { file }, cipher)
        store.save(GitHubToken(secret), validatedAtEpochSeconds = 0L)

        cipher.destroyKey()

        val first = store.read()
        assertTrue("$first", first is StoredToken.Unreadable && first.permanent)
        assertEquals("a second read says the same thing, not \"none\"", first, store.read())
        // Pasting a new token overwrites it, which is the owner's way out.
        assertTrue(store.save(GitHubToken(secret), validatedAtEpochSeconds = 1L))
        assertEquals(StoredToken.Present(GitHubToken(secret), 1L), store.read())
    }

    /**
     * The key is the plain TEE kind the pairing identity has lived with: not StrongBox, and usable
     * whether or not the device is unlocked. Those two properties are what the lost key had.
     */
    @Test
    fun theKeyIsNeitherStrongBoxNorBoundToTheLockScreen() {
        cipher.encrypt(secret)
        val ks = KeyStore.getInstance(KeystoreTokenCipher.ANDROID_KEYSTORE).apply { load(null) }
        val key = (ks.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
        val info = SecretKeyFactory.getInstance(key.algorithm, KeystoreTokenCipher.ANDROID_KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo

        assertFalse("no unlocked-device requirement", info.isUnlockedDeviceRequired)
        assertFalse("no user authentication requirement", info.isUserAuthenticationRequired)
        // KeyInfo has no StrongBox getter before API 31's securityLevel; on API 31+ it is the level.
        assertTrue(info.securityLevel != KeyProperties.SECURITY_LEVEL_STRONGBOX)
    }

    /** A blob written under the legacy alias still reads, and is flagged for rewrapping. */
    @Test
    fun aLegacyBlobReadsAndAsksToBeRewrapped() {
        val legacy = "github_token_test_legacy"
        val old = KeystoreTokenCipher(legacy, legacyAlias = null)
        val current = KeystoreTokenCipher(alias, legacyAlias = legacy)
        try {
            val blob = old.encrypt(secret)

            assertEquals(secret, current.decrypt(blob))
            assertTrue("a legacy blob needs rewrapping", current.needsRewrap(blob))
            val fresh = current.encrypt(secret)
            assertFalse("a current blob does not", current.needsRewrap(fresh))
            assertEquals(secret, current.decrypt(fresh))
        } finally {
            current.destroyKey()
        }
    }
}
