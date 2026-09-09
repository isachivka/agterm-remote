package dev.isachivka.agtermremote.pairing

/**
 * What the pairing screen is showing, as a value.
 *
 * Held apart from Compose so the sequence can be tested without a device. The states below are the
 * ones the owner can actually be in, and the one that matters is [Comparing]: **it is the only step
 * in REQ-0008 or REQ-0009 that depends on a human**, and the only one where the app has decoded
 * something valid and still must not act on it.
 *
 * ### Pairing has two halves and this app can only see one of them
 *
 * The laptop's half is *this phone holds the laptop's profile*. The phone's half is *the laptop holds
 * this phone's certificate*. **Only the first is observable from here.** Whether `bridgecert pin` ever
 * ran is a fact about the laptop, and nothing on the phone can check it.
 *
 * So there is no state meaning "the laptop has pinned us", because there is no way to know it. What
 * [Paired] means is *this phone has done everything it can*, and the copy says exactly that. Claiming
 * otherwise is the defect REQ-0009 finding 1 was about: a screen reading `Paired` while
 * `chooseClientAlias` returned null, because "paired" had been made to mean one stored profile.
 */
sealed interface PairingState {

    /** No laptop yet. The screen offers to take a photograph of the code, or to choose one. */
    data object NotPaired : PairingState

    /**
     * This phone has no secure lock screen, so it cannot hold a key that requires the owner present.
     *
     * **A state rather than a crash.** A phone with no lock is an ordinary phone; the app says so and
     * names the remedy instead of dying in `KeyGenParameterSpec`.
     */
    data object NoScreenLock : PairingState

    /**
     * A code was read and decoded, and **nothing has been stored**.
     *
     * The owner compares [laptopFingerprint] against what the laptop shows. Both certificates are
     * public, so the QR did not need to be private — what it needed was to be authentic, and this
     * comparison is where that comes from. If the two differ, the code came from somewhere other than
     * the laptop in front of them and pairing stops.
     */
    data class Comparing(
        val profile: ConnectionProfile,
        val laptopFingerprint: List<String>,
    ) : PairingState

    /**
     * The laptop is confirmed and stored, this phone has an identity, and **the laptop does not have
     * it yet**.
     *
     * The half-done state, and it is its own state rather than a failure or a kind of [Paired],
     * because nothing is wrong and nothing is finished. It carries [phoneFingerprint] and is the
     * screen that offers to send the certificate across.
     *
     * The owner leaves it by saying they have done it — not by the app detecting anything, which it
     * cannot.
     */
    data class PhoneHalfOutstanding(
        val profile: ConnectionProfile,
        val laptopFingerprint: List<String>,
        val phoneFingerprint: List<String>,
    ) : PairingState

    /**
     * Both halves done as far as this phone can tell.
     *
     * **Three things are true here, where an earlier version required one**: the laptop's profile is
     * stored, this phone's key exists, and its certificate has been sent to the laptop. The third is
     * the owner's word rather than an observation, and the copy is written to say so — the proof that
     * the laptop accepted it is the session list appearing, not this screen.
     */
    data class Paired(
        val profile: ConnectionProfile,
        val laptopFingerprint: List<String>,
        val phoneFingerprint: List<String>,
    ) : PairingState

    /**
     * This phone has an identity and it will not sign, so no connection can be made with it.
     *
     * **A dead end the owner is told about rather than one the app clears up for them.** Replacing the
     * key is the only remedy and it costs the pairing: the laptop pins [phoneFingerprint], and the
     * moment a new key is minted that pin is stale and nothing on this phone can refresh it. So the
     * screen names the cost and waits, and [PairingSequence.onReplaceIdentity] is the only way past.
     *
     * Measured on the owner's Pixel, 2026-07-29: the app used to do the replacement itself, on the
     * strength of the same verdict, as a side effect of this screen opening. It minted a new identity
     * at 18:28:50, the bridge refused every handshake from then on, and what the owner was shown was
     * that their laptop was not answering.
     */
    data class IdentityCannotSign(val phoneFingerprint: List<String>) : PairingState

    /**
     * This phone's key still works and was built the way that stops working.
     *
     * **Separate from [IdentityCannotSign] because the key has not failed yet**, and the owner is
     * being asked to give up something that is currently fine. That deserves its own sentence: one
     * screen is *this is broken*, the other is *this will break, and here is what fixing it costs*.
     *
     * It exists because updating the app does not touch the keystore. Keys minted before 2026-07-29
     * are bound to a recent unlock and survive the update that stopped minting them that way — so on
     * the one phone this fix was written for, the fix is invisible without this state. There was also
     * no route out: the replace button was only offered for a key that could not sign, and a key made
     * the old way signs right up until it doesn't.
     */
    data class IdentityMadeTheOldWay(val phoneFingerprint: List<String>) : PairingState

    /**
     * The image was not a pairing code.
     *
     * One state rather than several, because the remedies are identical — take another photograph —
     * and because the distinctions available to us are not ones the owner can act on. A blurred
     * frame, a QR code belonging to a wifi network, and a payload from a newer app all mean *that is
     * not the code*.
     */
    data object NotAPairingCode : PairingState
}

/**
 * The pairing sequence.
 *
 * Deliberately not a `ViewModel`: it holds no coroutines, touches no Android type, and every
 * transition is a pure function of what it was handed. That is what lets the whole sequence — the
 * part where a wrong code must not be stored — be tested on the JVM rather than only on a device.
 */
class PairingSequence(
    private val store: PairedLaptop,
    private val phone: PhoneHalf,
    /**
     * Whether this phone has already sent its certificate to the laptop.
     *
     * Stored, because it is the owner's assertion rather than something observable, and it must
     * survive the app closing — otherwise a finished pairing would present itself as half-done every
     * time the screen opened.
     */
    private val handover: Handover,
) {

    /** Remembers that the owner said they handed the certificate over. */
    interface Handover {
        fun done(): Boolean
        fun markDone()
        fun clear()
    }

    /**
     * The state to resume into.
     *
     * Reads all three conditions rather than only the stored profile, which is finding 1 exactly: the
     * screen said `Paired` on a phone that had no key at all, because "paired" had been made to mean
     * one of them.
     */
    fun initial(): PairingState {
        val profile = store.read() ?: return PairingState.NotPaired
        val laptop = fingerprintOf(profile)
        val mine = when (val p = phone.provision()) {
            is PhoneHalf.Provisioning.Ready -> p.fingerprint
            PhoneHalf.Provisioning.NoScreenLock -> return PairingState.NoScreenLock
            is PhoneHalf.Provisioning.CannotSign ->
                return PairingState.IdentityCannotSign(p.fingerprint)
            is PhoneHalf.Provisioning.MadeTheOldWay ->
                return PairingState.IdentityMadeTheOldWay(p.fingerprint)
        }
        return if (handover.done()) {
            PairingState.Paired(profile, laptop, mine)
        } else {
            PairingState.PhoneHalfOutstanding(profile, laptop, mine)
        }
    }

    /**
     * Pairing begins. **This is where the identity is generated**, and the timing is the point.
     *
     * The owner is present, the phone is unlocked, and a failure can be explained to somebody who is
     * looking at the screen. Generating it lazily at first connect instead would produce a
     * certificate at the moment they are *away from the laptop* — with nowhere to send it and nobody
     * to tell.
     *
     * Returns [PairingState.NoScreenLock] when the device cannot hold such a key, so the caller shows
     * a sentence rather than catching an exception.
     */
    fun begin(): PairingState = when (val p = phone.provision()) {
        is PhoneHalf.Provisioning.Ready -> PairingState.NotPaired
        PhoneHalf.Provisioning.NoScreenLock -> PairingState.NoScreenLock
        is PhoneHalf.Provisioning.CannotSign -> PairingState.IdentityCannotSign(p.fingerprint)
        is PhoneHalf.Provisioning.MadeTheOldWay -> PairingState.IdentityMadeTheOldWay(p.fingerprint)
    }

    /**
     * An image arrived from the camera or the picker.
     *
     * **Nothing is written here.** A decoded profile moves to [PairingState.Comparing] and waits for
     * the owner. Storing on decode would make the fingerprint comparison decorative — the app would
     * already be paired by the time the owner was asked to check, and a *stop* they could act on
     * would have nothing left to prevent.
     */
    fun onImage(pixels: IntArray, width: Int, height: Int): PairingState {
        val profile = PairingCode.decode(pixels, width, height) ?: return PairingState.NotAPairingCode
        return onProfile(profile)
    }

    /**
     * A profile that some route has already decoded — today, the live viewfinder.
     *
     * **The same landing point as [onImage], and that is the point.** The scanner does not get its own
     * path into the state machine: it produces a `ConnectionProfile` and arrives exactly where the
     * file route arrives, at a comparison the owner has to confirm. Nothing is written here either.
     */
    fun onProfile(profile: ConnectionProfile): PairingState =
        PairingState.Comparing(profile, fingerprintOf(profile))

    /**
     * The owner confirmed the fingerprints match. This is the only thing that writes the profile.
     *
     * It lands on [PairingState.PhoneHalfOutstanding] rather than on `Paired`, because at this moment
     * the laptop still has no idea this phone exists.
     */
    fun onFingerprintConfirmed(state: PairingState.Comparing): PairingState {
        val mine = when (val p = phone.provision()) {
            is PhoneHalf.Provisioning.Ready -> p.fingerprint
            PhoneHalf.Provisioning.NoScreenLock -> return PairingState.NoScreenLock
            // Even here, where the owner is deliberately pairing and a dead key would be replaced
            // anyway, the replacement is theirs to press. The verdict that says a key cannot sign is
            // broad on purpose, and this is the branch where acting on it silently used to cost a
            // working pairing.
            is PhoneHalf.Provisioning.CannotSign ->
                return PairingState.IdentityCannotSign(p.fingerprint)
            // Even mid-pairing this only reports. The owner came here to pair, not to be told their
            // key was swapped while they were reading a fingerprint.
            is PhoneHalf.Provisioning.MadeTheOldWay ->
                return PairingState.IdentityMadeTheOldWay(p.fingerprint)
        }
        store.write(state.profile)
        handover.clear()
        return PairingState.PhoneHalfOutstanding(state.profile, state.laptopFingerprint, mine)
    }

    /**
     * The owner asked for a new identity, knowing it takes the pairing down until they pin it.
     *
     * **The only call in this class that destroys anything the laptop depends on.** It lands on
     * [PairingState.PhoneHalfOutstanding] and clears the handover record, because the laptop pins the
     * certificate that has just stopped existing — carrying "already sent" across a replacement is
     * what let a phone with a brand-new key present itself as fully paired while every handshake it
     * made was refused.
     */
    fun onReplaceIdentity(): PairingState {
        val mine = when (val p = phone.replace()) {
            is PhoneHalf.Provisioning.Ready -> p.fingerprint
            PhoneHalf.Provisioning.NoScreenLock -> return PairingState.NoScreenLock
            is PhoneHalf.Provisioning.CannotSign ->
                return PairingState.IdentityCannotSign(p.fingerprint)
            is PhoneHalf.Provisioning.MadeTheOldWay ->
                return PairingState.IdentityMadeTheOldWay(p.fingerprint)
        }
        handover.clear()
        val profile = store.read() ?: return PairingState.NotPaired
        return PairingState.PhoneHalfOutstanding(profile, fingerprintOf(profile), mine)
    }

    /**
     * The owner says they have given the certificate to the laptop.
     *
     * **Their word, not our observation**, and the only honest answer available: whether
     * `bridgecert pin` ran is a fact about the laptop. Recorded so the screen does not ask again every
     * time it opens.
     */
    fun onHandoverDone(state: PairingState.PhoneHalfOutstanding): PairingState {
        handover.markDone()
        return PairingState.Paired(state.profile, state.laptopFingerprint, state.phoneFingerprint)
    }

    /**
     * The owner said the fingerprints differ.
     *
     * Returns to the start with nothing stored. There is deliberately no *continue anyway*: the
     * comparison is the whole authenticity argument, and an override would make it advisory.
     */
    fun onFingerprintRejected(): PairingState = PairingState.NotPaired

    /** Forget the laptop. Does not discard this phone's identity — see [PairedLaptop.clear]. */
    fun onUnpair(): PairingState {
        store.clear()
        handover.clear()
        return PairingState.NotPaired
    }

    private fun fingerprintOf(profile: ConnectionProfile): List<String> =
        Fingerprint.rows(Fingerprint.of(profile.bridgeCertificate))
}
