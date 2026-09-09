package dev.isachivka.agtermremote.pairing

/**
 * What the pairing screen is showing, as a value.
 *
 * ### Five states, where there were eight
 *
 * The states this replaces described a pairing with two halves: the phone stored the laptop, and then
 * the owner carried the phone's certificate to the laptop by hand and told the app they had done it.
 * Three of the old states existed only to describe that second half, one existed to hold a fingerprint
 * the owner had to compare by eye, and one described a key the app could no longer mint.
 *
 * **Enrolment removed all of them.** The phone sends its certificate over the connection the pairing
 * code authenticates, so there is no handover to track; the code carries the laptop's fingerprint, so
 * the comparison is done by the handshake rather than by a person reading hex off two screens; and the
 * key is minted without a screen-lock requirement, so a phone with no lock is not a state.
 *
 * What is left is what a person actually goes through: point the camera, or paste the code; wait;
 * and then either it worked or it did not and here is what to do.
 */
sealed interface PairingUi {

    /**
     * There is no camera to use — refused, or absent from the device.
     *
     * **Not an error state.** The payload is base64 text on purpose, so a code can be sent to the
     * phone through any channel the owner already has and pasted in. This screen is that route, and it
     * needs no permission at all.
     */
    data object NeedsCamera : PairingUi

    /** The viewfinder is open, reading frames. The paste field is under it, always. */
    data object Scanning : PairingUi

    /**
     * A code was read and the phone is talking to the laptop.
     *
     * Nothing has been stored: [PairedLaptop] is written by the enrolment, on success, and by nothing
     * else.
     */
    data object Working : PairingUi

    /**
     * It did not work, and [reason] says what to do next.
     *
     * **One state for every failure, and the sentence is the whole content.** The distinctions the app
     * can make - a spent code, a refused token, an address nothing answers on - matter to the sentence
     * and not to the screen; see [PairingOutcome], which is where they are turned into words and where
     * the rule that every one of them names an action is asserted.
     */
    data class Failed(val reason: String) : PairingUi

    /**
     * Paired: the laptop is stored, and it has pinned this phone.
     *
     * [fingerprint] is **this phone's**, as the bridge stored it — the receipt. It is shown so that a
     * person who wants to check the Mac's menu against this screen can. It is not a thing they must do:
     * unlike the design this replaces, nothing here waits on a human comparison.
     */
    data class Paired(val fingerprint: String) : PairingUi
}

/**
 * Every way pairing can end, turned into a screen.
 *
 * ### Why this is not inside the composable
 *
 * Because it is the part that can be wrong, and a screen needs a device to test. Both `when`s below
 * are exhaustive over sealed types with no `else`, so a new outcome on either side stops this file
 * compiling rather than inheriting somebody's blank sentence. `PairingOutcomeTest` runs on the JVM and
 * covers every arm.
 *
 * ### The house rule, in its narrowest form
 *
 * **A failure that only reports is a defect.** Every sentence here ends with something the owner can
 * do, and the test asserts that property rather than the wording. The reason it is worth a test: the
 * failure this project has met most often is a screen saying the laptop did not answer, about a laptop
 * that answered, with nothing on it to act on.
 */
object PairingOutcome {

    /**
     * Something answered at that address and it is not the Mac the code names.
     *
     * **Nothing was sent.** The fingerprint is checked during the handshake, so the one-time token and
     * this phone's certificate never left the device — which is why this is a different sentence from
     * a refusal, and not a scarier version of one.
     */
    const val WRONG_MAC =
        "Something answered at that address and it is not the Mac on the code. " +
            "Open a new pairing code on your Mac and scan it."

    /**
     * Nothing answered at all, and the reach of this sentence ends at the pinned handshake.
     *
     * Past that point the far end has proved who it is, and everything after is that Mac declining to
     * finish — which is [Enrollment.REFUSED], not this. The distinction is the one this project keeps
     * paying for when it is lost.
     */
    const val UNREACHABLE =
        "Nothing answered at the address on the code. Check your Mac is awake and reachable from " +
            "this phone, then scan a new code."

    /** The Mac is newer than this app. A different sentence from "that is not a code", on purpose. */
    const val NEWER_MAC =
        "That code comes from a newer version of the Mac app. Update this app, then scan it again."

    /** Not a pairing code, or a damaged one. One sentence for every refusal the codec can name. */
    const val NOT_A_CODE =
        "That is not a pairing code. Point the camera at the code on your Mac, or paste the code text."

    /**
     * This phone's key will not sign, so the pairing is refused **before** it is made.
     *
     * The enrolment itself would succeed - it presents no client certificate - and the phone would
     * then be unable to reach the API, holding a receipt for a pairing that cannot work. See
     * [EnrolGate], which is where the refusal happens and why it refuses rather than repairs.
     */
    const val KEY_CANNOT_SIGN =
        "This phone's key can no longer prove who it is, so pairing would finish and then not work. " +
            "Reinstall this app to make a new key, then pair again."

    /** The key would not mint at all. Nothing about the Mac is wrong, so the sentence must not blame it. */
    const val NO_IDENTITY =
        "This phone could not make the key that proves who it is. Try again, and if it keeps failing, " +
            "restart the phone."

    /**
     * What the enrolment did, as a screen.
     *
     * [EnrollResult.Refused] already carries a sentence written by the code that knows what failed, and
     * it is used as it stands rather than re-worded here: inventing a second vocabulary for the same
     * failures is how two parts of an app come to disagree about what happened.
     */
    fun of(result: EnrollResult): PairingUi = when (result) {
        is EnrollResult.Paired -> PairingUi.Paired(result.fingerprint)
        is EnrollResult.Refused -> PairingUi.Failed(result.reason)
        EnrollResult.NotTheLaptopInTheCode -> PairingUi.Failed(WRONG_MAC)
        // The cause is deliberately not shown. It can carry the address the phone tried to reach, and
        // this is the screen somebody photographs for a bug report.
        is EnrollResult.Unreachable -> PairingUi.Failed(UNREACHABLE)
    }

    /**
     * What reading the code produced, as a screen — or null when there is nothing to say, because the
     * code was read and the caller is about to enrol with it.
     *
     * **Nothing branches on [EnrollRefusal].** It is the wire contract's field: every value in it means
     * the same thing to somebody holding a phone, and a screen that told them which one would be
     * telling them about the format instead of what to do.
     */
    fun of(decode: EnrollDecode): PairingUi? = when (decode) {
        is EnrollDecode.Read -> null
        is EnrollDecode.UnsupportedVersion -> PairingUi.Failed(NEWER_MAC)
        is EnrollDecode.NotAPairingCode -> PairingUi.Failed(NOT_A_CODE)
    }
}
