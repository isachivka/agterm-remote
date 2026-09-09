package dev.isachivka.agtermremote.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.isachivka.agtermremote.pairing.ConnectionProfile
import dev.isachivka.agtermremote.pairing.PairedLaptop
import dev.isachivka.agtermremote.pairing.PairingAddress
import dev.isachivka.agtermremote.pairing.SigningState
import dev.isachivka.agtermremote.pairing.TypedAddress

/**
 * The settings screen's half of pairing: **move the laptop, read the fingerprint, throw it all away.**
 *
 * ### Why it is a plain class rather than a ViewModel
 *
 * The same argument as `PairingFlow`. It holds five values and calls three functions; a ViewModel
 * would add a lifecycle to something with none and put the thing under test behind a factory. What it
 * does hold is Compose state, so the screen recomposes when it changes and a JVM test can read it
 * directly — which matters here more than anywhere else in this app, because **the two destructive
 * acts in the whole product are in this file** and neither of them may be reachable only from a
 * device.
 *
 * ### The keystore arrives as two lambdas
 *
 * [discardIdentity] and [keyState] rather than direct calls to `PhoneIdentity`, so every rule below is
 * exercised by an ordinary unit test. The store is real — `PairedLaptop` is a file, and a fake of it
 * would make *the certificate survived* a statement about the fake.
 */
class LaptopSettings(
    private val store: PairedLaptop,
    private val discardIdentity: () -> Unit,
    private val keyState: () -> SigningState,
) {

    /** The paired laptop, or null when there is none. Re-read after anything that could change it. */
    var laptop: ConnectionProfile? by mutableStateOf(store.read())
        private set

    /** What is in the address box. Seeded from the store, rendered by the one renderer. */
    var address: String by mutableStateOf(laptop?.let(PairingAddress::of).orEmpty())
        private set

    /** The one sentence under the box: a refusal, or what just happened. Null while there is nothing to say. */
    var note: String? by mutableStateOf(null)
        private set

    /** The confirmation that is on screen, or null. Only ever one, and only ever because it was asked for. */
    var pending: Destruction? by mutableStateOf(null)
        private set

    /**
     * Whether this phone's key will never sign again, asked once when the screen opens.
     *
     * **This decides whether the replacement is OFFERED, and nothing else.** It is the verdict
     * `PhoneIdentity.signingState` reaches by catching `GeneralSecurityException`, which is broad on
     * purpose — right for deciding what to show, and catastrophic as a decision to delete. On
     * 2026-07-29 exactly that wiring destroyed the owner's pairing. The deletion below is performed by
     * the person, twice, and never by this flag.
     */
    var keyIsUnusable: Boolean by mutableStateOf(keyState() == SigningState.Unusable)
        private set

    fun edit(typed: String) {
        address = typed
        note = null
    }

    /**
     * Stores the address, **keeping the pinned certificate byte for byte.**
     *
     * This is the whole of spec §5.5 as a person experiences it, and the reason it is a security
     * property rather than a convenience: the pinned identity is independent of where that laptop
     * happens to answer. A dynamic IP that changed, a tunnel that moved — those are address problems,
     * not trust problems, and an owner meeting one must not have to re-pair. Re-pairing would mint a
     * new key on this phone and evict the peer their Mac pinned by hand, so a text field that quietly
     * reset trust would be the most expensive control in the app.
     *
     * The keeping is [PairedLaptop.setAddress]'s doing, not this function's, and it is asserted at
     * both levels: `PairedLaptopTest` on the store and `LaptopSettingsTest` through here.
     *
     * Returns whether anything was stored, for a caller that wants to close a keyboard.
     */
    fun save(): Boolean = when (val read = PairingAddress.parse(address)) {
        is TypedAddress.Refused -> {
            note = read.why.sentence
            false
        }

        is TypedAddress.Read -> {
            store.setAddress(read.host, read.port)
            laptop = store.read()
            // Re-rendered from what was stored rather than left as typed, so the box shows the value
            // the phone will actually dial. Whitespace the owner pasted in is gone at that point, and
            // a box that still shows it invites them to "fix" an address that is already right.
            address = laptop?.let(PairingAddress::of).orEmpty()
            note = MOVED
            true
        }
    }

    /** Puts the confirmation on screen. Nothing is destroyed here. */
    fun ask(what: Destruction) {
        pending = what
    }

    /** Takes it away again. The ordinary outcome, and the one the Cancel button reaches. */
    fun dismiss() {
        pending = null
    }

    /**
     * **The only place in this application that destroys anything.**
     *
     * It clears both halves — the laptop this phone is paired with, and the key that proves which
     * phone this is — because in this version the bridge keeps exactly one peer and half a pairing is
     * not a state worth being in. A phone that kept its key after unpairing would present a
     * certificate the Mac has already been told to forget.
     *
     * It cannot be reached without [ask] having been called first: there is no state here that a
     * single press can move from *paired* to *nothing*. That is the shape the predecessor project
     * lacked when a broad verdict was wired straight to a `clear()`.
     */
    fun confirm() {
        val what = pending ?: return
        pending = null
        store.clear()
        discardIdentity()
        laptop = null
        address = ""
        note = when (what) {
            Destruction.Unpair -> UNPAIRED
            Destruction.ReplaceKey -> KEY_REPLACED
        }
        // Asked again rather than assumed. A key that was unusable is now absent, and the section
        // offering to replace it has to go away — a destructive button left on screen after it has
        // done its work is the second press nobody meant to make.
        keyIsUnusable = keyState() == SigningState.Unusable
    }

    /**
     * The two irreversible things, and what each of them costs, in the words the owner reads before
     * pressing anything.
     *
     * **Every sentence names what will be lost.** That is the requirement, not a style: a
     * confirmation that only asks *are you sure* transfers no information and is answered yes by
     * reflex. `LaptopSettingsTest` asserts the property.
     */
    enum class Destruction(val warning: String) {

        /** Asked for by a paired owner who means to pair with something else, or with nothing. */
        Unpair(
            "Unpairing forgets your Mac and destroys this phone's key. Your Mac will stop accepting " +
                "this phone, and pairing again needs a new code from it.",
        ),

        /**
         * Asked for by an owner whose key will not sign, which until now had no remedy short of
         * reinstalling the app.
         *
         * It destroys exactly as much as unpairing does, and it is a separate act because it is
         * reached differently: it is offered **only** while [keyIsUnusable], so a healthy phone never
         * sees this button at all.
         */
        ReplaceKey(
            "Replacing the key destroys the one this phone has now and forgets your Mac with it. " +
                "Pairing again makes a new key and needs a new code from your Mac.",
        ),
    }

    private companion object {

        /** Said in terms of what changed and what did not, because the second half is the point. */
        const val MOVED =
            "Saved. Your Mac is still the one you paired with — only where this phone looks for it " +
                "has changed."

        const val UNPAIRED = "Unpaired. Scan a code from your Mac to pair again."

        const val KEY_REPLACED =
            "The old key is gone. Scan a code from your Mac to pair again; this phone makes a new key " +
                "as it does."
    }
}
