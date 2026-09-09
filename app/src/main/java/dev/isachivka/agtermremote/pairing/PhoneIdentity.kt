package dev.isachivka.agtermremote.pairing

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * This phone's own identity: an EC keypair that cannot leave the hardware keystore, and the
 * self-signed certificate the laptop pins.
 *
 * REQ-0008 §5 settled the model; this is its other half. Two self-signed certificates, no certificate
 * authority, each side pinning the exact bytes of the other. **The QR carries a certificate, never a
 * signing request** — there is no issuer anywhere in this design, and REQ-0008 ruling 2 is only true
 * because of that.
 *
 * ### Nothing here ever holds a private key
 *
 * The keystore generates the key AND mints the certificate, in one call. That is not a convenience:
 * it means there is no moment, in any process this app controls, when the private key exists
 * somewhere it could be copied, logged or written to a file. The alternative — building a certificate
 * with a third-party library — would need the key handed to a signer, and would add a dependency to
 * the milestone that handles the credential.
 *
 * `setCertificateSubject`, `setCertificateSerialNumber`, `setCertificateNotBefore` and
 * `setCertificateNotAfter` are what make the platform emit a usable self-signed certificate rather
 * than a placeholder.
 *
 * ### What is proven rather than asserted
 *
 * REQ-0006 proved *nothing is persisted* by asserting the absence of the artefacts rather than by
 * writing the rule down. The same shape applies here and each property fails independently, so each
 * gets its own assertion: non-exportable and hardware-backed. See `PhoneIdentityTest`.
 *
 * **Unlock-gating was the third of those and is gone as of 2026-07-29** — the owner's ruling, after it
 * destroyed their pairing. The reasoning is at the `build()` call in [generate], where the flag used
 * to be; it is written there rather than here because that is where somebody would put it back.
 */
object PhoneIdentity {

    private const val KEYSTORE = "AndroidKeyStore"

    /** One identity per app install. Re-pairing replaces it; there is never a second live one. */
    const val ALIAS = "agterm-remote.phone-identity"

    /**
     * Twenty years, matching the bridge.
     *
     * PLAN-0008 open question 2: an expiry on a pinned self-signed pair buys an attacker nothing and
     * guarantees a day the owner's phone stops working while they are away from the only machine that
     * can fix it.
     */
    private const val VALIDITY_YEARS = 20

    /** The certificate for this phone, generating the identity on first use. */
    fun certificate(): X509Certificate = existing() ?: generate()

    /** The pinned identity, or null when this phone has never been paired. */
    fun existing(): X509Certificate? =
        keyStore().getCertificate(ALIAS) as X509Certificate?

    /**
     * The private key handle. Note the type: a handle, not key material.
     *
     * `getEncoded()` on this returns null, which is the platform's way of saying the bytes are not
     * available to this process and never will be. Asserted rather than trusted.
     */
    fun privateKey(): PrivateKey? = keyStore().getKey(ALIAS, null) as PrivateKey?

    /**
     * What the platform will say about where this key lives and how it is protected.
     *
     * Returned rather than asserted here, because the honest answer differs by hardware and the
     * caller — a test on the owner's Pixel, or a screen — is what decides whether the answer is
     * acceptable. A helper that returned a Boolean would be the place a strict assertion quietly
     * became a loose one.
     */
    fun keyInfo(): KeyInfo? {
        val key = privateKey() ?: return null
        return KeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java)
    }

    /**
     * This identity, in the form JSSE wants for a client certificate.
     *
     * **Here rather than at the call site, so the keystore stays behind one door.** The alternative is
     * every caller building a `KeyManagerFactory` from the keystore name themselves, and the moment
     * there are two of those, one of them can be built from a different store — which is how a
     * hardware-backed identity quietly becomes a software one.
     *
     * The private key still never leaves: `KeyStore.getKey` on an `AndroidKeyStore` entry hands back a
     * handle with no extractable bytes, and the factory signs *through* that handle. Nothing in this
     * process ever holds key material, which is the property iteration 1 asserts directly.
     */
    fun keyManager(): KeyManager {
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore(), null)
        return factory.keyManagers.first()
    }

    /**
     * What this phone's key can do RIGHT NOW, asked before anything opens a socket.
     *
     * **This exists because the failure is otherwise invisible and lands on the wrong machine.** A key
     * that cannot sign makes the key manager quietly present no certificate: the handshake fails at
     * the far end, the phone sees a closed stream, and the copy tells the owner their LAPTOP did not
     * understand — about a laptop that behaved perfectly. Measured three times on a real user's device
     * before it was understood once, and it is the owner's complaint 5.
     *
     * Asking here is deterministic and costs nothing: `initSign` throws exactly when the key will not
     * sign, before any connection exists to be blamed.
     *
     * NONEwithECDSA rather than SHA256withECDSA because that is what TLS 1.3 actually asks this key to
     * do - the same digest whose absence from the key spec made it unusable at all.
     *
     * **There is no longer a NeedsOwner answer**, because since 2026-07-29 the key is not bound to a
     * recent unlock — see the spec in [generate]. Nothing about this key expires, so "cannot sign now,
     * ask the owner and retry" is no longer a state it can be in.
     */
    fun signingState(): SigningState {
        val key = privateKey() ?: return SigningState.Absent
        return try {
            Signature.getInstance("NONEwithECDSA").initSign(key)
            SigningState.Ready
        } catch (e: GeneralSecurityException) {
            // EVERYTHING ELSE IS UNUSABLE, and this arm is the point of the whole type.
            //
            // The case that forced it is not hypothetical: every key minted before DIGEST_NONE was
            // added to the spec above CAN NEVER SIGN. initSign on such a key throws for an
            // incompatible digest. Before this arm existed, an owner who already had a key hit an
            // UNCAUGHT throw on a pre-flight that runs before any socket is opened: a crash on
            // opening Terminal rather than a failure surface.
            //
            // Catching the supertype is deliberate rather than lazy. The precise exception for a bad
            // digest is a platform detail, it differs by provider, and enumerating the ones we have
            // seen would rebuild exactly the defect this milestone is about - a check that describes
            // the failures we happened to meet rather than the property we need. The property is
            // simple: if the key will not initialise a signature, it cannot sign, whatever the
            // reason.
            //
            // **The breadth is only safe because this verdict no longer destroys anything.** It was a
            // sentence on a screen AND a deletion, and as a deletion one misclassification cost the
            // owner their pairing on 2026-07-29. Reporting broadly is right; acting broadly was not.
            // See KeystorePhoneHalf.provision, which reports, against replace, which the owner asks
            // for. Do not reconnect this verdict to a clear().
            SigningState.Unusable
        }
    }

    /**
     * Whether this key is bound to a recent unlock — which is to say, whether it was minted by the
     * code that existed before 2026-07-29.
     *
     * **Read back off [KeyInfo], never inferred.** The flag is not recorded anywhere in this app, and
     * a key minted under the old spec survives an app update untouched: the keystore is not part of
     * the APK. So "was this key made the old way?" cannot be answered from our own source or from a
     * version number — only by asking the platform what it applied.
     *
     * That is why this exists at all. Removing the flag from [generate] changes keys minted from now
     * on, and the only key that matters on the owner's phone was minted before. Without this, the fix
     * would be invisible on the one device it was written for.
     *
     * **This reports. It must never be wired to a deletion** — that is the exact shape of the defect
     * this whole change is about. See [PhoneHalf.Provisioning.MadeTheOldWay].
     */
    fun isBoundToRecentUnlock(): Boolean = keyInfo()?.isUserAuthenticationRequired == true

    /** Removes the identity. Pairing again generates a new one; the old certificate stops being valid. */
    fun clear() {
        keyStore().deleteEntry(ALIAS)
    }

    private fun generate(): X509Certificate {
        val notBefore = Date()
        val notAfter = Date(notBefore.time + VALIDITY_YEARS * 365L * 24 * 60 * 60 * 1000)

        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            // DIGEST_NONE is required and its absence made this key unusable for the one thing it
            // exists to do. Conscrypt signs TLS 1.3 CertificateVerify as NONEwithECDSA - BoringSSL
            // hashes the transcript itself and asks the key to sign the digest raw - so a key minted
            // with only DIGEST_SHA256 is refused by the keystore with INCOMPATIBLE_DIGEST, and the
            // handshake dies at the moment the client must prove possession.
            //
            // Measured on-device, not reasoned: the engine consumed the server's whole flight, the
            // pinned trust check passed, and the failure landed on the record after it.
            //
            // SHA256 stays for anything that signs the ordinary way. Nothing here widens what the key
            // can be used FOR - PURPOSE_SIGN is unchanged, and the certificate it mints is unchanged.
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
            // The certificate the keystore mints for us, so no code here ever touches the key.
            .setCertificateSubject(X500Principal("CN=agterm-remote phone"))
            .setCertificateSerialNumber(BigInteger.valueOf(notBefore.time))
            .setCertificateNotBefore(notBefore)
            .setCertificateNotAfter(notAfter)
            // NO setUserAuthenticationRequired. Removed 2026-07-29 by the owner's ruling, and this is
            // the single most consequential line in the file, so it is a comment rather than an
            // absence.
            //
            // The flag bound the key to a recent unlock. That is the ONLY thing in this spec that can
            // make a working key stop working without anything else changing, by exactly two routes:
            // UserNotAuthenticatedException when the window lapses, and permanent invalidation when
            // the biometric or credential enrolment changes. Both are why a key that signed at
            // 18:27:32 on the owner's Pixel was called unusable at 18:28:50.
            //
            // What is given up is real and was measured against what it cost: a handset taken while
            // unlocked was already inside the window by definition, so the flag never defended the
            // case people imagine. It bought the handset taken cold and unlocked later — minutes
            // rather than indefinitely — and it charged for that in unpredictable failures on the
            // owner's own phone, on the network this feature exists for. The answers that actually
            // work are the ones the owner controls and neither needs a timer: mint a new identity, at
            // which point the old certificate is refused, or remove the port forward.
            //
            // What is NOT given up: the key is still generated in and confined to the hardware
            // keystore, still non-exportable, still TRUSTED_ENVIRONMENT on this device, and still
            // PURPOSE_SIGN only. Nothing here widens what the key can do.
            .build()

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).run {
            initialize(spec)
            generateKeyPair()
        }
        return existing() ?: error("the keystore generated a key without a certificate")
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
}
