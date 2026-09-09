package dev.isachivka.bewareofsugar.pairing

/**
 * What this phone's key can do, asked before anything opens a socket.
 *
 * **Three answers where there were four.** `NeedsOwner` — the key exists but the unlock window has
 * lapsed, prompt and retry — was removed on 2026-07-29 along with the flag that created it. Nothing
 * about this key expires any more, so a key that will not sign now will not sign later either, and a
 * state meaning "try again once the owner is present" would describe a condition the key cannot be
 * in. See `PhoneIdentity.generate`.
 */
sealed interface SigningState {

    /** The key exists and will sign now. */
    data object Ready : SigningState

    /** This phone has no key at all. Nothing to rescue. */
    data object Absent : SigningState

    /**
     * The key exists and can never sign again.
     *
     * The case that matters: **a key minted before `DIGEST_NONE` was in its spec.** Every one of those
     * is in this state permanently — TLS 1.3 asks it to sign a raw digest and the keystore refuses.
     * Nothing the owner does from where they are standing changes the answer.
     *
     * **This verdict is broad on purpose and must stay non-destructive.** It is reached by catching
     * `GeneralSecurityException`, so it covers conditions nobody has enumerated — which is right for
     * something that produces a sentence on a screen, and was catastrophic while it also produced a
     * `clear()`. On 2026-07-29 one misclassification of a working key destroyed the owner's identity
     * and their pairing with it. The remedy is minting a new one, and the owner presses that button.
     */
    data object Unusable : SigningState
}
