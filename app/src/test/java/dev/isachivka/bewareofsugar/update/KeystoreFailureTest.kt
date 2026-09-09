package dev.isachivka.bewareofsugar.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.ProviderException
import java.security.UnrecoverableEntryException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.IllegalBlockSizeException
import javax.crypto.NoSuchPaddingException

/**
 * Which Keystore failures wipe the stored token and which leave it alone.
 *
 * This exists because Kotlin has no checked exceptions: `KeyStore.getEntry` declares
 * `UnrecoverableEntryException` and `KeyStoreException`, `deleteEntry` declares `KeyStoreException`,
 * and nothing warns when a handler catches something narrower. The first version of this code caught
 * `UnrecoverableKeyException` only — the *subclass* — so its parent went straight through and
 * crashed the one screen whose job is to fail politely.
 *
 * The direction of the trade-off is the point. Being wrong towards "keep it" costs the owner nothing
 * but a token that stays until it is replaced. Being wrong towards "wipe it" destroys a working
 * credential over a locked phone.
 */
class KeystoreFailureTest {

    @Test
    fun `a bad authentication tag is permanent`() {
        assertTrue(keystoreFailure(AEADBadTagException("tag")).permanent)
    }

    @Test
    fun `an unrecoverable entry is permanent`() {
        assertTrue(keystoreFailure(UnrecoverableEntryException("gone")).permanent)
    }

    /** The subclass the original code caught. It must still be permanent. */
    @Test
    fun `an unrecoverable key is permanent too`() {
        assertTrue(keystoreFailure(UnrecoverableKeyException("gone")).permanent)
    }

    /**
     * The regression this class was written for: the parent type used to escape the handler
     * entirely. Now it is classified rather than propagated.
     */
    @Test
    fun `the parent type does not escape unclassified`() {
        val parent: UnrecoverableEntryException = UnrecoverableEntryException("gone")
        val failure = keystoreFailure(parent)
        assertTrue(failure.permanent)
        assertTrue(failure.cause === parent)
    }

    @Test
    fun `a keystore that will not work is not the blob's fault`() {
        assertFalse(keystoreFailure(KeyStoreException("not initialised")).permanent)
    }

    @Test
    fun `a locked device keeps the token`() {
        // What a read on a locked phone looks like: the key requires an unlocked device, and the
        // platform surfaces that as a block-size or invalid-key failure wrapping a KeyStoreException.
        assertFalse(keystoreFailure(IllegalBlockSizeException("locked")).permanent)
        assertFalse(keystoreFailure(InvalidKeyException("locked")).permanent)
    }

    /**
     * The third instance of this defect class, and the one that hid best: `ProviderException` is a
     * `RuntimeException`, so it slips past every catch of `GeneralSecurityException`. It was guarded
     * on the write path and not on the read path — one direction handled, the other not.
     *
     * Transient, deliberately: a provider failing right now is not evidence that the ciphertext is
     * dead, and wiping on it would destroy a good token over misbehaving hardware.
     */
    @Test
    fun `a failing keystore provider keeps the token`() {
        val failure = keystoreFailure(ProviderException("StrongBox is unhappy"))
        assertFalse(failure.permanent)
    }

    @Test
    fun `the provider failure is classified rather than left to escape`() {
        // StrongBoxUnavailableException is a ProviderException subclass and would land here too; it
        // is an Android framework type, so it cannot be constructed in a JVM test - the parent
        // standing in for it is the point, since the parent is what used to go unhandled.
        val cause = ProviderException("hardware failure")
        val failure = keystoreFailure(cause)
        assertTrue(failure.cause === cause)
        assertTrue(failure.message.orEmpty().isNotEmpty())
    }

    @Test
    fun `a missing algorithm or padding keeps the token`() {
        assertFalse(keystoreFailure(NoSuchAlgorithmException("no AES")).permanent)
        assertFalse(keystoreFailure(NoSuchPaddingException("no GCM")).permanent)
    }

    @Test
    fun `no classification carries any plaintext`() {
        val failures = listOf(
            keystoreFailure(AEADBadTagException("tag")),
            keystoreFailure(KeyStoreException("not initialised")),
            keystoreFailure(UnrecoverableEntryException("gone")),
        )
        assertTrue(failures.all { it.message.orEmpty().isNotEmpty() })
        assertFalse(failures.any { it.message.orEmpty().contains("not-a-real-token") })
    }
}
