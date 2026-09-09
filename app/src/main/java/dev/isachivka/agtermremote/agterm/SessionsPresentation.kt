package dev.isachivka.agtermremote.agterm

import androidx.annotation.StringRes
import dev.isachivka.agtermremote.R
import dev.isachivka.agtermremote.wire.WireFailure

/**
 * What the owner is told, and the two things it must never do.
 *
 * **It must not assert a cause the app cannot observe.** The house rule since REQ-0005, held over the
 * Immich report, and this feature has three indistinguishable causes rather than one: a sleeping
 * laptop, a filtered network and a VPN all arrive here as the same silence. So the copy says what was
 * observed and points at the preconditions document; it does not guess between them.
 *
 * **It must not blur the identity failures into that group.** [WireFailure.NotPinned] and
 * [WireFailure.CertificateExpired] are the two the owner can actually act on, and they need
 * *different* actions — re-pair the phone for one, mint a new certificate on the laptop for the other.
 * That distinction is the reason iteration 2b moved the verdict off the provider's cause chain: if
 * these two collapse, the app sends the owner to the wrong machine.
 *
 * The `when` is exhaustive with no `else`, so a new [WireFailure] cannot ship with no copy behind it —
 * it stops the build instead, which is the only reminder that cannot be forgotten.
 */
@StringRes
fun copyFor(failure: WireFailure): Int = when (failure) {

    // --- The identity is wrong, and each names its own remedy ------------------------------------
    WireFailure.NotPinned -> R.string.agterm_failure_not_pinned
    WireFailure.CertificateExpired -> R.string.agterm_failure_certificate_expired
    WireFailure.NotPaired -> R.string.agterm_failure_not_paired

    // The owner declined the unlock prompt. Their choice, not a fault, and it names no machine.
    WireFailure.IdentityUnusable -> R.string.agterm_failure_identity_unusable

    // --- Observed, and nothing beyond it ----------------------------------------------------------

    // "Not answering" and nothing about why. Three causes, one symptom, no way to tell them apart
    // from a phone.
    WireFailure.CannotReach -> R.string.agterm_failure_cannot_reach

    // This one MAY be specific, because it was observed: the router replied and the thing behind it
    // did not. That is the shape a port mismatch takes, and calling it a network problem would send
    // the owner to their carrier over two digits in a config file.
    WireFailure.BridgeNotListening -> R.string.agterm_failure_bridge_not_listening

    // A stall names no stage to the owner. `Connecting` versus `AwaitingResponse` is a fact about
    // this app's internals, not about their laptop, and putting it on screen would be jargon
    // masquerading as diagnosis.
    is WireFailure.TimedOut -> R.string.agterm_failure_timed_out

    // Ours, and it says so. The bridge behaved correctly and this app stopped draining the stream;
    // copy that blamed the laptop would send the owner to a machine that was fine.
    WireFailure.ReaderFellBehind -> R.string.agterm_failure_reader_fell_behind

    // The far end spoke and it was not the protocol. Distinct from silence, because silence has three
    // ordinary causes and this has none.
    WireFailure.Malformed -> R.string.agterm_failure_malformed
}

/**
 * Whether this failure means *the owner must go and change something about the identity*.
 *
 * Used to decide whether the screen offers the pairing route rather than a retry. Retrying a wrong
 * certificate will never succeed, and a button that cannot work is worse than no button: it teaches
 * the owner that the app's controls are decorative.
 */
fun isIdentityFailure(failure: WireFailure): Boolean = when (failure) {
    // IdentityUnusable is ours rather than theirs, but it belongs here for the same reason: the
    // remedy is a new identity, and a retry button could never produce one.
    WireFailure.NotPinned, WireFailure.CertificateExpired, WireFailure.NotPaired,
    WireFailure.IdentityUnusable,
    -> true
    WireFailure.CannotReach,
    WireFailure.BridgeNotListening,
    WireFailure.ReaderFellBehind,
    WireFailure.Malformed,
    is WireFailure.TimedOut,
    -> false
}
