package dev.isachivka.bewareofsugar.pairing

/**
 * This phone's own half of pairing, behind an interface so the sequence stays testable off-device.
 *
 * [PairingSequence] is deliberately free of Android types — that is what lets the step where a wrong
 * code must not be stored be tested on the JVM. Generating a hardware-backed key is as Android as it
 * gets, so it arrives through here rather than as a direct call to [PhoneIdentity].
 */
interface PhoneHalf {

    /**
     * Makes sure this phone has an identity, returning its fingerprint rows, or a reason it cannot.
     *
     * **Called when pairing BEGINS**, not lazily and not at first connect. See [PairingSequence.begin].
     *
     * **This never destroys an identity, and that is the whole point of [replace] existing.** Measured
     * on the owner's Pixel on 2026-07-29: an earlier version deleted the key here whenever
     * [SigningState.Unusable] came back, so *arriving at the pairing screen* silently minted a new
     * identity at 18:28:50 — one the laptop does not pin and cannot be made to pin from the phone. The
     * bridge refused every handshake afterwards and the owner was told their laptop was not answering.
     * Reading the state of a credential must not be able to end it.
     */
    fun provision(): Provisioning

    /**
     * Destroys this phone's identity and mints a new one. **The only destructive call in this file.**
     *
     * Separate from [provision] because the cost is invisible from here: the laptop pins the OLD
     * certificate, so a replacement takes the pairing down until the owner pins the new one. That is a
     * decision only they can make, and it must follow from something they pressed.
     */
    fun replace(): Provisioning

    /** Whether an identity already exists — used to decide the state to resume into. */
    fun exists(): Boolean

    sealed interface Provisioning {
        /** The phone has an identity. [fingerprint] is what the owner compares on the laptop. */
        data class Ready(val fingerprint: List<String>) : Provisioning

        /**
         * There is no screen lock, so a key that requires the owner to be present cannot be created.
         *
         * **Its own outcome rather than an exception.** A phone with no lock is an ordinary phone,
         * and `KeyGenParameterSpec` throws `InvalidAlgorithmParameterException` for it — a crash
         * where the honest answer is a sentence naming the remedy.
         */
        data object NoScreenLock : Provisioning

        /**
         * There is an identity, and right now it will not sign.
         *
         * **A report, not an action.** [SigningState.Unusable] is a deliberately broad verdict — its
         * catch-all arm exists so that any key which refuses to initialise a signature is treated as
         * unable to sign, whatever the platform's reason. That breadth is right when the consequence
         * is a sentence on a screen and wrong when the consequence is deleting the credential: a
         * misclassification then costs the owner their pairing rather than a retry.
         *
         * [fingerprint] is the EXISTING certificate, because that is still the one the laptop pins and
         * the one the owner can check against it.
         */
        data class CannotSign(val fingerprint: List<String>) : Provisioning

        /**
         * There is an identity, it signs perfectly well today, and it was minted by the code that
         * bound keys to a recent unlock.
         *
         * **Its own outcome precisely because the key still works.** [CannotSign] is a key that has
         * already failed; this is one that has not failed *yet* and will — the window can lapse and a
         * biometric enrolment can invalidate it, which is what took the owner's pairing down on
         * 2026-07-29. A phone that updates the app keeps its old key: the keystore is not part of the
         * APK, so removing the flag from the spec changes nothing about a key already minted.
         *
         * Without this outcome there is no way out at all — the replace button was only offered for a
         * key that could not sign, and a key made the old way signs right up until it doesn't.
         *
         * **Reported, never acted on.** The remedy costs the pairing and the owner presses it.
         */
        data class MadeTheOldWay(val fingerprint: List<String>) : Provisioning
    }
}

/**
 * The real one.
 *
 * An identity that exists but will not sign is **reported**, never quietly replaced — see
 * [PhoneHalf.Provisioning.CannotSign]. Every key minted before `DIGEST_NONE` is in that condition
 * permanently and does need replacing; that is what [replace] is for, and the owner asks for it.
 *
 * A **usable** identity is left alone, so opening the pairing screen and backing out cannot invalidate
 * a pairing that already works.
 */
class KeystorePhoneHalf : PhoneHalf {

    override fun exists(): Boolean = PhoneIdentity.existing() != null

    override fun provision(): PhoneHalf.Provisioning = guarded {
        val existing = PhoneIdentity.existing()
        // Note what this does NOT do: it never deletes. The existing certificate is fingerprinted and
        // handed back, because the laptop still pins it and it is the thing the owner needs to see.
        when {
            existing == null ->
                PhoneHalf.Provisioning.Ready(Fingerprint.rows(Fingerprint.of(PhoneIdentity.certificate())))
            // Already broken beats about-to-break: a key that cannot sign is the more urgent sentence
            // and the owner can act on only one thing at a time.
            PhoneIdentity.signingState() == SigningState.Unusable ->
                PhoneHalf.Provisioning.CannotSign(Fingerprint.rows(Fingerprint.of(existing)))
            // Signs today, will not keep signing. Asked of the platform rather than assumed, because
            // an app update leaves an old key exactly where it was.
            PhoneIdentity.isBoundToRecentUnlock() ->
                PhoneHalf.Provisioning.MadeTheOldWay(Fingerprint.rows(Fingerprint.of(existing)))
            else ->
                PhoneHalf.Provisioning.Ready(Fingerprint.rows(Fingerprint.of(existing)))
        }
    }

    override fun replace(): PhoneHalf.Provisioning = guarded {
        PhoneIdentity.clear()
        PhoneHalf.Provisioning.Ready(Fingerprint.rows(Fingerprint.of(PhoneIdentity.certificate())))
    }

    private fun guarded(body: () -> PhoneHalf.Provisioning): PhoneHalf.Provisioning = try {
        body()
    } catch (e: java.security.InvalidAlgorithmParameterException) {
        // The platform's way of saying "this device has no secure lock screen". Nothing else about
        // the spec can produce it, and the remedy is the owner's to apply.
        PhoneHalf.Provisioning.NoScreenLock
    }
}
