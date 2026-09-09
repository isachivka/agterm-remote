package dev.isachivka.agtermremote.pairing

import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The private key must never leave the keystore — not to a file, not to a backup, not into a log.
 *
 * REQ-0006 proved *nothing is persisted* by asserting the absence of the artefacts rather than by
 * writing the rule down, and this is the same shape. Three properties, each failing independently, so
 * each gets its own assertion.
 *
 * ### The one that is easiest to make meaningless
 *
 * `getSecurityLevel` reports `SECURITY_LEVEL_SOFTWARE` on an emulator. An assertion loose enough to
 * pass there proves nothing — and it would be the check guarding the private key, which is a bad
 * place for the sixth check in this project that cannot fail.
 *
 * **So it is strict, and it runs on the owner's Pixel.** On hardware that cannot satisfy it, it fails
 * and the reported level is quoted in the result rather than softened into a pass. An
 * environment-limited failure is an honest one; a green tick on an emulator would not be.
 */
@RunWith(AndroidJUnit4::class)
class PhoneIdentityTest {

    @Before
    fun setUp() = PhoneIdentity.clear()

    @After
    fun tearDown() = PhoneIdentity.clear()

    // --- The key cannot leave -----------------------------------------------------------------------

    @Test
    fun theKeyMaterialCannotBeObtained() {
        PhoneIdentity.certificate()

        val key = PhoneIdentity.privateKey()
        assertNotNull("the identity must exist after generation", key)
        // The platform's way of saying the bytes are not available to this process and never will be.
        assertNull("a keystore key must not expose its encoding", key!!.encoded)
        assertNull("nor after a fresh load from the keystore", PhoneIdentity.privateKey()!!.encoded)
    }

    @Test
    fun theKeyIsInsideSecureHardware() {
        PhoneIdentity.certificate()

        val level = PhoneIdentity.keyInfo()!!.securityLevel
        // Reported on PASS as well as on failure. A green test says "hardware-backed"; it does not
        // say WHICH, and TRUSTED_ENVIRONMENT and STRONGBOX are different guarantees. The value is the
        // result here - the assertion is only the floor.
        android.util.Log.i("AgtermRemoteQA", "key security level: $level (${securityLevelName(level)})")
        assertTrue(
            "the key must live in secure hardware; the platform reports level $level " +
                "(${securityLevelName(level)}). SOFTWARE is what an emulator reports - this " +
                "assertion is strict on purpose and is expected to fail off real hardware.",
            level == SECURITY_LEVEL_TRUSTED_ENVIRONMENT || level == SECURITY_LEVEL_STRONGBOX,
        )
    }

    /**
     * **The key is NOT gated on a recent unlock, and this asserts the absence deliberately.**
     *
     * Three tests used to live here: that the key required authentication, that the applied window
     * was the one asked for, and that authentication was not per-use. All three passed, on every run,
     * about a flag that was the single cause of the owner losing their pairing on 2026-07-29 — the
     * key stopped signing 78 seconds after it had signed, the app called it unusable, and the pairing
     * screen replaced it.
     *
     * The flag is gone by the owner's ruling. This asserts that it stays gone, because it reads like
     * an obvious security improvement to somebody skimming `KeyGenParameterSpec`, and the reasons it
     * is not are in `PhoneIdentity.generate` where they will be read.
     */
    /**
     * **The accessor that decides an old key's fate must agree with the platform.**
     *
     * `provision()` routes on [PhoneIdentity.isBoundToRecentUnlock], so if that ever answers wrongly,
     * either every owner is told to replace a perfectly good key, or the one owner who needs to hear
     * it never does. Asserted through the accessor rather than through `KeyInfo` directly, because the
     * accessor is what ships.
     */
    @Test
    fun aFreshKeyIsNotReportedAsMadeTheOldWay() {
        PhoneIdentity.certificate()

        assertTrue(
            "a key minted by the current spec is being reported as bound to a recent unlock, so " +
                "every owner would be told to replace a key that is fine",
            !PhoneIdentity.isBoundToRecentUnlock(),
        )
    }

    @Test
    fun theKeyIsNotGatedOnARecentUnlock() {
        PhoneIdentity.certificate()

        assertTrue(
            "the key demands a recent unlock again; that flag is what made a working key stop " +
                "working and cost the owner their pairing - see PhoneIdentity.generate",
            !PhoneIdentity.keyInfo()!!.isUserAuthenticationRequired,
        )
    }

    // --- Nothing on disk ----------------------------------------------------------------------------

    /**
     * There is nothing to search for directly — that is the point — so this searches for the *public*
     * key bytes instead. If any part of the keypair had been written out by this app, the public half
     * is the part that would plausibly be cached beside it.
     */
    @Test
    fun noPartOfTheKeypairIsWrittenToTheAppsStorage() {
        val certificate = PhoneIdentity.certificate()
        val publicKey = certificate.publicKey.encoded

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val roots = listOfNotNull(context.filesDir.parentFile, context.cacheDir, context.noBackupFilesDir)

        val offenders = roots.flatMap { it.walkTopDown().filter(File::isFile).toList() }
            .filter { file -> runCatching { file.readBytes().containsSequence(publicKey) }.getOrDefault(false) }

        assertEquals("no part of the keypair may reach the filesystem", emptyList<File>(), offenders)
    }

    // --- The certificate the laptop will pin ---------------------------------------------------------

    /**
     * `bridgecert pin` refuses a certificate that is a CA or carries `KeyUsageCertSign`, because a
     * pinned peer must not be able to sign others. The platform mints this certificate, not us, so
     * what it puts in is measured rather than assumed — and the rejection would otherwise happen on
     * the laptop, in front of the owner, at pairing time.
     */
    @Test
    fun theMintedCertificateIsNotAnIssuer() {
        val certificate = PhoneIdentity.certificate()

        assertEquals("basicConstraints must not mark this a CA", -1, certificate.basicConstraints)
        val keyUsage = certificate.keyUsage
        if (keyUsage != null) {
            assertTrue("keyCertSign must not be set", !keyUsage[KEY_CERT_SIGN])
        }
    }

    @Test
    fun theIdentityIsStableAcrossCalls() {
        val first = PhoneIdentity.certificate()
        val second = PhoneIdentity.certificate()

        assertTrue("asking twice must not mint a second identity", first.encoded.contentEquals(second.encoded))
    }

    @Test
    fun clearingRemovesTheIdentity() {
        PhoneIdentity.certificate()
        PhoneIdentity.clear()

        assertNull("re-pairing must start from nothing", PhoneIdentity.existing())
    }

    private companion object {
        // KeyProperties.SECURITY_LEVEL_* are API 31+; named here so the failure message can quote them.
        const val SECURITY_LEVEL_TRUSTED_ENVIRONMENT = KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
        const val SECURITY_LEVEL_STRONGBOX = KeyProperties.SECURITY_LEVEL_STRONGBOX
        const val KEY_CERT_SIGN = 5

        fun securityLevelName(level: Int) = when (level) {
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> "SOFTWARE"
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TRUSTED_ENVIRONMENT"
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> "STRONGBOX"
            KeyProperties.SECURITY_LEVEL_UNKNOWN -> "UNKNOWN"
            else -> "UNKNOWN_SECURE ($level)"
        }

        fun ByteArray.containsSequence(needle: ByteArray): Boolean {
            if (needle.isEmpty() || needle.size > size) return false
            outer@ for (i in 0..size - needle.size) {
                for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
                return true
            }
            return false
        }
    }
}
