package dev.isachivka.agtermremote.pairing

/**
 * Whether this phone can show a viewfinder, and what the pairing screen offers when it cannot.
 *
 * ### The deadlock this shape exists to prevent
 *
 * The first version of this type had three states and hid the scan affordance unless the permission
 * was already held — while the only thing that requested the permission was that same affordance. On
 * **every phone that had never granted it, which is every phone in existence**, the viewfinder was
 * unreachable forever, and the copy explaining the refusal was reachable while the thing it explained
 * was not. Found on an emulator on 2026-08-09, before it reached anybody.
 *
 * So the rule now, and it is the rule rather than the fix: **the affordance exists whenever the
 * hardware does, and the permission is asked on the tap.** Only missing hardware removes the button.
 *
 * ### Why "not yet asked" and "asked and refused" are now two states
 *
 * They were deliberately collapsed, and the reason was sound: the screen's answer was identical, so a
 * distinction it could not act on was only a branch to get wrong. **That reason expired when the
 * button stopped being conditional.** Both states now offer the same button, and they differ in
 * exactly one thing — whether there is a refusal to explain. Explaining a refusal that never happened
 * would be the app answering a question nobody asked; staying silent about one that did would be the
 * button doing nothing for a reason the owner cannot see.
 *
 * ### The rule that outranks the rest, unchanged
 *
 * **Choosing a file is always available, and reaching it never triggers the permission request.** It
 * is the only route that has ever completed a pairing in this project.
 */
enum class CameraAccess {

    /** A camera exists and the permission is held. The tap opens the viewfinder with no dialog. */
    Ready,

    /**
     * A camera exists, the permission is not held, and nothing has asked yet.
     *
     * **The affordance is present.** The tap is what asks — that is the whole correction. No
     * explanation is shown, because nothing has been refused.
     */
    Askable,

    /**
     * A camera exists, and the owner said no.
     *
     * The affordance stays, because a permission refused today is one they may grant tomorrow and the
     * tap is still the only thing that asks. The refusal copy appears beside it so the button is not
     * silently doing nothing. **No "open Settings" button**: they refused on purpose, and a screen
     * that immediately asks again in a different shape is the app arguing with them.
     */
    Refused,

    /**
     * There is no camera on this device at all.
     *
     * The scan affordance is **absent, not broken**. This is the only state that removes it.
     */
    Absent,
    ;

    /** Whether the scan button is on screen. True unless the hardware is missing. */
    val offersScanning: Boolean get() = this != Absent

    /** Whether tapping it must request the permission first. */
    val asksOnTap: Boolean get() = this == Askable || this == Refused

    /** Whether the screen has a refusal to explain. Only after one has actually happened. */
    val explainsItself: Boolean get() = this == Refused

    companion object {

        /**
         * The single place the facts become one answer.
         *
         * **No camera outranks everything.** A device without a camera can hold the permission — it is
         * a declaration, not a capability — and sending that owner to Settings to grant what they
         * already granted, for hardware they do not have, is the screen that makes someone distrust
         * the rest of the app.
         *
         * [permissionGranted] and [hasCamera] are read at the moment they are needed and never
         * cached: a permission revoked in Settings while the app sat in the background must be
         * observed the next time it matters, and a remembered `true` is how an app tells the owner
         * something that stopped being true.
         *
         * [refusedAlready] is the one thing that cannot be read from the platform — it is whether
         * *this app* has asked and been told no. It resets when the process does, and the cost of
         * that is one extra dialog after a restart, which is the harmless direction.
         */
        fun of(hasCamera: Boolean, permissionGranted: Boolean, refusedAlready: Boolean = false): CameraAccess = when {
            !hasCamera -> Absent
            permissionGranted -> Ready
            refusedAlready -> Refused
            else -> Askable
        }
    }
}
