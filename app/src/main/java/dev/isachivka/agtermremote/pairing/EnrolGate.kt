package dev.isachivka.agtermremote.pairing

import java.security.GeneralSecurityException
import java.security.cert.X509Certificate

/**
 * What has to be true about **this phone** before a pairing is allowed to start.
 *
 * ### Why there is a gate at all, when the enrolment does not need the key
 *
 * The enrolment handshake presents no client certificate. It only *sends* the certificate's bytes, so
 * a phone whose keystore key will not sign enrols perfectly: the Mac pins it, the screen says
 * **Paired** and shows a fingerprint, and then every API connection afterwards is refused — because
 * the API handshake is the one that asks the key to prove possession.
 *
 * That is a receipt handed to somebody who has just successfully scanned a code, for a pairing that
 * cannot work. It also leaves litter on the far side: the Mac now pins a phone that has to be removed
 * from there, and the screen that would remove it from here does not exist yet.
 *
 * ### The gate is not the replacement button, and the difference is the whole argument
 *
 * `SigningState.Unusable` is reached by catching `GeneralSecurityException`, which is deliberately
 * broad — right for a sentence on a screen, catastrophic as a deletion. On 2026-07-29 one
 * misclassification of a working key destroyed the owner's identity and the pairing with it, because
 * the verdict was wired to a `clear()`.
 *
 * **Refusing is not deleting.** The cost of being wrong here is a false refusal on a key that would
 * have worked: nothing is destroyed, nothing is stored, and the owner presses Try again. The cost of
 * not gating is the false receipt above. So the *gate* lands now and the *replacement* — which is
 * destructive, and needs a button the owner presses — stays with the screen that owns unpairing.
 *
 * ### It runs before anything is opened
 *
 * No socket, no handshake, no token spent, nothing written to [PairedLaptop] and nothing pinned on the
 * Mac. `EnrolGateTest` asserts exactly that: the enrolment lambda is never reached.
 */
internal object EnrolGate {

    /**
     * Mints or reads this phone's identity, checks it will sign, and only then enrols.
     *
     * The keystore arrives as two lambdas rather than as a direct call, so this is exercised by
     * ordinary JVM tests. That is the same seam `Enrollment` uses for its transport and for the same
     * reason: the thing worth testing here is the ORDER, and an order can only be observed by
     * something that notices what was not called.
     *
     * [identity] is called first because it is what generates the key on a phone that has none, and a
     * freshly generated key is the one case where asking [signing] first would refuse a phone that is
     * about to be perfectly fine.
     */
    fun run(
        identity: () -> X509Certificate,
        signing: () -> SigningState,
        enrol: (X509Certificate) -> EnrollResult,
    ): EnrollResult {
        val certificate = try {
            identity()
        } catch (e: GeneralSecurityException) {
            // The key would not mint. Nothing about the Mac is wrong, so the sentence must not
            // blame it, and this is not a network failure of any kind.
            return EnrollResult.Refused(PairingOutcome.NO_IDENTITY)
        }

        // **Before the network.** Everything past this line spends a one-time token.
        if (signing() != SigningState.Ready) {
            return EnrollResult.Refused(PairingOutcome.KEY_CANNOT_SIGN)
        }

        return enrol(certificate)
    }
}
